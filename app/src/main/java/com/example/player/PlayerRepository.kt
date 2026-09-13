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
 * - 加载：进程启动一次性加载（含旧 prefs 迁移），完成前的写自动链到加载之后；
 *   失败（DB 损坏/磁盘满）降级为空数据，绝不让异常逃逸到调用方
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
    private const val TAG = "PlayerRepository"
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

    /**
     * 「仓库未初始化期间」到达的写任务暂存队列（runWhenReady 的排队兜底）。
     * 正常流程下 PlayerApp.onCreate 会先触发 ensureLoaded，本队列恒为空；
     * 一旦初始化约定被破坏（未来新增入口、初始化顺序变化），写入会先排队
     * 而非静默蒸发。与 loadJob 的判空/赋值共用 [loadLock]：「入队」与
     * 「开始加载」必须互斥——否则存在「判空后、入队前加载恰好完成并清空
     * 队列」的交错窗口，任务从此无人消费。
     */
    private val pendingWrites = mutableListOf<suspend () -> Unit>()

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

    /**
     * 等待一次性加载完成（含旧数据迁移）后再读内存镜像。
     * 加载失败（DB 损坏/磁盘满等）不再向调用方抛异常：记录日志并把内存镜像
     * 重置为空状态，让上层以「空列表」继续运行——宁可丢播放列表也不能让 App
     * 每次启动都闪退（循环闪退会让用户彻底打不开 App）。
     * 协程取消（CancellationException）不属于加载失败，仍原样上抛，
     * 以配合调用方作用域的取消语义（如 lifecycleScope 随 Activity 结束）。
     */
    suspend fun awaitLoaded(context: Context) {
        ensureLoaded(context)
        val failure = loadJob!!.awaitOrNull()
        if (failure != null) {
            Log.e(TAG, "数据库加载失败，内存镜像已重置为空", failure)
            // loadInternal 可能在中途失败（如 playlist 已读出但 progress 查询失败），
            // 残留半加载状态；统一清空，保证所有调用方看到一致的「空数据」
            synchronized(stateLock) {
                playlistState = emptyList()
                progressState = emptyMap()
                lastItemState = null
            }
        }
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
        // 加载失败静默放过（awaitOrNull 不抛）：日志与镜像降级已由 awaitLoaded
        // 承担，这里只负责等已提交的写任务；会把……加让 onDestroy 的
        // 后台 flush 以未捕获协程异常闪退
        loadJob?.awaitOrNull()
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

    /**
     * 统一「等待加载协程」的骨架：成功返回 null、失败返回原始异常。
     * 只收敛 CancellationException 上抛/其余捕获的公共骨架，本身不做任何
     * 降级动作——三处调用方对失败的处理各不相同（awaitLoaded 记日志并清空
     * 镜像；flush 与 runWhenReady 静默跳过，避免周期性写在坏 DB 上每 2 秒
     * 刷一条错误日志），由各调用方依据返回值自行决定。
     */
    private suspend fun Deferred<Unit>.awaitOrNull(): Exception? = try {
        await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    /**
     * 写任务调度：已初始化则链到加载完成之后执行（防半加载合并）；
     * 未初始化（loadJob 为 null）则推入待办队列，由 loadInternal 加载完成后
     * 按序不执行（见 [drainPendingWrites]）。
     * 原实现依赖「调用方保证 PlayerApp.onCreate 已先 ensureLoaded」的隐式约定，
     * 约定一旦被破坏（未来新增入口、初始化顺序变化），写入会被静默丢弃——
     * 无日志无崩溃，极难排查；现从「依赖调用方保证已初始化」改为「自带排队
     * 兜底」，约定被破坏时只损失「落库推迟到加载完成」而非「数据丢失」。
     */
    private fun runWhenReady(block: suspend () -> Unit) {
        // 判空与入队必须原子（共用 loadLock，见 pendingWrites 的 KDoc）；
        // 若 loadJob 非空，本任务与既有路径一致：链到加载之后执行
        val job: Deferred<Unit>? = synchronized(loadLock) {
            if (loadJob == null) {
                pendingWrites.add(block)
                null
            } else {
                loadJob
            }
        }
        if (job == null) {
            // 防御层一：不静默丢弃，入队等 loadInternal 不执行。
            // 此处不打逐条日志：drainPendingWrites 的带数量汇总日志信息量更大，
            // 逐条会与之重复；若加载失败致 drain 不执行，已有「数据库加载失败」
            // 的 Log.e 可查
            return
        }
        persistScope.launch {
            // 加载失败静默跳过本次写：镜像已重置为空，继续写只会反复撞损坏的 DB；
            // 不记日志——2 秒周期写会把逐条日志放大成风暴，失败已由 awaitLoaded 记过
            if (job.awaitOrNull() != null) return@launch
            block()
        }
    }

    /**
     * 不执行「仓库未初始化期间」积压的写任务（runWhenReady 的第二层防御）。
     * 仅在 loadInternal 末尾（三份镜像就绪后）调用，保证：
     * - 严格晚于加载完成，不与加载并发，杜绝「半加载合并」；
     * - 本函数天然运行在 persistScope 单线程上，顺序执行即满足
     *   「在 persistScope 上逐个执行」；
     * - 一定先于经 job.await() 链接的后续写任务（它们要等 loadJob 完成），
     *   「排队在前、链接在后」的真实提交顺序得以保留。
     * 若加载中途失败（异常上抛）则不会走到这里，队列任务随既有的
     * 「降级为空数据」策略一并放弃——宁丢数据不闪退。
     */
    private suspend fun drainPendingWrites() {
        // 快照 + 清空同锁完成：runWhenReady 的入队可能来自任意线程
        val backlog: List<suspend () -> Unit>
        synchronized(loadLock) {
            backlog = pendingWrites.toList()
            pendingWrites.clear()
        }
        if (backlog.isEmpty()) return
        Log.w(TAG, "补执行未初始化期间积压的 ${backlog.size} 个写任务")
        for (task in backlog) {
            try {
                task()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 单个积压任务失败不波及其余任务，也不阻断加载完成
                Log.e(TAG, "积压写任务执行失败", e)
            }
        }
    }

    /**
     * 单个写任务的 DB 段落：锁 + 事务，与其它写任务严格串行。
     * 写失败（磁盘满/DB 损坏）只记日志不抛出：本函数跑在 persistScope.launch
     * 里，未捕获异常同样会闪退 App；内存镜像已更新，本次丢失由下次启动对账兜底
     */
    private suspend fun persist(block: suspend PlayerDatabase.() -> Unit) {
        val database = db ?: return
        try {
            writeMutex.withLock { database.withTransaction { database.block() } }
        } catch (e: Exception) {
            Log.e(TAG, "数据库写入失败，本次变更未落盘", e)
        }
    }

    /** 一次性加载：建库 → 首次启动迁移旧 prefs → 读出三份数据进内存镜像 */
    private suspend fun loadInternal(appCtx: Context) {
        val database = Room.databaseBuilder(appCtx, PlayerDatabase::class.java, DB_NAME)
            // 当前 DB version = 1、尚无任何迁移，此配置是为未来升 version 2+ 预留的兜底：
            // 版本升级未提供迁移路径时销毁重建（dropAllTables = true 连 Room 之外的表
            // 一并清掉，确保重建彻底干净），宁可丢播放列表也不因缺迁移而打不开 App。
            // 注意：它不覆盖「DB 文件本身损坏」的场景——那种失败由 awaitLoaded 的
            // 异常捕获 + 重置空状态兜底（无参 fallbackToDestructiveMigration() 在
            // Room 2.7+ 已废弃，布尔重载是现行 API）
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
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
        // 防御层二：三份镜像就绪后，不执行未初始化期间积压的写任务（见 drainPendingWrites）
        drainPendingWrites()
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
 * 「单个文件的进度此刻该如何落缓存」判定的封闭结果，三选一：
 * 清除（播到末尾）/ 写入新值 / 跳过（零位置等无效值）。
 */
internal sealed interface ProgressWriteDecision {
    /** 播到末尾视为看完：应清除该 uri 的进度记录 */
    data object Clear : ProgressWriteDecision

    /** 正常播放中：应写入 [positionMs] */
    data class Store(val positionMs: Long) : ProgressWriteDecision

    /** 无效位置：不动作（旧进度保持原样） */
    data object Skip : ProgressWriteDecision
}

/**
 * 进度写入判定纯函数：
 * - 播到末尾（[durationMs] > 0 且 [positionMs] >= [durationMs]）→ [ProgressWriteDecision.Clear]
 * - 位置无效（[positionMs] <= 0）→ [ProgressWriteDecision.Skip]（与 [mergeProgressMap]
 *   的「0 值不覆盖」呼应：0 既不清旧值也不写新值）
 * - 其余 → [ProgressWriteDecision.Store]
 * 时长未知时调用方传入 C.TIME_UNSET（负值，天然被 > 0 判定排除，纯函数无需感知该常量）。
 * 这套规则的唯一权威实现，MainActivity.saveCurrentProgress 与
 * PlayerService.cacheCurrentPosition 都据此裁决，防止两侧规则漂移。
 * 历史备注：Service 侧旧实现会在「零位置且缓存无旧值」时写入 0——该 0 值会被
 * mergeProgressMap（仅 >0）在内存镜像与落库两侧全部过滤，属惰性无效写，
 * 统一时收敛为 Skip 而非参数化保留这个无意义差异。
 */
internal fun decideProgressWrite(positionMs: Long, durationMs: Long): ProgressWriteDecision {
    if (durationMs in 1..positionMs) return ProgressWriteDecision.Clear
    if (positionMs <= 0) return ProgressWriteDecision.Skip
    return ProgressWriteDecision.Store(positionMs)
}

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
