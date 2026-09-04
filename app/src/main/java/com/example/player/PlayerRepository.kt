package com.example.player

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ==================== 数据模型 ====================

/**
 * 播放列表中的单个媒体项。只承载元信息（来源、名称、时长），
 * 播放进度统一存在 uri -> position 的进度映射（progress 表 + cachedProgress），
 * 避免同一份进度存多份导致对账复杂化。
 */
data class MediaItemData(
    val uri: Uri,
    val name: String,
    /** 总时长（毫秒），扫描时可能未知，播放后回填 */
    val duration: Long = 0L
)

/** 规整化 Uri 字符串（去 query/末尾斜杠 + 拼接末段路径），作为去重/匹配的稳定 key */
fun normalizeUri(uri: Uri): String {
    val base = uri.buildUpon().clearQuery().build().toString().trimEnd('/')
    val lastSeg = uri.lastPathSegment ?: base
    return "$base|$lastSeg"
}

/**
 * 毫秒格式化为时长文本（"12:34" / "1:02:03"）。
 * 结果按毫秒值做 LiuCache，列表滚动绑定场景中相同数值被反复请求。
 */
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

/** MediaStore 查询与权限判定。只负责「读」，列表合并/对账由上层编排 */
class MediaStoreScanner(private val context: Context) {

    /** 查询全部视频（按名称升序）；查询失败返回 null */
    fun queryVideos(): List<MediaItemData>? = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    /** 查询全部音频（按名称升序）；查询失败返回 null */
    fun queryAudios(): List<MediaItemData>? = query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    /**
     * 查询 MediaStore。失败（异常/拿不到游标）返回 null——必须与「空结果」区分：
     * 空结果会被上层对账当作「全部已删除」，瞬时失败若会被……吞整类误删。
     */
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

    /**
     * 是否具备指定媒体类型的「全量」读取权限（API 34+ 的「仅选中」授权不算）。
     * 用于删除对账前保护，避免部分授权下误删未授权文件。
     */
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
 * 持久化层：Room 数据库 + 内存镜像仓库（替代旧 SharedPreferences）。
 * - 读：全部走内存镜像（@Volatile 不可变快照），保持上层主线程同步读
 * - 写：锁内更新内存（copy-on-write），再按提交顺序异步落库（单线程 + 互斥锁 + 事务）
 * - 加载：进程启动一次性加载（含旧 prefs 迁移），完成前的写自动链到加载之后
 * - flush：Service 销毁等场景同步等待已提交写任务落盘
 */

/** 播放列表条目（sortOrder 维护列表顺序） */
@Entity(tableName = "playlist")
data class PlaylistItemEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val duration: Long,
    val sortOrder: Int,
)

/** 播放进度（uri -> 位置毫秒），断点续播的唯一数据源 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
)

/** 轻量键值存储：上次播放项、旧数据迁移标记等 */
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

    /** 全量替换播放列表（clear + insert 原子完成） */
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
    exportSchema = false,
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun progressDao(): ProgressDao
    abstract fun kvDao(): KvDao
}

// ---------- 仓库 ----------

object PlayerRepository {
    private const val DB_NAME = "player.db"
    /** 旧版 SharedPreferences 文件名（仅用于一次性数据迁移） */
    private const val LEGACY_PREFS = "player"
    private const val KEY_LAST_ITEM = "lastItem"
    private const val KEY_MIGRATED = "migratedFromPrefs"

    /** 单线程 dispatcher + 公平互斥锁，保证 DB 写入顺序严格等于调用顺序 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val writeMutex = Mutex()
    private val stateLock = Any()

    private var db: PlayerDatabase? = null
    private var loadJob: Deferred<Unit>? = null
    private val loadLock = Any()

    /** 内存镜像：不可变快照 + copy-on-write，读方永远见一致状态 */
    @Volatile
    private var playlistState: List<MediaItemData> = emptyList()

    @Volatile
    private var progressState: Map<String, Long> = emptyMap()

    @Volatile
    private var lastItemState: String? = null

    /** 启动一次性加载（幂等）。由 PlayerApp.onCreate 触发 */
    fun ensureLoaded(context: Context) {
        synchronized(loadLock) {
            if (loadJob == null) {
                val appCtx = context.applicationContext
                loadJob = persistScope.async { loadInternal(appCtx) }
            }
        }
    }

    /** 等待一次性加载完成（含旧数据迁移）后再读内存镜像 */
    suspend fun awaitLoaded(context: Context) {
        ensureLoaded(context)
        loadJob!!.await()
    }

    // ---- 同步读（加载完成后调用；返回不可变快照） ----

    fun getPlaylist(): List<MediaItemData> = playlistState

    fun getProgressMap(): Map<String, Long> = progressState

    fun getProgress(uri: String): Long? = progressState[uri]

    fun getLastItem(): String? = lastItemState

    // ---- 写：内存即时更新，DB 按调用顺序异步落库 ----

