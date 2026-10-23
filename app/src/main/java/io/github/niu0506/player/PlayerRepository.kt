/**
 * 数据与存储层：媒体模型、MediaStore 扫描、排序、Room 持久化与进度写入裁决。
 * 内存状态是权威读取源，所有写库操作经 [PlayerRepository.dispatchWrite] 串行执行。
 */
package io.github.niu0506.player

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabaseCorruptException
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

/** 归一化 URI 作为比较键：去掉 query 与末尾斜杠，并拼接最后一段路径。 */
fun normalizeUri(uri: Uri): String {
    val base = uri.toString().substringBefore('?').trimEnd('/')
    val lastSeg = uri.lastPathSegment ?: base
    return "$base|$lastSeg"
}

/** 扫描到的名称为空时用兜底文案，避免平台空值传给非空模型。 */
internal fun scanDisplayNameOrFallback(raw: String?, fallback: String): String =
    if (raw.isNullOrBlank()) fallback else raw

/** 铃声 / 通知音 / 闹钟音不属于媒体播放列表。 */
internal fun isExcludedAudioType(isRingtone: Int, isNotification: Int, isAlarm: Int): Boolean =
    isRingtone != 0 || isNotification != 0 || isAlarm != 0

/** 在播放器队列中按 URI 定位条目；找不到返回 -1，避免用列表下标误删队列项。 */
internal fun findMediaItemIndexByUri(
    mediaItemCount: Int,
    uriAt: (Int) -> Uri?,
    target: Uri,
): Int {
    val key = normalizeUri(target)
    return (0 until mediaItemCount).firstOrNull { index ->
        uriAt(index)?.let(::normalizeUri) == key
    } ?: -1
}

/** API 29+ 使用 MediaStore 的 RELATIVE_PATH 作为分组键：去除首尾空白与斜杠。 */
internal fun folderKeyFromRelativePath(relativePath: String?): String =
    relativePath?.trim()?.trim('/')?.trim().orEmpty()

/** API 29 以下从绝对路径取父目录；在存储根下则转成相对路径。 */
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

/** 超出安全位数时用「长度 + 字典序」比较，避免 Long 溢出。 */
private fun compareDigitTails(x: String, y: String): Int {
    val byLength = x.length.compareTo(y.length)
    return if (byLength != 0) byLength else x.compareTo(y)
}

/** 比较两段纯数字：先按数值大小；超出安全位数时退化为长度 + 字典序。 */
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

/** 自然排序：数字段按数值比较（第2集 < 第10集），数字段排在文本段之前。 */
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

/** 首次加载时合并数据库列表与内存中尚未落盘的条目（数据库顺序优先，按 URI 去重）。 */
internal fun mergePlaylistOnLoad(
    stored: List<MediaItemData>,
    scanned: List<MediaItemData>
): List<MediaItemData> {
    val result = ArrayList<MediaItemData>(stored.size + scanned.size)
    val seen = HashSet<String>(stored.size + scanned.size)
    for (item in stored) {
        if (seen.add(normalizeUri(item.uri))) result.add(item)
    }
    for (item in scanned) {
        if (seen.add(normalizeUri(item.uri))) result.add(item)
    }
    return result
}

/** 在锁内重试写入，保证重试顺序；最终失败回调 [onFinalFailure] 并返回 null。 */
internal suspend fun <T> withOrderedRetry(
    mutex: Mutex,
    maxAttempts: Int,
    retryDelayMs: Long,
    onFinalFailure: (Exception) -> Unit = {},
    block: suspend () -> T
): T? = mutex.withLock {
    repeat(maxAttempts) { attempt ->
        try {
            return@withLock block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt == maxAttempts - 1) {
                onFinalFailure(e)
                return@withLock null
            }
            delay(retryDelayMs.milliseconds)
        }
    }
    null
}

/** 扫描导入的最短时长：过滤提示音、极短片段等噪声。 */
internal const val MIN_SCAN_DURATION_MS = 5_000L

