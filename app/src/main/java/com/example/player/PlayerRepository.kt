package com.example.player

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** 一条媒体条目（视频或音频）的运行时数据。 */
data class MediaItemData(
    val uri: Uri,
    val name: String,
    /** 时长(毫秒)，未知时为 0。 */
    val duration: Long = 0L,
    /**
     * 所属文件夹分组键（相对外部存储的目录，如 `Movies/剧A`）；无法识别时为 ""。
     * 用于把同一部剧的分集排在一起，保证连播不跨剧。
     */
    val folderKey: String = "",
)

/**
 * 归一化 uri 作为去重键：去掉查询参数并裁掉结尾斜杠，再拼上末段路径。
 * 该键仅在内存中用于比较（不落盘），因此实现调整不影响已保存的数据。
 */
fun normalizeUri(uri: Uri): String {
    val base = uri.toString().substringBefore('?').trimEnd('/')
    val lastSeg = uri.lastPathSegment ?: base
    return "$base|$lastSeg"
}

/**
 * 把 MediaStore 的 `RELATIVE_PATH`（如 `Movies/剧A/`）归一化为分组键。
 * 去掉首尾空白与结尾斜杠；值为空时返回 ""（视为无法分组）。
 */
internal fun folderKeyFromRelativePath(relativePath: String?): String =
    relativePath?.trim()?.trim('/')?.trim().orEmpty()

/**
 * 低版本（API < 29）没有 `RELATIVE_PATH`，用文件绝对路径的父目录作为分组键，
 * 并剥掉主外部存储根前缀，使其与 [folderKeyFromRelativePath] 的结果形态一致。
 * 文件直接位于存储根目录时返回 ""；非主存储卷（如 SD 卡）保留绝对父目录。
 */
internal fun folderKeyFromAbsolutePath(absolutePath: String?, storageRoot: String?): String {
    val path = absolutePath?.trim().orEmpty()
    if (path.isEmpty()) return ""
    val parent = path.substringBeforeLast('/', "")
    if (parent.isEmpty() || parent == "/") return ""
    val root = storageRoot?.trim()?.trimEnd('/').orEmpty()
    if (root.isNotEmpty() && (parent == root || parent.startsWith("$root/"))) {
        return parent.removePrefix(root).trim('/')
    }
    return parent.trimEnd('/')
}

/** 取分组键的末段作为展示用文件夹名（如 `Movies/剧A` -> `剧A`）；空键返回 ""。 */
internal fun folderDisplayName(folderKey: String): String = folderKey.substringAfterLast('/')

/** 数字段超过该位数时改用长度 + 字典序比较，避免转 Long 溢出。 */
private const val NATURAL_MAX_DIGITS = 18

/**
 * 比较两个数字段（仅由数字字符组成）：
 * 忽略前导 0 按数值比较；数值相同则位数少者在前；超长数字段按长度再按字典序。
 * 仅当两段完全相等时返回 0。
 */
private fun compareDigitSegments(a: String, b: String): Int {
    val x = a.trimStart('0').ifEmpty { "0" }
    val y = b.trimStart('0').ifEmpty { "0" }
    if (x.length > NATURAL_MAX_DIGITS || y.length > NATURAL_MAX_DIGITS) {
        val byLength = x.length.compareTo(y.length)
        return if (byLength != 0) byLength else x.compareTo(y)
    }
    val vx = x.toLong()
    val vy = y.toLong()
    if (vx != vy) return if (vx < vy) -1 else 1
    // 数值相同（如 "2" 与 "02"）：按位数打破平局，保证结果不依赖输入顺序
    val byLength = x.length.compareTo(y.length)
    return if (byLength != 0) byLength else x.compareTo(y)
}

/**
 * 比较两个非数字段：先忽略大小写比较，完全相同时再用区分大小写的比较打破平局。
 * 仅当两段完全相等时返回 0。
 */
private fun compareTextSegments(a: String, b: String): Int {
    val byIgnoreCase = a.compareTo(b, ignoreCase = true)
    if (byIgnoreCase != 0) return if (byIgnoreCase < 0) -1 else 1
    val byCase = a.compareTo(b)
    return if (byCase < 0) -1 else if (byCase > 0) 1 else 0
}

