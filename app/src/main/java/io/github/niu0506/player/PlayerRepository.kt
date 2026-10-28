/** 数据层：媒体模型、MediaStore 扫描、排序与 Room 持久化。 */
package io.github.niu0506.player

import android.annotation.SuppressLint
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

/** 播放列表条目；[folderKey] 为空表示未分组（排序时置后）。 */
data class MediaItemData(
    val uri: Uri,
    val name: String,
    val duration: Long = 0L,
    val folderKey: String = "",
)

/** 归一化 URI 作为比较键：去掉 query 与末尾斜杠。 */
fun normalizeUri(uri: Uri): String = normalizeUri(uri.toString())

fun normalizeUri(uri: String): String = uri.substringBefore('?').trimEnd('/')

internal fun scanDisplayNameOrFallback(raw: String?, fallback: String): String =
    if (raw.isNullOrBlank()) fallback else raw

internal fun isExcludedAudioType(isRingtone: Int, isNotification: Int, isAlarm: Int): Boolean =
    isRingtone != 0 || isNotification != 0 || isAlarm != 0

/** 排除铃声、通知音、闹钟音、非音乐与录音；低版本 [isRecording] 传 0。 */
internal fun isExcludedAudioRow(
    isRingtone: Int,
    isNotification: Int,
    isAlarm: Int,
    isMusic: Int,
    isRecording: Int,
): Boolean =
    isExcludedAudioType(isRingtone, isNotification, isAlarm) ||
        isMusic == 0 ||
        isRecording != 0

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

internal fun folderKeyFromRelativePath(relativePath: String?): String =
    relativePath?.trim()?.trim('/')?.trim().orEmpty()

/** 低版本从绝对路径取父目录；存储根下转为相对路径。 */
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

/** 按数值比较数字段，避免前导零与溢出问题。 */
private fun compareDigitSegments(a: String, b: String): Int {
    val x = a.trimStart('0').ifEmpty { "0" }
    val y = b.trimStart('0').ifEmpty { "0" }
    val byLength = x.length.compareTo(y.length)
    return if (byLength != 0) byLength else x.compareTo(y)
}

/** 先忽略大小写比较，再按大小写敏感比较以保证全序。 */
private fun compareTextSegments(a: String, b: String): Int {
    val byIgnoreCase = a.compareTo(b, ignoreCase = true)
    return if (byIgnoreCase != 0) byIgnoreCase else a.compareTo(b)
}

/** 自然排序：数字段按数值比较，且排在文本段之前。 */
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

/** 文件夹分组（未分组置后）→ 名称自然序 → URI 兜底。 */
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

internal fun sortPlaylist(items: List<MediaItemData>): List<MediaItemData> =
    items.sortedWith(::comparePlaylistItems)

/** 合并数据库与扫描结果，按 URI 去重且数据库顺序优先。 */
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

/** 在锁内重试；最终失败回调 [onFinalFailure] 并返回 null。 */
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

internal const val MIN_SCAN_DURATION_MS = 5_000L

/** 用扫描结果刷新名称、时长与文件夹；未扫描到的条目保持原样。 */
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

internal data class ScanReconcileResult(
    val playlist: List<MediaItemData>,
    val addedCount: Int,
    val removedCount: Int,
    val orderChanged: Boolean,
)

