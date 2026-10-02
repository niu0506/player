package com.example.player

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ==================== 数据模型 ====================

/** 播放列表单个媒体项：仅元信息；进度统一存 uri→positions 映射 */
data class MediaItemData(
    val uri: Uri,
    val name: String,
    /** 总时长（毫秒），扫描时可能未知，播放后回填 */
    val duration: Long = 0L
)

/** 规整化 Uri 字符串，作为去重/匹配的稳定 key */
fun normalizeUri(uri: Uri): String {
    val base = uri.buildUpon().clearQuery().build().toString().trimEnd('/')
    val lastSeg = uri.lastPathSegment ?: base
    return "$base|$lastSeg"
}

/** 毫秒格式化时长文本（"12:34"/"1:02:03"），结果按值缓存 */
private val timeFormatCache = LruCache<Long, String>(512)

fun formatTime(ms: Long): String {
    timeFormatCache.get(ms)?.let { return it }
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    val result = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
    timeFormatCache.put(ms, result)
    return result
}

// ==================== 本地媒体库扫描 ====================

/** MediaStore 查询与权限判定，只负责读，合并/对账由上层编排 */
class MediaStoreScanner(private val context: Context) {

    /** 查询全部视频（按名称升序）；失败返回 null */
    fun queryVideos(): List<MediaItemData>? = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    /** 查询全部音频（按名称升序）；失败返回 null */
    fun queryAudios(): List<MediaItemData>? = query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    /** 查询 MediaStore；失败返回 null（与「空结果」区分，避免被当作误删） */
    private fun query(contentUri: Uri): List<MediaItemData>? {
        val items = mutableListOf<MediaItemData>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION
        )
        return try {
            val cursor = context.contentResolver.query(
                contentUri, projection, null, null,
                "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
            ) ?: return null
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(BaseColumns._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx)
                    val duration = it.getLong(durIdx)
                    items.add(
                        MediaItemData(Uri.withAppendedPath(contentUri, id.toString()), name, duration)
                    )
                }
            }
            items
        } catch (_: Exception) {
            null
        }
    }

    /** 是否具备全量读取权限（API 34+ 的「仅选中」授权不算），用于删除对账前保护 */
    fun hasFullMediaAccess(permission: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

// ==================== Room 持久化层 ====================

/**
 * 持久化层：Room 数据库 + 内存镜像。
 * 读走内存镜像（主线程同步可见）；一次性加载完成后，写在调用线程锁内同步更新镜像
 * （写方法返回即对读方可见），DB 段按提交顺序异步落库；加载完成前的写整体排队到加载后执行。
 */

/** 播放列表条目（sortOrder 维护顺序） */
@Entity(tableName = "playlist")
data class PlaylistItemEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val duration: Long,
    val sortOrder: Int,
)

/** 播放进度（uri → 位置毫秒），断点续播的唯一数据源 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
)

/** 轻量键值存储：上次播放项、迁移标记等 */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    val value: String?,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY sortOrder ASC")
    suspend fun getAll(): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist")
    suspend fun clear()

    /** 全量替换：clear + insert 原子完成 */
    @Transaction
    suspend fun replaceAll(items: List<PlaylistItemEntity>) {
        clear()
        insertAll(items)
    }
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProgressEntity>)

    @Query("DELETE FROM progress WHERE uri IN (:uris)")
    suspend fun deleteAll(uris: Set<String>)
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: KvEntity)
}

