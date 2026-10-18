/**
 * 数据与存储层。
 *
 * 主要职责：
 * 1. 定义播放列表条目模型 [MediaItemData]，并提供 URI 归一化、文件夹分组键提取与自然排序；
 * 2. 通过 [MediaStoreScanner] 扫描系统媒体库（视频 / 音频）；
 * 3. 通过 Room（[PlayerDatabase]）持久化播放列表、播放进度与 KV 配置，作为全应用唯一的写入口；
 * 4. 提供播放进度写入裁决（末尾容差）与「合并写」语义。
 *
 * 设计要点：内存状态始终为权威读取源（[PlayerRepository.getPlaylist] 等），
 * 数据库写入经 [PlayerRepository.dispatchWrite] 串行化，失败会按冷却时间重试并对外广播。
 */
package com.example.player

import android.content.Context
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
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * 播放列表中的一个媒体条目。
 *
 * @property uri 媒体内容 URI（MediaStore 的 content:// 地址）
 * @property name 展示名称（通常为文件名）
 * @property duration 时长（毫秒），未知时为 0
 * @property folderKey 文件夹分组键，空串表示「未分组」（排序时置于列表末尾）
 */
data class MediaItemData(
    val uri: Uri,
    val name: String,
    val duration: Long = 0L,
    val folderKey: String = "",
)

/**
 * 将 URI 归一化为稳定的键：去掉 query 参数与结尾斜杠，再拼接最后一段路径。
 * 用于跨扫描 / 存储对比同一个文件（避免 query 变化或末尾斜杠差异导致误判为新文件）。
 */
fun normalizeUri(uri: Uri): String {
    val base = uri.toString().substringBefore('?').trimEnd('/')
    val lastSeg = uri.lastPathSegment ?: base
    return "$base|$lastSeg"
}

/** API 29+ 使用 MediaStore 的 RELATIVE_PATH 作为分组键：去除首尾空白与斜杠。 */
internal fun folderKeyFromRelativePath(relativePath: String?): String =
    relativePath?.trim()?.trim('/')?.trim().orEmpty()

/**
 * API 29 以下没有 RELATIVE_PATH，改用绝对路径取父目录。
 * 若父目录位于主存储根之下，则返回相对根目录的路径；否则保留绝对父目录。
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

/** 超过此位数的数字段改用「长度 + 字典序」比较，避免 Long 溢出。 */
private const val NATURAL_MAX_DIGITS = 18

/** 数值相同（前导零）或超出安全位数时，用「长度 + 字典序」保证排序确定性。 */
private fun compareDigitTails(x: String, y: String): Int {
    val byLength = x.length.compareTo(y.length)
    return if (byLength != 0) byLength else x.compareTo(y)
}

/** 比较两段纯数字：先按数值大小，数值相同时退化为长度 + 字典序（处理前导零与超长数字）。 */
private fun compareDigitSegments(a: String, b: String): Int {
    val x = a.trimStart('0').ifEmpty { "0" }
    val y = b.trimStart('0').ifEmpty { "0" }
    if (x.length <= NATURAL_MAX_DIGITS && y.length <= NATURAL_MAX_DIGITS) {
        val vx = x.toLong()
        val vy = y.toLong()
        if (vx != vy) return if (vx < vy) -1 else 1
    }
    return compareDigitTails(x, y)
}

/** 比较两段非数字文本：忽略大小写优先，完全相同再按大小写敏感比较，保证排序确定性。 */
private fun compareTextSegments(a: String, b: String): Int {
    val byIgnoreCase = a.compareTo(b, ignoreCase = true)
    if (byIgnoreCase != 0) return if (byIgnoreCase < 0) -1 else 1
    val byCase = a.compareTo(b)
    return if (byCase < 0) -1 else if (byCase > 0) 1 else 0
}

/**
 * 自然排序：把字符串切成「数字段 / 文本段」交替比较。
 * 效果：`第2集` < `第10集`（按数值而非字典序），数字段排在文本段之前。
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
 * 播放列表排序比较器：先按文件夹分组（未分组排最后），组内按名称自然序，
 * 名称完全相同再以 URI 兜底，确保排序是全序且稳定。
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

/** 按 [comparePlaylistItems] 对整份播放列表排序。 */
internal fun sortPlaylist(items: List<MediaItemData>): List<MediaItemData> =
    items.sortedWith(::comparePlaylistItems)

/**
 * 回填缺失的文件夹分组键：仅对 [MediaItemData.folderKey] 为空的条目，
 * 按扫描结果的 uri→folder 映射补齐（已有分组不覆盖）。无变化时原样返回。
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

/** 时间格式化结果缓存，避免列表中大量相同时长反复格式化。 */
private val timeFormatCache = LruCache<Long, String>(512)

