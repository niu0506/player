package com.example.player

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.player.databinding.ActivityMainBinding
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import androidx.media3.common.MediaItem as M3MediaItem
import androidx.core.net.toUri

/**
 * 主界面：播放器 + 播放列表面板。
 *
 * 通过 [MediaController] 连接到 [PlayerService] 中的播放器进行控制，同时承担：
 * - 本地媒体扫描（视频/音频）并维护播放列表
 * - 播放进度的恢复与保存（内存缓存 + 磁盘持久化 + 列表项进度条）
 * - 手势控制（双击/滑动调进度、亮度、音量）
 * - 全屏、横竖屏切换、画中画（PiP）小窗
 * - 倍速切换与音轨/字幕选择
 */
class MainActivity : AppCompatActivity() {
    /** 视图绑定对象，提供对全部布局控件的访问 */
    private lateinit var binding: ActivityMainBinding
    /** 播放列列表适配器 */
    private lateinit var adapter: MediaListAdapter
    /** 内存中的播放列表数据 */
    private val playlist = mutableListOf<MediaItemData>()
    /** 异步构建 MediaController 的 Future（用于返回前台的重新连接） */
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    /** 当前已连接上的 MediaController */
    private var controller: MediaController? = null
    /** 进度/列表等数据存储的 SharedPreferences，懒加载复用 */
    private val playerPrefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    /** 当前正在播放的列表项下标，-1 表示无 */
    private var currentIndex = -1
    /** 标记播放列表是否已从磁盘加载过（防止重复加载） */
    private var playlistLoaded = false
    /** 内存中的播放进度缓存（uri 字符串 -> 位置毫秒） */
    private val cachedProgress = mutableMapOf<String, Long>()

    /** 手势模式常量 */
    private companion object {
        const val GESTURE_NONE = 0 // 无手势
        const val GESTURE_SEEK = 1 // 左右滑动快进/快退
        const val GESTURE_BRIGHTNESS = 2 // 左半屏上下滑动调亮度
        const val GESTURE_VOLUME = 3 // 右半屏上下滑动调音量
        const val MENU_ID_CHECK_UPDATE = 100 // 溢出菜单里的「检查更新」项
        const val MENU_ID_PROXY_SETTINGS = 101 // 溢出菜单里的「下载代理设置」项
    }

