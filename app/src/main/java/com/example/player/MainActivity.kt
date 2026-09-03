package com.example.player

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as M3MediaItem

/**
 * 应用入口：进程启动时最先执行，触发持久化仓库的一次性加载
 * （Room 建库 + 旧 SharedPreferences 数据迁移），保证任何组件
 * （MainActivity / PlayerService）运行时数据已就绪或写入自动排队。
 */
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerRepository.ensureLoaded(this)
    }
}

/**
 * 主界面：播放器 + 播放列表面板。
 *
 * 通过 [MediaController] 连接到 [PlayerService] 中的播放器进行控制，同时承担：
 * - 本地媒体扫描（视频/音频）并维护播放列表
 * - 播放进度的恢复与保存（内存缓存 + 磁盘持久化 + 列表项进度条）
 * - 倍速切换与音轨/字幕选择
 *
 * 显示模式（全屏/PiP）、手势交互、应用内更新分别委托给：
 * [FullscreenPipHelper]、[GestureController]、[UpdateManager]。
 */
class MainActivity : AppCompatActivity() {
    /** 视图绑定对象，提供对全部布局控件的访问 */
    private lateinit var binding: ActivityMainBinding
    /** 播放列表适配器 */
    private lateinit var adapter: MediaListAdapter
    /** 内存中的播放列表数据 */
    private val playlist = mutableListOf<MediaItemData>()
    /** 异步构建 MediaController 的 Future（用于返回前台的重新连接） */
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    /** 当前已连接上的 MediaController */
    private var controller: MediaController? = null
    /** 当前正在播放的列表项下标，-1 表示无 */
    private var currentIndex = -1
    /** 标记播放列表是否已从磁盘加载过（防止重复加载） */
    private var playlistLoaded = false
    /** 内存中的播放进度缓存（uri 字符串 -> 位置毫秒） */
    private val cachedProgress = mutableMapOf<String, Long>()

    /** 本地媒体库扫描器（MediaStore 查询与全量权限判定） */
    private val scanner = MediaStoreScanner(this)
    /** 应用内更新管家（检查更新、下载、安装） */
    private lateinit var updateManager: UpdateManager
    /** 播放器手势控制（快进快退、亮度、音量、seek 预览） */
    private lateinit var gestures: GestureController
    /** 全屏与画中画切换 */
    private lateinit var fullscreenPip: FullscreenPipHelper

    private companion object {
        const val MENU_ID_CHECK_UPDATE = 100 // 溢出菜单里的「检查更新」项
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

    // ===== 播放列表管理 =====

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
        // 同步校正适配器内的高亮下标，否则删除后高亮漂移，直到下次切换/播放状态变化才自愈
        adapter.setCurrentPlaying(
            currentIndex.takeIf { it in playlist.indices } ?: -1,
            controller?.isPlaying == true
        )
        refreshPlaylist()
        savePlaylist()
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }

    /** 刷新列表与顶部计数 / 空态提示 */
    private fun refreshPlaylist() {
        // 先把当前内存进度刷进适配器，保证进度条展示与 cachedProgress 一致
        adapter.setProgress(cachedProgress)
        adapter.submitList(playlist.toList())
        binding.tvCount.text = if (playlist.isEmpty()) "空" else "${playlist.size} 个"
        binding.tvEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 把当前播放列表与进度快照交给仓库持久化（Room 全量替换列表 + 合并进度，单事务落盘）。
     * 仓库内部保证按调用顺序落库，这里先在主线程做快照，防止 IO 侧遍历期间列表被交互修改。
     */
    private fun savePlaylist() {
        PlayerRepository.savePlaylist(playlist.toList(), cachedProgress.toMap())
    }

    /**
     * 从仓库内存镜像恢复播放列表（数据源为 Room 数据库，进程启动时已一次性加载）。
     * 关键点（项目记忆）：loadPlaylist 只做数据加载，**不允许**调用 addMediaItem 同步到控制器；
     * 媒体项与控制器同步只发生在 onStart 且 controller.mediaItemCount == 0 时，
     * 否则服务存活重建时会导致队列被重复添加。
     */
    private fun loadPlaylist() {
        val progressMap = PlayerRepository.getProgressMap()
        // 跳过内存中已存在的项，并对磁盘数据按 uri 去重
        val existingKeys = playlist.map { normalizeUri(it.uri) }.toMutableSet()
        for (item in PlayerRepository.getPlaylist()) {
            val key = normalizeUri(item.uri)
            if (key in existingKeys) continue
            existingKeys.add(key)
            // 进度只取独立的 progress 表（唯一数据源）；有进度则同步进内存缓存
            val lastPos = progressMap[item.uri.toString()] ?: 0L
            if (lastPos > 0) cachedProgress[item.uri.toString()] = lastPos
            playlist.add(item)
        }
    }

    // ===== 播放进度管理 =====

    /**
     * 用持久层进度(Service 清理后的权威值)校正内存中的 cachedProgress 与列表进度条。
     * 背景：后台自动播完切集时 MainActivity 监听器已解绑，内存会残留"播完进度"；
     * 回前台若不校正，该残留值将在下次 savePlaylist 时被写回磁盘导致进度复活。
     */
    private fun reconcileProgressFromDisk() {
        val diskMap = PlayerRepository.getProgressMap()
        for (i in playlist.indices) {
            val uri = playlist[i].uri.toString()
            val diskVal = diskMap[uri]
            val memVal = cachedProgress[uri]
            if (diskVal != null && diskVal > 0) {
                // 磁盘有有效进度，以磁盘为权威对齐内存
                if (memVal != diskVal) {
                    cachedProgress[uri] = diskVal
                    adapter.updateProgress(i, diskVal)
                }
            } else if (memVal != null) {
                // 磁盘已无该进度(后台播完被清理/被删除)：移除内存残留，避免写回复活
                cachedProgress.remove(uri)
                adapter.updateProgress(i, 0L)
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
            adapter.updateProgress(index, 0)
            return
        }
        cachedProgress[uri] = position
        adapter.updateProgress(index, position)
    }

    /** 彻底清除某个 uri 的进度：内存缓存、Service 缓存、持久层三处一致删除（仓库异步落库） */
    private fun clearProgress(uri: Uri) {
        val key = uri.toString()
        cachedProgress.remove(key)
        PlayerService.dropProgress(key)
        // 复用统一的合并写入口（removes 语义），与 Service 的删除规则保持一致
        PlayerRepository.applyProgressUpdates(emptyMap(), setOf(key))
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
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (fullscreenPip.isInPipMode) fullscreenPip.updatePipAspectRatio(videoSize)
        }
    }

    /**
     * 统一的续播位置来源：以持久层(仓库内存镜像，Service 后台写入的唯一事实来源)为优先，
     * 持久层无该 uri 的记录时才回退到内存 cachedProgress。
     * 用于 fix「播放中切到别的条目，旧条目进度丢失」：持久层进度由 Service 周期+切换时精确落盘，更可靠。
     */
    private fun resolveResumePosition(item: MediaItemData): Long {
        val disk = PlayerRepository.getProgress(item.uri.toString())
        if (disk != null && disk > 0) return disk
        return cachedProgress[item.uri.toString()] ?: 0L
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
        val lastUri = PlayerRepository.getLastItem() ?: return
        val idx = playlist.indexOfFirst { it.uri.toString() == lastUri }
        if (idx !in playlist.indices) return
        if (ctrl.currentMediaItemIndex == idx) return
        val pos = resolveResumePosition(playlist[idx])
        if (pos > 0) ctrl.seekTo(idx, pos) else ctrl.seekToDefaultPosition(idx)
        currentIndex = idx
        adapter.setCurrentPlaying(idx, ctrl.isPlaying)
    }

    // ===== 播放器自定义控件（倍速/音轨/字幕） =====

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
        // 播放时保持屏幕常亮，防止系统屏幕超时自动变暗/熄屏
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
        AlertDialog.Builder(this)
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

    // ===== 本地媒体扫描与对账 =====

    /** 是否正在扫描（防止重复触发导致重复添加） */
    private var isScanning = false

    /**
     * MediaStore 内容观察者：监听本地视频/音频集合的变化。
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
            try {
                val videos = scanner.queryVideos()
                val audios = scanner.queryAudios()
                withContext(Dispatchers.Main) {
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
            } finally {
                isScanning = false
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
            try {
                if (!hasStoragePermission()) {
                    return@launch
                }
                val videos = scanner.queryVideos()
                val audios = scanner.queryAudios()
                withContext(Dispatchers.Main) {
                    if (pruneDeletedMedia(videos, audios) > 0) {
                        refreshPlaylist()
                        savePlaylist()
                    }
                }
            } finally {
                isScanning = false
            }
        }
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
        val videoKeys = if (scanner.hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_VIDEO)) {
            scannedVideos.map { normalizeUri(it.uri) }.toSet()
        } else null
        val audioKeys = if (scanner.hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_AUDIO)) {
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
        // 有删除时同步校正适配器高亮下标，避免删除后播放项高亮漂移
        if (removed > 0) {
            adapter.setCurrentPlaying(
                currentIndex.takeIf { it in playlist.indices } ?: -1,
                controller?.isPlaying == true
            )
        }
        return removed
    }

    // ===== 生命周期 =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateManager = UpdateManager(this)
        gestures = GestureController(this, binding.playerView, binding.gestureOverlay) { controller }
        fullscreenPip = FullscreenPipHelper(this, binding) { controller }

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
                AlertDialog.Builder(this)
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
        binding.btnPip.setOnClickListener { fullscreenPip.enterPipMode() }
        // 溢出菜单（⋮）：手动检查更新
        binding.btnMore.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_ID_CHECK_UPDATE, 0, R.string.check_update)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ID_CHECK_UPDATE -> {
                        updateManager.checkForUpdate(manual = true)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
        binding.playerView.setFullscreenButtonClickListener { enabled ->
            fullscreenPip.setFullscreen(enabled)
        }
        // 返回键：全屏时先退出全屏，否则退出界面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenPip.isFullscreen) {
                    fullscreenPip.setFullscreen(false)
                } else {
                    finish()
                }
            }
        })

        setupControllerExtras()
        gestures.setup()

        // API 33+ 申请通知权限
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        updateManager.registerReceivers()
        // 冷启动静默检查一次新版本（失败不打扰）
        updateManager.checkForUpdate(manual = false)
    }

    override fun onStart() {
        super.onStart()
        // 用户已在设置页授权「安装未知应用」：继续完成被挂起的更新安装
        updateManager.resumePendingInstall()
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
            // 数据相关部分等仓库一次性加载完成（通常早已就绪）后再执行，
            // 挂起恢复后重新读取 ctrl 状态，无过期读取风险
            lifecycleScope.launch {
                PlayerRepository.awaitLoaded(this@MainActivity)
                // 首次进入才从持久层恢复列表，避免服务存活重建时重复加载
                if (!playlistLoaded) {
                    loadPlaylist()
                    playlistLoaded = true
                } else {
                    // 回前台：用持久层(Service 已完成的清理)校正内存，防止后台播完项的残留进度复活
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
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        // 停止监听媒体库变化，并取消尚未触发的延时扫描
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        scanHandler.removeCallbacks(debouncedScanRunnable)
        // PiP 关闭返回前台时暂停播放，并复位 PiP 状态
        fullscreenPip.onStopped()
        gestures.cancelPendingHide()
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
        // 注销更新相关系统广播（下载完成/应用替换）
        updateManager.unregisterReceivers()
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        fullscreenPip.onPipModeChanged(isInPictureInPictureMode)
    }

    // ===== 存储权限 =====

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