/** 按 URI 用扫描结果刷新名称、时长与文件夹；扫描结果中不存在的条目保持原样。 */
internal fun mergeScannedMetadata(
    items: List<MediaItemData>,
    scanned: List<MediaItemData>
): List<MediaItemData> {
    if (items.isEmpty() || scanned.isEmpty()) return items
    val scannedByKey = scanned.associateBy { normalizeUri(it.uri) }
    var changed = false
    val merged = items.map { item ->
        val scannedItem = scannedByKey[normalizeUri(item.uri)] ?: return@map item
        val name = scannedItem.name.ifBlank { item.name }
        val duration = if (scannedItem.duration > 0) scannedItem.duration else item.duration
        if (name == item.name &&
            duration == item.duration &&
            scannedItem.folderKey == item.folderKey
        ) {
            item
        } else {
            changed = true
            item.copy(name = name, duration = duration, folderKey = scannedItem.folderKey)
        }
    }
    return if (changed) merged else items
}

/** 一次扫描对账的结果；纯计算，不涉及 Activity/播放器状态。 */
internal data class ScanReconcileResult(
    val playlist: List<MediaItemData>,
    val addedCount: Int,
    val removedCount: Int,
    /** 条目顺序是否发生变化（仅比较 URI 顺序，不把元数据刷新算作重排）。 */
    val orderChanged: Boolean,
)

/** 纯函数对账：删除已消失条目、追加新文件、刷新元数据并重排；静默扫描不追加。 */
internal fun reconcilePlaylist(
    current: List<MediaItemData>,
    scannedVideos: List<MediaItemData>?,
    scannedAudios: List<MediaItemData>?,
    removedUris: Set<String>,
    silent: Boolean,
): ScanReconcileResult {
    val afterPrune = if (removedUris.isEmpty()) {
        current
    } else {
        current.filterNot { it.uri.toString() in removedUris }
    }
    val scanned = scannedVideos.orEmpty() + scannedAudios.orEmpty()
    val newItems = if (silent) {
        emptyList()
    } else {
        val seen = afterPrune.map { normalizeUri(it.uri) }.toMutableSet()
        scanned.filter { item ->
            item.duration >= MIN_SCAN_DURATION_MS && seen.add(normalizeUri(item.uri))
        }
    }
    val appended = afterPrune + newItems
    val merged = mergeScannedMetadata(appended, scanned)
    val ordered = sortPlaylist(merged)
    return ScanReconcileResult(
        playlist = ordered,
        addedCount = newItems.size,
        removedCount = current.size - afterPrune.size,
        orderChanged = ordered.map { it.uri.toString() } != appended.map { it.uri.toString() },
    )
}

/** 缺失文件对账的中间结果；由 IO 线程计算，主线程只负责应用。 */
internal data class PruneCandidates(
    val missingUris: Set<String>,
    val observedUris: Set<String>,
    val removedUris: Set<String>,
)