    /** 主线程 Handler，用于手势提示的延时隐藏 */
    private val gestureHandler = Handler(Looper.getMainLooper())
    /** 隐藏手势提示的延时任务 */
    private val hideOverlayRunnable = Runnable { binding.gestureOverlay.visibility = View.GONE }
    /** 当前正在进行的手势模式 */
    private var gestureMode = GESTURE_NONE
    /** 手势开始时的播放位置（用于 seek） */
    private var seekStartPosition = 0L
    /** 手势结束时最终要跳转到的位置 */
    private var seekTargetPosition = 0L
    /** 手势开始时的亮度 */
    private var brightnessStart = 0f
    /** 手势开始时的音量 */
    private var volumeStart = 0
    /** 音频管理器（懒加载，用于音量调节） */
    private val gestureAudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }
    /** 通知权限请求（API 33+ 需要） */
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    /** 存储权限请求（多个权限，用于扫描本地媒体） */
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants: Map<String, Boolean> ->
        if (grants.values.any { it }) {
            Toast.makeText(this, "权限已授予，请点击扫描", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 应用内更新（GitHub Releases） =====
    /** 更新检查器 */
    private val updateChecker = UpdateChecker()
    /** 系统下载管理器（懒加载，更新包下载用它，自带通知栏进度与重试） */
    private val downloadManager by lazy { getSystemService(DOWNLOAD_SERVICE) as DownloadManager }
    /** 最近一次发起的下载 id，用于过滤广播 */
    private var lastDownloadId = -1L
    /** 等待用户授予「安装未知应用」权限后再安装的 APK 文件 */
    private var pendingInstallFile: File? = null

    /** 下载完成监听：更新包下载成功后自动调起系统安装器 */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != lastDownloadId) return
            val file = downloadedApkFile() ?: return
            installApk(file)
        }
    }

    /**
     * 规整化 Uri 字符串，作为去重/匹配的稳定 key。
     * 去掉 query 参数与末尾 '/'，并拼接末段路径，用于区分不同集合下同名的条目。
     */
    private fun normalizeUri(uri: Uri): String {
        val base = uri.buildUpon().clearQuery().build().toString().trimEnd('/')
        val lastSeg = uri.lastPathSegment ?: base
        return "$base|$lastSeg"
    }

    /** 从播放列表删除某个条目（同步清理其播放进度、控制器与磁盘记录） */
    private fun removeItemFromPlaylist(index: Int) {
        if (index !in playlist.indices) return
        val item = playlist[index]
        clearProgress(item.uri)
        playlist.removeAt(index)
        controller?.removeMediaItem(index)
        // 删除播放项之前的条目会使下标前移但不触发 transition，需手动校正 currentIndex，
        // 否则残留下标会绕过 AUTO 清理分支，把"播完进度"写回复活
        if (index < currentIndex) currentIndex--
        refreshPlaylist()
        savePlaylist()
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }

    /** 刷新列表与顶部计数 / 空态提示 */
    private fun refreshPlaylist() {
        adapter.submitList(playlist.toList())
        binding.tvCount.text = if (playlist.isEmpty()) "空" else "${playlist.size} 个"
        binding.tvEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
    }
    /** 是否正在扫描（防止重复触发导致重复添加） */
    private var isScanning = false

    /**
     * MediaStore 内容观察器：监听本地视频/音频集合的变化。
     * 媒体库有新增/修改/删除时系统会通知，触发一次「去重增量扫描」，
     * 避免每次点扫描都对整库全量重查，也免去手动点击的维护负担。
     */
    private val mediaChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        @Deprecated("Deprecated in Java")
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // MediaStore 常常连发多次通知，合并到一次延时扫描
            scanHandler.removeCallbacks(debouncedScanRunnable)
            scanHandler.postDelayed(debouncedScanRunnable, 800)
        }
    }
    /** 用于合并 MediaStore 多次通知的 Handler */
    private val scanHandler = Handler(Looper.getMainLooper())
    /** 延时的增量扫描任务（先校验权限，避免无权限时白白查询） */
    private val debouncedScanRunnable = Runnable {
        if (hasStoragePermission()) scanLocalMedia()
    }


    /** 扫描本地音视频并加入播放列表（在 IO 线程执行，带重入保护） */
    private fun scanLocalMedia() {
        if (isScanning) return
        isScanning = true
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            val audios = queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            withContext(Dispatchers.Main) {
                isScanning = false
                val scanned = mutableListOf<MediaItemData>()
                scanned.addAll(videos)
                scanned.addAll(audios)
                // 对账移除「文件已被外部删除」的条目，保证列表与实际文件一致
                val removedCount = pruneDeletedMedia(videos, audios)
                val existingKeys = playlist.map { normalizeUri(it.uri) }.toSet()
                // 过滤掉时长过短(<5秒)与已存在(去重)的条目
                val newItems = scanned.filter {
                    it.duration >= 5000 && normalizeUri(it.uri) !in existingKeys
                }
                if (newItems.isEmpty() && removedCount == 0) {
                    Toast.makeText(this@MainActivity, "没有新文件", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                playlist.addAll(newItems)
                for (item in newItems) {
                    controller?.addMediaItem(M3MediaItem.fromUri(item.uri))
                }
                refreshPlaylist()
                savePlaylist()
                val parts = mutableListOf<String>()
                if (removedCount > 0) parts.add("删除 $removedCount 个已消失文件")
                if (newItems.isNotEmpty()) parts.add("添加 ${newItems.size} 个文件")
                Toast.makeText(this@MainActivity, parts.joinToString("，"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 回前台时静默对账：移除「后台期间被外部删除」的文件。
     * 后台时 onStop 已取消 MediaStore 观察者、删除通知会错过，因此回到前台主动对账一次；
     * 只删除已消失的文件，不自动新增，也不弹提示，避免打扰用户。
     */
    private fun pruneDeletedMediaOnResume() {
        if (isScanning) return
        isScanning = true
        lifecycleScope.launch(Dispatchers.IO) {
            if (!hasStoragePermission()) {
                isScanning = false
                return@launch
            }
            val videos = queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            val audios = queryMediaStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            withContext(Dispatchers.Main) {
                isScanning = false
                if (pruneDeletedMedia(videos, audios) > 0) {
                    refreshPlaylist()
                    savePlaylist()
                }
            }
        }
    }

    /**
     * 查询 MediaStore 获取媒体文件列表。
     * @param contentUri 视频或音频的集合 Uri
     * @return 查询到的媒体列表（可能为空）
     */
    private fun queryMediaStore(contentUri: Uri): List<MediaItemData> {
        val items = mutableListOf<MediaItemData>()
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION
        )
        try {
            contentResolver.query(
                contentUri, projection, null, null,
                "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)
                    val duration = cursor.getLong(durIdx)
                    items.add(
                        MediaItemData(Uri.withAppendedPath(contentUri, id.toString()), name, duration)
                    )
                }
            }
        } catch (_: Exception) {
        }
        return items
    }

    /**
     * 用 MediaStore 扫描结果对账播放列表，移除「文件已从系统媒体库删除」的条目
     * （同时清理其播放进度与控制器队列中的对应项）。
     *
     * 权限保护：API 34+ 用户可能只授予「仅选中的照片/视频」(READ_MEDIA_VISUAL_USER_SELECTED)，
     * 此时 MediaStore 查询结果只含被选中的文件，若会把……「误删未授权但
     * 仍存在的文件。因此仅当拿到对应媒体类型的完整读取权限时才执行该类型的删除对账。
     *
     * @param scannedVideos 本次扫描到的视频集合
     * @param scannedAudios 本次扫描到的音频集合
     * @return 被移除的条数
     */
    private fun pruneDeletedMedia(
        scannedVideos: List<MediaItemData>,
        scannedAudios: List<MediaItemData>
    ): Int {
        val videoKeys = if (hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_VIDEO)) {
            scannedVideos.map { normalizeUri(it.uri) }.toSet()
        } else null
        val audioKeys = if (hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_AUDIO)) {
            scannedAudios.map { normalizeUri(it.uri) }.toSet()
        } else null
        val videoPrefix = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()
        val audioPrefix = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString()
        var removed = 0
        // 逆序遍历，保证删除过程下标不失效（playlist 与控制器队列保持同步）
        for (i in playlist.indices.reversed()) {
            val item = playlist[i]
            val uriStr = item.uri.toString()
            val keys = when {
                uriStr.startsWith(videoPrefix) -> videoKeys
                uriStr.startsWith(audioPrefix) -> audioKeys
                else -> null
            }
            if (keys != null && normalizeUri(item.uri) !in keys) {
                clearProgress(item.uri)
                playlist.removeAt(i)
                controller?.removeMediaItem(i)
                if (i < currentIndex) currentIndex--
                removed++
            }
        }
        return removed
    }

    /** 是否具备指定媒体类型的「全量」读取权限（API 34+ 的「仅选中」授权不算全量） */
    private fun hasFullMediaAccess(permission: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 把当前播放列表序列化为 JSON 存到 SharedPreferences（含各条目的上次进度） */
    private fun savePlaylist() {
        // 先把内存进度按与 Service 一致的「合并非覆盖」语义落盘，统一两侧来源，避免互相覆盖
        PlayerService.writeProgressBatch(
            playerPrefs, cachedProgress.toMap()
        )
        val arr = JSONArray()
        for (item in playlist) {
            val obj = JSONObject()
            obj.put("uri", item.uri.toString())
            obj.put("name", item.name)
            obj.put("duration", item.duration)
            obj.put("lastPosition", cachedProgress[item.uri.toString()] ?: item.lastPosition)
            arr.put(obj)
        }
        val snapshot = arr.toString()
        playerPrefs.edit {
            putString("playlist", snapshot)
        }
    }

    /**
     * 从 SharedPreferences 恢复播放列表。
     * 关键点（项目记忆）：loadPlaylist 只做数据加载，**不允许**调用 addMediaItem 同步到控制器；
     * 媒体项与控制器同步只发生在 onStart 且 controller.mediaItemCount == 0 时，
     * 否则服务存活重建时会导致队列被重复添加。
     */
    private fun loadPlaylist() {
        val prefs = playerPrefs
        val json = prefs.getString("playlist", null) ?: return
        val progressMap = PlayerService.readProgressMap(prefs)
        try {
            val arr = JSONArray(json)
            // 跳过内存中已存在的项，并对磁盘数据按 uri 去重
            val existingKeys = playlist.map { normalizeUri(it.uri) }.toMutableSet()
            val seen = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uri = Uri.parse(obj.getString("uri"))
                val key = normalizeUri(uri)
                if (key in seen || key in existingKeys) continue
                seen.add(key)
                existingKeys.add(key)
                val name = obj.getString("name")
                val duration = obj.optLong("duration", 0L)
                // 优先取 UUID 独立的进度存储，其次回退到 playlist 内记录
                val lastPos = progressMap[uri.toString()] ?: obj.optLong("lastPosition", 0L)
                playlist.add(MediaItemData(uri, name, duration, lastPos))
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 用磁盘进度(Service 清理后的权威值)校正内存中的 cachedProgress / playlist.lastPosition。
     * 背景：后台自动播完切集时 MainActivity 监听器已解绑，内存会残留"播完进度"；
     * 回前台若不校正，该残留值将在下次 savePlaylist 时被写回磁盘导致进度复活。
     */
    private fun reconcileProgressFromDisk() {
        val prefs = playerPrefs
        val diskMap = PlayerService.readProgressMap(prefs)
        for (i in playlist.indices) {
            val uri = playlist[i].uri.toString()
            val diskVal = diskMap[uri]
            if (diskVal != null && diskVal > 0) {
                // 磁盘有有效进度，以磁盘为权威对齐内存
                cachedProgress[uri] = diskVal
                if (playlist[i].lastPosition != diskVal) {
                    playlist[i] = playlist[i].copy(lastPosition = diskVal)
                    adapter.updateProgress(i, diskVal)
                }
            } else if (cachedProgress[uri] != null) {
                // 磁盘已无该进度(后台播完被清理/被删除)：移除内存残留，避免写回复活
                cachedProgress.remove(uri)
                if (playlist[i].lastPosition != 0L) {
                    playlist[i] = playlist[i].copy(lastPosition = 0L)
                    adapter.updateProgress(i, 0L)
                }
            }
        }
    }

    /** 保存当前播放项的进度到内存缓存并刷新列表进度条；播放到末尾则清除进度 */
    private fun saveCurrentProgress() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        if (index < 0 || index >= playlist.size) return
        val position = ctrl.currentPosition
        val duration = ctrl.duration
        val uri = playlist[index].uri.toString()
        if (position <= 0) return
        if (duration != C.TIME_UNSET && duration > 0 && position >= duration) {
            // 已播放到末尾，视为看完，清除该条目的进度
            cachedProgress.remove(uri)
            playlist[index] = playlist[index].copy(lastPosition = 0)
            adapter.updateProgress(index, 0)
            return
        }
        cachedProgress[uri] = position
        playlist[index] = playlist[index].copy(lastPosition = position)
        adapter.updateProgress(index, position)
    }

    /** 彻底清除某个 uri 的进度：内存缓存、磁盘映射、SharedPreferences 三处一致删除 */
    private fun clearProgress(uri: Uri) {
        cachedProgress.remove(uri.toString())
        PlayerService.dropProgress(uri.toString())
        val prefs = playerPrefs
        val map = PlayerService.readProgressMap(prefs).toMutableMap()
        map.remove(uri.toString())
        prefs.edit {
            putString("progress", PlayerService.writeProgressMap(map))
        }
    }

    /** 播放器事件监听：切换项/播放状态变化/就绪时的 UI 刷新与进度更新 */
    private val playerListener = object : Player.Listener {
        /** 媒体项发生切换：保存进度 / 清理刚播完项的残留进度 / 刷新当前播放项 / 恢复进度 */
        override fun onMediaItemTransition(mediaItem: M3MediaItem?, reason: Int) {
            saveCurrentProgress()
            // 自然播完自动切换到下一项时，用「上一项的下标」清掉它的近末尾进度
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                && currentIndex in playlist.indices
                && currentIndex != controller?.currentMediaItemIndex
            ) {
                val finishedIndex = currentIndex
                cachedProgress.remove(playlist[finishedIndex].uri.toString())
                playlist[finishedIndex] = playlist[finishedIndex].copy(lastPosition = 0)
                adapter.updateProgress(finishedIndex, 0)
            }
            currentIndex = controller?.currentMediaItemIndex ?: -1
            adapter.setCurrentPlaying(currentIndex, controller?.isPlaying == true)
            restoreProgressIfNeeded()
        }

        /** 播放/暂停变化：非播放时刷新进度到内存(UI)，磁盘由 Service 周期落盘，此处不重复 */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            adapter.setCurrentPlaying(currentIndex, isPlaying)
            if (!isPlaying) {
                saveCurrentProgress()
            }
        }

        /** 播放器就绪：回填真正读取到的时长 */
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val index = controller?.currentMediaItemIndex ?: -1
                val duration = controller?.duration ?: C.TIME_UNSET
                if (duration != C.TIME_UNSET && duration > 0 && index in playlist.indices) {
                    adapter.updateDuration(index, duration)
                    playlist[index] = playlist[index].copy(duration = duration)
                }
            }
        }

        /**
         * 视频尺寸变化时，若当前处于 PiP 小窗，则同步更新小窗宽高比例。
         * 这样进入小窗后如果画质/分辨率切换（含第一次取到真实分辨率），
         * 小窗会跟着视频比例自适应伸缩，避免出现黑边。
         */
        @RequiresApi(Build.VERSION_CODES.O)
        @OptIn(UnstableApi::class)
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (isInPipMode) updatePipAspectRatio()
        }
    }

    /**
     * 统一的续播位置来源：以磁盘(Service 后台写入的唯一事实来源)为优先，
     * 磁盘无该 uri 的记录时才回退到内存 lastPosition。
     * 用于 fix「播放中切到别的条目，旧条目进度丢失」：内存 lastPosition 只在少数字段
     * 更新、易过期，而磁盘进度由 Service 周期+切换时精确落盘，更可靠。
     */
    private fun resolveResumePosition(item: MediaItemData): Long {
        val disk = PlayerService.readProgressMap(playerPrefs)[item.uri.toString()]
        return if (disk != null && disk > 0) disk else item.lastPosition
    }

    /** 若当前项有保存过的进度（>0）则跳转到该位置，实现断点续播 */
    private fun restoreProgressIfNeeded() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        if (index < 0 || index >= playlist.size) return
        val savedPos = resolveResumePosition(playlist[index])
        if (savedPos > 0) {
            ctrl.seekTo(savedPos)
        }
    }

    /**
     * 冷启动/服务重建后恢复上次播放项：仅定位并回放断点，不自动起播，等用户按播放键。
     * 若播放器当下正在播放(如服务仍存活)，则不打断。
     */
    private fun restoreLastPlayed() {
        val ctrl = controller ?: return
        if (ctrl.playWhenReady || ctrl.isPlaying) return
        val prefs = playerPrefs
        val lastUri = prefs.getString("lastItem", null) ?: return
        val idx = playlist.indexOfFirst { it.uri.toString() == lastUri }
        if (idx !in playlist.indices) return
        if (ctrl.currentMediaItemIndex == idx) return
        val pos = resolveResumePosition(playlist[idx])
        if (pos > 0) ctrl.seekTo(idx, pos) else ctrl.seekToDefaultPosition(idx)
        currentIndex = idx
        adapter.setCurrentPlaying(idx, ctrl.isPlaying)
    }

    /** 可选择的倍速档位 */
    private val speedLevels = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 配置播放器自定义控件：循环/随机按钮、倍速、音轨、字幕 */
    @OptIn(UnstableApi::class)
    private fun setupControllerExtras() {
        // 启用「单曲循环」与「列表循环」切换；第二次类型需同时再点一次切换
        binding.playerView.setRepeatToggleModes(
            RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
        )
        binding.playerView.setShowShuffleButton(true)
        // 播放时保持屏幕常亮，防止系统屏幕超时自动变暗/熄灭
        binding.playerView.keepScreenOn = true

        val speedBtn = binding.playerView.findViewById<TextView>(R.id.btn_speed)
        speedBtn?.setOnClickListener {
            showSpeedSelection(speedBtn)
        }

        binding.playerView.findViewById<View>(R.id.btn_audio)?.setOnClickListener {
            showTrackSelection(C.TRACK_TYPE_AUDIO, "选择音轨")
        }
        binding.playerView.findViewById<View>(R.id.btn_subtitle)?.setOnClickListener {
            showTrackSelection(C.TRACK_TYPE_TEXT, "选择字幕")
        }
    }

    /** 在倍速档位中找到给定倍速的下标（找不到时回退到 1.0x 的下标） */
    private fun speedsIndex(speed: Float): Int {
        val idx = speedLevels.indexOfFirst { it == speed }
        if (idx >= 0) return idx
        val one = speedLevels.indexOfFirst { it == 1.0f }
        return if (one >= 0) one else 0
    }

    /** 弹出倍速选择对话框并应用所选倍速 */
    private fun showSpeedSelection(speedBtn: TextView) {
        val ctrl = controller ?: return
        val labels = speedLevels.map { formatSpeed(it) }.toTypedArray()
        val checked = speedsIndex(ctrl.playbackParameters.speed)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("倍速播放")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ctrl.setPlaybackSpeed(speedLevels[which])
                speedBtn.text = formatSpeed(speedLevels[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 倍速的展示文案，如 "1x"、"1.5x"、"2x" */
    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    }

    /** 弹出音轨/字幕选择对话框 */
    @OptIn(UnstableApi::class)
    private fun showTrackSelection(trackType: Int, title: String) {
        val ctrl = controller ?: run {
            Toast.makeText(this, "播放器未连接", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            TrackSelectionDialogBuilder(this, title, ctrl, trackType)
                .setShowDisableOption(false)
                .build()
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, "当前视频没有可选的轨道", Toast.LENGTH_SHORT).show()
        }
    }

    /** 初始化手势识别（单击显隐控制栏、双击快进快退、滑动调进度/亮度/音量） */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // 按下即消费事件，保证后续手势均由我们处理
            override fun onDown(e: MotionEvent): Boolean = true

            /** 单击：切换控制栏显隐 */
            @OptIn(UnstableApi::class)
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.playerView.isControllerFullyVisible) {
                    binding.playerView.hideController()
                } else {
                    binding.playerView.showController()
                }
                return true
            }

            /** 双击：左侧快退 15 秒，右侧快进 15 秒 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val ctrl = controller ?: return true
                if (e.x < binding.playerView.width / 2f) {
                    if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
                        ctrl.seekBack()
                        showGestureOverlay("快退 15 秒")
                    }
                } else {
                    if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
                        ctrl.seekForward()
                        showGestureOverlay("快进 15 秒")
                    }
                }
                return true
            }

            /** 滑动：
             * 横向手势 -> 进度快进/快退
             * 纵向 + 起点在左半屏 -> 亮度
             * 纵向 + 起点在右半屏 -> 音量
             */
            @OptIn(UnstableApi::class)
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float
            ): Boolean {
                val start = e1 ?: return false
                val ctrl = controller ?: return false
                // 手势刚开始时确定模式
                if (gestureMode == GESTURE_NONE) {
                    if (binding.playerView.isControllerFullyVisible) {
                        binding.playerView.hideController()
                    }
                    gestureMode = if (abs(e2.x - start.x) > abs(e2.y - start.y)) {
                        seekStartPosition = ctrl.currentPosition
                        seekTargetPosition = seekStartPosition
                        GESTURE_SEEK
                    } else if (start.x < binding.playerView.width / 2f) {
                        brightnessStart = currentBrightness()
                        GESTURE_BRIGHTNESS
                    } else {
                        volumeStart = gestureAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        GESTURE_VOLUME
                    }
                }
                when (gestureMode) {
                    // 根据横向位移换算毫秒目标位置，并实时预览
                    GESTURE_SEEK -> {
                        val duration = ctrl.duration
                        if (duration == C.TIME_UNSET || duration <= 0) {
                            gestureMode = GESTURE_NONE
                            return false
                        }
                        val msPerPx = max(300f, duration / binding.playerView.width * 1.2f)
                        val target = (seekStartPosition + (e2.x - start.x) * msPerPx)
                            .toLong().coerceIn(0L, duration)
                        seekTargetPosition = target
                        val deltaSec = (target - seekStartPosition) / 1000
                        val action = if (deltaSec >= 0) "快进" else "快退"
                        showGestureOverlay(
                            "$action ${abs(deltaSec)} 秒\n" +
                                "${formatTime(target)} / ${formatTime(duration)}"
                        )
                    }
                    // 纵向位移换算亮度增量并实时预览
                    GESTURE_BRIGHTNESS -> {
                        val delta = (start.y - e2.y) / binding.playerView.height
                        applyBrightness(brightnessStart + delta)
                        showGestureOverlay("亮度 ${(currentBrightness() * 100).toInt()}%")
                    }
                    // 纵向位移换算音量增量并实时预览
                    GESTURE_VOLUME -> {
                        val maxVolume =
                            gestureAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta =
                            ((start.y - e2.y) / binding.playerView.height * maxVolume).toInt()
                        val target = (volumeStart + delta).coerceIn(0, maxVolume)
                        gestureAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        val pct = if (maxVolume > 0) target * 100 / maxVolume else 0
                        showGestureOverlay("音量 $pct%")
                    }
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            // 手指抬起/取消时结束手势，若为 seek 则跳转到目标位置
            if (event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endGesture()
            }
            true
        }
    }

    /** 手势结束时执行最终动作（seek 手势才需要真正跳转），并复位手势状态 */
    private fun endGesture() {
        if (gestureMode == GESTURE_SEEK) {
            controller?.seekTo(seekTargetPosition)
        }
        gestureMode = GESTURE_NONE
    }

    /** 读取当前窗口亮度（跟随系统时返回默认 0.5） */
    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0f) 0.5f else b
    }

    /** 设置窗口亮度（限制在 0.02~1.0 之间） */
    private fun applyBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    /** 显示手势提示浮层，并在 800ms 后自动隐藏 */
    private fun showGestureOverlay(text: String) {
        gestureHandler.removeCallbacks(hideOverlayRunnable)
        binding.gestureOverlay.text = text
        binding.gestureOverlay.visibility = View.VISIBLE
        gestureHandler.postDelayed(hideOverlayRunnable, 800)
    }

    /** 是否处于全屏状态 */
    private var isFullscreen = false
    /** 是否处于画中画（PiP）模式 */
    private var isInPipMode = false

    /** PiP 模式变化回调：根据是否在小窗/全屏调整 UI 显隐与视频表面尺寸 */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        val uiVisible = !isInPipMode && !isFullscreen
        binding.toolbarLayout.visibility = if (uiVisible) View.VISIBLE else View.GONE
        binding.playlistContainer.visibility = if (uiVisible) View.VISIBLE else View.GONE
        binding.playerView.useController = !isInPipMode
        applyPipVideoSurface(isInPictureInPictureMode)
    }

    /** 依据当前是否 PiP / 全屏，调整视频容器边距、圆角与缩放模式，避免黑边 */
    @OptIn(UnstableApi::class)
    private fun applyPipVideoSurface(inPip: Boolean) {
        if (inPip) {
            // PiP：ZOOM 填满 + 无圆角无边距
            binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            binding.playerCard.radius = 0f
            val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.layoutParams = lp
        } else if (isFullscreen) {
            // 全屏：平滑铺满屏幕，无圆角无边距
            binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            binding.playerCard.radius = 0f
            val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.layoutParams = lp
        } else {
            // 普通横屏/竖屏：加圆角与边距的卡片样式
            applyNormalVideoSurfaceStyle()
        }
    }

    /** 普通非全屏、非 PiP 的卡片样式：FIT 缩放 + 12dp 边距 + 18dp 圆角 */
    @OptIn(UnstableApi::class)
    private fun applyNormalVideoSurfaceStyle() {
        binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val m = (12 * resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m / 3)
        binding.playerCard.radius = 18 * resources.displayMetrics.density
        binding.playerCard.layoutParams = lp
    }

    /** 切换全屏：隐藏/显示顶栏与播放列表，横屏/竖屏，隐藏/显示系统栏 */
    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        binding.btnBackFs.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
            binding.toolbarLayout.visibility = View.GONE
            binding.playlistContainer.visibility = View.GONE
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.radius = 0f
            binding.playerCard.layoutParams = lp
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
        } else {
            binding.toolbarLayout.visibility = View.VISIBLE
            binding.playlistContainer.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
            applyNormalVideoSurfaceStyle()
        }
    }

    /** 隐藏系统状态栏/导航栏（沉浸式全屏） */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 恢复显示系统状态栏/导航栏 */
    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** 进入画中画（PiP）小窗模式，失败时回退并提示 */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "系统不支持小窗播放", Toast.LENGTH_SHORT).show()
            return
        }
        val vs = controller?.videoSize
        if (vs == null || vs.width <= 0) {
            Toast.makeText(this, "当前没有正在播放的视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (isFullscreen) {
            setFullscreen(false)
        }
        // 隐藏 UI、关闭控制器、调整为 PiP 视频尺寸
        binding.toolbarLayout.visibility = View.GONE
        binding.playlistContainer.visibility = View.GONE
        binding.playerView.useController = false
        applyPipVideoSurface(true)
        try {
            // 以视频宽高比作为小窗比例，解析失败时回退 16:9
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(try {
                    Rational(vs.width, vs.height)
                } catch (_: Exception) {
                    Rational(16, 9)
                })
            // seamless resize 仅 API 31+ 支持；低版本省略该选项即可(此前误用 TODO 抛异常崩溃)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(true)
            }
            val params = builder.build()
            if (enterPictureInPictureMode(params)) return
        } catch (_: Exception) {
        }
        // 进入失败：恢复原有 UI
        binding.toolbarLayout.visibility = View.VISIBLE
        binding.playlistContainer.visibility = View.VISIBLE
        binding.playerView.useController = true
        applyPipVideoSurface(false)
        Toast.makeText(this, "无法进入小窗模式", Toast.LENGTH_SHORT).show()
    }

    /**
     * 用当前视频的真实宽高比动态更新 PiP 小窗比例，使小窗贴合视频、避免黑边。
     * 在 [enterPipMode] 之后由 onVideoSizeChanged 触发：视频分辨率在进入小窗后
     * 才确定（或中途切换清晰度）时，窗口会跟随比例平滑伸缩。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipAspectRatio() {
        val vs = controller?.videoSize ?: return
        if (vs.width <= 0 || vs.height <= 0) return
        if (!isInPipMode) return
        val ratio = try {
            Rational(vs.width, vs.height)
        } catch (_: Exception) {
            return
        }
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        try {
            setPictureInPictureParams(builder.build())
        } catch (_: Exception) {
        }
    }

    // ===== 应用内更新（GitHub Releases） =====

    /** 检查更新：请求 GitHub latest Release 并与本地版本比较。失败时仅手动触发给提示 */
    private fun checkForUpdate(manual: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val release = try {
                updateChecker.checkLatest()
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                val current = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
                when {
                    release == null -> if (manual) {
                        Toast.makeText(this@MainActivity, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                    !UpdateChecker.isNewer(release.version, current) -> if (manual) {
                        Toast.makeText(this@MainActivity, "已是最新版本 v$current", Toast.LENGTH_SHORT).show()
                    }
                    else -> showUpdateDialog(release)
                }
            }
        }
    }

    /** 弹出更新确认框：新版本号 + Release Notes，确认后下载 */
    private fun showUpdateDialog(release: UpdateChecker.Release) {
        val notes = release.notes.trim().ifEmpty { "优化体验并修复已知问题" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("发现新版本 v${release.version}")
            .setMessage(notes)
            .setPositiveButton("立即更新") { _, _ -> downloadApk(release) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 返回用户在「下载代理设置」里填的 GitHub 代理前缀，未配置时为空串 */
    private fun downloadProxyPrefix(): String =
        playerPrefs.getString("download_proxy", "")?.trim().orEmpty()

    /** 弹出「下载代理设置」对话框：填写用于下载更新包的 GitHub 代理前缀（可为空） */
    private fun showDownloadProxyDialog() {
        val input = android.widget.EditText(this)
        input.hint = "如 https://ghproxy.com/"
        input.setText(downloadProxyPrefix())
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.download_proxy_settings)
            .setMessage("填写后，下载更新 APK 时会在此代理前缀后面拼接 GitHub 直链，用于解决大陆无法直连 github.com 的问题。留空则走直连。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                playerPrefs.edit { putString("download_proxy", input.text.toString().trim()) }
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 拼接最终下载地址：若配置了代理前缀（如 https://ghproxy.com/），
     * 则在 GitHub 直链前拼接，以绕过大陆对 github.com 下载的不稳定访问。
     */
    private fun buildDownloadUrl(apkUrl: String): Uri {
        val prefix = downloadProxyPrefix()
        val url = if (prefix.isEmpty()) apkUrl else {
            val p = if (prefix.endsWith("/")) prefix else "$prefix/"
            p + apkUrl
        }
        return Uri.parse(url)
    }

    /** 用系统 DownloadManager 把更新包下载到应用专属目录（无需存储权限，通知栏自带进度） */
    private fun downloadApk(release: UpdateChecker.Release) {
        val fileName = "player-v${release.version}-release.apk"
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir == null) {
            Toast.makeText(this, "存储不可用，下载失败", Toast.LENGTH_SHORT).show()
            return
        }
        // 清掉旧的同名包，避免 DownloadManager 因目标文件已存在而拒绝覆盖
        File(dir, fileName).delete()
        val request = DownloadManager.Request(buildDownloadUrl(release.apkUrl))
            .setTitle("影音盒 v${release.version}")
            .setDescription("正在下载更新包")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        lastDownloadId = downloadManager.enqueue(request)
        Toast.makeText(this, "开始下载，完成后自动弹出安装", Toast.LENGTH_SHORT).show()
    }

    /** 取回刚下载完成的更新包文件（应用专属下载目录里最新的 .apk） */
    private fun downloadedApkFile(): File? {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() }
    }

    /** 安装更新包：FileProvider 暴露 APK 给系统安装器；Android 8+ 需「安装未知应用」授权 */
    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !packageManager.canRequestPackageInstalls()
        ) {
            // 未授权：跳到设置页，用户授权返回前台后自动继续安装
            pendingInstallFile = file
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri()
                    )
                )
            } catch (_: Exception) {
            }
            Toast.makeText(this, "请允许本应用安装未知应用，返回后将自动继续", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.file provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "无法启动安装器，请在下载通知中手动安装", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 构建列表适配器：点击播放（优先断点续播），删除移除
        adapter = MediaListAdapter(
            onClick = { index ->
                if (index in playlist.indices) {
                    // 切走前先把当前播放项(A)的精确进度同时记录到内存与磁盘(saveCurrentProgress 更新
                    // 续播所读的内存值；flushCurrentPosition 同步落盘)。因 onMediaItemTransition 触发
                    // 时 controller 已切到新条目、读不到旧项位置，必须在 seekTo 之前记录。
                    saveCurrentProgress()
                    PlayerService.flushCurrentPosition()
                    val item = playlist[index]
                    // 统一续播位置：优先磁盘权威进度，磁盘无记录才回退内存
                    val pos = resolveResumePosition(item)
                    if (pos > 0 && (item.duration !in 1..pos)) {
                        controller?.seekTo(index, pos)
                    } else {
                        controller?.seekToDefaultPosition(index)
                    }
                    controller?.play()
                }
            },
            onDelete = { index ->
                if (index !in playlist.indices) return@MediaListAdapter
                val item = playlist[index]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("删除条目")
                    .setMessage("确定从播放列表中删除「${item.name}」吗？\n该文件的播放进度也会被清除。")
                    .setPositiveButton("删除") { _, _ -> removeItemFromPlaylist(index) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        binding.recyclerPlaylist.adapter = adapter
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(this)

        // 扫描按钮：先检查存储权限
        binding.btnScan.setOnClickListener {
            if (hasStoragePermission()) {
                scanLocalMedia()
            } else {
                requestStoragePermission()
            }
        }
        binding.btnPip.setOnClickListener { enterPipMode() }
        // 溢出菜单（⋮）：手动检查更新
        binding.btnMore.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_ID_CHECK_UPDATE, 0, R.string.check_update)
            popup.menu.add(0, MENU_ID_PROXY_SETTINGS, 0, R.string.download_proxy_settings)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ID_CHECK_UPDATE -> {
                        checkForUpdate(manual = true)
                        true
                    }
                    MENU_ID_PROXY_SETTINGS -> {
                        showDownloadProxyDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
        binding.playerView.setFullscreenButtonClickListener { enabled ->
            setFullscreen(enabled)
        }
        binding.btnBackFs.setOnClickListener {
            if (isFullscreen) setFullscreen(false)
        }
        // 返回键：全屏时先退出全屏，否则退出界面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    setFullscreen(false)
                } else {
                    finish()
                }
            }
        })

        setupControllerExtras()
        setupGestures()

        // API 33+ 申请通知权限
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // 监听更新包下载完成（API 33+ 需 RECEIVER_NOT_EXPORTED 标志）
        ContextCompat.registerReceiver(
            this, downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 冷启动静默检查一次新版本（失败不打扰）
        checkForUpdate(manual = false)
    }

    override fun onStart() {
        super.onStart()
        // 用户已在设置页授权「安装未知应用」：继续完成被挂起的更新安装
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || packageManager.canRequestPackageInstalls()
            ) {
                pendingInstallFile = null
                installApk(file)
            }
        }
        // 监听本地媒体库变化，实现新增/修改时的去重增量自动扫描
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        // 回前台对账：后台期间文件被外部删除时 MediaStore 通知已错过，主动移除已消失的条目
        pruneDeletedMediaOnResume()
        // 通过 SessionToken 异步连接后台服务中的播放器
        val sessionToken = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            // 关键点（项目记忆）：回调可能晚于 onStop 触发导致 future 已被释放，
            // 因此必须用 controllerFuture !== future 判空，避免 NPE 或重挂已释放控制器
            if (controllerFuture !== future) return@addListener
            val ctrl = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            controller = ctrl
            binding.playerView.player = ctrl
            ctrl.addListener(playerListener)
            currentIndex = ctrl.currentMediaItemIndex
            adapter.setCurrentPlaying(currentIndex, ctrl.isPlaying)
            // 首次进入才从磁盘恢复列表，避免服务存活重建时重复加载
            if (!playlistLoaded) {
                loadPlaylist()
                playlistLoaded = true
            } else {
                // 回前台：用磁盘(Service 已完成的清理)校正内存，防止后台播完项的残留进度复活
                reconcileProgressFromDisk()
            }
            // 队列与内存列表数量不一致时(含控制器尚为空/重连失步)全量重灌自愈；
            // 空队列时 cur 为 null，天然不触发 seek
            if (playlist.isNotEmpty() && ctrl.mediaItemCount != playlist.size) {
                val cur = ctrl.currentMediaItem
                val curUri = cur?.localConfiguration?.uri?.toString()
                val curPos = ctrl.currentPosition
                ctrl.clearMediaItems()
                for (item in playlist) {
                    ctrl.addMediaItem(M3MediaItem.fromUri(item.uri))
                }
                val curIdx = curUri?.let { u -> playlist.indexOfFirst { it.uri.toString() == u } }
                if (curIdx != null && curIdx >= 0) {
                    ctrl.seekTo(curIdx, curPos)
                }
            }
            // 冷启动/服务重建后恢复上次播放项(仅定位 + 断点，等用户按播放)
            restoreLastPlayed()
            refreshPlaylist()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        // 停止监听媒体库变化，并取消尚未触发的延时扫描
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        scanHandler.removeCallbacks(debouncedScanRunnable)
        // PiP 关闭返回前台时暂停播放，并复位 PiP 状态
        if (isInPipMode) {
            controller?.pause()
            isInPipMode = false
        }
        gestureHandler.removeCallbacks(hideOverlayRunnable)
        saveCurrentProgress()
        // 释放控制器与播放器视图的绑定
        binding.playerView.player = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgress()
    }

    override fun onDestroy() {
        // 注销更新包下载完成监听
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** 是否已获得存储读取权限（API 33+ 检查媒体权限，旧版本检查外部存储权限） */
    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        val fullOrAudio =
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        // API 34+ 用户可选「仅访问选中的照片/视频」，此时授予 READ_MEDIA_VISUAL_USER_SELECTED
        return if (Build.VERSION.SDK_INT >= 34) {
            fullOrAudio || ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            fullOrAudio
        }
    }

    /** 请求存储读取权限（按系统版本选择对应权限组合） */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            storagePermission.launch(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            )
        } else if (Build.VERSION.SDK_INT >= 33) {
            storagePermission.launch(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
                )
            )
        } else {
            storagePermission.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
}