    /**
     * 「合并非覆盖」式批量更新进度：
     * 先剔除 [removes]，再合并 [writes]（仅 >0 的值，0 不覆盖已有非零进度）。
     */
    fun applyProgressUpdates(writes: Map<String, Long>, removes: Set<String> = emptySet()) {
        if (writes.isEmpty() && removes.isEmpty()) return
        // 入口同步快照：调用方在返回后可能立即修改原集合
        val writesSnapshot = writes.toMap()
        val removesSnapshot = removes.toSet()
        runWhenReady {
            val delta: List<ProgressEntity>
            synchronized(stateLock) {
                delta = mergeProgressLocked(writesSnapshot, removesSnapshot)
            }
            persist {
                if (removesSnapshot.isNotEmpty()) progressDao().deleteAll(removesSnapshot)
                if (delta.isNotEmpty()) progressDao().upsertAll(delta)
            }
        }
    }

    /** 全量替换播放列表并合并进度，两段写在同一事务中原子完成 */
    fun savePlaylist(items: List<MediaItemData>, progressWrites: Map<String, Long>) {
        val itemsSnapshot = items.toList()
        val progressSnapshot = progressWrites.toMap()
        runWhenReady {
            val entities: List<PlaylistItemEntity>
            val delta: List<ProgressEntity>
            synchronized(stateLock) {
                playlistState = itemsSnapshot
                entities = itemsSnapshot.mapIndexed { i, it ->
                    PlaylistItemEntity(it.uri.toString(), it.name, it.duration, i)
                }
                delta = mergeProgressLocked(progressSnapshot, emptySet())
            }
            persist {
                playlistDao().replaceAll(entities)
                if (delta.isNotEmpty()) progressDao().upsertAll(delta)
            }
        }
    }

    /** 记录上次播放项 uri（供冷启动恢复定位） */
    fun setLastItem(uri: String) {
        runWhenReady {
            lastItemState = uri
            persist { kvDao().put(KvEntity(KEY_LAST_ITEM, uri)) }
        }
    }

    /** 等待一次性加载与所有已提交写任务落盘（Service 销毁等同步路径用） */
    suspend fun flush() {
        loadJob?.await()
        persistScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    // ---- 内部实现 ----

    /**
     * 进度写公共段落（必须在 [stateLock] 内调用）：
     * 更新内存镜像并返回需要落库的增量实体（仅 >0 的值）。
     */
    private fun mergeProgressLocked(
        writes: Map<String, Long>,
        removes: Set<String>
    ): List<ProgressEntity> {
        progressState = mergeProgressMap(progressState, writes, removes)
        return writes.filterValues { it > 0 }.map { ProgressEntity(it.key, it.value) }
    }

    /** 已加载则立即执行；未完成则链到加载之后（防半加载合并） */
    private inline fun runWhenReady(crossinline block: suspend () -> Unit) {
        val job = loadJob ?: return
        persistScope.launch {
            job.await()
            block()
        }
    }

    /** 单个写任务的 DB 段落：锁 + 事务，与其它写任务严格串行 */
    private suspend fun persist(block: suspend PlayerDatabase.() -> Unit) {
        val database = db ?: return
        writeMutex.withLock { database.withTransaction { database.block() } }
    }

    /** 一次性加载：建库 → 首次启动迁移旧 prefs → 读出三份数据进内存镜像 */
    private suspend fun loadInternal(appCtx: Context) {
        val database = Room.databaseBuilder(appCtx, PlayerDatabase::class.java, DB_NAME).build()
        db = database
        val prefs = appCtx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (database.kvDao().get(KEY_MIGRATED) == null) {
            migrateFromPrefs(database, prefs)
        } else if (prefs.contains("playlist") || prefs.contains("progress")
            || prefs.contains("lastItem")
        ) {
            // 迁移事务已提交但旧文件清理被中断（进程被杀）：懒清理兜底
            prefs.edit { clear() }
        }
        playlistState = database.playlistDao().getAll().map {
            MediaItemData(it.uri.toUri(), it.name, it.duration)
        }
        progressState = database.progressDao().getAll().associate { it.uri to it.positionMs }
        lastItemState = database.kvDao().get(KEY_LAST_ITEM)
    }

    /**
     * 旧 SharedPreferences 数据一次性迁移：单事务写入 Room + 打迁移标记，
     * 提交成功后才清空旧文件。中途被杀则事务回滚，下次启动重试。
     */
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

/**
 * 进度合并纯函数：先剔除 [removes]，再合并 [writes]（仅 >0）。
 * 「合并非覆盖 + 0 值不覆盖 + removes 优先剔除」语义的唯一权威实现。
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

/** 解析旧 "progress" JSON，仅保留 >0 的值（与旧写盘语义一致） */
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

/** 旧 "playlist" JSON 条目的纯字符串载体（不依赖 android.net.Uri，便于 JVM 单测） */
internal data class LegacyPlaylistItem(
    val uri: String,
    val name: String,
    val duration: Long,
)

/** 解析旧 "playlist" JSON 数组（按 uri 去重）；Uri 转换留给调用方在 Android 运行时做 */
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