/**
 * 自然序比较：把字符串切成「连续数字段 / 连续非数字段」交替比较，数字段按数值大小。
 * 因此 `第2集` 排在 `第10集` 之前，`E02` 排在 `E10` 之前。
 *
 * 数字段排在非数字段之前，段数与长度不足则短串在前，最后按原文兜底 ——
 * 这样保证是**全序**（自反、反对称、传递），排序结果不依赖输入顺序，
 * 避免每次扫描都误判“顺序变了”而反复重建播放队列。
 */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val aIsDigit = a[i].isDigit()
        val bIsDigit = b[j].isDigit()
        if (aIsDigit != bIsDigit) return if (aIsDigit) -1 else 1

        val startA = i
        val startB = j
        val cmp = if (aIsDigit) {
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            compareDigitSegments(a.substring(startA, i), b.substring(startB, j))
        } else {
            while (i < a.length && !a[i].isDigit()) i++
            while (j < b.length && !b[j].isDigit()) j++
            compareTextSegments(a.substring(startA, i), b.substring(startB, j))
        }
        if (cmp != 0) return cmp
    }
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b)
}

/**
 * 播放列表项比较：先按文件夹分组键（**无法分组的空键统一排在最后**），
 * 再按文件名自然序，最后按 uri 兜底（同名不同 uri 时顺序也确定）。
 */
internal fun comparePlaylistItems(a: MediaItemData, b: MediaItemData): Int {
    val aUngrouped = a.folderKey.isEmpty()
    val bUngrouped = b.folderKey.isEmpty()
    if (aUngrouped != bUngrouped) return if (aUngrouped) 1 else -1
    if (!aUngrouped) {
        val byFolder = a.folderKey.compareTo(b.folderKey)
        if (byFolder != 0) return byFolder
    }
    val byName = naturalCompare(a.name, b.name)
    if (byName != 0) return byName
    return a.uri.toString().compareTo(b.uri.toString())
}

/** 按「文件夹分组 + 组内自然序」重排播放列表（稳定排序，幂等）。 */
internal fun sortPlaylist(items: List<MediaItemData>): List<MediaItemData> =
    items.sortedWith(Comparator { a, b -> comparePlaylistItems(a, b) })

/**
 * 用扫描结果（uri 键 -> 文件夹键）回填播放列表中缺失的分组键：
 * 只填空值、不覆盖已有非空值，映射缺失则保持为空；无变化时原样返回。
 */
internal fun backfillFolderKeys(
    items: List<MediaItemData>,
    uriToFolder: Map<String, String>
): List<MediaItemData> {
    if (uriToFolder.isEmpty()) return items
    var changed = false
    val result = items.map { item ->
        if (item.folderKey.isNotEmpty()) return@map item
        val folder = uriToFolder[normalizeUri(item.uri)].orEmpty()
        if (folder.isEmpty()) return@map item
        changed = true
        item.copy(folderKey = folder)
    }
    return if (changed) result else items
}

/** 时间格式化结果的 LRU 缓存，避免列表滚动时重复计算。 */
private val timeFormatCache = LruCache<Long, String>(512)

/** 把毫秒格式化为 mm:ss 或 h:mm:ss。 */
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

/** 通过 MediaStore 扫描设备上的视频与音频。 */
class MediaStoreScanner(private val context: Context) {

    /** 查询设备视频，失败返回 null。 */
    fun queryVideos(): List<MediaItemData>? = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    /** 查询设备音频，失败返回 null。 */
    fun queryAudios(): List<MediaItemData>? = query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    /**
     * 用于读取所属文件夹的 MediaStore 列名：
     * API 29+ 为 `RELATIVE_PATH`（相对外部存储的目录）；更低版本回退到 `DATA`（绝对路径）。
     * 只会查询本机存在的列，避免把“列不存在”变成整次查询失败。
     */
    @Suppress("DEPRECATION")
    private val folderColumn: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    /** 低版本剥绝对路径前缀时用的主外部存储根目录（API 29+ 用不到）。 */
    @Suppress("DEPRECATION")
    private val storageRoot: String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Environment.getExternalStorageDirectory()?.absolutePath
        } else {
            null
        }

    /**
     * 按内容 uri 执行查询，返回 id/名称/时长/所属文件夹列表；异常时返回 null 以区分“无数据”。
     */
    private fun query(contentUri: Uri): List<MediaItemData>? {
        val items = mutableListOf<MediaItemData>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            folderColumn
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
                // 文件夹列可能缺失：用 getColumnIndex 判 -1 并逐项降级，不能 OrThrow
                val folderIdx = it.getColumnIndex(folderColumn)
                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx)
                    val duration = it.getLong(durIdx)
                    items.add(
                        MediaItemData(
                            Uri.withAppendedPath(contentUri, id.toString()),
                            name,
                            duration,
                            folderKeyOf(it, folderIdx)
                        )
                    )
                }
            }
            items
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 读取某一行的文件夹分组键；列不存在或取值失败时返回 ""，
     * 保证路径不可读只影响该项的排序位置，不会丢掉整批扫描结果。
     */
    private fun folderKeyOf(cursor: Cursor, folderIdx: Int): String {
        if (folderIdx < 0) return ""
        return runCatching {
            val raw = cursor.getString(folderIdx) ?: return@runCatching ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                folderKeyFromRelativePath(raw)
            } else {
                folderKeyFromAbsolutePath(raw, storageRoot)
            }
        }.getOrDefault("")
    }

    /** 是否拥有某种媒体类型的完整读取权限。 */
    fun hasFullMediaAccess(permission: String): Boolean {
        return hasMediaReadAccess(context, permission, includePartial = false)
    }
}