/** 判断哪些缺失条目可以删除：单条复核确认，或宽限期到期。 */
internal fun decidePruneCandidates(
    playlistSnapshot: List<MediaItemData>,
    videoKeys: Set<String>?,
    audioKeys: Set<String>?,
    videoPrefix: String,
    audioPrefix: String,
    canConfirmImmediately: Boolean,
    missingInfo: (String) -> MissingInfo?,
    confirmedDeleted: (Uri) -> Boolean,
    now: Long,
    graceMs: Long,
): PruneCandidates {
    val missingNow = mutableSetOf<String>()
    val observedNow = mutableSetOf<String>()
    val removedUris = mutableSetOf<String>()
    for ((uri) in playlistSnapshot) {
        val uriStr = uri.toString()
        val keys = when {
            uriStr.startsWith(videoPrefix) -> videoKeys
            uriStr.startsWith(audioPrefix) -> audioKeys
            else -> null
        } ?: continue
        observedNow.add(uriStr)
        if (normalizeUri(uri) in keys) continue
        missingNow.add(uriStr)
        val prev = missingInfo(uriStr)
        val graceExpired = prev != null && prev.missCount >= 1 &&
            now - prev.firstMissingMs >= graceMs
        val confirmed = canConfirmImmediately && confirmedDeleted(uri)
        if (graceExpired || confirmed) removedUris.add(uriStr)
    }
    return PruneCandidates(missingNow, observedNow, removedUris)
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

/** MediaStore 扫描器：API 29+ 用 RELATIVE_PATH 分组，低版本用 DATA 的父目录。 */
class MediaStoreScanner(private val context: Context) {

    private companion object {
        const val TAG = "MediaStoreScanner"

        /** 音频类型列：用于过滤铃声、通知音与闹钟音。 */
        val AUDIO_TYPE_COLUMNS = arrayOf(
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM,
        )
    }

    /** 查询所有视频；失败（权限/异常）返回 null。 */
    fun queryVideos(): List<MediaItemData>? = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    /** 查询所有音频；失败（权限/异常）返回 null。 */
    fun queryAudios(): List<MediaItemData>? = query(
        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        extraColumns = AUDIO_TYPE_COLUMNS,
        includeRow = ::shouldIncludeAudioRow,
    )

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

    /** 执行一次查询；异常或 cursor 为 null 时返回 null，交由调用方按「扫描失败」处理。 */
    @Suppress("InlinedApi") // DURATION 常量 API 29+，但为编译期内联字符串，低版本可安全使用
    private fun query(
        contentUri: Uri,
        extraColumns: Array<String> = emptyArray(),
        includeRow: (Cursor) -> Boolean = { true },
    ): List<MediaItemData>? {
        val items = mutableListOf<MediaItemData>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            folderColumn
        ) + extraColumns
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
                    if (!includeRow(it)) continue
                    val id = it.getLong(idIdx)
                    // DISPLAY_NAME 是平台类型：某些坏数据会返回 null，
                    // 直接传给非空模型会让整次扫描失败，这里按行兜底。
                    val name = scanDisplayNameOrFallback(
                        it.getString(nameIdx),
                        context.getString(R.string.unknown_file),
                    )
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
        } catch (e: Exception) {
            Log.e(TAG, "查询媒体库失败: $contentUri", e)
            null
        }
    }

    /** 过滤铃声、通知音与闹钟音；列缺失或为 NULL 时按“不排除”处理。 */
    private fun shouldIncludeAudioRow(cursor: Cursor): Boolean = !isExcludedAudioType(
        isRingtone = cursor.getIntOrZero(MediaStore.Audio.Media.IS_RINGTONE),
        isNotification = cursor.getIntOrZero(MediaStore.Audio.Media.IS_NOTIFICATION),
        isAlarm = cursor.getIntOrZero(MediaStore.Audio.Media.IS_ALARM),
    )

    /** 读取整型列；列不存在或值为 NULL 时返回 0。 */
    private fun Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
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

/** Room 数据库：未启用破坏性迁移，升级版本时必须补 Migration 并提交导出的 schema。 */
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

/** 全局仓库：内存状态为权威读取源，写入串行落盘，加载失败可按冷却时间重试。 */
object PlayerRepository {
    private const val TAG = "PlayerRepository"
    private const val DB_NAME = "player.db"

    /** 进度批量删除时单条 SQL 的最大绑定数（SQLite 约 999）。 */
    private const val PROGRESS_DELETE_CHUNK = 500

    /** KV 表里存放「缺失文件记录」的键。 */
    private const val KEY_MISSING_URIS = "missingUris"

    /** 无法即时确认时，文件消失后需等待此时长才允许删除，避免瞬时不可用导致误删。 */
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

    /** 手动重置进行中：阻止 ensureLoaded 并发启动新的加载。 */
    @Volatile
    private var resetting = false

    /** 本进程是否已尝试过损坏/迁移失败自愈，避免反复清库。 */
    @Volatile
    private var selfHealAttempted = false

    /** 数据库旁路文件：备份与删除时一并处理。 */
    private val DB_SIDECAR_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")

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
            if (resetting) return
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