/** 删除消失项、追加新文件、刷新元数据并重排；手动与静默扫描都导入新文件。 */
internal fun reconcilePlaylist(
    current: List<MediaItemData>,
    scannedVideos: List<MediaItemData>?,
    scannedAudios: List<MediaItemData>?,
    removedUris: Set<String>,
): ScanReconcileResult {
    val afterPrune = if (removedUris.isEmpty()) {
        current
    } else {
        current.filterNot { it.uri.toString() in removedUris }
    }
    val scanned = scannedVideos.orEmpty() + scannedAudios.orEmpty()
    val newItems = buildList {
        val seen = afterPrune.map { normalizeUri(it.uri) }.toMutableSet()
        for (item in scanned) {
            if (item.duration >= MIN_SCAN_DURATION_MS && seen.add(normalizeUri(item.uri))) {
                add(item)
            }
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

internal data class PruneCandidates(
    val missingUris: Set<String>,
    val observedUris: Set<String>,
    val removedUris: Set<String>,
)

/** 判断可删除的缺失条目：单条复核确认或宽限期到期。 */
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

private val timeFormatCache = LruCache<Long, String>(512)

/** 格式化为 `mm:ss` 或 `h:mm:ss`。 */
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

/** MediaStore 扫描器：API 29+ 用 RELATIVE_PATH 分组，低版本用 DATA 父目录。 */
class MediaStoreScanner(private val context: Context) {

    private companion object {
        const val TAG = "MediaStoreScanner"

        val AUDIO_TYPE_COLUMNS = arrayOf(
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_MUSIC,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // IS_RECORDING 仅 API 31+ 存在
            arrayOf(MediaStore.Audio.Media.IS_RECORDING)
        } else {
            emptyArray()
        }
    }

    fun queryVideos(): List<MediaItemData>? = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    fun queryAudios(): List<MediaItemData>? = query(
        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        extraColumns = AUDIO_TYPE_COLUMNS,
        includeRow = ::shouldIncludeAudioRow,
    )

    /** 文件夹分组列：API 29+ 用 RELATIVE_PATH，否则用 DATA。 */
    @Suppress("DEPRECATION")
    private val folderColumn: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    @Suppress("DEPRECATION")
    private val storageRoot: String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Environment.getExternalStorageDirectory()?.absolutePath
        } else {
            null
        }

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
                    // DISPLAY_NAME 可能为 null，按行兜底以免整次扫描失败
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

    @Suppress("InlinedApi") // IS_RECORDING 常量 API 31+，但为编译期内联字符串，低版本安全
    private fun shouldIncludeAudioRow(cursor: Cursor): Boolean = !isExcludedAudioRow(
        isRingtone = cursor.getIntOrZero(MediaStore.Audio.Media.IS_RINGTONE),
        isNotification = cursor.getIntOrZero(MediaStore.Audio.Media.IS_NOTIFICATION),
        isAlarm = cursor.getIntOrZero(MediaStore.Audio.Media.IS_ALARM),
        isMusic = cursor.getIntOrZero(MediaStore.Audio.Media.IS_MUSIC),
        isRecording = cursor.getIntOrZero(MediaStore.Audio.Media.IS_RECORDING),
    )

    /** 读取整型列；列缺失或为 NULL 返回 0。 */
    private fun Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

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

/** 是否拥有媒体读取权限；[includePartial] 允许 API 34+ 的「选中项」授权。 */
fun hasMediaReadAccess(context: Context, permission: String, includePartial: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < 33) {
        return isPermissionGranted(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return isPermissionGranted(context, permission) ||
        (includePartial && Build.VERSION.SDK_INT >= 34 &&
            isPermissionGranted(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
}

@SuppressLint("InlinedApi")
fun hasVideoReadAccess(context: Context, includePartial: Boolean): Boolean =
    hasMediaReadAccess(context, android.Manifest.permission.READ_MEDIA_VIDEO, includePartial)

@SuppressLint("InlinedApi")
fun hasAudioReadAccess(context: Context, includePartial: Boolean): Boolean =
    hasMediaReadAccess(context, android.Manifest.permission.READ_MEDIA_AUDIO, includePartial)

fun hasAnyMediaReadAccess(context: Context, includePartial: Boolean): Boolean =
    hasVideoReadAccess(context, includePartial) || hasAudioReadAccess(context, includePartial)

private fun isPermissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Entity(tableName = "playlist")
data class PlaylistItemEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val duration: Long,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "''") val folder: String = "",
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
)

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

/** Room 数据库：升级版本需补 Migration 并提交 schema。 */
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
        /** v1→v2：playlist 新增 folder 列。 */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

/** 全局仓库：内存状态为权威读取源，写库串行执行。 */
object PlayerRepository {
    private const val TAG = "PlayerRepository"
    private const val DB_NAME = "player.db"

    private const val PROGRESS_DELETE_CHUNK = 500

    private const val KEY_MISSING_URIS = "missingUris"

    /** 无法即时确认时的删除宽限期，避免瞬时不可用导致误删。 */
    internal const val MISSING_DELETE_GRACE_MS = 24 * 60 * 60 * 1000L

    private const val KEY_LAST_ITEM = "lastItem"

    /** 单线程 IO 作用域，保证写库按提交顺序串行。 */
    private val persistScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val writeMutex = Mutex()

    private val writeFailureChannel = Channel<Unit>(Channel.CONFLATED)
    val writeFailures: Flow<Unit> = writeFailureChannel.receiveAsFlow()

    private val stateLock = Any()

    @Volatile
    private var db: PlayerDatabase? = null
    private var loadJob: Deferred<Unit>? = null
    private val loadLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastLoadAttemptMs = 0L

    @Volatile
    private var resetting = false

    /** 写代际：重置时自增；旧代际的写任务会在提交前被丢弃。 */
    @Volatile
    private var writeGeneration = 0L

    /** 本进程是否已尝试过自愈，避免反复清库。 */
    @Volatile
    private var selfHealAttempted = false

    private val DB_SIDECAR_SUFFIXES = arrayOf("", "-wal", "-shm", "-journal")

    private const val LOAD_RETRY_COOLDOWN_MS = 30_000L

    private const val PERSIST_MAX_ATTEMPTS = 2

    private const val PERSIST_RETRY_DELAY_MS = 200L

    @Volatile
    private var playlistState: List<MediaItemData> = emptyList()

    @Volatile
    private var progressState: Map<String, Long> = emptyMap()

    @Volatile
    private var lastItemState: String? = null

    @Volatile
    private var missingState: Map<String, MissingInfo> = emptyMap()

    @Volatile
    private var loadSucceeded = false

    fun ensureLoaded(context: Context) {
        synchronized(loadLock) {
            if (appContext == null) appContext = context.applicationContext
            if (resetting) return
            val running = loadJob?.isCompleted == false
            if (!running && !loadSucceeded) restartLoadLocked()
        }
    }

    suspend fun awaitLoaded(context: Context): Boolean {
        ensureLoaded(context)
        val job = synchronized(loadLock) { loadJob } ?: return false
        val failure = job.awaitOrNull() ?: return true
        Log.e(TAG, "数据库加载失败", failure)
        synchronized(loadLock) {
            if (loadJob === job && !loadSucceeded) {
                synchronized(stateLock) { clearMemoryStateLocked() }
            }
        }
        return loadSucceeded
    }

    fun getPlaylist(): List<MediaItemData> = playlistState

    fun isLoaded(): Boolean = loadSucceeded

    /** 清空内存状态；调用方需持有 [stateLock]。 */
    private fun clearMemoryStateLocked() {
        playlistState = emptyList()
        progressState = emptyMap()
        lastItemState = null
        missingState = emptyMap()
    }

    suspend fun resetAllData(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        synchronized(loadLock) {
            appContext = appCtx
            resetting = true
            // 推进代际，使在途写任务在提交前放弃
            writeGeneration++
        }
        try {
            // 等当前加载结束再关闭数据库
            synchronized(loadLock) { loadJob }?.join()
            val reset = writeMutex.withLock {
                synchronized(loadLock) {
                    loadJob = null
                    loadSucceeded = false
                    closeDb("关闭数据库失败")
                    synchronized(stateLock) { clearMemoryStateLocked() }
                }
                val dbFile = appCtx.getDatabasePath(DB_NAME)
                if (!deleteDatabaseFiles(appCtx, dbFile)) {
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

    fun getProgress(uri: String): Long? = progressState[uri]

    fun getLastItem(): String? = lastItemState

    /** 批量应用进度变更（0 不覆盖旧值）；[onPersisted] 报告落盘结果。 */
    fun applyProgressUpdates(
        writes: Map<String, Long>,
        removes: Set<String> = emptySet(),
        onPersisted: (Boolean) -> Unit = {}
    ) {
        // 0 不覆盖旧值：内存与数据库共用同一次过滤
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
                // 绑定参数有上限，批量删除按块提交
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

    fun savePlaylist(items: List<MediaItemData>) {
        // 兜底去重，避免主键冲突写出脏数据
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

    fun setLastItem(uri: String) {
        dispatchWrite(
            memPart = { lastItemState = uri },
            dbPart = { kvDao().put(KvEntity(KEY_LAST_ITEM, uri)) }
        )
    }

    internal fun getMissingInfo(uri: String): MissingInfo? = missingState[uri]

    fun recordMissing(uri: String) {
        updateMissingState { current ->
            if (current.containsKey(uri)) {
                current
            } else {
                current + (uri to MissingInfo(System.currentTimeMillis(), 0))
            }
        }
    }

    /** 用本轮扫描结果对账缺失记录（未见到 +1，见到即恢复）。 */
    fun updateMissingRecords(
        missingUris: Collection<String>,
        observedUris: Collection<String>,
        removedUris: Collection<String> = emptySet(),
    ) {
        val now = System.currentTimeMillis()
        updateMissingState { current ->
            mergeMissingRecords(current, missingUris, observedUris, removedUris, now)
        }
    }

    private fun updateMissingState(
        transform: (Map<String, MissingInfo>) -> Map<String, MissingInfo>
    ) {
        val snapshot = synchronized(stateLock) {
            // 重置期间不再写入，避免清空后又被加回内存
            if (resetting) return
            val newState = transform(missingState)
            if (newState == missingState) return
            missingState = newState
            newState
        }
        dispatchWrite(
            memPart = {},
            dbPart = { kvDao().put(KvEntity(KEY_MISSING_URIS, encodeMissingJson(snapshot))) },
        )
    }

    /** 等待任务：成功返回 null，普通异常返回，取消异常继续抛出。 */
    private suspend fun Deferred<Unit>.awaitOrNull(): Exception? = try {
        await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    private fun dispatchWrite(
        memPart: () -> Unit,
        dbPart: suspend PlayerDatabase.() -> Unit,
        onComplete: (Boolean) -> Unit = {}
    ) {
        // 重置开始后的写入一律丢弃
        val snapshot = synchronized(loadLock) {
            if (resetting) null else loadJob to writeGeneration
        } ?: run {
            Log.w(TAG, "仓库正在重置，丢弃一次写任务")
            onComplete(false)
            return
        }
        val job = snapshot.first
        val generation = snapshot.second
        if (job == null) {
            Log.w(TAG, "仓库尚未初始化，丢弃一次写任务")
            onComplete(false)
            return
        }
        if (loadSucceeded) {
            persistScope.launch {
                if (!applyMemoryPart(generation, memPart)) {
                    Log.w(TAG, "仓库已重置，丢弃一次写任务")
                    onComplete(false)
                    return@launch
                }
                onComplete(persist(dbPart, generation))
            }
        } else {
            persistScope.launch {
                val failure = job.awaitOrNull()
                // 等待期间已重置：本次写入作废
                if (writeGeneration != generation) {
                    Log.w(TAG, "仓库已重置，丢弃一次写任务")
                    onComplete(false)
                    return@launch
                }
                if (failure != null && !reloadAfterFailure(generation)) {
                    Log.e(TAG, "数据库加载失败且重试未成功，本次写入未落盘")
                    onComplete(false)
                    return@launch
                }
                if (!applyMemoryPart(generation, memPart)) {
                    Log.w(TAG, "仓库已重置，丢弃一次写任务")
                    onComplete(false)
                    return@launch
                }
                onComplete(persist(dbPart, generation))
            }
        }
    }

    private fun applyMemoryPart(generation: Long, memPart: () -> Unit): Boolean =
        synchronized(stateLock) {
            if (writeGeneration != generation) {
                false
            } else {
                memPart()
                true
            }
        }

    private suspend fun reloadAfterFailure(generation: Long): Boolean {
        val job = synchronized(loadLock) {
            // 重置期间/重置之后不重新加载，交由 resetAllData 自己启动新库
            if (writeGeneration != generation || resetting) return false
            if (loadSucceeded) return true
            val running = loadJob?.isCompleted == false
            if (!running && nowMs() - lastLoadAttemptMs >= LOAD_RETRY_COOLDOWN_MS) {
                restartLoadLocked()
            }
            loadJob
        } ?: return false
        return job.awaitOrNull() == null
    }

    private fun closeDb(logMessage: String? = null) {
        try {
            db?.close()
        } catch (e: Exception) {
            if (logMessage != null) Log.w(TAG, logMessage, e)
        }
        db = null
    }

    private fun deleteDatabaseFiles(appCtx: Context, dbFile: File): Boolean {
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
        return !dbFile.exists()
    }

    /** 重启加载（调用方持有 loadLock）。 */
    private fun restartLoadLocked() {
        val ctx = appContext ?: return
        loadJob = null
        loadSucceeded = false
        closeDb()
        lastLoadAttemptMs = nowMs()
        loadJob = persistScope.async { loadInternal(ctx) }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** 写锁内事务落盘；重试保持顺序。 */
    private suspend fun persist(
        block: suspend PlayerDatabase.() -> Unit,
        generation: Long,
    ): Boolean {
        // 等锁前先快速判断，避免为已作废的写任务排队
        if (writeGeneration != generation) return false
        val result = withOrderedRetry(
            mutex = writeMutex,
            maxAttempts = PERSIST_MAX_ATTEMPTS,
            retryDelayMs = PERSIST_RETRY_DELAY_MS,
            onFinalFailure = { e ->
                Log.e(TAG, "数据库写入 $PERSIST_MAX_ATTEMPTS 次失败，本次变更未落盘", e)
                writeFailureChannel.trySend(Unit)
            }
        ) {
            // 拿锁后再次核对代际，避免向重建后的库写旧数据
            if (writeGeneration != generation) return@withOrderedRetry false
            // 锁内读取，避免向已关闭的旧句柄写入
            val database = db ?: error("数据库尚未初始化")
            database.withTransaction { database.block() }
            true
        }
        return result == true
    }

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

    private fun backupAndResetDatabaseFiles(appCtx: Context): Boolean {
        val dbFile = appCtx.getDatabasePath(DB_NAME)
        val backupDir = File(appCtx.noBackupFilesDir, "db-backup-${System.currentTimeMillis()}")
        return try {
            closeDb("关闭损坏数据库失败")
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
                        // 主库必须备份成功；旁路文件失败可忽略
                        if (suffix.isEmpty()) throw e
                        Log.w(TAG, "备份数据库旁路文件失败: ${source.name}", e)
                    }
                }
                Log.w(TAG, "旧数据库已备份到 ${backupDir.absolutePath}")
            }
            if (!deleteDatabaseFiles(appCtx, dbFile)) {
                Log.e(TAG, "删除损坏数据库失败: ${dbFile.absolutePath}")
                false
            } else {
                synchronized(stateLock) { clearMemoryStateLocked() }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "备份或重建数据库失败", e)
            false
        }
    }
}

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

internal sealed interface ProgressWriteDecision {

    /** 记录位置；已看完时记录总时长。 */
    data class Store(val positionMs: Long) : ProgressWriteDecision

    data object Skip : ProgressWriteDecision
}

/** 距末尾 5 秒内视为已看完。 */
internal const val PROGRESS_FINISH_TAIL_MS = 5_000L

/** 是否到达末尾（5 秒或 10% 容差）。 */
internal fun isProgressFinished(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0) return false
    val tail = minOf(PROGRESS_FINISH_TAIL_MS, durationMs / 10)
    return positionMs >= durationMs - tail
}

/** 可续播位置；无记录、开头或已看完时返回 null。 */
internal fun resumablePositionMs(positionMs: Long?, durationMs: Long): Long? =
    if (positionMs != null && positionMs > 0 && !isProgressFinished(positionMs, durationMs)) {
        positionMs
    } else {
        null
    }

internal fun decideProgressWrite(positionMs: Long, durationMs: Long): ProgressWriteDecision {
    if (isProgressFinished(positionMs, durationMs)) {
        return ProgressWriteDecision.Store(durationMs)
    }
    if (positionMs <= 0) return ProgressWriteDecision.Skip
    return ProgressWriteDecision.Store(positionMs)
}

/** 合并进度：先删除再写入，同一 URI 以写入为准。 */
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

internal data class MissingInfo(
    val firstMissingMs: Long,
    val missCount: Int,
)

/** 缺失记录状态迁移：删除优先，见到即恢复，未见到计数 +1。 */
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

/** 解析缺失记录 JSON；非法数据跳过。 */
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
