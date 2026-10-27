/**
 * 界面入口与流程编排：播放列表 UI、MediaController 连接、播放队列差分更新、
 * 扫描对账、断点恢复与更新。手势、全屏/PiP、列表适配器见 PlayerUi.kt。
 */
package io.github.niu0506.player

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
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
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import io.github.niu0506.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        const val MENU_RESET_DATA = 3

        /** 可选倍速档位。 */
        val speedLevels = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

        /** 日志标签。 */
        const val TAG = "MainActivity"
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasStoragePermission()) {
            // 授权成功后直接开始首次扫描，避免用户还要再点一次扫描
            startScan(silent = false)
        } else {
            toast(R.string.toast_permission_denied)
        }
    }

    /** 删除单条：同步列表、播放队列与进度，然后刷新 UI、保存并提示。 */
    private fun removeItemFromPlaylist(index: Int) {
        if (index !in playlist.indices) return
        val item = playlist[index]
        val uri = item.uri.toString()
        PlayerService.dropProgress(uri)

        // 队列与列表理论上由 rebuildQueue* 对齐，但扫描/重连窗口内可能错位；
        // 用 URI 定位队列项，避免用列表下标误删或越界崩溃。
        val ctrl = controller
        if (ctrl != null) {
            val queueIndex = findMediaItemIndexByUri(
                mediaItemCount = ctrl.mediaItemCount,
                uriAt = { i -> ctrl.getMediaItemAt(i).localConfiguration?.uri },
                target = item.uri,
            )
            if (queueIndex >= 0) ctrl.removeMediaItem(queueIndex)
        }

        playlist.removeAt(index)
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
            controller?.currentMediaItem.uriString,
            controller?.isPlaying == true
        )
    }

    /** 用播放器解析出的真实时长回填列表条目（只刷新单条，不触发整表差分）。 */
    private fun updateDurationAt(index: Int, duration: Long) {
        if (index !in playlist.indices || playlist[index].duration == duration) return
        playlist[index] = playlist[index].copy(duration = duration)
        // 差分进行中或列表尚未同步到适配器时退回整表提交，避免单条更新被旧结果覆盖
        if (adapter.isDiffing || !adapter.updateItem(playlist[index])) submitPlaylist()
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
        // playlistLoaded 之前尚未合并已存列表：此时落盘会用不完整的列表覆盖仓库，
        // 造成用户数据丢失（例如控制器尚未连接或连接失败就退到后台）。
        if (!playlistLoaded || !PlayerRepository.isLoaded()) return
        PlayerRepository.savePlaylist(playlist.toList())
    }

    /** 取消防抖并立即保存（onStop 调用）。 */
    private fun flushPendingSave() {
        mainHandler.removeCallbacks(savePlaylistRunnable)
        savePlaylistNow()
    }

    /** 按统一的身份键（[normalizeUri]）定位列表条目；找不到返回 -1。 */
    private fun indexOfUri(uri: String): Int {
        val key = normalizeUri(uri)
        return playlist.indexOfFirst { normalizeUri(it.uri) == key }
    }

    /** 首次加载时把仓库列表合并进内存（保留加载失败期间手动扫描的条目）。 */
    private fun loadPlaylist() {
        val merged = mergePlaylistOnLoad(PlayerRepository.getPlaylist(), playlist)
        playlist.clear()
        playlist.addAll(merged)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncAdapterPlayingState()
            fullscreenPip.refreshInfoBarContent()
            // 自动连播 / 跨条目 seek 的断点续播由 PlayerService 在本地完成：
            // 界面退到后台时会释放控制器，放在这里会漏掉切歌。
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncAdapterPlayingState()
            updateKeepScreenOn()
            fullscreenPip.refreshPipParams()
            if (!isPlaying) {
                // Service 自己的监听器已在暂停时落盘，这里只刷新界面
                adapter.refreshProgress(controller?.currentMediaItemIndex ?: -1)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
            // 倍速由服务侧持有，重连或外部改速后同步按钮文案
            syncSpeedLabel()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateKeepScreenOn()
            fullscreenPip.refreshPipParams()
        }
    }

    /** 把内部模型转换为 Media3 的 MediaItem。 */
    private fun buildMediaItem(item: MediaItemData): MediaItem =
        MediaItem.Builder()
            .setUri(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.name).build()
            )
            .build()

    /** 播放队列是否与当前列表一致（URI、标题与顺序）；扫描刷新文件名后需重建队列。 */
    private fun queueMatchesPlaylist(ctrl: MediaController): Boolean {
        if (ctrl.mediaItemCount != playlist.size) return false
        for (i in playlist.indices) {
            val queueItem = ctrl.getMediaItemAt(i)
            val queueUri = queueItem.uriString ?: return false
            if (queueUri != playlist[i].uri.toString()) return false
            val queueTitle = queueItem.mediaMetadata.title?.toString()
            if (queueTitle != null && queueTitle != playlist[i].name) return false
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

    /**
     * 列表变化后差分更新播放队列：扫描通常会插入/删除少量条目，
     * 用 add/remove/move/replace 可以避免 setMediaItems 重建整个队列导致短暂断音。
     * 只有当前播放项被移除（或差分无法安全落地）时才整体重建。
     */
    private fun rebuildQueueIfNeeded(ctrl: MediaController) {
        if (queueMatchesPlaylist(ctrl)) return
        // 列表被清空时必须显式清空队列，否则播放器仍保留旧条目
        if (playlist.isEmpty()) {
            ctrl.setMediaItems(emptyList())
            return
        }

        val currentItem = ctrl.currentMediaItem
        val curUri = currentItem.uriString
        val curPos = ctrl.currentPosition
        val curIdx = curUri?.let(::indexOfUri) ?: -1

        val queue = (0 until ctrl.mediaItemCount).map { index ->
            val item = ctrl.getMediaItemAt(index)
            QueueItemSnapshot(
                uri = item.uriString.orEmpty(),
                title = item.mediaMetadata.title?.toString(),
            )
        }
        val target = playlist.map { QueueItemSnapshot(it.uri.toString(), it.name) }
        val plan = planQueueDiff(queue, target, curUri)

        // 当前项仍可识别时优先增量更新；当前项已被移除才整体重建。
        // 当前项存在但没有 URI 属于异常状态，同样退回整体重建以避免误删。
        if (plan.requiresFullReset || (currentItem != null && curUri == null)) {
            val items = playlist.map(::buildMediaItem)
            if (curIdx >= 0) {
                // 仅当当前项仍在列表时才需要先落盘再重建，避免为已移除项写回进度
                PlayerService.flushCurrentPosition()
                ctrl.setMediaItems(items, curIdx, curPos)
            } else {
                ctrl.setMediaItems(items)
            }
            return
        }

        // 下标由 planQueueDiff 按“执行到该步时”的队列状态计算，必须顺序执行。
        // 连续插入合并为一次 addMediaItems，避免逐条插入触发多次队列回调。
        var cursor = 0
        while (cursor < plan.edits.size) {
            val edit = plan.edits[cursor]
            if (edit is QueueEdit.Add) {
                val startIndex = edit.index
                val items = mutableListOf<MediaItem>()
                while (cursor < plan.edits.size) {
                    val add = plan.edits[cursor] as? QueueEdit.Add ?: break
                    items += buildMediaItem(playlist[add.index])
                    cursor++
                }
                ctrl.addMediaItems(startIndex, items)
            } else {
                when (edit) {
                    is QueueEdit.Remove -> ctrl.removeMediaItem(edit.index)
                    is QueueEdit.Move -> ctrl.moveMediaItem(edit.fromIndex, edit.toIndex)
                    is QueueEdit.Replace -> ctrl.replaceMediaItem(edit.index, buildMediaItem(playlist[edit.index]))
                    // Add 已在上面的合并分支处理；这里仅用于保证 when 穷尽，正常不会执行
                    is QueueEdit.Add -> error("队列插入已在上面的合并分支处理")
                }
                cursor++
            }
        }
    }

    /** 返回可续播的位置；无有效断点（未播放 / 已看完）返回 null。 */
    private fun resumePositionOrNull(index: Int): Long? {
        if (index !in playlist.indices) return null
        val item = playlist[index]
        return resumablePositionMs(PlayerRepository.getProgress(item.uri.toString()), item.duration)
    }

    /** seek 到断点位置；无断点时回到条目开头。 */
    private fun seekToResume(index: Int) {
        val ctrl = controller ?: return
        val pos = resumePositionOrNull(index)
        if (pos != null) ctrl.seekTo(index, pos)
        else ctrl.seekToDefaultPosition(index)
    }

    /** 恢复上次播放的条目与位置（仅在播放器空闲时）。 */
    private fun restoreLastPlayed() {
        val ctrl = controller ?: return
        if (ctrl.playWhenReady || ctrl.isPlaying) return
        val lastUri = PlayerRepository.getLastItem() ?: return
        val idx = indexOfUri(lastUri)
        if (idx !in playlist.indices) return

        seekToResume(idx)
        syncAdapterPlayingState()
    }

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
        return if (idx >= 0) idx else speedLevels.indexOfFirst { it == 1.0f }
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
     * 启动或复用扫描任务：静默扫描复用运行中的任务；
     * 手动扫描在静默扫描之后排队，确保能追加新文件。
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

    /** IO 线程查询媒体库并计算对账结果；playlist 只在主线程读写，IO 侧使用快照。 */
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

    /** 应用扫描结果：清理已删除项、追加新文件、刷新元数据并重排。 */
    private fun reconcileScanResult(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?,
        silent: Boolean,
        prune: PruneCandidates
    ) {
        if (scannedVideos == null && scannedAudios == null) {
            if (!silent) {
                toast(R.string.toast_scan_failed)
            }
            return
        }
        // 即使本轮没有条目被移除，也清理一次服务侧缓存（用户可能已手动删过条目）。
        PlayerService.dropProgress(prune.removedUris)
        val result = reconcilePlaylist(
            current = playlist,
            scannedVideos = scannedVideos,
            scannedAudios = scannedAudios,
            removedUris = prune.removedUris,
            silent = silent,
        )
        val changed = result.playlist != playlist
        if (!changed) {
            if (!silent) {
                toast(R.string.toast_no_new_media)
            }
            return
        }
        playlist.clear()
        playlist.addAll(result.playlist)
        if (result.removedCount > 0) syncAdapterPlayingState()
        refreshPlaylist()
        savePlaylist()
        // 空列表同样由 rebuildQueueIfNeeded 的 setMediaItems(emptyList()) 处理
        controller?.let(::rebuildQueueAndClearPending)
        if (!silent) {
            val parts = mutableListOf<String>()
            if (result.removedCount > 0) {
                parts.add(getString(R.string.toast_scan_removed, result.removedCount))
            }
            if (result.addedCount > 0) {
                parts.add(getString(R.string.toast_scan_added, result.addedCount))
            }
            if (result.orderChanged) parts.add(getString(R.string.toast_scan_reordered))
            if (parts.isEmpty()) parts.add(getString(R.string.toast_scan_updated))
            toastMessage(parts.joinToString("，"))
        }
    }

    /**
     * 计算本轮需要删除的条目：完整权限下做单条复核，
     * 无法确认时退回「连续两轮缺失 + 24 小时宽限期」。
     */
    private fun computePruneDeletedMedia(
        playlistSnapshot: List<MediaItemData>,
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?
    ): PruneCandidates {
        if (scannedVideos == null && scannedAudios == null) {
            return PruneCandidates(emptySet(), emptySet(), emptySet())
        }
        val videoKeys = if (scannedVideos != null &&
            hasVideoReadAccess(this, includePartial = false)
        ) {
            scannedVideos.map { normalizeUri(it.uri) }.toSet()
        } else null
        val audioKeys = if (scannedAudios != null &&
            hasAudioReadAccess(this, includePartial = false)
        ) {
            scannedAudios.map { normalizeUri(it.uri) }.toSet()
        } else null
        val candidates = decidePruneCandidates(
            playlistSnapshot = playlistSnapshot,
            videoKeys = videoKeys,
            audioKeys = audioKeys,
            videoPrefix = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString(),
            audioPrefix = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString(),
            // 有可移除卷未挂载时，MediaStore 可能把该卷条目报成“查不到”，不能据此立即删除
            canConfirmImmediately = externalVolumesFullyMounted(),
            missingInfo = PlayerRepository::getMissingInfo,
            confirmedDeleted = ::isConfirmedDeleted,
            now = System.currentTimeMillis(),
            graceMs = PlayerRepository.MISSING_DELETE_GRACE_MS,
        )
        // 缺失记录更新是线程安全的；内部没有变化时不会触发数据库写入
        PlayerRepository.updateMissingRecords(
            candidates.missingUris,
            candidates.observedUris,
            candidates.removedUris,
        )
        return candidates
    }

    /** 可移除卷是否全部挂载；有卷离线时禁用「单条查询后立即删除」，避免误删。 */
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

    /** 单条精确查询：查不到行才算已删除；查询异常返回 false，交回保守判定。 */
    private fun isConfirmedDeleted(uri: Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)
            ?.use { !it.moveToFirst() } ?: false
    } catch (_: Exception) {
        false
    }

    /** 「更多」菜单：检查更新 / 恢复跟随系统亮度 / 重置本地数据。 */
    private fun showMoreMenu() {
        val popup = PopupMenu(this, binding.btnMore)
        popup.menu.add(0, MENU_CHECK_UPDATE, 0, R.string.check_update)
        popup.menu.add(0, MENU_BRIGHTNESS_SYSTEM, 1, R.string.brightness_follow_system)
        popup.menu.add(0, MENU_RESET_DATA, 2, R.string.reset_local_data)
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
                MENU_RESET_DATA -> {
                    confirmResetLocalData()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 重置本地数据前的二次确认。 */
    private fun confirmResetLocalData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_local_data)
            .setMessage(R.string.reset_data_message)
            .setPositiveButton(R.string.reset_data_confirm) { _, _ -> resetLocalData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 删除数据库并重建；成功后清空界面列表并重新扫描。 */
    private fun resetLocalData() {
        // 取消进行中的扫描与待保存任务，避免它们把旧列表写回新库
        cancelScans()
        mainHandler.removeCallbacks(savePlaylistRunnable)
        // 重置期间禁止服务侧写进度并清空待写缓存，避免 stop/clear 的回调把旧进度写回
        PlayerService.beginReset()
        controller?.stop()
        controller?.clearMediaItems()
        lifecycleScope.launch {
            val ok = try {
                PlayerRepository.resetAllData(this@MainActivity)
            } finally {
                // 无论成功与否都恢复落盘，否则之后的正常播放进度会一直丢
                PlayerService.endReset()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                toastLong(R.string.reset_data_failed)
                return@launch
            }
            // 重置期间到达的播放器回调可能已重新填充队列，这里再清一次
            controller?.clearMediaItems()
            playlist.clear()
            playlistLoaded = true
            refreshPlaylist()
            toast(R.string.reset_data_done)
            if (hasStoragePermission()) {
                startScan(silent = false)
            } else {
                requestStoragePermission()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 深色界面固定用浅色系统栏图标；Android 15+ 强制 edge-to-edge，
        // 普通界面的 insets 由 FullscreenPipHelper 统一消费。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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

        // 列表适配器：点击续播、删除按钮移除、实时读取进度
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
        // 关闭条目变更动画，避免 DiffUtil 刷新时列表闪烁
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
        // 全屏状态可能跨后台保留：恢复沉浸式系统栏与顶部信息
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
            } catch (e: Exception) {
                Log.e(TAG, "连接播放服务失败", e)
                if (controllerFuture === future) {
                    toastLong(R.string.toast_player_not_connected)
                }
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

                    // 数据加载失败：提示（后续写入会触发冷却重试）
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
                // 扫描有变化时 reconcileScanResult 已重建队列并刷新；这里只处理
                // 当前项被删除后需要重新定位上次播放项的情况。
                restoreLastPlayed()
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ 由 setAutoEnterEnabled 自动进入小窗（仅播放视频时开启）；
        // 低版本在 onUserLeaveHint 中进入。
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

// ---------------------------------------------------------------------------
// 播放队列差分更新
// 只描述「增 / 删 / 移 / 换」四类操作，不直接依赖 Media3，便于在 JVM 单测中验证下标变化。
// ---------------------------------------------------------------------------

/** 队列中一个条目的最小快照：差分只需要 URI 与标题。 */
internal data class QueueItemSnapshot(val uri: String, val title: String?)

/** 队列差异操作；索引均为执行该操作时的实时下标。 */
internal sealed interface QueueEdit {
    /** 在 [index] 插入 [uri]；对应播放列表的同一目标下标。 */
    data class Add(val index: Int, val uri: String) : QueueEdit

    /** 删除 [index] 处的 [uri]。 */
    data class Remove(val index: Int, val uri: String) : QueueEdit

    /** 把 [fromIndex] 处的 [uri] 移到 [toIndex]。 */
    data class Move(val fromIndex: Int, val toIndex: Int, val uri: String) : QueueEdit

    /** 用播放列表同一目标下标的条目替换 [index]（通常只改标题）。 */
    data class Replace(val index: Int, val uri: String) : QueueEdit
}

/**
 * [requiresFullReset] 为 true 表示当前播放项已不在目标列表中，
 * 或者差分无法安全落地；调用方应回退到 setMediaItems。
 */
internal data class QueueDiffPlan(
    val edits: List<QueueEdit>,
    val requiresFullReset: Boolean,
)

/**
 * 计算把 [current] 变成 [target] 的最小操作序列。
 *
 * 算法：
 * 1. 删除目标中不存在的 URI；
 * 2. 从左到右，把目标项从后方移到当前位，找不到则插入；
 * 3. 截掉尾部多余项；
 * 4. 对仍然存在的条目按需替换标题。
 *
 * 所有操作都在「执行到该步时」的队列上计算，因此可以按顺序直接作用到控制器。
 */
internal fun planQueueDiff(
    current: List<QueueItemSnapshot>,
    target: List<QueueItemSnapshot>,
    currentUri: String?,
): QueueDiffPlan {
    // 当前项被移除时增量操作无法保住播放位置，交给调用方整体重建。
    if (currentUri != null && target.none { it.uri == currentUri }) {
        return QueueDiffPlan(emptyList(), requiresFullReset = true)
    }

    val working = current.toMutableList()
    val edits = mutableListOf<QueueEdit>()
    val targetUris = target.map { it.uri }
    val targetUriSet = targetUris.toSet()

    // 先删掉目标里完全不存在的项，从后往前避免下标漂移。
    for (index in working.indices.reversed()) {
        if (working[index].uri !in targetUriSet) {
            val removed = working.removeAt(index)
            edits += QueueEdit.Remove(index, removed.uri)
        }
    }

    // 把前缀逐位对齐：能移动就移动，不能移动就插入。
    for (index in target.indices) {
        if (index < working.size && working[index].uri == target[index].uri) continue

        val found = (index + 1 until working.size)
            .firstOrNull { working[it].uri == target[index].uri }
        if (found != null) {
            val moved = working.removeAt(found)
            working.add(index, moved)
            edits += QueueEdit.Move(found, index, moved.uri)
        } else {
            working.add(index, target[index])
            edits += QueueEdit.Add(index, target[index].uri)
        }
    }

    // 目标更短时删除尾部多余项。
    while (working.size > target.size) {
        val index = working.lastIndex
        val removed = working.removeAt(index)
        edits += QueueEdit.Remove(index, removed.uri)
    }

    if (working.map { it.uri } != targetUris) {
        // 理论上不会发生；出现重复 URI 等异常时宁可整体重建。
        return QueueDiffPlan(emptyList(), requiresFullReset = true)
    }

    // 结构一致后只替换标题变化的条目，避免重建当前媒体源。
    for (index in target.indices) {
        if (working[index].title != target[index].title) {
            working[index] = target[index]
            edits += QueueEdit.Replace(index, target[index].uri)
        }
    }

    return QueueDiffPlan(edits, requiresFullReset = false)
}