    /**
     * 删除本地数据库并重建（播放列表、进度、上次播放项、缺失记录都会清空）。
     * 调用方必须先向用户确认；返回 true 表示新库已成功加载。
     */
    suspend fun resetAllData(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        synchronized(loadLock) {
            appContext = appCtx
            resetting = true
        }
        try {
            // 先等当前加载结束，避免关闭数据库时它仍在读取
            synchronized(loadLock) { loadJob }?.join()
            val reset = writeMutex.withLock {
                synchronized(loadLock) {
                    loadJob = null
                    loadSucceeded = false
                    try {
                        db?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "关闭数据库失败", e)
                    }
                    db = null
                    synchronized(stateLock) {
                        playlistState = emptyList()
                        progressState = emptyMap()
                        lastItemState = null
                        missingState = emptyMap()
                    }
                }
                val dbFile = appCtx.getDatabasePath(DB_NAME)
                val deleted = try {
                    appCtx.deleteDatabase(DB_NAME)
                } catch (e: Exception) {
                    Log.e(TAG, "删除数据库失败", e)
                    false
                }
                if (!deleted) {
                    for (suffix in DB_SIDECAR_SUFFIXES) {
                        File(dbFile.path + suffix).delete()
                    }
                }
                if (dbFile.exists()) {
                    Log.e(TAG, "重置失败：数据库文件仍存在")
                    false
                } else {
                    synchronized(loadLock) {
                        selfHealAttempted = false
                        lastLoadAttemptMs = 0L
                        restartLoadLocked()
                    }
                    true
                }
            }
            if (!reset) return@withContext false
            val job = synchronized(loadLock) { loadJob } ?: return@withContext false
            job.awaitOrNull() == null && loadSucceeded
        } finally {
            synchronized(loadLock) { resetting = false }
        }
    }

    /** 读取某 uri 的进度；无记录返回 null。 */
    fun getProgress(uri: String): Long? = progressState[uri]

    /** 读取上次播放的 uri。 */
    fun getLastItem(): String? = lastItemState

    /**
     * 批量应用进度变更：只写 > 0 的位置（0 不覆盖旧值），先删除后写入。
     * @param onPersisted 落盘完成回调，true 表示已成功写库
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

    /** 全量保存播放列表：内存立即更新，数据库异步整体替换。 */
    fun savePlaylist(items: List<MediaItemData>) {
        // 兜底去重：即使界面侧因异常路径产生重复 URI，也不让主键冲突的整表替换写出脏数据
        val itemsSnapshot = items.distinctBy { normalizeUri(it.uri) }
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
        updateMissingState { current ->
            if (current.containsKey(uri)) {
                current
            } else {
                current + (uri to MissingInfo(nowWallClockMs(), 0))
            }
        }
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
        updateMissingState { current ->
            mergeMissingRecords(current, missingUris, observedUris, removedUris, now)
        }
    }

    /** 缺失记录写入口：在 [stateLock] 内读-改-写，无变化时不写库。 */
    private fun updateMissingState(
        transform: (Map<String, MissingInfo>) -> Map<String, MissingInfo>
    ) {
        val snapshot = synchronized(stateLock) {
            val newState = transform(missingState)
            if (newState == missingState) return
            missingState = newState
            newState
        }
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

    /** 统一写入入口：先更新内存，再串行落盘；未加载成功时先尝试重试加载。 */
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

    /** 在写锁与事务中落盘，失败重试一次；重试保持在锁内以维持写入顺序。 */
    private suspend fun persist(block: suspend PlayerDatabase.() -> Unit): Boolean {
        val result = withOrderedRetry(
            mutex = writeMutex,
            maxAttempts = PERSIST_MAX_ATTEMPTS,
            retryDelayMs = PERSIST_RETRY_DELAY_MS,
            onFinalFailure = { e ->
                Log.e(TAG, "数据库写入 $PERSIST_MAX_ATTEMPTS 次失败，本次变更未落盘", e)
                writeFailureChannel.trySend(Unit)
            }
        ) {
            // 在锁内读取，避免重试期间数据库被重建/关闭后仍向旧句柄写入
            val database = db ?: error("数据库尚未初始化")
            database.withTransaction { database.block() }
        }
        return result != null
    }

    /** 真正打开数据库并把全量数据载入内存。 */
    private suspend fun loadInternal(appCtx: Context) {
        try {
            openAndLoad(appCtx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (selfHealAttempted || !isSelfHealableDatabaseFailure(e)) throw e
            selfHealAttempted = true
            Log.e(TAG, "数据库损坏或迁移失败，备份旧库后重建", e)
            if (!backupAndResetDatabaseFiles(appCtx)) throw e
            openAndLoad(appCtx)
        }
    }

    /** 打开 Room 并全量读取；调用方负责失败后的重试/自愈。 */
    private suspend fun openAndLoad(appCtx: Context) {
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

    /** 备份旧库文件（含 -wal/-shm/-journal）后删除，成功返回 true。 */
    private fun backupAndResetDatabaseFiles(appCtx: Context): Boolean {
        val dbFile = appCtx.getDatabasePath(DB_NAME)
        val backupDir = File(appCtx.noBackupFilesDir, "db-backup-${System.currentTimeMillis()}")
        return try {
            try {
                db?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭损坏数据库失败", e)
            }
            db = null
            if (dbFile.exists()) {
                if (!backupDir.mkdirs() && !backupDir.isDirectory) {
                    Log.e(TAG, "无法创建数据库备份目录: ${backupDir.absolutePath}")
                    return false
                }
                for (suffix in DB_SIDECAR_SUFFIXES) {
                    val source = File(dbFile.path + suffix)
                    if (!source.exists()) continue
                    try {
                        source.copyTo(File(backupDir, source.name), overwrite = true)
                    } catch (e: Exception) {
                        // 主库必须备份成功；旁路文件在极端情况下可能被 SQLite 回收，失败可忽略
                        if (suffix.isEmpty()) throw e
                        Log.w(TAG, "备份数据库旁路文件失败: ${source.name}", e)
                    }
                }
                Log.w(TAG, "旧数据库已备份到 ${backupDir.absolutePath}")
            }
            val deleted = appCtx.deleteDatabase(DB_NAME)
            if (!deleted) {
                for (suffix in DB_SIDECAR_SUFFIXES) {
                    File(dbFile.path + suffix).delete()
                }
            }
            if (dbFile.exists()) {
                Log.e(TAG, "删除损坏数据库失败: ${dbFile.absolutePath}")
                false
            } else {
                synchronized(stateLock) {
                    playlistState = emptyList()
                    progressState = emptyMap()
                    lastItemState = null
                    missingState = emptyMap()
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "备份或重建数据库失败", e)
            false
        }
    }
}

/** 判断加载失败是否属于可通过备份旧库并重建来自愈的损坏/迁移问题。 */
internal fun isSelfHealableDatabaseFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        val message = current.message.orEmpty().lowercase(Locale.US)
        if (current is SQLiteDatabaseCorruptException) return true
        if ("database disk image is malformed" in message ||
            "file is encrypted or is not a database" in message ||
            "database corruption" in message ||
            "no such table: room_master_table" in message
        ) {
            return true
        }
        if (current is IllegalStateException &&
            ("a migration from" in message ||
                "migration didn't properly handle" in message ||
                "room cannot verify the data integrity" in message)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/** 进度写入裁决结果。 */
internal sealed interface ProgressWriteDecision {

    /** 记录位置；已看完时记录总时长，用于列表显示 100% 完成标记。 */
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

/**
 * 根据位置与时长决定一次进度写入：完成标记 / 记录 / 跳过。
 * 看完后保留「位置 = 总时长」的记录，列表可显示 100% 与「已看完」；
 * 续播判定仍会忽略已看完的位置，不会从末尾重新开始。
 */
internal fun decideProgressWrite(positionMs: Long, durationMs: Long): ProgressWriteDecision {
    if (isProgressFinished(positionMs, durationMs)) {
        return ProgressWriteDecision.Store(durationMs)
    }
    if (positionMs <= 0) return ProgressWriteDecision.Skip
    return ProgressWriteDecision.Store(positionMs)
}

/** 合并进度：先删除，再写入 > 0 的位置；同一 URI 同时删除与写入时以写入为准。 */
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

/**
 * 缺失记录状态迁移：删除优先；见到的 URI 视为恢复；
 * 未见到的 URI 计数 +1，首次缺失时间不变。
 */
internal fun mergeMissingRecords(
    current: Map<String, MissingInfo>,
    missingUris: Collection<String>,
    observedUris: Collection<String>,
    removedUris: Collection<String>,
    now: Long,
): Map<String, MissingInfo> {
    val missingSet = missingUris.toSet()
    val removedSet = removedUris.toSet()
    val newState = HashMap(current)
    for (uri in removedSet) newState.remove(uri)
    for (uri in observedUris) {
        if (uri !in missingSet) newState.remove(uri)
    }
    for (uri in missingUris) {
        if (uri in removedSet) continue
        val prev = current[uri]
        newState[uri] = if (prev == null) {
            MissingInfo(now, 1)
        } else {
            MissingInfo(prev.firstMissingMs, prev.missCount + 1)
        }
    }
    return newState
}

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
