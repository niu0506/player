package com.example.player.data

import android.content.Context
import android.content.SharedPreferences
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
import com.example.player.model.MediaItemData
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

/**
 * 持久化层：Room 数据库 + 内存镜像仓库。
 *
 * 替代旧 SharedPreferences("player") 的三份数据（playlist / progress / lastItem），
 * 职责划分：
 * - **读**：全部走内存镜像（@Volatile 不可变快照），保持上层主线程同步读的调用模式，
 *   无需把点击/事件监听改成异步
 * - **写**：先在锁内更新内存（copy-on-write），再按提交顺序异步落库
 *   （单线程 dispatcher + 互斥锁 + 每任务一个事务），落库顺序严格等于调用顺序，
 *   替代旧 progressPrefsLock 的「读-合并-写」手动串行化
 * - **加载**：进程启动时一次性从 Room 加载（含旧 SharedPreferences 数据迁移）；
 * 加载完成前的写操作自动链到加载之后，避免合并进半加载镜像丢数据
 * - **flush**：Service 销毁等需要同步落盘的场景，join 全部已提交写任务
 *   （替代旧 commit() 同步写盘）
 */

// ---------- 实体 ----------

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

// ---------- DAO（全 suspend，仅可在后台线程调用） ----------

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

    /**
     * 落库专用作用域：单线程 dispatcher 保证任务按 launch 顺序执行；
     * 配合 [writeMutex]（公平锁，按挂起先后授予）保证 DB 写入顺序等于调用顺序
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    /** DB 写段落互斥锁：与单线程 dispatcher 双保险，防止协程挂起后语句交错提交 */
    private val writeMutex = Mutex()
    /** 内存镜像的读-改-写串行化锁 */
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

    /** 启动一次性加载（幂等）。由 PlayerApp.onCreate 触发，先于任何组件代码执行 */
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

    // ---- 同步读（加载完成后调用；返回不可变快照，调用方可安全持有） ----

    fun getPlaylist(): List<MediaItemData> = playlistState

    fun getProgressMap(): Map<String, Long> = progressState

    fun getProgress(uri: String): Long? = progressState[uri]

    fun getLastItem(): String? = lastItemState

    // ---- 写：内存即时更新，DB 按调用顺序异步落库 ----

    /**
     * 「合并非覆盖」式批量更新进度。
     * 语义与旧 writeToDisk/writeProgressBatch 完全一致：
     * 先剔除 [removes]，再合并 [writes]（仅 >0 的值，0 不覆盖已有非零进度）。
     */
    fun applyProgressUpdates(writes: Map<String, Long>, removes: Set<String> = emptySet()) {
        if (writes.isEmpty() && removes.isEmpty()) return
        // 入口同步快照：调用方（如 PlayerService 的 removedUris）在返回后可能立即修改原集合
        val writesSnapshot = writes.toMap()
        val removesSnapshot = removes.toSet()
        runWhenReady {
            val delta: List<ProgressEntity>
            synchronized(stateLock) {
                progressState = mergeProgressMap(progressState, writesSnapshot, removesSnapshot)
                delta = writesSnapshot.filterValues { it > 0 }
                    .map { ProgressEntity(it.key, it.value) }
            }
            persist {
                if (removesSnapshot.isNotEmpty()) progressDao().deleteAll(removesSnapshot)
                if (delta.isNotEmpty()) progressDao().upsertAll(delta)
            }
        }
    }

    /**
     * 全量替换播放列表，并同时合并一批进度（对应旧 savePlaylist 的两段写）。
     * 列表替换与进度写入在同一个 DB 事务中原子完成。
     */
    fun savePlaylist(items: List<MediaItemData>, progressWrites: Map<String, Long>) {
        val itemsSnapshot = items.toList()
        val progressSnapshot = progressWrites.toMap()
        runWhenReady {
            val entities: List<PlaylistItemEntity>
            val delta: List<ProgressEntity>
            synchronized(stateLock) {
                playlistState = itemsSnapshot
                progressState = mergeProgressMap(progressState, progressSnapshot, emptySet())
                entities = itemsSnapshot.mapIndexed { i, it ->
                    PlaylistItemEntity(it.uri.toString(), it.name, it.duration, i)
                }
                delta = progressSnapshot.filterValues { it > 0 }
                    .map { ProgressEntity(it.key, it.value) }
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
     * 已加载则立即执行；加载未完成则链到加载之后执行（防半加载合并）。
     * loadJob 为 null 的情况不存在：Application.onCreate 先于一切组件执行。
     */
    private inline fun runWhenReady(crossinline block: suspend () -> Unit) {
        val job = loadJob ?: return
        persistScope.launch {
            job.await()
            block()
        }
    }

    /** 单个写任务的 DB 段落：锁 + 事务，与其它写任务严格按提交顺序串行 */
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
            // 迁移事务已提交但旧文件清理被中断（如进程被杀）：懒清理兜底
            prefs.edit { clear() }
        }
        playlistState = database.playlistDao().getAll().map {
            MediaItemData(it.uri.toUri(), it.name, it.duration)
        }
        progressState = database.progressDao().getAll().associate { it.uri to it.positionMs }
        lastItemState = database.kvDao().get(KEY_LAST_ITEM)
    }

    /**
     * 旧 SharedPreferences 数据一次性迁移：
     * 解析 playlist / progress / lastItem 三个 key → 单事务写入 Room + 打迁移标记；
     * 事务提交成功后才清空旧 prefs 文件。中途进程被杀则事务回滚，下次启动重试。
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

/**
 * 进度合并纯函数：在已有进度 [disk] 上，先剔除 [removes] 中的条目，再合并 [writes](仅 >0)。
 * 「合并非覆盖 + 0 值不覆盖 + removes 优先剔除」语义的唯一权威实现（原 PlayerService.kt 迁入），
 * 与 SharedPreferences/Room 存储解耦，便于单元测试。
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

/** 解析旧 "progress" JSON 为映射，仅保留 >0 的值（与旧写盘语义一致） */
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

/** 解析旧 "playlist" JSON 数组为条目列表（按 uri 去重）；Uri 转换留给调用方在 Android 运行时做 */
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