@Database(
    entities = [PlaylistItemEntity::class, ProgressEntity::class, KvEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun progressDao(): ProgressDao
    abstract fun kvDao(): KvDao
}

// ---------- 仓库 ----------

object PlayerRepository {
    private const val TAG = "PlayerRepository"
    private const val DB_NAME = "player.db"
    /** 旧版 SharedPreferences 文件名（仅一次性迁移用） */
    private const val LEGACY_PREFS = "player"
    private const val KEY_LAST_ITEM = "lastItem"
    private const val KEY_MIGRATED = "migratedFromPrefs"

    /** 单线程 dispatcher + 公正互斥锁，保证写序 = 调用序 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val writeMutex = Mutex()
    private val stateLock = Any()

    private var db: PlayerDatabase? = null
    private var loadJob: Deferred<Unit>? = null
    private val loadLock = Any()

    /** 应用上下文（[ensureLoaded] 捕获，供写入路径在加载失败后自行重试加载） */
    @Volatile
    private var appContext: Context? = null

    /** 最近一次加载发起时刻（单调时钟毫秒），用于失败重试冷却 */
    @Volatile
    private var lastLoadAttemptMs = 0L

    /**
     * 加载失败后的自动重试冷却：失败可能是永久性的（如迁移报错），
     * 没有冷却会被 2 秒一次的进度写任务反复触发「重建数据库 + 失败」。
     */
    private const val LOAD_RETRY_COOLDOWN_MS = 30_000L

    /** 内存镜像：不可变快照 + copy-on-write，读方永远见一致状态 */
    @Volatile
    private var playlistState: List<MediaItemData> = emptyList()

    @Volatile
    private var progressState: Map<String, Long> = emptyMap()

    @Volatile
    private var lastItemState: String? = null

    /** 一次性加载是否已成功完成；完成后写方法在调用线程同步更新镜像 */
    @Volatile
    private var loadSucceeded = false

    /**
     * 启动一次性加载（幂等）。已成功或正在进行时不重复；
     * **上一次失败则重新发起**——失败若成为终态，[dispatchWrite] 会永远命中失败结果，
     * 把用户的进度与列表改动静默丢弃（库损坏/瞬时 I/O 报错本可恢复）。
     */
    fun ensureLoaded(context: Context) {
        appContext = context.applicationContext
        synchronized(loadLock) {
            val running = loadJob?.isCompleted == false
            if (!running && !loadSucceeded) restartLoadLocked()
        }
    }

    /**
     * 等待加载完成；失败返回 false，绝不向外抛（避免启动闪退）。
     * 失败时日志并重置镜像为空，但**不把失败当终态**：下一次 [ensureLoaded]/[awaitLoaded]
     * 会重新尝试加载，调用方可据此提示用户。
     */
    suspend fun awaitLoaded(context: Context): Boolean {
        ensureLoaded(context)
        val job = synchronized(loadLock) { loadJob } ?: return false
        val failure = job.awaitOrNull() ?: return true
        Log.e(TAG, "数据库加载失败，内存镜像已重置为空（下次调用会重试）", failure)
        synchronized(stateLock) {
            playlistState = emptyList()
            progressState = emptyMap()
            lastItemState = null
        }
        return false
    }

    // ---- 同步读（加载后调用；返回不可变快照） ----

    fun getPlaylist(): List<MediaItemData> = playlistState

    fun getProgress(uri: String): Long? = progressState[uri]

    fun getLastItem(): String? = lastItemState

    // ---- 写：加载完成后内存同步更新（调用线程），DB 按调用顺序异步落库 ----

    /** 批量更新进度：先剔除 [removes]，再合并 [writes]（仅 >0，0 不覆盖） */
    fun applyProgressUpdates(writes: Map<String, Long>, removes: Set<String> = emptySet()) {
        if (writes.isEmpty() && removes.isEmpty()) return
        val writesSnapshot = writes.toMap()
        val removesSnapshot = removes.toSet()
        var delta: List<ProgressEntity> = emptyList()
        dispatchWrite(
            memPart = { delta = mergeProgressLocked(writesSnapshot, removesSnapshot) },
            dbPart = {
                if (removesSnapshot.isNotEmpty()) progressDao().deleteAll(removesSnapshot)
                if (delta.isNotEmpty()) progressDao().upsertAll(delta)
            }
        )
    }

    /** 全量替换播放列表；进度统一经 [applyProgressUpdates] 写入，不再借列表落盘搭车 */
    fun savePlaylist(items: List<MediaItemData>) {
        val itemsSnapshot = items.toList()
        var entities: List<PlaylistItemEntity> = emptyList()
        dispatchWrite(
            memPart = {
                playlistState = itemsSnapshot
                entities = itemsSnapshot.mapIndexed { i, it ->
                    PlaylistItemEntity(it.uri.toString(), it.name, it.duration, i)
                }
            },
            dbPart = { playlistDao().replaceAll(entities) }
        )
    }

    /** 记录上次播放项 uri（供冷启动恢复定位） */
    fun setLastItem(uri: String) {
        dispatchWrite(
            memPart = { lastItemState = uri },
            dbPart = { kvDao().put(KvEntity(KEY_LAST_ITEM, uri)) }
        )
    }

    /** 等待一次性加载与所有已提交写任务落盘（Service 销毁等同步路径用） */
    suspend fun flush() {
        loadJob?.awaitOrNull()
        persistScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    // ---- 内部实现 ----

    /** 进度写公共段落（须在 [stateLock] 内）：更新内存并返回需落库的 >0 增量 */
    private fun mergeProgressLocked(
        writes: Map<String, Long>,
        removes: Set<String>
    ): List<ProgressEntity> {
        progressState = mergeProgressMap(progressState, writes, removes)
        return writes.filterValues { it > 0 }.map { ProgressEntity(it.key, it.value) }
    }

    /** 等待加载协程：成功返回 null，失败返回异常（取消仍上抛）；不做降级 */
    private suspend fun Deferred<Unit>.awaitOrNull(): Exception? = try {
        await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    /**
     * 写任务调度：一次性加载成功后，[memPart]（镜像更新，须在 [stateLock] 内执行）
     * 在调用线程同步完成、写方法返回即对读方可见，[dbPart] 按提交顺序进入
     * persistScope 异步落库；加载尚未完成则两段一起排队到加载后执行
     * （防镜像先写、随后被加载结果覆盖）。
     * 加载失败时**不静默丢弃**：先按冷却策略重试一次加载，成功了照常落盘；
     * 仍失败才放弃本次写入，并留明确日志（加载失败已由 [awaitLoaded] 返回值告知 UI）。
     * 注：跨越加载完成边界的两次同键写，镜像合并顺序可能与提交顺序相反
     * （仅进程启动瞬间的极窄窗口），DB 侧仍严格按提交序、重启后以 DB 为准，可接受。
     * loadJob 由 PlayerApp 进程启动即经 [ensureLoaded] 保证非空，兜底分支仅作防御。
     */
    private fun dispatchWrite(memPart: () -> Unit, dbPart: suspend PlayerDatabase.() -> Unit) {
        val job: Deferred<Unit>? = synchronized(loadLock) { loadJob }
        if (job == null) {
            Log.w(TAG, "仓库尚未初始化，丢弃一次写任务")
            return
        }
        if (loadSucceeded) {
            synchronized(stateLock) { memPart() }
            persistScope.launch { persist(dbPart) }
        } else {
            persistScope.launch {
                if (job.awaitOrNull() != null && !reloadAfterFailure()) {
                    Log.e(TAG, "数据库加载失败且重试未成功，本次写入未落盘")
                    return@launch
                }
                synchronized(stateLock) { memPart() }
                persist(dbPart)
            }
        }
    }

    /**
     * 加载失败后的补救：等（或按冷却期重新发起）一次加载。
     * 冷却期内已有重试在跑时直接等它，避免并发写各自重启加载。
     * @return true 表示当下镜像可用（调用方可继续落盘）
     */
    private suspend fun reloadAfterFailure(): Boolean {
        val job = synchronized(loadLock) {
            if (loadSucceeded) return true
            val running = loadJob?.isCompleted == false
            if (!running && nowMs() - lastLoadAttemptMs >= LOAD_RETRY_COOLDOWN_MS) {
                restartLoadLocked()
            }
            loadJob
        } ?: return false
        return job.awaitOrNull() == null
    }

    /** 须在 [loadLock] 内：丢弃失败的加载任务与其数据库实例，重新发起一次加载 */
    private fun restartLoadLocked() {
        val ctx = appContext ?: return
        loadJob = null
        loadSucceeded = false
        try {
            db?.close()
        } catch (_: Exception) {
        }
        db = null
        lastLoadAttemptMs = nowMs()
        loadJob = persistScope.async { loadInternal(ctx) }
    }

    /** 单调时钟毫秒（不依赖系统时间，避免时钟回拨让冷却失效） */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** 单个写任务的 DB 段落：锁 + 事务，写失败只记日志不上抛（内存已更新） */
    private suspend fun persist(block: suspend PlayerDatabase.() -> Unit) {
        val database = db ?: return
        try {
            writeMutex.withLock { database.withTransaction { database.block() } }
        } catch (e: Exception) {
            Log.e(TAG, "数据库写入失败，本次变更未落盘", e)
        }
    }

    /** 一次性加载：建库 → 迁移旧 prefs → 读出三份数据进内存镜像 */
    private suspend fun loadInternal(appCtx: Context) {
        // 刻意不启用 fallbackToDestructiveMigration：宁可升版本漏写迁移时明确报错，
        // 也不能静默清空用户的播放列表与全部进度。改 schema 时须递增 version
        // 并补 Migration、addMigrations(...)，同时提交 app/schemas/ 下新导出的 json。
        val database = Room.databaseBuilder(appCtx, PlayerDatabase::class.java, DB_NAME)
            .build()
        db = database
        val prefs = appCtx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (database.kvDao().get(KEY_MIGRATED) == null) {
            migrateFromPrefs(database, prefs)
        } else if (prefs.contains("playlist") || prefs.contains("progress")
            || prefs.contains("lastItem")
        ) {
            prefs.edit { clear() }
        }
        playlistState = database.playlistDao().getAll().map {
            MediaItemData(it.uri.toUri(), it.name, it.duration)
        }
        progressState = database.progressDao().getAll().associate { it.uri to it.positionMs }
        lastItemState = database.kvDao().get(KEY_LAST_ITEM)
        // 置于最后：三份镜像赋值对 loadSucceeded 的读方 happens-before，
        // 此后的写方法走调用线程同步更新镜像的快路径
        loadSucceeded = true
    }

    /** 旧 prefs 一次性迁移：单事务写 Room + 打标记，提交成功后才清旧文件（中途被杀下次重试） */
    private suspend fun migrateFromPrefs(database: PlayerDatabase, prefs: SharedPreferences) {
        val items = parseLegacyPlaylistJson(prefs.getString("playlist", null))
        val progress = parseLegacyProgressJson(prefs.getString("progress", null))
        val last = prefs.getString("lastItem", null)
        database.withTransaction {
            database.playlistDao().replaceAll(
                items.mapIndexed { i, it ->
                    PlaylistItemEntity(it.uri, it.name, it.duration, i)
                }
            )
            database.progressDao().upsertAll(progress.map { ProgressEntity(it.key, it.value) })
            database.kvDao().put(KvEntity(KEY_MIGRATED, "1"))
            if (last != null) database.kvDao().put(KvEntity(KEY_LAST_ITEM, last))
        }
        prefs.edit { clear() }
    }
}

// ==================== 纯函数与旧数据迁移解析 ====================

/** 单个文件进度落缓存判定结果：清除 / 写入 / 跳过 */
internal sealed interface ProgressWriteDecision {
    /** 播到末尾视为看完：清除该 uri 的进度 */
    data object Clear : ProgressWriteDecision

    /** 正常播放中：写入 [positionMs] */
    data class Store(val positionMs: Long) : ProgressWriteDecision

    /** 无效位置：不动作 */
    data object Skip : ProgressWriteDecision
}

/** 末尾容差上限：距总时长 5 秒以内即视为播完（同时不超过总时长 10%，短媒体不被整段吞掉） */
internal const val PROGRESS_FINISH_TAIL_MS = 5_000L

/**
 * 「进度已到末尾」判定（唯一权威实现）：距末尾 [PROGRESS_FINISH_TAIL_MS] 以内视为已看完。
 * 周期落盘的采样点几乎必然落在末尾几秒内，若只把「位置 ≥ 总时长」当播完，
 * 这个「接近末尾」的值会被当成断点存下来：重启后从末尾续播，单曲循环时每轮回绕
 * 都被 seek 回片尾（表现为只重复片尾的几秒）。
 */
internal fun isProgressFinished(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0) return false
    val tail = minOf(PROGRESS_FINISH_TAIL_MS, durationMs / 10)
    return positionMs >= durationMs - tail
}

/**
 * 进度写入判定纯函数（唯一权威实现，调用方据此裁决不各自漂移）：
 * 播到末尾（含末尾容差）→Clear；位置<=0→Skip；其余→Store。
 */
internal fun decideProgressWrite(positionMs: Long, durationMs: Long): ProgressWriteDecision {
    if (isProgressFinished(positionMs, durationMs)) return ProgressWriteDecision.Clear
    if (positionMs <= 0) return ProgressWriteDecision.Skip
    return ProgressWriteDecision.Store(positionMs)
}

/**
 * 进度裁决应用纯函数：把 [decideProgressWrite] 的结果分发到 [store]/[clear]，
 * Skip 不动作。调用方传入各自存储介质的操作（Service 的 progressCache/removedUris、
 * Activity 的仓库直写等），保证各处裁决应用行为完全一致。
 */
internal fun applyProgressDecision(
    decision: ProgressWriteDecision,
    uri: String,
    store: (uri: String, positionMs: Long) -> Unit,
    clear: (uri: String) -> Unit,
) {
    when (decision) {
        is ProgressWriteDecision.Clear -> clear(uri)
        is ProgressWriteDecision.Store -> store(uri, decision.positionMs)
        ProgressWriteDecision.Skip -> Unit
    }
}

/**
 * 进度合并纯函数（唯一权威实现）：先剔除 [removes]，再合并 [writes]（仅 >0）。
 */
internal fun mergeProgressMap(
    disk: Map<String, Long>,
    writes: Map<String, Long>,
    removes: Set<String>
): Map<String, Long> {
    val merged = disk.toMutableMap()
    for (uri in removes) merged.remove(uri)
    for ((k, v) in writes) {
        if (v > 0) merged[k] = v
    }
    return merged
}

/** 解析旧 "progress" JSON，仅保留 >0 的值 */
internal fun parseLegacyProgressJson(json: String?): Map<String, Long> {
    if (json.isNullOrEmpty()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        buildMap {
            for (key in obj.keys()) {
                val v = obj.getLong(key)
                if (v > 0) put(key, v)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 旧 "playlist" JSON 条目的纯字符串载体（不依赖 Uri，便于 JVM 单测） */
internal data class LegacyPlaylistItem(
    val uri: String,
    val name: String,
    val duration: Long,
)

/** 解析旧 "playlist" JSON 数组（按 uri 去重）；Uri 转换留给运行时处理 */
internal fun parseLegacyPlaylistJson(json: String?): List<LegacyPlaylistItem> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val arr = JSONArray(json)
        val seen = mutableSetOf<String>()
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uri = obj.getString("uri")
                if (uri in seen) continue
                seen.add(uri)
                add(LegacyPlaylistItem(uri, obj.getString("name"), obj.optLong("duration", 0L)))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}