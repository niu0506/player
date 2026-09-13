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
import androidx.media3.common.PlaybackException
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
import androidx.media3.common.MediaMetadata as M3MediaMetadata

/** 应用入口：进程启动即触发仓库一次性加载（Room 建库 + 旧 prefs 迁移） */
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerRepository.ensureLoaded(this)
    }
}

/**
 * 主界面：播放器 + 播放列表面板。经 MediaController 控制 PlayerService 中的播放器，
 * 负责本地媒体扫描、播放进度恢复/保存、倍速与音轨/字幕选择；
 * 全屏/PiP、手势、更新分别委托给 [FullscreenPipHelper]、[GestureController]、[UpdateManager]。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaListAdapter
    private val playlist = mutableListOf<MediaItemData>()
    /** 异步构建 MediaController 的 Future（用于返回前台重新连接） */
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    /** 当前播放项下标，-1 表示无 */
    private var currentIndex = -1
    /** 列表是否已从磁盘加载过（防止重复加载） */
    private var playlistLoaded = false
    /** 内存进度缓存（uri -> 位置毫秒） */
    private val cachedProgress = mutableMapOf<String, Long>()

    private val scanner = MediaStoreScanner(this)
    private lateinit var updateManager: UpdateManager
    private lateinit var gestures: GestureController
    private lateinit var fullscreenPip: FullscreenPipHelper

    private companion object {
        const val MENU_ID_CHECK_UPDATE = 100
    }

    /** 通知权限请求（API 33+） */
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants: Map<String, Boolean> ->
        if (grants.values.any { it }) {
            Toast.makeText(this, "权限已授予，请点击扫描", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 播放列表管理 =====

    /**
     * 移除指定下标的条目（清理进度/控制器队列并校正 currentIndex），不含 UI 刷新与落盘。
     * 删除当前项之前的条目不触发 transition，必须手动校正下标，否则残留下标会让「播完进度」复活。
     */
    private fun removeItemAt(index: Int) {
        if (index !in playlist.indices) return
        clearProgress(playlist[index].uri)
        playlist.removeAt(index)
        controller?.removeMediaItem(index)
        if (index < currentIndex) currentIndex--
    }

    /** 用户删除条目：移除后校正高亮、刷新 UI、落盘并提示 */
    private fun removeItemFromPlaylist(index: Int) {
        if (index !in playlist.indices) return
        removeItemAt(index)
        adapter.setCurrentPlaying(
            currentIndex.takeIf { it in playlist.indices } ?: -1,
            controller?.isPlaying == true
        )
        refreshPlaylist()
        savePlaylist()
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }

    /** 刷新列表、顶部计数与空态提示 */
    private fun refreshPlaylist() {
        adapter.setProgress(cachedProgress)
        adapter.submitList(playlist.toList())
        binding.tvCount.text = if (playlist.isEmpty()) "空" else "${playlist.size} 个"
        binding.tvEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 列表与进度快照交给仓库持久化（主线程先做快照，防 IO 侧遍历期间列表被修改） */
    private fun savePlaylist() {
        PlayerRepository.savePlaylist(playlist.toList(), cachedProgress.toMap())
    }

    /**
     * 当前播放列表的 normalizeUri 键集合，作为「已存在」判定的共用基准：
     * loadPlaylist 据此去重（防历史脏数据），reconcileScanResult 据此过滤
     * 扫描重复项；两处共用同一实现，防止「已存在」的定义各自漂移。
     */
    private fun existingUriKeys(): Set<String> = playlist.map { normalizeUri(it.uri) }.toSet()

    /**
     * 从仓库恢复播放列表（按 uri 去重）。只加载数据，不同步控制器——
     * 媒体项同步只发生在 onStart 且数量失不时，否则服务存活重建会重复添加队列。
     */
    private fun loadPlaylist() {
        val progressMap = PlayerRepository.getProgressMap()
        // toMutableSet：循环内逐个 add，连带对「本次加载列表内部」的重复去重
        val existingKeys = existingUriKeys().toMutableSet()
        for (item in PlayerRepository.getPlaylist()) {
            val key = normalizeUri(item.uri)
            if (key in existingKeys) continue
            existingKeys.add(key)
            val lastPos = progressMap[item.uri.toString()] ?: 0L
            if (lastPos > 0) cachedProgress[item.uri.toString()] = lastPos
            playlist.add(item)
        }
    }

    // ===== 播放进度管理 =====

    /**
     * 用持久层的权威进度校正内存缓存与列表进度条。
     * 后台播完切集时前台监听已解绑、内存残留「播完进度」，不校正会在下次落盘时复活。
     */
    private fun reconcileProgressFromDisk() {
        val diskMap = PlayerRepository.getProgressMap()
        for (i in playlist.indices) {
            val uri = playlist[i].uri.toString()
            val diskVal = diskMap[uri]
            val memVal = cachedProgress[uri]
            if (diskVal != null && diskVal > 0) {
                if (memVal != diskVal) {
                    cachedProgress[uri] = diskVal
                    adapter.updateProgress(i, diskVal)
                }
            } else if (memVal != null) {
                // 磁盘已无该进度（播完被清理/删除）：移除内存残留
                cachedProgress.remove(uri)
                adapter.updateProgress(i, 0L)
            }
        }
    }

    /**
     * 保存当前项进度到内存缓存并刷新进度条。
     * 写入/清除/跳过的判定统一由 decideProgressWrite 裁决（唯一权威实现），
     * 这里只负责把结果落到 cachedProgress 与列表进度条
     */
    private fun saveCurrentProgress() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        if (index < 0 || index >= playlist.size) return
        val uri = playlist[index].uri.toString()
        when (val decision = decideProgressWrite(ctrl.currentPosition, ctrl.duration)) {
            is ProgressWriteDecision.Clear -> {
                // 播到末尾视为看完：清除进度，进度条归零
                cachedProgress.remove(uri)
                adapter.updateProgress(index, 0)
            }
            is ProgressWriteDecision.Store -> {
                cachedProgress[uri] = decision.positionMs
                adapter.updateProgress(index, decision.positionMs)
            }
            ProgressWriteDecision.Skip -> Unit // 零位置不覆盖旧进度
        }
    }

    /** 清除某个 uri 的进度：内存、Service 缓存、持久层三处一致删除 */
    private fun clearProgress(uri: Uri) {
        val key = uri.toString()
        cachedProgress.remove(key)
        PlayerService.dropProgress(key)
        PlayerRepository.applyProgressUpdates(emptyMap(), setOf(key))
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: M3MediaItem?, reason: Int) {
            saveCurrentProgress()
            // 自然播完自动切换时，用「上一项的下标」清掉其近末尾进度
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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            adapter.setCurrentPlaying(currentIndex, isPlaying)
            if (!isPlaying) {
                saveCurrentProgress()
            }
        }

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

        override fun onPlayerError(error: PlaybackException) {
            val ctrl = controller ?: return
            // 出错文件名：列表可能为空或下标尚未同步，先做边界检查防下标越界
            val index = currentIndex
            val name = playlist.getOrNull(index)?.name ?: "未知文件"

            // 「文件找不到」类 IO 错误才移除条目：MediaStore 的 content:// 地址
            // 在文件被删除后正是报 ERROR_CODE_IO_FILE_NOT_FOUND；
            // 解码失败等其他错误只提示不删——文件本身还在，不排除换环境后能播
            val isFileGone = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

            // 尝试跳到下一项继续播放，避免用户卡在一个坏文件上。
            // 出错后播放器处于 IDLE：seek 只移动位置不会自动恢复播放，
            // 需要 prepare 清除错误状态重新准备（已切到下一项）再起播
            if (ctrl.hasNextMediaItem()) {
                ctrl.seekToNextMediaItem()
                ctrl.prepare()
                ctrl.play()
            }

            if (isFileGone && index in playlist.indices) {
                Toast.makeText(this@MainActivity, "无法读取「$name」", Toast.LENGTH_SHORT).show()
                // 顺手移除失效文件并清理其进度，避免反复点到同一个坏文件。
                // 必须放在上面的 seekToNext 之后：MediaController 命令按序送达服务端，
                // 先 seek 再 remove，队列下标前移后恰好落在紧邻的下一项上；
                // 若先删后跳，「下一项」已前移一位，会再越过一个文件
                removeItemFromPlaylist(index)
            } else {
                Toast.makeText(this@MainActivity, "播放出错：$name", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            // 暂时性焦点丢失（来电等）：播放被抑制，焦点归还后自动续播
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                Toast.makeText(this@MainActivity, "已被其他应用暂时打断，稍后自动续播", Toast.LENGTH_SHORT).show()
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (fullscreenPip.isInPipMode) fullscreenPip.updatePipAspectRatio(videoSize)
        }
    }

    /**
     * 构建带元数据的 MediaItem：title 用于通知栏/锁屏媒体控制显示文件名，
     * 不设置时系统通知会显示「未知」占位。
     */
    private fun buildMediaItem(item: MediaItemData): M3MediaItem =
        M3MediaItem.Builder()
            .setUri(item.uri)
            .setMediaMetadata(
                M3MediaMetadata.Builder().setTitle(item.name).build()
            )
            .build()

    /** 续播位置：优先持久层（Service 落盘的权威值），无记录才回退内存缓存 */
    private fun resolveResumePosition(item: MediaItemData): Long {
        val disk = PlayerRepository.getProgress(item.uri.toString())
        if (disk != null && disk > 0) return disk
        return cachedProgress[item.uri.toString()] ?: 0L
    }

    /** 当前项有保存进度（>0）则 seek 到该位置，实现断点续播 */
    private fun restoreProgressIfNeeded() {
        val ctrl = controller ?: return
        val index = ctrl.currentMediaItemIndex
        if (index < 0 || index >= playlist.size) return
        val savedPos = resolveResumePosition(playlist[index])
        if (savedPos > 0) {
            ctrl.seekTo(savedPos)
        }
    }

    /** 恢复上次播放项：仅定位与断点回放，不自动起播；正在播放时不打断 */
    private fun restoreLastPlayed() {
        val ctrl = controller ?: return
        if (ctrl.playWhenReady || ctrl.isPlaying) return
        val lastUri = PlayerRepository.getLastItem() ?: return
        val idx = playlist.indexOfFirst { it.uri.toString() == lastUri }
        if (idx !in playlist.indices) return
        // 不因「当前正好在下标 0」提前 return：冷启动重建队列时下标 0 是默认值，
        // 并非真实进度，必须走到 seekTo 才能恢复断点（重复 seek 到同位置无副作用）
        val pos = resolveResumePosition(playlist[idx])
        if (pos > 0) ctrl.seekTo(idx, pos) else ctrl.seekToDefaultPosition(idx)
        currentIndex = idx
        adapter.setCurrentPlaying(idx, ctrl.isPlaying)
    }

    // ===== 播放器自定义控件（倍速/音轨/字幕） =====

    private val speedLevels = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    @OptIn(UnstableApi::class)
    private fun setupControllerExtras() {
        binding.playerView.setRepeatToggleModes(
            RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
        )
        binding.playerView.setShowShuffleButton(true)
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

    /** 倍速档位下标，找不到时回退 1.0x */
    private fun speedsIndex(speed: Float): Int {
        val idx = speedLevels.indexOfFirst { it == speed }
        if (idx >= 0) return idx
        val one = speedLevels.indexOfFirst { it == 1.0f }
        return if (one >= 0) one else 0
    }

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

    /** 倍速文案，如 "1x"、"1.5x" */
    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    }

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

    /** 是否正在扫描（重入保护）。主线程读写、IO 线程复位，需保证可见性 */
    @Volatile
    private var isScanning = false

    /** MediaStore 观察者：媒体库变化时触发去抖的增量扫描 */
    private val mediaChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        @Deprecated("Deprecated in Java")
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // MediaStore 常连发多次通知，合并到一次延时扫描
            scanHandler.removeCallbacks(debouncedScanRunnable)
            scanHandler.postDelayed(debouncedScanRunnable, 800)
        }
    }
    private val scanHandler = Handler(Looper.getMainLooper())
    private val debouncedScanRunnable = Runnable {
        if (hasStoragePermission()) scanLocalMedia()
    }

    /**
     * 扫描本地音视频并对账播放列表（IO 线程，带重入保护）。
     * @param silent true 为回前台静默对账：只删不增、不弹提示、无权限时跳过
     */
    private fun scanLocalMedia(silent: Boolean = false) {
        if (isScanning) return
        isScanning = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (silent && !hasStoragePermission()) return@launch
                val videos = scanner.queryVideos()
                val audios = scanner.queryAudios()
                withContext(Dispatchers.Main) {
                    reconcileScanResult(videos, audios, silent)
                }
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * 主线程消化扫描结果：先对账移除已删除的条目，非静默时再合并新增（过滤 <5 秒与重复）。
     */
    private fun reconcileScanResult(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?,
        silent: Boolean
    ) {
        if (scannedVideos == null && scannedAudios == null) {
            if (!silent) {
                Toast.makeText(this, "扫描失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 查询失败的类型(null)由 pruneDeletedMedia 内部跳过其删除对账
        val removedCount = pruneDeletedMedia(scannedVideos, scannedAudios)
        val newItems = if (silent) emptyList() else {
            // scanned 合并只服务「找新增」，构建收敛到非静默分支：
            // silent（每次回前台对账）路径不做这次全量合并
            val scanned = mutableListOf<MediaItemData>()
            scannedVideos?.let(scanned::addAll)
            scannedAudios?.let(scanned::addAll)
            val existingKeys = existingUriKeys()
            scanned.filter {
                it.duration >= 5000 && normalizeUri(it.uri) !in existingKeys
            }
        }
        if (newItems.isEmpty() && removedCount == 0) {
            if (!silent) {
                Toast.makeText(this, "没有新文件", Toast.LENGTH_SHORT).show()
            }
            return
        }
        playlist.addAll(newItems)
        for (item in newItems) {
            controller?.addMediaItem(buildMediaItem(item))
        }
        refreshPlaylist()
        savePlaylist()
        if (!silent) {
            val parts = mutableListOf<String>()
            if (removedCount > 0) parts.add("删除 $removedCount 个已消失文件")
            if (newItems.isNotEmpty()) parts.add("添加 ${newItems.size} 个文件")
            Toast.makeText(this, parts.joinToString("，"), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 用扫描结果对账播放列表，移除文件已从媒体库删除的条目。
     *
     * 权限保护：API 34+ 的「仅选中的照片/视频」部分授权下，查询结果只含被选中文件，
     * 直接对账会误删未授权文件，因此仅在全量权限时才执行该类型删除；
     * 查询失败（参数为 null）同理跳过——失败 ≠ 空集，当空集处理会整类误删。
     */
    private fun pruneDeletedMedia(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?
    ): Int {
        val videoKeys = if (scannedVideos != null && scanner.hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_VIDEO)) {
            scannedVideos.map { normalizeUri(it.uri) }.toSet()
        } else null
        val audioKeys = if (scannedAudios != null && scanner.hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_AUDIO)) {
            scannedAudios.map { normalizeUri(it.uri) }.toSet()
        } else null
        val videoPrefix = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()
        val audioPrefix = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString()
        var removed = 0
        // 逆序遍历，删除时下标不失效
        for (i in playlist.indices.reversed()) {
            val item = playlist[i]
            val uriStr = item.uri.toString()
            val keys = when {
                uriStr.startsWith(videoPrefix) -> videoKeys
                uriStr.startsWith(audioPrefix) -> audioKeys
                else -> null
            }
            if (keys != null && normalizeUri(item.uri) !in keys) {
                removeItemAt(i)
                removed++
            }
        }
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

        // 回调回传 uri 而非下标：适配器 items 异步 diff 存在下标错位窗口，处理时现查下标
        adapter = MediaListAdapter(
            onClick = { uri ->
                val index = playlist.indexOfFirst { it.uri.toString() == uri }
                if (index >= 0) {
                    // 切走前先记录当前项精确进度：transition 触发后 controller 已切到新项，
                    // 读不到旧项位置，必须在 seekTo 之前落盘
                    saveCurrentProgress()
                    PlayerService.flushCurrentPosition()
                    val item = playlist[index]
                    val pos = resolveResumePosition(item)
                    if (pos > 0 && (item.duration !in 1..pos)) {
                        controller?.seekTo(index, pos)
                    } else {
                        controller?.seekToDefaultPosition(index)
                    }
                    controller?.play()
                }
            },
            onDelete = { uri ->
                val item = playlist.firstOrNull { it.uri.toString() == uri }
                    ?: return@MediaListAdapter
                AlertDialog.Builder(this)
                    .setTitle("删除条目")
                    .setMessage("确定从播放列表中删除「${item.name}」吗？\n该文件的播放进度也会被清除。")
                    .setPositiveButton("删除") { _, _ ->
                        // 对话框存续期间列表可能位移，确认时按 uri 重新定位
                        playlist.indexOfFirst { it.uri.toString() == uri }
                            .takeIf { it >= 0 }?.let { removeItemFromPlaylist(it) }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        binding.recyclerPlaylist.adapter = adapter
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(this)

        binding.btnScan.setOnClickListener {
            if (hasStoragePermission()) {
                scanLocalMedia()
            } else {
                requestStoragePermission()
            }
        }
        binding.btnPip.setOnClickListener { fullscreenPip.enterPipMode() }
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

        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        updateManager.registerReceivers()
        updateManager.checkForUpdate(manual = false)
    }

    override fun onStart() {
        super.onStart()
        updateManager.resumePendingInstall()
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        val sessionToken = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            // 回调可能晚于 onStop（future 已释放），必须用 !== 判空防 NPE 或重挂已释放控制器
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
            lifecycleScope.launch {
                // 最后一道防线：仓库侧已把加载异常降级为空数据，这里兜住极端漏网
                // 情况，失败时提示并跳过后续加载逻辑，保证 App 仍能启动进入界面
                try {
                    PlayerRepository.awaitLoaded(this@MainActivity)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "数据加载失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (!playlistLoaded) {
                    loadPlaylist()
                    playlistLoaded = true
                } else {
                    // 回前台：用持久层校正内存，防止后台播完项的残留进度复活
                    reconcileProgressFromDisk()
                }
                // 回前台静默对账：等播放列表加载完成后再扫，确保冷启动时
                // 对账能基于已填充的 playlist 删除已消失文件（只删不增、无权限跳过）
                scanLocalMedia(silent = true)
                // 队列与内存列表失不时（控制器为空/重连错位）全量重灌自愈
                if (playlist.isNotEmpty() && ctrl.mediaItemCount != playlist.size) {
                    val cur = ctrl.currentMediaItem
                    val curUri = cur?.localConfiguration?.uri?.toString()
                    val curPos = ctrl.currentPosition
                    ctrl.clearMediaItems()
                    for (item in playlist) {
                        ctrl.addMediaItem(buildMediaItem(item))
                    }
                    val curIdx = curUri?.let { u -> playlist.indexOfFirst { it.uri.toString() == u } }
                    if (curIdx != null && curIdx >= 0) {
                        ctrl.seekTo(curIdx, curPos)
                    }
                }
                restoreLastPlayed()
                refreshPlaylist()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        scanHandler.removeCallbacks(debouncedScanRunnable)
        fullscreenPip.onStopped()
        gestures.cancelPendingHide()
        binding.playerView.player = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // 进度保存只放 onPause：离开前台必然先 onPause 再 onStop，后者再存属重复；
        // 且弹对话框/覆盖透明 Activity 只触发 onPause 不触发 onStop，这里覆盖面更全
        saveCurrentProgress()
    }

    override fun onDestroy() {
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

    /** 是否有存储读取权限（API 33+ 查媒体权限，API 34+ 含「仅选中」授权） */
    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        val fullOrAudio =
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 34) {
            fullOrAudio || ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            fullOrAudio
        }
    }

    /** 按系统版本请求对应权限组合 */
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