/** 将毫秒格式化为 `mm:ss` 或 `h:mm:ss`（不足 1 小时不显示小时位）。 */
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

/**
 * MediaStore 扫描器：查询设备上的视频与音频，构造 [MediaItemData]。
 * 目录列在 API 29+ 用 RELATIVE_PATH，低版本退回 DATA（绝对路径）。
 */
class MediaStoreScanner(private val context: Context) {

    /** 查询所有视频；失败（权限/异常）返回 null。 */
    fun queryVideos(): List<MediaItemData>? = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    /** 查询所有音频；失败（权限/异常）返回 null。 */
    fun queryAudios(): List<MediaItemData>? = query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    /** 用于提取文件夹分组键的列：API 29+ 用 RELATIVE_PATH，更低版本用 DATA。 */
    @Suppress("DEPRECATION")
    private val folderColumn: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    /** API 29 以下把 DATA 绝对路径转相对路径时用到的存储根目录。 */
    @Suppress("DEPRECATION")
    private val storageRoot: String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Environment.getExternalStorageDirectory()?.absolutePath
        } else {
            null
        }

    /** 执行一次查询；任何异常或 cursor 为空都返回 null，交由调用方按「扫描失败」处理。 */
    @Suppress("InlinedApi") // DURATION 常量 API 29+，但为编译期内联字符串，低版本可安全使用
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
                contentUri, projection, null, null, null
            ) ?: return null
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(BaseColumns._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
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

    /** 从当前行读取目录列并归一化为文件夹分组键。 */
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

}

/**
 * 判断是否拥有指定媒体读取权限。
 * @param permission API 33+ 要检查的具体媒体权限（低版本固定检查 READ_EXTERNAL_STORAGE）
 * @param includePartial API 34+ 允许把「仅访问选中的照片/视频」也算作有权限
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

/** 视频或音频任一读取权限即视为有访问权（低版本为 READ_EXTERNAL_STORAGE）。 */
fun hasAnyMediaReadAccess(context: Context, includePartial: Boolean): Boolean =
    if (Build.VERSION.SDK_INT < 33) {
        hasMediaReadAccess(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            includePartial = false
        )
    } else {
        hasMediaReadAccess(context, android.Manifest.permission.READ_MEDIA_VIDEO, includePartial) ||
            hasMediaReadAccess(context, android.Manifest.permission.READ_MEDIA_AUDIO, includePartial)
    }

/** 播放列表表：uri 为主键，sortOrder 维持用户可见顺序，folder 为文件夹分组键。 */
@Entity(tableName = "playlist")
data class PlaylistItemEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val duration: Long,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "''") val folder: String = "",
)

/** 播放进度表：按 uri 记录最后播放位置（毫秒）。 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
)

/** 通用 KV 表：存放最后播放项、缺失文件记录等零散配置。 */
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

    /** 只更新单条时长，避免为一次回填整表替换。 */
    @Query("UPDATE playlist SET duration = :duration WHERE uri = :uri")
    suspend fun updateDuration(uri: String, duration: Long)

    /** 整体替换：先清空再插入（调用方已开启事务）。 */
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

/**
 * Room 数据库。注意：**未启用** fallbackToDestructiveMigration，
 * 新增字段/表时必须在下方补 Migration 并递增 version，否则打开数据库会直接报错，
 * 而不会静默清空用户数据。
 */