/**
 * 判断是否拥有媒体读取权限。
 * Android 13 以下使用 READ_EXTERNAL_STORAGE；
 * Android 13+ 使用传入的分类型权限；Android 14+ 还支持“仅选择部分照片/视频”的部分授权。
 */
fun hasMediaReadAccess(context: Context, permission: String, includePartial: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < 33) {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val full = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return full || (includePartial && Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED)
}

/** 播放列表项（Room 实体）。 */
@Entity(tableName = "playlist")
data class PlaylistItemEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val duration: Long,
    /** 列表中的排序序号。 */
    val sortOrder: Int,
    /** 所属文件夹分组键；旧数据与无法识别的项为空串。 */
    @ColumnInfo(defaultValue = "''") val folder: String = "",
)

/** 播放进度（Room 实体）：uri -> 位置(毫秒)。 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
)

/** 通用键值对（Room 实体），用于保存“上次播放项”等状态。 */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    val value: String?,
)

/** 播放列表数据访问对象。 */
@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY sortOrder ASC")
    suspend fun getAll(): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist")
    suspend fun clear()

    /** 事务内整体替换播放列表（先清空再插入）。 */
    @Transaction
    suspend fun replaceAll(items: List<PlaylistItemEntity>) {
        clear()
        insertAll(items)
    }
}

/** 播放进度数据访问对象。 */
@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ProgressEntity>)

    @Query("DELETE FROM progress WHERE uri IN (:uris)")
    suspend fun deleteAll(uris: Set<String>)
}

/** 键值对数据访问对象。 */
@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: KvEntity)
}

