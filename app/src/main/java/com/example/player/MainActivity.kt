/**
 * 界面入口与流程编排。
 *
 * 包含：
 * - [PlayerApp]：应用启动时触发仓库加载；
 * - [MainActivity]：维护播放列表 UI、连接 [PlayerService] 的 MediaController、
 *   扫描媒体库并与已存列表对账（含已删除文件的清理）、断点续播与更新流程编排。
 *
 * 界面逻辑拆分为 [GestureController]、[FullscreenPipHelper]、[MediaListAdapter]（见 PlayerUi.kt）。
 */
package com.example.player

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.BaseColumns
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

        /** 更多菜单项 id。 */
        const val MENU_CHECK_UPDATE = 1
        const val MENU_BRIGHTNESS_SYSTEM = 2
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
        val uri = playlist[index].uri.toString()
        PlayerService.dropProgress(uri)
        playlist.removeAt(index)
        val ctrl = controller
        if (ctrl != null) {
            ctrl.removeMediaItem(index)
            // 同进程下 removeMediaItem 已在主线程完成，可立即解除进度抑制
            PlayerService.clearPendingDrops(listOf(uri))
        }
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
        binding.playerView.keepScreenOn = ctrl?.isPlaying == true && ctrl.videoSize.hasVideo()
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

    /**
     * 从仓库把已存列表载入当前列表（仅在首次成功加载时调用）。
     *
     * 不能直接 addAll：首次加载失败期间用户可能已经手动扫描，内存列表里会有
     * 尚未落盘的条目；按 normalizeUri 合并去重，既保留这些条目又不产生重复。
     */
    private fun loadPlaylist() {
        val merged = mergePlaylistOnLoad(PlayerRepository.getPlaylist(), playlist)
        playlist.clear()
        playlist.addAll(merged)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: M3MediaItem?, reason: Int) {

            syncAdapterPlayingState()
            // 全屏顶部信息条里的文件名跟随当前条目变化
            fullscreenPip.refreshInfoBarContent()

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
            // 依据是否有视频刷新系统 PiP 参数（比例、自动进入开关与动画锚点）
            fullscreenPip.refreshPipParams()
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

    /** 重建队列；若队列已与列表一致，顺带解除之前删除进度的抑制。 */
    private fun rebuildQueueAndClearPending(ctrl: MediaController) {
        rebuildQueueIfNeeded(ctrl)
        if (queueMatchesPlaylist(ctrl)) {
            PlayerService.clearAllPendingDrops()
        }
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

    /** 运行中 + 排队中的扫描任务；onStop 需要把它们全部取消。 */
    private val activeScanJobs = mutableSetOf<Job>()

    /** 最近一次手动扫描（运行中或排队中），用于避免连续点击重复排队。 */
    private var manualScanJob: Job? = null

    /** 监听媒体库变化：防抖后静默扫描。 */
    private val mediaChangeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(debouncedScanRunnable)
            mainHandler.postDelayed(debouncedScanRunnable, MEDIA_CHANGE_DEBOUNCE_MS)
        }
    }
    private val debouncedScanRunnable = Runnable { startScan(silent = true) }

    /**
     * 启动或复用一次扫描任务；调用方可对返回的 Job 选择是否 join。
     *
     * 静默扫描请求直接复用正在运行的任务；手动扫描请求在静默扫描运行时排队，
     * 等它结束后再完整扫描一次（静默对账不会追加新文件，不能把手动请求吞掉）。
     */
    private fun startScan(silent: Boolean): Job {
        val running = scanJob?.takeIf { it.isActive }
        if (running != null && silent) return running
        if (!silent) {
            // 连续点击扫描时复用正在进行/排队的手动任务，避免叠加多次完整扫描
            manualScanJob?.takeIf { it.isActive }?.let { return it }
        }
        val job = lifecycleScope.launch {
            running?.join()
            if (!hasStoragePermission()) return@launch
            scanMediaInternal(silent)
        }
        activeScanJobs += job
        job.invokeOnCompletion {
            // 完成回调可能不在主线程；集合只在主线程维护
            mainHandler.post {
                activeScanJobs -= job
                if (scanJob === job) scanJob = null
                if (manualScanJob === job) manualScanJob = null
            }
        }
        scanJob = job
        if (!silent) manualScanJob = job
        return job
    }

    /** 取消所有运行中与排队中的扫描（onStop 调用）。 */
    private fun cancelScans() {
        val jobs = activeScanJobs.toList()
        activeScanJobs.clear()
        jobs.forEach { it.cancel() }
        scanJob = null
        manualScanJob = null
    }

    /** 一次对账中需要应用的删除结果；计算在 IO 线程完成。 */
    private data class PruneResult(val removedUris: Set<String> = emptySet())

    /**
     * 在 IO 线程查询媒体库并完成对账计算，只把结果应用回主线程。
     *
     * 单条 ContentResolver 查询（[isConfirmedDeleted]）和缺失记录更新都不再占用主线程；
     * playlist 本身只在主线程读写，IO 侧使用进入扫描时的快照。
     */
    private suspend fun scanMediaInternal(silent: Boolean) {
        val playlistSnapshot = playlist.toList()
        val (videos, audios) = withContext(Dispatchers.IO) {
            scanner.queryVideos() to scanner.queryAudios()
        }
        val prune = withContext(Dispatchers.IO) {
            computePruneDeletedMedia(playlistSnapshot, videos, audios)
        }
        withContext(Dispatchers.Main) {
            reconcileScanResult(videos, audios, silent, prune)
        }
    }

    /**
     * 用扫描结果对账：清理已删除文件、追加新文件、回填目录并重排。
     * @param silent 后台静默扫描（不弹提示，也不主动追加新文件）
     */
    private fun reconcileScanResult(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?,
        silent: Boolean,
        prune: PruneResult
    ) {
        if (scannedVideos == null && scannedAudios == null) {
            if (!silent) {
                toast(R.string.toast_scan_failed)
            }
            return
        }
        val removedCount = applyPruneResult(prune.removedUris)
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
                if (queueMatchesPlaylist(ctrl)) PlayerService.clearAllPendingDrops()
            } else {
                rebuildQueueAndClearPending(ctrl)
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
     * 在 IO 线程计算扫描中已不存在的条目（不修改 playlist）：
     * 拥有对应类型完整权限时，对权威扫描未列出的条目做一次单条精确查询确认；
     * 无法确认（查询失败/卷不可用）时退回「连续两轮未见且超过宽限期」的保守判定。
     */
    private fun computePruneDeletedMedia(
        playlistSnapshot: List<MediaItemData>,
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?
    ): PruneResult {
        if (scannedVideos == null && scannedAudios == null) return PruneResult()
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
        // 有可移除卷未挂载时，MediaStore 可能把该卷条目报成“查不到”，不能据此立即删除
        val canConfirmImmediately = externalVolumesFullyMounted()

        val missingNow = mutableSetOf<String>()
        val observedNow = mutableSetOf<String>()
        val removedUris = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        for (item in playlistSnapshot) {
            val uriStr = item.uri.toString()
            val keys = when {
                uriStr.startsWith(videoPrefix) -> videoKeys
                uriStr.startsWith(audioPrefix) -> audioKeys
                else -> null
            }
            if (keys == null) continue
            observedNow.add(uriStr)
            val key = normalizeUri(item.uri)
            if (key in keys) continue
            missingNow.add(uriStr)
            val prev = PlayerRepository.getMissingInfo(uriStr)
            // 兜底判定：上一轮已缺失（count>=1）且超过宽限期，才允许在精确查询无法确认时删除
            val graceExpired = prev != null && prev.missCount >= 1 &&
                now - prev.firstMissingMs >= PlayerRepository.MISSING_DELETE_GRACE_MS
            // 权威扫描未见该条时再做一次单条精确查询：确认媒体库中已无此行即立即清理，
            // 让外部删除的文件即时消失；卷未全部挂载或查询失败/仍有行则退回宽限期兜底。
            val confirmed = canConfirmImmediately && isConfirmedDeleted(item.uri)
            if (graceExpired || confirmed) {
                removedUris.add(uriStr)
            }
        }
        // 缺失记录更新是线程安全的；内部没有变化时不会触发数据库写入
        PlayerRepository.updateMissingRecords(missingNow, observedNow, removedUris)
        return PruneResult(removedUris)
    }

    /** 在主线程应用对账删除结果，返回实际从列表移除的数量。 */
    private fun applyPruneResult(removedUris: Set<String>): Int {
        if (removedUris.isEmpty()) return 0
        val before = playlist.size
        playlist.removeAll { it.uri.toString() in removedUris }
        val removedCount = before - playlist.size
        // 进度一次批量删除；播放队列由本轮扫描末尾的 rebuildQueueIfNeeded 统一重建。
        // 即使 removedCount == 0 也调用：用户可能已手动删条目，但服务侧缓存仍需清理。
        PlayerService.dropProgress(removedUris)
        if (removedCount > 0) syncAdapterPlayingState()
        return removedCount
    }

    /**
     * 外部存储卷是否全部处于挂载状态。
     *
     * 任一可移除卷未挂载时，禁止走“单条查询确认后立即删除”，退回 24 小时宽限期，
     * 避免拔 SD 卡/OTG 时把整卷条目误判为已删除。API 29+ 也可用
     * MediaStore.getExternalVolumeNames()，这里用 StorageManager 兼容 minSdk 24。
     */
    private fun externalVolumesFullyMounted(): Boolean {
        val primaryState = Environment.getExternalStorageState()
        if (primaryState != Environment.MEDIA_MOUNTED &&
            primaryState != Environment.MEDIA_MOUNTED_READ_ONLY
        ) {
            return false
        }
        val storageManager = getSystemService(StorageManager::class.java) ?: return true
        // API 30+ 用「当前 + 最近可用」卷，能覆盖已经拔掉的 SD 卡；低版本只能看到
        // 仍挂在系统里的卷（其 state 也可能已是 UNMOUNTED/REMOVED）。
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageManager.recentStorageVolumes
        } else {
            storageManager.storageVolumes
        }
        return volumes.none { volume ->
            volume.isRemovable &&
                volume.state != Environment.MEDIA_MOUNTED &&
                volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
        }
    }

    /**
     * 对单条媒体 URI 做一次精确查询：查不到任何行即确认该文件已从媒体库删除。
     * 查询返回 null（媒体库暂不可用）或抛异常时返回 false，交回保守判定，避免误删。
     */
    private fun isConfirmedDeleted(uri: Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)
            ?.use { !it.moveToFirst() } ?: false
    } catch (_: Exception) {
        false
    }

    /** 「更多」菜单：检查更新 / 恢复跟随系统亮度。 */
    private fun showMoreMenu() {
        val popup = PopupMenu(this, binding.btnMore)
        popup.menu.add(0, MENU_CHECK_UPDATE, 0, R.string.check_update)
        popup.menu.add(0, MENU_BRIGHTNESS_SYSTEM, 1, R.string.brightness_follow_system)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CHECK_UPDATE -> {
                    updateManager.checkForUpdate(manual = true)
                    true
                }
                MENU_BRIGHTNESS_SYSTEM -> {
                    gestures.resetBrightnessToSystem()
                    toast(R.string.toast_brightness_follow_system)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 只在界面可见（STARTED）时收集提示事件：进入后台后不再对着不可见界面弹 Toast。
        // 注意：文件缺失登记已经放到 PlayerService，不能依赖这里的收集器。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 监听仓库写失败并限频提示
                launch {
                    var lastShownMs = 0L
                    PlayerRepository.writeFailures.collect {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastShownMs < WRITE_FAILURE_TOAST_MIN_INTERVAL_MS) return@collect
                        lastShownMs = now
                        toastLong(R.string.toast_repository_write_failed)
                    }
                }

                // 监听播放错误，并按「是否已跳过 / 是否全部失败」给出不同提示
                launch {
                    PlayerService.errorEvents.collect { event ->
                        val isFileGone =
                            event.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        val name = event.uri?.let { playlist.getOrNull(indexOfUri(it)) }?.name
                            ?: getString(R.string.unknown_file)
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
                val index = indexOfUri(uri)
                val item = playlist.getOrNull(index) ?: return@MediaListAdapter
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_dialog_title)
                    .setMessage(getString(R.string.delete_dialog_message, item.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        // 弹窗期间列表可能已变化，按 uri 重新定位一次
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
        binding.btnMore.setOnClickListener { showMoreMenu() }
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
        // 全屏状态可能跨后台保留：恢复顶部信息显示与刷新
        fullscreenPip.onStarted()
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
                // 先把已载入的列表显示出来并建立播放队列；扫描在后台继续，
                // 不要让用户盯着空白列表等扫描结束。
                refreshPlaylist()
                rebuildQueueAndClearPending(ctrl)
                restoreLastPlayed()

                startScan(silent = true).join()

                if (!isCurrentController(future, ctrl)) return@launch
                // 扫描可能追加/删除/重排条目，完成后统一重建队列并刷新
                rebuildQueueAndClearPending(ctrl)
                restoreLastPlayed()
                refreshPlaylist()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        updateManager.unregisterReceivers()
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        mainHandler.removeCallbacks(debouncedScanRunnable)
        cancelScans()
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