@Database(
    entities = [PlaylistItemEntity::class, ProgressEntity::class, KvEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun progressDao(): ProgressDao
    abstract fun kvDao(): KvDao

    companion object {
        /** v1→v2：playlist 新增 folder 列（默认空串，与实体 defaultValue 一致）。 */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

/**
 * 全局单例仓库：内存状态为权威读取源，所有写操作经 [dispatchWrite] 串行落盘。
 * 加载失败不是终态，会按 [LOAD_RETRY_COOLDOWN_MS] 冷却重试。
 */
object PlayerRepository {
    private const val TAG = "PlayerRepository"
    private const val DB_NAME = "player.db"

    /** 进度批量删除时单条 SQL 的最大绑定数（SQLite 约 999）。 */
    private const val PROGRESS_DELETE_CHUNK = 500

    /** KV 表里存放「缺失文件记录」的键。 */
    private const val KEY_MISSING_URIS = "missingUris"

    /** 文件消失后需等待此时长才允许从列表中删除，避免外部瞬时不可用导致误删。 */
    internal const val MISSING_DELETE_GRACE_MS = 24 * 60 * 60 * 1000L

    /** KV 表里存放「上次播放项」的键。 */
    private const val KEY_LAST_ITEM = "lastItem"

    /** 单线程 IO 作用域：保证所有数据库写入按提交顺序串行执行。 */
    private val persistScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val writeMutex = Mutex()

    /** 落盘失败事件（合并型，避免重复刷屏）。 */
    private val writeFailureChannel = Channel<Unit>(Channel.CONFLATED)
    val writeFailures: Flow<Unit> = writeFailureChannel.receiveAsFlow()

    /** 保护内存状态（playlistState 等）读写的锁。 */
    private val stateLock = Any()

    /** 数据库句柄：跨线程读写（主线程发起加载 / 后台串行落盘），需保证可见性。 */
    @Volatile
    private var db: PlayerDatabase? = null
    private var loadJob: Deferred<Unit>? = null
    private val loadLock = Any()

    @Volatile
    private var appContext: Context? = null

    /** 上次发起加载的时间（用于失败后的冷却重试）。 */
    @Volatile
    private var lastLoadAttemptMs = 0L

    /** 加载失败后再次重试的最小间隔。 */
    private const val LOAD_RETRY_COOLDOWN_MS = 30_000L

    /** 单次落盘的最大尝试次数（首次 + 一次重试）。 */
    private const val PERSIST_MAX_ATTEMPTS = 2

    /** 落盘重试前的等待时长。 */
    private const val PERSIST_RETRY_DELAY_MS = 200L

    /** 内存中的权威播放列表。 */
    @Volatile
    private var playlistState: List<MediaItemData> = emptyList()

    /** 内存中的权威进度映射（uri → 毫秒）。 */
    @Volatile
    private var progressState: Map<String, Long> = emptyMap()

    /** 上次播放的 uri。 */
    @Volatile
    private var lastItemState: String? = null

    /** 已记录缺失的文件（uri → 缺失信息）。 */
    @Volatile
    private var missingState: Map<String, MissingInfo> = emptyMap()

    /** 是否已成功加载过（失败时为 false，读取将返回空数据）。 */
    @Volatile
    private var loadSucceeded = false

    /** 确保在应用启动早期调用：只会在未加载且无进行中任务时发起一次加载。 */
    fun ensureLoaded(context: Context) {
        synchronized(loadLock) {
            if (appContext == null) appContext = context.applicationContext
            val running = loadJob?.isCompleted == false
            if (!running && !loadSucceeded) restartLoadLocked()
        }
    }

    /** 挂起等待加载完成；失败时清空内存状态并返回 false，供界面提示。 */
    suspend fun awaitLoaded(context: Context): Boolean {
        ensureLoaded(context)
        val job = synchronized(loadLock) { loadJob } ?: return false
        val failure = job.awaitOrNull() ?: return true
        Log.e(TAG, "数据库加载失败", failure)
        synchronized(loadLock) {
            if (loadJob === job && !loadSucceeded) {
                synchronized(stateLock) {
                    playlistState = emptyList()
                    progressState = emptyMap()
                    lastItemState = null
                    missingState = emptyMap()
                }
            }
        }
        return loadSucceeded
    }

    /** 返回当前内存中的播放列表。 */
    fun getPlaylist(): List<MediaItemData> = playlistState

    /** 数据是否已成功加载。 */
    fun isLoaded(): Boolean = loadSucceeded

    /** 读取某 uri 的进度；无记录返回 null。 */
    fun getProgress(uri: String): Long? = progressState[uri]

    /** 读取上次播放的 uri。 */
    fun getLastItem(): String? = lastItemState

    /**
     * 应用一批进度变更（合并写）。
     *
     * @param writes uri → 位置；仅保留 > 0 的值（0 不覆盖已有进度）
     * @param removes 需要删除进度记录的 uri
     * @param onPersisted 落盘完成回调，true 表示已成功写入数据库
     */
    fun applyProgressUpdates(
        writes: Map<String, Long>,
        removes: Set<String> = emptySet(),
        onPersisted: (Boolean) -> Unit = {}
    ) {
        // 0 值不覆盖已有进度：内存与数据库共用同一次过滤，规则只写一遍
        val validWrites = writes.filterValues { it > 0 }
        if (validWrites.isEmpty() && removes.isEmpty()) {
            onPersisted(true)
            return
        }
        val removesSnapshot = removes.toSet()
        dispatchWrite(
            memPart = {
                progressState = mergeProgressMap(progressState, validWrites, removesSnapshot)
            },
            dbPart = {
                // 单条 SQL 的绑定参数有上限，批量删除按块提交
                for (chunk in removesSnapshot.chunked(PROGRESS_DELETE_CHUNK)) {
                    progressDao().deleteAll(chunk.toSet())
                }
                if (validWrites.isNotEmpty()) {
                    progressDao().upsertAll(
                        validWrites.map { ProgressEntity(it.key, it.value) }
                    )
                }
            },
            onComplete = onPersisted
        )
    }

    /**
     * 全量保存播放列表（内存立即更新，数据库异步整体替换）。
     *
     * 增删/重排会让所有条目的 sortOrder 变化，逐行 diff 并不比整体替换更省；
     * 单条时长回填请走 [updatePlaylistDuration]，避免为一处变更重写整表。
     */
    fun savePlaylist(items: List<MediaItemData>) {
        val itemsSnapshot = items.toList()
        dispatchWrite(
            memPart = {
                playlistState = itemsSnapshot
            },
            dbPart = {
                val entities = itemsSnapshot.mapIndexed { i, it ->
                    PlaylistItemEntity(it.uri.toString(), it.name, it.duration, i, it.folderKey)
                }
                playlistDao().replaceAll(entities)
            }
        )
    }

    /** 回填单条时长：只更新目标行，避免为一次回填整表替换。 */
    fun updatePlaylistDuration(uri: Uri, duration: Long) {
        dispatchWrite(
            memPart = {
                playlistState = playlistState.map {
                    if (it.uri == uri) it.copy(duration = duration) else it
                }
            },
            dbPart = { playlistDao().updateDuration(uri.toString(), duration) }
        )
    }

    /** 记录上次播放的 uri。 */
    fun setLastItem(uri: String) {
        dispatchWrite(
            memPart = { lastItemState = uri },
            dbPart = { kvDao().put(KvEntity(KEY_LAST_ITEM, uri)) }
        )
    }

    /** 查询某 uri 的缺失记录。 */
    internal fun getMissingInfo(uri: String): MissingInfo? = missingState[uri]

    /** 播放出错（文件不存在）时登记一次缺失，首次记录时间为当前时间。 */
    fun recordMissing(uri: String) {
        if (missingState.containsKey(uri)) return
        persistMissing(missingState + (uri to MissingInfo(nowWallClockMs(), 0)))
    }

    /**
     * 用本轮扫描结果对账缺失记录。
     * @param missingUris 本轮扫描中未见到的 uri（计数 +1）
     * @param observedUris 本轮扫描中见到的 uri（视为恢复，移除缺失记录）
     * @param removedUris 已从列表删除、需清除缺失记录的 uri
     */
    fun updateMissingRecords(
        missingUris: Collection<String>,
        observedUris: Collection<String>,
        removedUris: Collection<String> = emptySet(),
    ) {
        if (missingUris.isEmpty() && observedUris.isEmpty() && removedUris.isEmpty() && missingState.isEmpty()) return
        val now = nowWallClockMs()
        val missingSet = missingUris.toSet()
        val removedSet = removedUris.toSet()
        val newState = HashMap(missingState)
        for (uri in removedSet) newState.remove(uri)
        for (uri in observedUris) {
            if (uri !in missingSet) newState.remove(uri)
        }
        for (uri in missingUris) {
            if (uri in removedSet) continue
            val prev = missingState[uri]
            newState[uri] = if (prev == null) {
                MissingInfo(now, 1)
            } else {
                MissingInfo(prev.firstMissingMs, prev.missCount + 1)
            }
        }
        persistMissing(newState)
    }

    /**
     * 缺失记录写入：内存先行同步更新（[recordMissing] / [updateMissingRecords] 的读取依赖它立即可见），
     * 数据库落盘走统一入口；内存变更受 [stateLock] 保护。
     */
    private fun persistMissing(state: Map<String, MissingInfo>) {
        val snapshot = state.toMap()
        synchronized(stateLock) { missingState = snapshot }
        dispatchDbWrite {
            kvDao().put(KvEntity(KEY_MISSING_URIS, encodeMissingJson(snapshot)))
        }
    }

    private fun nowWallClockMs(): Long = System.currentTimeMillis()

    /** 等待任务完成：成功返回 null，普通异常返回该异常，取消异常继续抛出。 */
    private suspend fun Deferred<Unit>.awaitOrNull(): Exception? = try {
        await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    /**
     * 统一写入入口：先更新内存，再串行落盘。
     * 若尚未加载成功，会在写入前尝试重试加载（受冷却限制），避免写入被静默丢弃。
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

    /** 仅落库的写入：内存状态已由调用方先行更新（如缺失记录）。 */
    private fun dispatchDbWrite(dbPart: suspend PlayerDatabase.() -> Unit) =
        dispatchWrite(memPart = {}, dbPart = dbPart)

    /** 加载失败后按冷却时间重启加载；返回是否已成功加载。 */
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

    /** 关闭旧数据库并重新发起加载（调用方需持有 loadLock）。 */
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

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** 在写锁与事务中执行一次落盘；失败重试一次，最终失败会广播 [writeFailures]。 */
    private suspend fun persist(block: suspend PlayerDatabase.() -> Unit): Boolean {
        repeat(PERSIST_MAX_ATTEMPTS) { attempt ->
            val database = db
            try {
                if (database == null) error("数据库尚未初始化")
                writeMutex.withLock { database.withTransaction { database.block() } }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == PERSIST_MAX_ATTEMPTS - 1) {
                    Log.e(TAG, "数据库写入 $PERSIST_MAX_ATTEMPTS 次失败，本次变更未落盘", e)
                    writeFailureChannel.trySend(Unit)
                    return false
                }
                delay(PERSIST_RETRY_DELAY_MS.milliseconds)
            }
        }
        return false
    }

    /** 真正打开数据库并把全量数据载入内存。 */
    private suspend fun loadInternal(appCtx: Context) {

        val database = Room.databaseBuilder(appCtx, PlayerDatabase::class.java, DB_NAME)
            .addMigrations(PlayerDatabase.MIGRATION_1_2)
            .build()
        db = database
        val playlist = database.playlistDao().getAll().map {
            MediaItemData(it.uri.toUri(), it.name, it.duration, it.folder)
        }
        val progress = database.progressDao().getAll().associate { it.uri to it.positionMs }
        val lastItem = database.kvDao().get(KEY_LAST_ITEM)
        val missing = parseMissingJson(database.kvDao().get(KEY_MISSING_URIS))

        synchronized(stateLock) {
            playlistState = playlist
            progressState = progress
            lastItemState = lastItem
            missingState = missing
        }
        loadSucceeded = true
    }
}

/** 进度写入裁决结果。 */
internal sealed interface ProgressWriteDecision {

    /** 已看完（落在末尾容差内）：清除该条进度。 */
    data object Clear : ProgressWriteDecision

    /** 正常记录该位置。 */
    data class Store(val positionMs: Long) : ProgressWriteDecision

    /** 无有效位置（<=0）：跳过，不写不删。 */
    data object Skip : ProgressWriteDecision
}

/** 末尾容差：距末尾 5 秒内视为已看完（周期落盘采样点常落在末尾几秒内）。 */
internal const val PROGRESS_FINISH_TAIL_MS = 5_000L

/** 判断位置是否已到达末尾（容差不超过总时长的 10%，适配超短片）。 */
internal fun isProgressFinished(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0) return false
    val tail = minOf(PROGRESS_FINISH_TAIL_MS, durationMs / 10)
    return positionMs >= durationMs - tail
}

/** 根据位置与时长效决定一次进度写入：清除 / 记录 / 跳过。 */
internal fun decideProgressWrite(positionMs: Long, durationMs: Long): ProgressWriteDecision {
    if (isProgressFinished(positionMs, durationMs)) return ProgressWriteDecision.Clear
    if (positionMs <= 0) return ProgressWriteDecision.Skip
    return ProgressWriteDecision.Store(positionMs)
}

/**
 * 合并写语义：先应用删除，再写入 > 0 的位置（0 不覆盖已有非零进度）。
 * 同一 uri 同时出现在 removes 与 writes 时，以 writes 为准。
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

/**
 * 缺失文件记录。
 * @property firstMissingMs 首次发现缺失的时间（墙钟毫秒，用于宽限期判断）
 * @property missCount 连续未在扫描中见到的次数
 */
internal data class MissingInfo(
    val firstMissingMs: Long,
    val missCount: Int,
)

/** 解析缺失记录 JSON（容错：非法或非对象值一律跳过）。 */
internal fun parseMissingJson(json: String?): Map<String, MissingInfo> {
    if (json.isNullOrEmpty()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        buildMap {
            for (key in obj.keys()) {
                val value = obj.optJSONObject(key) ?: continue
                val first = value.optLong("first", 0L)
                val count = value.optInt("count", 0)
                if (first > 0L) put(key, MissingInfo(first, count))
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 序列化缺失记录为 JSON。 */
internal fun encodeMissingJson(map: Map<String, MissingInfo>): String {
    val obj = JSONObject()
    for ((uri, info) in map) {
        obj.put(
            uri,
            JSONObject()
                .put("first", info.firstMissingMs)
                .put("count", info.missCount)
        )
    }
    return obj.toString()
}
