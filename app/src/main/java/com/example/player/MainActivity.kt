/**
 * 界面入口与流程编排。
 *
 * 包含：
 * - [PlayerApp]：应用启动时触发仓库加载；
 * - [MainActivity]：维护播放列表 UI、连接 [PlayerService] 的 MediaController、
 *   扫描媒体库并与已存列表对账（含已删除文件的宽限期清理）、断点续播与更新流程编排。
 *
 * 界面逻辑拆分为 [GestureController]、[FullscreenPipHelper]、[MediaListAdapter]（见 PlayerUi.kt）。
 */
package com.example.player

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.example.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as M3MediaItem
import androidx.media3.common.MediaMetadata as M3MediaMetadata

/** 应用入口：尽早触发数据库加载，让界面启动时直接读到内存数据。 */
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerRepository.ensureLoaded(this)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaListAdapter

    /** 当前展示的播放列表（与 UI 同步，保存时写回仓库）。 */
    private val playlist = mutableListOf<MediaItemData>()

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    /** 与 [PlayerService] 的连接；每次 onStart 重建、onStop 释放。 */
    private var controller: MediaController? = null

    /** 是否已从仓库载入过播放列表（用于 onStart 时区分首次与后续）。 */
    private var playlistLoaded = false

    private val scanner = MediaStoreScanner(this)
    private lateinit var updateManager: UpdateManager
    private lateinit var gestures: GestureController
    private lateinit var fullscreenPip: FullscreenPipHelper

    private companion object {
        /** 写失败提示的最小间隔，避免连续失败刷屏。 */
        const val WRITE_FAILURE_TOAST_MIN_INTERVAL_MS = 15_000L

        /** 播放列表变更后延迟保存的防抖时长。 */
        const val SAVE_PLAYLIST_DEBOUNCE_MS = 2_000L

        /** 媒体库变化后延迟扫描的防抖时长。 */
        const val MEDIA_CHANGE_DEBOUNCE_MS = 800L
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants: Map<String, Boolean> ->
        if (grants.values.any { it }) {
            toast(R.string.toast_permission_granted)
        }
    }

    /** 删除单条：同步列表、播放队列与进度，然后刷新 UI、保存并提示。 */
    private fun removeItemFromPlaylist(index: Int) {
        if (index !in playlist.indices) return
        PlayerService.dropProgress(playlist[index].uri.toString())
        playlist.removeAt(index)
        controller?.removeMediaItem(index)
        syncAdapterPlayingState()
        refreshPlaylist()
        savePlaylist()
        toast(R.string.toast_deleted)
    }

    private fun submitPlaylist() {
        adapter.submitList(playlist.toList())
    }

    /** 刷新适配器与计数、空态。 */
    private fun refreshPlaylist() {
        submitPlaylist()
        binding.tvCount.text = if (playlist.isEmpty()) getString(R.string.list_count_empty)
        else getString(R.string.list_count, playlist.size)
        binding.tvEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 仅在「正在播放视频」时保持屏幕常亮；纯音频不阻止系统息屏。 */
    private fun updateKeepScreenOn() {
        val ctrl = controller
        val video = ctrl?.videoSize
        binding.playerView.keepScreenOn =
            ctrl?.isPlaying == true && video != null && video.width > 0 && video.height > 0
    }

    /** 把当前播放项 / 播放状态同步到列表高亮。 */
    private fun syncAdapterPlayingState() {
        adapter.setCurrentPlaying(
            controller?.currentMediaItem?.localConfiguration?.uri?.toString(),
            controller?.isPlaying == true
        )
    }

    /** 用播放器解析出的真实时长回填列表条目（只写单行，不触发整表替换）。 */
    private fun updateDurationAt(index: Int, duration: Long) {
        if (index !in playlist.indices || playlist[index].duration == duration) return
        playlist[index] = playlist[index].copy(duration = duration)
        submitPlaylist()
        PlayerRepository.updatePlaylistDuration(playlist[index].uri, duration)
    }

    private val savePlaylistRunnable = Runnable { savePlaylistNow() }

    /** 界面侧共用的主线程 Handler（保存防抖与扫描防抖）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 防抖保存播放列表。 */
    private fun savePlaylist() {
        mainHandler.removeCallbacks(savePlaylistRunnable)
        mainHandler.postDelayed(savePlaylistRunnable, SAVE_PLAYLIST_DEBOUNCE_MS)
    }

    private fun savePlaylistNow() {
        // playlistLoaded 之前 playlist 还是空的：此时落盘会用空列表覆盖已存列表，
        // 清空用户数据（例如控制器尚未连接或连接失败就退到后台）。
        if (!playlistLoaded || !PlayerRepository.isLoaded()) return
        PlayerRepository.savePlaylist(playlist.toList())
    }

    /** 取消防抖并立即保存（onStop 调用）。 */
    private fun flushPendingSave() {
        mainHandler.removeCallbacks(savePlaylistRunnable)
        savePlaylistNow()
    }

    /** 现有条目的统一身份键集合：与扫描去重、目录回填同口径（[normalizeUri]）。 */
    private fun existingUriKeys(): Set<String> = playlist.map { normalizeUri(it.uri) }.toSet()

    /** 按统一的身份键（[normalizeUri]）定位列表条目；找不到返回 -1。 */
    private fun indexOfUri(uri: String): Int {
        val key = normalizeUri(uri.toUri())
        return playlist.indexOfFirst { normalizeUri(it.uri) == key }
    }

    /** 从仓库把已存列表载入当前列表（仅在首次加载、列表为空时调用）。 */
    private fun loadPlaylist() {
        playlist.addAll(PlayerRepository.getPlaylist())
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: M3MediaItem?, reason: Int) {

            syncAdapterPlayingState()

            // 单曲循环回绕不恢复断点，其余切歌按需续播
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                restoreProgressIfNeeded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncAdapterPlayingState()
            updateKeepScreenOn()
            fullscreenPip.refreshPipParams()
            if (!isPlaying) {
                // 先落盘再刷新：仓储内存同步更新后，下一行才能读到最新进度（服务侧落盘幂等）
                PlayerService.flushCurrentPosition()
                adapter.refreshProgress(controller?.currentMediaItemIndex ?: -1)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 就绪后回填真实时长
            if (playbackState == Player.STATE_READY) {
                val index = controller?.currentMediaItemIndex ?: -1
                val duration = controller?.duration ?: C.TIME_UNSET
                if (duration != C.TIME_UNSET && duration > 0 && index in playlist.indices) {
                    updateDurationAt(index, duration)
                }
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {

            // 被其他应用暂时抢占音频焦点：提示稍后自动续播
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                toast(R.string.toast_playback_interrupted)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {

            // 倍速由服务侧播放器持有，参数变化时同步按钮文案
            syncSpeedLabel()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateKeepScreenOn()
            // 依据是否有视频刷新系统 PiP 参数（自动进入的开关与小窗动画锚点）
            fullscreenPip.refreshPipParams()
            // 回调在全部版本都会触发；PiP 比例仅在 API 26+ 生效，故此处显式判版本而非标注 @RequiresApi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fullscreenPip.isInPipMode) {
                fullscreenPip.updatePipAspectRatio(videoSize)
            }
        }
    }

    /** 把内部模型转换为 Media3 的 MediaItem。 */
    private fun buildMediaItem(item: MediaItemData): M3MediaItem =
        M3MediaItem.Builder()
            .setUri(item.uri)
            .setMediaMetadata(
                M3MediaMetadata.Builder().setTitle(item.name).build()
            )
            .build()

    /** 播放队列是否与当前列表完全一致（URI 与顺序）。 */
    private fun queueMatchesPlaylist(ctrl: MediaController): Boolean {
        if (ctrl.mediaItemCount != playlist.size) return false
        for (i in playlist.indices) {
            val queueUri = ctrl.getMediaItemAt(i).localConfiguration?.uri?.toString() ?: return false
            if (queueUri != playlist[i].uri.toString()) return false
        }
        return true
    }

    /** 列表变化后重建播放队列，尽量保持当前项与播放位置。 */
    private fun rebuildQueueIfNeeded(ctrl: MediaController) {
        if (playlist.isEmpty() || queueMatchesPlaylist(ctrl)) return

        val curUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
        val curPos = ctrl.currentPosition
        val curIdx = curUri?.let(::indexOfUri) ?: -1

        val items = playlist.map(::buildMediaItem)
        if (curIdx >= 0) {
            // 仅当当前项仍在列表时才需要先落盘再重建，避免为已移除项写回进度
            PlayerService.flushCurrentPosition()
            ctrl.setMediaItems(items, curIdx, curPos)
        } else {
            ctrl.setMediaItems(items)
        }
    }

    /** 返回可续播的位置；无有效断点（未播放 / 已看完）返回 null。 */
    private fun resumePositionOrNull(index: Int): Long? {
        if (index !in playlist.indices) return null
        val item = playlist[index]
        val pos = PlayerRepository.getProgress(item.uri.toString()) ?: 0L
        return if (pos > 0 && !isProgressFinished(pos, item.duration)) pos else null
    }

    /** seek 到断点位置；无断点时可选回到条目开头。 */
    private fun seekToResume(index: Int, defaultIfMissing: Boolean = true) {
        val ctrl = controller ?: return
        val pos = resumePositionOrNull(index)
        if (pos != null) ctrl.seekTo(index, pos)
        else if (defaultIfMissing) ctrl.seekToDefaultPosition(index)
    }

    /** 切歌后按需恢复断点（不改动无断点的条目位置）。 */
    private fun restoreProgressIfNeeded() {
        seekToResume(controller?.currentMediaItemIndex ?: return, defaultIfMissing = false)
    }

    /** 冷启动时恢复上次播放的条目与位置。 */
    private fun restoreLastPlayed() {
        val ctrl = controller ?: return
        if (ctrl.playWhenReady || ctrl.isPlaying) return
        val lastUri = PlayerRepository.getLastItem() ?: return
        val idx = indexOfUri(lastUri)
        if (idx !in playlist.indices) return

        seekToResume(idx)
        syncAdapterPlayingState()
    }

    /** 可选倍速档位。 */
    private val speedLevels = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 配置控制栏扩展：循环 / 随机按钮、倍速与音轨 / 字幕入口。 */
    @OptIn(UnstableApi::class)
    private fun setupControllerExtras() {
        binding.playerView.setRepeatToggleModes(
            RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
        )
        binding.playerView.setShowShuffleButton(true)

        binding.playerView.findViewById<View>(R.id.btn_speed)?.setOnClickListener {
            showSpeedSelection()
        }

        binding.playerView.findViewById<View>(R.id.btn_audio)?.setOnClickListener {
            showTrackSelection(C.TRACK_TYPE_AUDIO, R.string.track_select_audio_title)
        }
        binding.playerView.findViewById<View>(R.id.btn_subtitle)?.setOnClickListener {
            showTrackSelection(C.TRACK_TYPE_TEXT, R.string.track_select_subtitle_title)
        }
        // 全屏左上角返回键：退出全屏
        binding.playerView.findViewById<View>(R.id.btn_fullscreen_back)?.setOnClickListener {
            fullscreenPip.setFullscreen(false)
        }
    }

    /** 把倍速按钮文案与播放器真实速度对齐（重连 / 外部改速后调用）。 */
    private fun syncSpeedLabel() {
        val speed = controller?.playbackParameters?.speed ?: return
        binding.playerView.findViewById<TextView>(R.id.btn_speed)?.text = formatSpeed(speed)
    }

    /** 找到当前速度对应的档位下标；不在档位内时回退到 1x。 */
    private fun speedsIndex(speed: Float): Int {
        val idx = speedLevels.indexOfFirst { it == speed }
        return if (idx >= 0) idx else speedLevels.indexOfFirst { it == 1.0f }.coerceAtLeast(0)
    }

    /** 弹出倍速选择对话框。 */
    private fun showSpeedSelection() {
        val ctrl = controller ?: return
        val labels = speedLevels.map { formatSpeed(it) }.toTypedArray()
        val checked = speedsIndex(ctrl.playbackParameters.speed)
        AlertDialog.Builder(this)
            .setTitle(R.string.speed_dialog_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->

                ctrl.setPlaybackSpeed(speedLevels[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 倍速文案：整数不带小数（1x），非整数保留原样（1.25x）。 */
    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    }

    /** 弹出音轨 / 字幕选择对话框。 */
    @OptIn(UnstableApi::class)
    private fun showTrackSelection(trackType: Int, @StringRes titleRes: Int) {
        val ctrl = controller ?: run {
            toast(R.string.toast_player_not_connected)
            return
        }
        try {
            TrackSelectionDialogBuilder(this, getString(titleRes), ctrl, trackType)
                .setShowDisableOption(false)
                .build()
                .show()
        } catch (_: Exception) {
            toast(R.string.toast_no_tracks)
        }
    }

    /** 当前扫描任务（并发去重，静默扫描也复用它）。 */
    @Volatile
    private var scanJob: Job? = null

    /** 监听媒体库变化：防抖后静默扫描。 */
    private val mediaChangeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(debouncedScanRunnable)
            mainHandler.postDelayed(debouncedScanRunnable, MEDIA_CHANGE_DEBOUNCE_MS)
        }
    }
    private val debouncedScanRunnable = Runnable { startScan(silent = true) }

    /** 启动或复用一次扫描任务；调用方可对返回的 Job 选择是否 join。 */
    private fun startScan(silent: Boolean): Job {
        scanJob?.takeIf { it.isActive }?.let { return it }
        return lifecycleScope.launch {
            if (!hasStoragePermission()) return@launch
            scanMediaInternal(silent)
        }.also { scanJob = it }
    }

    /** 在 IO 线程查询媒体库，回到主线程对账。 */
    private suspend fun scanMediaInternal(silent: Boolean) {
        val (videos, audios) = withContext(Dispatchers.IO) {
            scanner.queryVideos() to scanner.queryAudios()
        }
        withContext(Dispatchers.Main) {
            reconcileScanResult(videos, audios, silent)
        }
    }

    /**
     * 用扫描结果对账：清理已删除文件、追加新文件、回填目录并重排。
     * @param silent 后台静默扫描（不弹提示，也不主动追加新文件）
     */
    private fun reconcileScanResult(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?,
        silent: Boolean
    ) {
        if (scannedVideos == null && scannedAudios == null) {
            if (!silent) {
                toast(R.string.toast_scan_failed)
            }
            return
        }
        val removedCount = pruneDeletedMedia(scannedVideos, scannedAudios)
        val newItems = if (silent) emptyList() else {
            val scanned = mutableListOf<MediaItemData>()
            scannedVideos?.let(scanned::addAll)
            scannedAudios?.let(scanned::addAll)
            val existingKeys = existingUriKeys()
            scanned.filter {
                it.duration >= 5000 && normalizeUri(it.uri) !in existingKeys
            }
        }

        val before = playlist.toList()
        playlist.addAll(newItems)
        val appended = playlist.toList()
        val ordered = sortPlaylist(backfillFolderKeys(appended, buildFolderMap(scannedVideos, scannedAudios)))
        val orderReorganized = ordered != appended

        if (ordered == before && removedCount == 0) {
            if (!silent) {
                toast(R.string.toast_no_new_media)
            }
            return
        }
        playlist.clear()
        playlist.addAll(ordered)
        refreshPlaylist()
        savePlaylist()
        controller?.let { ctrl ->
            if (playlist.isEmpty() && removedCount > 0) {
                // 列表被清空：rebuildQueueIfNeeded 对空列表直接返回，这里显式清空队列
                ctrl.clearMediaItems()
            } else {
                rebuildQueueIfNeeded(ctrl)
            }
        }
        if (!silent) {
            val parts = mutableListOf<String>()
            if (removedCount > 0) parts.add(getString(R.string.toast_scan_removed, removedCount))
            if (newItems.isNotEmpty()) parts.add(getString(R.string.toast_scan_added, newItems.size))
            if (orderReorganized) parts.add(getString(R.string.toast_scan_reordered))
            toastMessage(parts.joinToString("，"))
        }
    }

    /** 用扫描结果构建「归一化 URI → 分组键」映射，供回填使用。 */
    private fun buildFolderMap(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for ((uri, _, _, folderKey) in scannedVideos.orEmpty() + scannedAudios.orEmpty()) {
            if (folderKey.isNotEmpty()) map[normalizeUri(uri)] = folderKey
        }
        return map
    }

    /**
     * 清理扫描中已不存在的条目：需连续两轮未见且超过宽限期才真正删除，
     * 避免部分授权（Android 14+）或瞬时不可用导致误删。返回删除数量。
     */
    private fun pruneDeletedMedia(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?
    ): Int {
        val videoKeys = if (scannedVideos != null &&
            hasMediaReadAccess(this, android.Manifest.permission.READ_MEDIA_VIDEO, includePartial = false)
        ) {
            scannedVideos.map { normalizeUri(it.uri) }.toSet()
        } else null
        val audioKeys = if (scannedAudios != null &&
            hasMediaReadAccess(this, android.Manifest.permission.READ_MEDIA_AUDIO, includePartial = false)
        ) {
            scannedAudios.map { normalizeUri(it.uri) }.toSet()
        } else null
        val videoPrefix = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()
        val audioPrefix = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString()
        var removed = 0

        val missingNow = mutableSetOf<String>()
        val observedNow = mutableSetOf<String>()
        val removedNow = mutableSetOf<String>()
        val removedUris = mutableSetOf<String>()
        val survivors = ArrayList<MediaItemData>(playlist.size)
        val now = System.currentTimeMillis()
        for (item in playlist) {
            val uriStr = item.uri.toString()
            val keys = when {
                uriStr.startsWith(videoPrefix) -> videoKeys
                uriStr.startsWith(audioPrefix) -> audioKeys
                else -> null
            }
            if (keys == null) {
                survivors.add(item)
                continue
            }
            observedNow.add(uriStr)
            val key = normalizeUri(item.uri)
            if (key in keys) {
                survivors.add(item)
                continue
            }
            missingNow.add(uriStr)
            val prev = PlayerRepository.getMissingInfo(uriStr)
            val missingBefore = prev != null && prev.missCount >= 1
            val oldEnough = prev != null &&
                now - prev.firstMissingMs >= PlayerRepository.MISSING_DELETE_GRACE_MS
            if (missingBefore && oldEnough) {
                removedUris.add(uriStr)
                removedNow.add(uriStr)
                removed++
            } else {
                survivors.add(item)
            }
        }
        if (removedUris.isNotEmpty()) {
            playlist.clear()
            playlist.addAll(survivors)
            // 进度一次批量删除；播放队列由本轮扫描末尾的 rebuildQueueIfNeeded 统一重建
            PlayerService.dropProgress(removedUris)
        }
        PlayerRepository.updateMissingRecords(missingNow, observedNow, removedNow)
        if (removed > 0) {
            syncAdapterPlayingState()
        }
        return removed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 监听仓库写失败并限频提示
        lifecycleScope.launch {
            var lastShownMs = 0L
            PlayerRepository.writeFailures.collect {
                val now = SystemClock.elapsedRealtime()
                if (now - lastShownMs < WRITE_FAILURE_TOAST_MIN_INTERVAL_MS) return@collect
                lastShownMs = now
                toastLong(R.string.toast_repository_write_failed)
            }
        }

        // 监听播放错误：文件缺失时登记，并按「是否已跳过 / 是否全部失败」给出不同提示
        lifecycleScope.launch {
            PlayerService.errorEvents.collect { event ->
                val isFileGone =
                    event.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                if (isFileGone && event.uri != null) {
                    PlayerRepository.recordMissing(event.uri)
                }
                val name = event.uri?.let { playlist.getOrNull(indexOfUri(it)) }?.name ?: "未知文件"
                val message = when {
                    event.exhausted -> getString(R.string.toast_playback_error_all_failed)
                    isFileGone -> getString(
                        if (event.hasNext) R.string.toast_file_unreadable
                        else R.string.toast_file_unreadable_last,
                        name
                    )
                    else -> getString(
                        if (event.hasNext) R.string.toast_playback_error
                        else R.string.toast_playback_error_last,
                        name
                    )
                }
                toastMessage(message)
            }
        }

        updateManager = UpdateManager(this)
        gestures = GestureController(this, binding.playerView, binding.gestureOverlay) { controller }
        fullscreenPip = FullscreenPipHelper(this, binding) { controller }

        // 列表适配器：点击续播、长按删除、实时读取进度
        adapter = MediaListAdapter(
            onClick = { uri ->
                val index = indexOfUri(uri)
                if (index >= 0) {
                    PlayerService.resetErrorCount()
                    PlayerService.flushCurrentPosition()
                    seekToResume(index)
                    controller?.play()
                }
            },
            onDelete = { uri ->
                val item = playlist.getOrNull(indexOfUri(uri)) ?: return@MediaListAdapter
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_dialog_title)
                    .setMessage(getString(R.string.delete_dialog_message, item.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        indexOfUri(uri).takeIf { it >= 0 }?.let { removeItemFromPlaylist(it) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },

            getProgress = { uri -> PlayerRepository.getProgress(uri) ?: 0L }
        )
        binding.recyclerPlaylist.adapter = adapter
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(this)
        // 关闭条目位移动画，避免 DiffUtil 刷新时列表闪烁
        (binding.recyclerPlaylist.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations =
            false

        // 扫描按钮：有权限直接扫，否则先申请
        binding.btnScan.setOnClickListener {
            if (hasStoragePermission()) {
                startScan(silent = false)
            } else {
                requestStoragePermission()
            }
        }
        binding.btnPip.setOnClickListener { fullscreenPip.enterPipMode() }
        binding.btnMore.setOnClickListener {
            updateManager.checkForUpdate(manual = true)
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

        // Android 13+ 需动态申请通知权限（前台服务通知）
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

    }

    /** 校验异步回调返回的控制器是否仍是当前有效实例（防止 onStop 后回调污染状态）。 */
    private fun isCurrentController(
        future: com.google.common.util.concurrent.ListenableFuture<MediaController>,
        ctrl: MediaController
    ): Boolean = controllerFuture === future && controller === ctrl

    override fun onStart() {
        super.onStart()
        updateManager.registerReceivers()
        updateManager.resumePendingDownload()
        // 监听视频 / 音频媒体库变化
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        // 异步连接前台播放服务
        val sessionToken = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({

            if (controllerFuture !== future) return@addListener
            val ctrl = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            controller = ctrl
            binding.playerView.player = ctrl
            ctrl.addListener(playerListener)

            syncSpeedLabel()
            syncAdapterPlayingState()
            updateKeepScreenOn()
            fullscreenPip.refreshPipParams()
            lifecycleScope.launch {

                val loaded = PlayerRepository.awaitLoaded(this@MainActivity)

                if (!isCurrentController(future, ctrl)) return@launch
                when {

                    // 数据加载失败：提示并等待自动重试
                    !loaded -> toastLong(R.string.toast_repository_load_failed)

                    // 首次进入：把已存列表载入内存
                    !playlistLoaded -> {
                        loadPlaylist()
                        playlistLoaded = true
                    }

                    // 后续返回：只刷新进度显示
                    else -> adapter.refreshAllProgress()
                }

                if (!isCurrentController(future, ctrl)) return@launch
                startScan(silent = true).join()

                if (!isCurrentController(future, ctrl)) return@launch
                rebuildQueueIfNeeded(ctrl)
                restoreLastPlayed()
                refreshPlaylist()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        updateManager.unregisterReceivers()
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        mainHandler.removeCallbacks(debouncedScanRunnable)
        scanJob?.cancel()
        scanJob = null
        flushPendingSave()
        fullscreenPip.onStopped()
        gestures.cancelPendingHide()
        // 断开与服务的连接并释放控制器
        binding.playerView.player = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // 切后台先把当前位置落盘
        PlayerService.flushCurrentPosition()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台补弹检查期间被推迟的更新提示
        updateManager.resumePendingUpdateDialog()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ 由 setAutoEnterEnabled 负责按 Home / 手势自动进入小窗（且仅在有视频时开启）；
        // 低版本没有该能力，退回在此手动进入。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            fullscreenPip.enterPipWhenPlayingVideo()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        fullscreenPip.onPipModeChanged(isInPictureInPictureMode)
    }

    private fun hasStoragePermission(): Boolean = hasAnyMediaReadAccess(this, includePartial = true)

    /** 按系统版本申请合适的媒体读取权限（Android 14+ 含「选中项」权限）。 */
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