/** 应用 Room 数据库，包含播放列表、进度与键值对三张表。 */
@Database(
    entities = [PlaylistItemEntity::class, ProgressEntity::class, KvEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun progressDao(): ProgressDao
    abstract fun kvDao(): KvDao
}

/**
 * v1 -> v2：播放列表项新增「文件夹分组键」列。
 * 旧数据统一填默认空串（随后由扫描结果回填），不删除任何用户数据。
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlist ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * 播放数据仓库（单例）。
 *
 * 采用“内存镜像 + 异步落盘”的模式：读操作直接读内存镜像（[playlistState] /
 * [progressState] / [lastItemState]），写操作先更新内存镜像，再在单线程作用域内
 * 串行写数据库；数据库加载失败时会重置内存镜像并在冷却后自动重试。
 */
object PlayerRepository {
    private const val TAG = "PlayerRepository"
    private const val DB_NAME = "player.db"

    // 旧版使用 SharedPreferences 存储，这里定义用于一次性迁移到 Room 的键名
    private const val LEGACY_PREFS = "player"
    private const val KEY_LAST_ITEM = "lastItem"
    private const val KEY_MIGRATED = "migratedFromPrefs"

    /** 串行落盘作用域：限制并发为 1，保证写库顺序。 */
    private val persistScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** 保护数据库事务的互斥锁。 */
    private val writeMutex = Mutex()

    /** 写盘失败通知通道，供界面层监听并提示。 */
    private val writeFailureChannel = Channel<WriteFailure>(Channel.BUFFERED)
    val writeFailures: Flow<WriteFailure> = writeFailureChannel.receiveAsFlow()

    /** 保护内存镜像状态的锁。 */
    private val stateLock = Any()

    private var db: PlayerDatabase? = null
    private var loadJob: Deferred<Unit>? = null
    private val loadLock = Any()

    /** 应用上下文（Application），用于数据库与 SharedPreferences。 */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastLoadAttemptMs = 0L

    /** 加载失败后的重试冷却时间。 */
    private const val LOAD_RETRY_COOLDOWN_MS = 30_000L

    /** 播放列表内存镜像。 */
    @Volatile
    private var playlistState: List<MediaItemData> = emptyList()

    /** 播放进度内存镜像：uri -> 位置(毫秒)。 */
    @Volatile
    private var progressState: Map<String, Long> = emptyMap()

    /** “上次播放项”内存镜像。 */
    @Volatile
    private var lastItemState: String? = null

    /** 写盘失败信息。 */
    data class WriteFailure(val message: String)

    /** 数据库是否已成功加载。 */
    @Volatile
    private var loadSucceeded = false

    /** 确保数据库已开始加载（幂等）：尚未成功且无进行中的加载时，触发一次加载。 */
    fun ensureLoaded(context: Context) {
        appContext = context.applicationContext
        synchronized(loadLock) {
            val running = loadJob?.isCompleted == false
            if (!running && !loadSucceeded) restartLoadLocked()
        }
    }

    /**
     * 等待数据库加载完成。
     * 返回是否加载成功；失败时会清空内存镜像，便于下次调用重试。
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

    /** 读取播放列表内存镜像。 */
    fun getPlaylist(): List<MediaItemData> = playlistState

    /** 读取某 uri 的进度，无记录返回 null。 */
    fun getProgress(uri: String): Long? = progressState[uri]

    /** 读取上次播放项 uri。 */
    fun getLastItem(): String? = lastItemState

    /**
     * 应用一批进度更新：先合并进内存镜像，再异步落盘。
     * @param writes 需要写入的 uri -> 位置
     * @param removes 需要删除的 uri 集合
     * @param onPersisted 落盘完成回调，参数表示是否成功
     */
    fun applyProgressUpdates(
        writes: Map<String, Long>,
        removes: Set<String> = emptySet(),
        onPersisted: (Boolean) -> Unit = {}
    ) {
        if (writes.isEmpty() && removes.isEmpty()) {
            onPersisted(true)
            return
        }
        val writesSnapshot = writes.toMap()
        val removesSnapshot = removes.toSet()
        dispatchWrite(
            // 内存部分：合并进度
            memPart = { mergeProgressLocked(writesSnapshot, removesSnapshot) },
            // 数据库部分：先删除再写入
            dbPart = {
                if (removesSnapshot.isNotEmpty()) progressDao().deleteAll(removesSnapshot)

                // 仅写入正数进度（0 视为无进度）
                val rows = writesSnapshot.filterValues { it > 0 }
                    .map { ProgressEntity(it.key, it.value) }
                if (rows.isNotEmpty()) progressDao().upsertAll(rows)
            },
            onComplete = onPersisted
        )
    }

    /** 保存整个播放列表：更新内存镜像并异步落盘。 */
    fun savePlaylist(items: List<MediaItemData>) {
        val itemsSnapshot = items.toList()
        dispatchWrite(
            memPart = {
                playlistState = itemsSnapshot
            },
            dbPart = {
                // 按顺序生成带 sortOrder 的实体并整体替换
                val entities = itemsSnapshot.mapIndexed { i, it ->
                    PlaylistItemEntity(it.uri.toString(), it.name, it.duration, i, it.folderKey)
                }
                playlistDao().replaceAll(entities)
            }
        )
    }

    /** 记录“上次播放项”。 */
    fun setLastItem(uri: String) {
        dispatchWrite(
            memPart = { lastItemState = uri },
            dbPart = { kvDao().put(KvEntity(KEY_LAST_ITEM, uri)) }
        )
    }

    /** 等待所有挂起的写任务完成（用于退出前保证落盘）。 */
    suspend fun flush() {
        loadJob?.awaitOrNull()
        persistScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    /** 在持锁状态下把更新合并进进度内存镜像。 */
    private fun mergeProgressLocked(
        writes: Map<String, Long>,
        removes: Set<String>
    ) {
        progressState = mergeProgressMap(progressState, writes, removes)
    }

    /** 等待 Deferred，成功返回 null，失败返回异常（不吞掉取消）。 */
    private suspend fun Deferred<Unit>.awaitOrNull(): Exception? = try {
        await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    /**
     * 统一的写入调度：若数据库已就绪则直接更新内存并异步落盘；
     * 否则等待加载完成（必要时重试）后再执行。
     */
    private fun dispatchWrite(
        memPart: () -> Unit,
        dbPart: suspend PlayerDatabase.() -> Unit,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val job: Deferred<Unit>? = synchronized(loadLock) { loadJob }
        if (job == null) {
            Log.w(TAG, "仓库尚未初始化，丢弃一次写任务")
            onComplete(false)
            return
        }
        if (loadSucceeded) {
            synchronized(stateLock) { memPart() }
            persistScope.launch { onComplete(persist(dbPart)) }
        } else {
            persistScope.launch {
                // 首次加载失败时尝试重试，仍失败则放弃本次写入
                if (job.awaitOrNull() != null && !reloadAfterFailure()) {
                    Log.e(TAG, "数据库加载失败且重试未成功，本次写入未落盘")
                    onComplete(false)
                    return@launch
                }
                synchronized(stateLock) { memPart() }
                onComplete(persist(dbPart))
            }
        }
    }

    /** 加载失败后按冷却策略重试，返回最终是否成功。 */
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

    /** 重启加载流程（需持有 loadLock）：关闭旧库、重置状态并重新异步加载。 */
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

    /** 基于纳秒时钟的毫秒时间戳，单调递增，适合做间隔判断。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** 在事务中执行一次写库，失败时最多重试 3 次；全部失败则上报通知。 */
    private suspend fun persist(block: suspend PlayerDatabase.() -> Unit): Boolean {
        repeat(3) { attempt ->
            val database = db
            try {
                if (database == null) error("数据库尚未初始化")
                writeMutex.withLock { database.withTransaction { database.block() } }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt < 2) {
                    delay(200.milliseconds)
                } else {
                    Log.e(TAG, "数据库写入三次失败，本次变更未落盘", e)
                    writeFailureChannel.trySend(WriteFailure(e.message ?: "database write failed"))
                    return false
                }
            }
        }
        return false
    }

    /** 实际加载：打开数据库，（首次）执行旧数据迁移，再把数据读入内存镜像。 */
    private suspend fun loadInternal(appCtx: Context) {

        val database = Room.databaseBuilder(appCtx, PlayerDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
        db = database
        val prefs = appCtx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        // 首次启动：从旧版 SharedPreferences 迁移；否则清理可能残留的旧数据
        if (database.kvDao().get(KEY_MIGRATED) == null) {
            migrateFromPrefs(database, prefs)
        } else if (prefs.contains("playlist") || prefs.contains("progress")
            || prefs.contains("lastItem")
        ) {
            prefs.edit { clear() }
        }
        playlistState = database.playlistDao().getAll().map {
            MediaItemData(it.uri.toUri(), it.name, it.duration, it.folder)
        }
        progressState = database.progressDao().getAll().associate { it.uri to it.positionMs }
        lastItemState = database.kvDao().get(KEY_LAST_ITEM)

        loadSucceeded = true
    }

    /** 把旧版 SharedPreferences 中的播放列表/进度/上次播放项迁移到数据库，并清理旧数据。 */
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

/** 播放进度写入决策：清空 / 写入指定位置 / 跳过。 */
internal sealed interface ProgressWriteDecision {

    /** 视为播放完成，应从进度中移除。 */
    data object Clear : ProgressWriteDecision

    /** 记录该位置。 */
    data class Store(val positionMs: Long) : ProgressWriteDecision

    /** 无需处理。 */
    data object Skip : ProgressWriteDecision
}

/** 判定“已播完”的尾部容差：不超过 5 秒，且不超过总时长的 1/10。 */
internal const val PROGRESS_FINISH_TAIL_MS = 5_000L

/** 判断是否已播放到接近结尾（视为看完/听完）。 */
internal fun isProgressFinished(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0) return false
    val tail = minOf(PROGRESS_FINISH_TAIL_MS, durationMs / 10)
    return positionMs >= durationMs - tail
}

/** 依据当前位置与时长决定进度写入策略。 */
internal fun decideProgressWrite(positionMs: Long, durationMs: Long): ProgressWriteDecision {
    if (isProgressFinished(positionMs, durationMs)) return ProgressWriteDecision.Clear
    if (positionMs <= 0) return ProgressWriteDecision.Skip
    return ProgressWriteDecision.Store(positionMs)
}

/** 按决策执行对应的进度操作（写入或清除）。 */
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

/** 合并进度映射：先移除指定 uri，再写入正数进度。 */
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

/** 解析旧版进度 JSON（uri -> 位置），解析失败返回空映射。 */
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

/** 旧版播放列表项（迁移用）。 */
internal data class LegacyPlaylistItem(
    val uri: String,
    val name: String,
    val duration: Long,
)

/** 解析旧版播放列表 JSON（数组），去重并解析失败时返回空列表。 */
internal fun parseLegacyPlaylistJson(json: String?): List<LegacyPlaylistItem> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val arr = JSONArray(json)
        val seen = mutableSetOf<String>()
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uri = obj.getString("uri")
                // 跳过重复项
                if (uri in seen) continue
                seen.add(uri)
                add(LegacyPlaylistItem(uri, obj.getString("name"), obj.optLong("duration", 0L)))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}