/** 界面入口：列表 UI、服务连接、队列差分、扫描对账与断点恢复。 */
package io.github.niu0506.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
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
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import io.github.niu0506.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerRepository.ensureLoaded(this)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaListAdapter

    private val playlist = mutableListOf<MediaItemData>()

    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** 与 [PlayerService] 的连接；onStart 创建、onStop 释放。 */
    private var controller: MediaController? = null

    private var playlistLoaded = false

    private val scanner = MediaStoreScanner(this)
    private lateinit var updateManager: UpdateManager
    private lateinit var gestures: GestureController
    private lateinit var fullscreenPip: FullscreenPipHelper

    private companion object {
        const val WRITE_FAILURE_TOAST_MIN_INTERVAL_MS = 15_000L

        const val SAVE_PLAYLIST_DEBOUNCE_MS = 2_000L

        const val MEDIA_CHANGE_DEBOUNCE_MS = 800L

        const val SCAN_NOTICE_HIDE_MS = 4_000L

        const val MENU_CHECK_UPDATE = 1
        const val MENU_BRIGHTNESS_SYSTEM = 2
        const val MENU_RESET_DATA = 3

        val speedLevels = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

        const val TAG = "MainActivity"
    }

    /** 撤销窗口内暂存的删除，窗口结束后才清理播放进度。 */
    private data class PendingDeletion(val item: MediaItemData, val index: Int)

    private var pendingDeletion: PendingDeletion? = null

    private var deleteSnackbar: Snackbar? = null

    /** 由 ACTION_VIEW / ACTION_SEND 带入、等待控制器就绪后入队播放的媒体。 */
    private var pendingOpenUri: Uri? = null

    private var pendingOpenName: String? = null

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasStoragePermission()) {
            startScan(silent = false)
        } else {
            snack(R.string.toast_permission_denied)
        }
    }

    private fun removeItemFromPlaylist(index: Int) {
        if (index !in playlist.indices) return
        // 上一笔删除先落定，避免撤销窗口叠加
        deleteSnackbar?.dismiss()
        commitPendingDeletion()
        val item = playlist[index]

        // 用 URI 定位队列项，避免列表/队列错位时误删
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
        // 进度延后清理：撤销窗口内仍可恢复，窗口结束才真正删除
        pendingDeletion = PendingDeletion(item, index)
        val bar = Snackbar.make(
            binding.root,
            getString(R.string.toast_deleted),
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.undo) { undoDelete() }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (deleteSnackbar === transientBottomBar) deleteSnackbar = null
                    commitPendingDeletion()
                }
            })
        deleteSnackbar = bar
        bar.show()
    }

    private fun undoDelete() {
        val pending = pendingDeletion ?: return
        pendingDeletion = null
        // 撤销窗口内扫描可能已把该文件加回，避免重复插入
        if (indexOfUri(pending.item.uri.toString()) < 0) {
            val index = pending.index.coerceIn(0, playlist.size)
            playlist.add(index, pending.item)
            refreshPlaylist()
            savePlaylist()
            controller?.let(::rebuildQueueIfNeeded)
        }
    }

    private fun commitPendingDeletion() {
        val pending = pendingDeletion ?: return
        pendingDeletion = null
        PlayerService.dropProgress(pending.item.uri.toString())
    }

    private fun submitPlaylist() {
        adapter.submitList(playlist.toList())
    }

    private fun refreshPlaylist() {
        submitPlaylist()
        binding.tvCount.text = if (playlist.isEmpty()) getString(R.string.list_count_empty)
        else getString(R.string.list_count, playlist.size)
        binding.tvEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 仅在「正在播放视频」时保持屏幕常亮；纯音频不阻止系统息屏。 */
    private fun updateKeepScreenOn() {
        val ctrl = controller
        binding.playerView.keepScreenOn = ctrl.isPlayingVideo()
    }

    private fun syncAdapterPlayingState() {
        adapter.setCurrentPlaying(
            controller?.currentMediaItem.uriString,
            controller?.isPlaying == true
        )
    }

    private fun updateDurationAt(index: Int, duration: Long) {
        if (index !in playlist.indices || playlist[index].duration == duration) return
        playlist[index] = playlist[index].copy(duration = duration)
        // 差分中或列表未同步时退回整表提交，避免被旧结果覆盖
        if (adapter.isDiffing || !adapter.updateItem(playlist[index])) submitPlaylist()
        PlayerRepository.updatePlaylistDuration(playlist[index].uri, duration)
    }

    private val savePlaylistRunnable = Runnable { savePlaylistNow() }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun savePlaylist() {
        mainHandler.removeCallbacks(savePlaylistRunnable)
        mainHandler.postDelayed(savePlaylistRunnable, SAVE_PLAYLIST_DEBOUNCE_MS)
    }

    private fun savePlaylistNow() {
        // 未合并已存列表前落盘会覆盖仓库，造成数据丢失
        if (!playlistLoaded || !PlayerRepository.isLoaded()) return
        PlayerRepository.savePlaylist(playlist.toList())
    }

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
            // 断点续播由 PlayerService 完成：界面退后台会释放控制器
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncAdapterPlayingState()
            updateKeepScreenOn()
            fullscreenPip.refreshPipParams()
            if (!isPlaying) {
                // 服务侧已落盘，这里只刷新界面
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
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                snack(R.string.toast_playback_interrupted)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // 倍速由服务侧持有，重连后同步按钮文案
            syncSpeedLabel()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateKeepScreenOn()
            fullscreenPip.refreshPipParams()
        }
    }

    private fun buildMediaItem(item: MediaItemData): MediaItem =
        MediaItem.Builder()
            .setUri(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.name).build()
            )
            .build()

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

    private fun rebuildQueueAndClearPending(ctrl: MediaController) {
        rebuildQueueIfNeeded(ctrl)
        if (queueMatchesPlaylist(ctrl)) {
            PlayerService.clearAllPendingDrops()
        }
    }

    /** 差分更新队列，避免整体重建导致断音；必要时才整体重建。 */
    private fun rebuildQueueIfNeeded(ctrl: MediaController) {
        if (queueMatchesPlaylist(ctrl)) return
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

        // 当前项被移除或状态异常时整体重建
        if (plan.requiresFullReset || (currentItem != null && curUri == null)) {
            val items = playlist.map(::buildMediaItem)
            if (curIdx >= 0) {
                // 仅在当前项仍在列表时先落盘
                PlayerService.flushCurrentPosition()
                ctrl.setMediaItems(items, curIdx, curPos)
            } else {
                ctrl.setMediaItems(items)
            }
            return
        }

        // 下标按执行到该步时的队列状态计算，必须顺序执行；
        // 连续插入合并为一次 addMediaItems，避免多次队列回调。
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
                    is QueueEdit.Add -> error("队列插入已在上面的合并分支处理")
                }
                cursor++
            }
        }
    }

    private fun seekToResume(index: Int) {
        val ctrl = controller ?: return
        val item = playlist.getOrNull(index)
        val pos = item?.let {
            resumablePositionMs(PlayerRepository.getProgress(it.uri.toString()), it.duration)
        }
        if (pos != null) ctrl.seekTo(index, pos)
        else ctrl.seekToDefaultPosition(index)
    }

    private fun restoreLastPlayed() {
        val ctrl = controller ?: return
        if (ctrl.playWhenReady || ctrl.isPlaying) return
        val lastUri = PlayerRepository.getLastItem() ?: return
        val idx = indexOfUri(lastUri)
        if (idx !in playlist.indices) return

        seekToResume(idx)
        syncAdapterPlayingState()
    }

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
        binding.playerView.findViewById<View>(R.id.btn_fullscreen_back)?.setOnClickListener {
            fullscreenPip.setFullscreen(false)
        }
    }

    private fun syncSpeedLabel() {
        val speed = controller?.playbackParameters?.speed ?: return
        binding.playerView.findViewById<TextView>(R.id.btn_speed)?.text = formatSpeed(speed)
    }

    private fun speedsIndex(speed: Float): Int =
        speedLevels.indexOfFirst { it == speed }.takeIf { it >= 0 }
            ?: speedLevels.indexOfFirst { it == 1.0f }

    private fun showSpeedSelection() {
        val ctrl = controller ?: return
        showSelectionPanel(
            activity = this,
            title = getString(R.string.speed_dialog_title),
            options = speedLevels.map(::formatSpeed),
            selectedIndex = speedsIndex(ctrl.playbackParameters.speed),
            immersive = fullscreenPip.isFullscreen && !fullscreenPip.isInPipMode,
        ) { which ->
            controller?.setPlaybackSpeed(speedLevels[which])
        }
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    }

    /** 选择面板中的一项：自动 / 关闭 / 具体轨道。 */
    private data class TrackSheetOption(
        val label: String,
        val override: TrackSelectionOverride? = null,
        val disabled: Boolean = false,
    )

    @OptIn(UnstableApi::class)
    private fun showTrackSelection(trackType: Int, @StringRes titleRes: Int) {
        val ctrl = controller ?: run {
            snack(R.string.toast_player_not_connected)
            return
        }
        val params = ctrl.trackSelectionParameters
        val disabled = params.disabledTrackTypes.contains(trackType)
        val override = params.overrides.values.firstOrNull { it.type == trackType }
        val isText = trackType == C.TRACK_TYPE_TEXT

        val options = mutableListOf<TrackSheetOption>()
        // 字幕可关闭；音轨不提供关闭，避免静音后难恢复
        if (isText) {
            options += TrackSheetOption(getString(R.string.track_off), disabled = true)
        }
        options += TrackSheetOption(getString(R.string.track_auto))
        val autoIndex = options.lastIndex

        var selectedIndex = if (disabled) 0 else autoIndex
        var concreteCount = 0
        for (group in ctrl.currentTracks.groups) {
            if (group.type != trackType || !group.isSupported) continue
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                concreteCount++
                val optionIndex = options.size
                options += TrackSheetOption(
                    label = trackLabel(group.getTrackFormat(trackIndex), concreteCount),
                    override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                )
                val isSelected = if (override != null) {
                    override.mediaTrackGroup == group.mediaTrackGroup &&
                        override.trackIndices.firstOrNull() == trackIndex
                } else {
                    group.isTrackSelected(trackIndex)
                }
                if (isSelected) selectedIndex = optionIndex
            }
        }
        if (concreteCount == 0) {
            snack(R.string.toast_no_tracks)
            return
        }

        showSelectionPanel(
            activity = this,
            title = getString(titleRes),
            options = options.map { it.label },
            selectedIndex = selectedIndex,
            immersive = fullscreenPip.isFullscreen && !fullscreenPip.isInPipMode,
        ) { index ->
            val option = options[index]
            val newParams = params.buildUpon().apply {
                when {
                    option.disabled -> {
                        clearOverridesOfType(trackType)
                        setTrackTypeDisabled(trackType, true)
                    }
                    option.override != null -> {
                        setTrackTypeDisabled(trackType, false)
                        setOverrideForType(option.override)
                    }
                    else -> {
                        clearOverridesOfType(trackType)
                        setTrackTypeDisabled(trackType, false)
                    }
                }
            }.build()
            controller?.trackSelectionParameters = newParams
        }
    }

    /** 轨道名称：优先用媒体自带的标签 / 语言，其次编码与 MIME。 */
    private fun trackLabel(format: Format, order: Int): String {
        val named = listOfNotNull(
            format.label?.takeIf { it.isNotBlank() },
            format.language?.takeIf { it.isNotBlank() && it != "und" },
        ).distinct()
        if (named.isNotEmpty()) return named.joinToString(" · ")
        return format.codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }
            ?: format.sampleMimeType?.takeIf { it.isNotBlank() }
            ?: getString(R.string.track_fallback, order)
    }

    @Volatile
    private var scanJob: Job? = null

    private val activeScanJobs = mutableSetOf<Job>()

    private var manualScanJob: Job? = null

    private val mediaChangeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(debouncedScanRunnable)
            mainHandler.postDelayed(debouncedScanRunnable, MEDIA_CHANGE_DEBOUNCE_MS)
        }
    }
    private val debouncedScanRunnable = Runnable { startScan(silent = true) }

    /** 启动或复用扫描；手动扫描排在静默扫描之后。 */
    private fun startScan(silent: Boolean): Job {
        val running = scanJob?.takeIf { it.isActive }
        if (running != null && silent) return running
        if (!silent) {
            // 连续点击时复用进行中/排队的手动任务
            manualScanJob?.takeIf { it.isActive }?.let { return it }
        }
        val job = lifecycleScope.launch {
            running?.join()
            if (!hasStoragePermission()) return@launch
            scanMediaInternal(silent)
        }
        activeScanJobs += job
        job.invokeOnCompletion {
            // 完成回调可能不在主线程
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

    private fun cancelScans() {
        val jobs = activeScanJobs.toList()
        activeScanJobs.clear()
        jobs.forEach { it.cancel() }
        scanJob = null
        manualScanJob = null
    }

    private suspend fun scanMediaInternal(silent: Boolean) {
        val playlistSnapshot = playlist.toList()
        val (videos, audios) = withContext(Dispatchers.IO) {
            scanner.queryVideos() to scanner.queryAudios()
        }
        val prune = withContext(Dispatchers.IO) {
            computePruneDeletedMedia(playlistSnapshot, videos, audios)
        }
        reconcileScanResult(videos, audios, silent, prune)
    }

    private fun reconcileScanResult(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?,
        silent: Boolean,
        prune: PruneCandidates
    ) {
        if (scannedVideos == null && scannedAudios == null) {
            if (!silent) {
                snack(R.string.toast_scan_failed)
            }
            return
        }
        PlayerService.dropProgress(prune.removedUris)
        val result = reconcilePlaylist(
            current = playlist,
            scannedVideos = scannedVideos,
            scannedAudios = scannedAudios,
            removedUris = prune.removedUris,
        )
        val changed = result.playlist != playlist
        if (!changed) {
            if (!silent) {
                snack(R.string.toast_no_new_media)
            }
            return
        }
        playlist.clear()
        playlist.addAll(result.playlist)
        if (result.removedCount > 0) syncAdapterPlayingState()
        refreshPlaylist()
        savePlaylist()
        // 空列表也交由 rebuildQueueIfNeeded 处理
        controller?.let(::rebuildQueueAndClearPending)
        if (silent) {
            // 静默扫描也导入新文件，但不打断操作：只在列表顶部给轻提示
            if (result.addedCount > 0) {
                showScanNotice(getString(R.string.toast_scan_added_silent, result.addedCount))
            }
        } else {
            val parts = mutableListOf<String>()
            if (result.removedCount > 0) {
                parts.add(getString(R.string.toast_scan_removed, result.removedCount))
            }
            if (result.addedCount > 0) {
                parts.add(getString(R.string.toast_scan_added, result.addedCount))
            }
            if (result.orderChanged) parts.add(getString(R.string.toast_scan_reordered))
            if (parts.isEmpty()) parts.add(getString(R.string.toast_scan_updated))
            snackMessage(parts.joinToString("，"))
        }
    }

    private val hideScanNoticeRunnable = Runnable {
        binding.tvScanNotice.visibility = View.GONE
    }

    /** 列表顶部的轻提示：静默扫描新增文件时短暂显示，不弹 Toast。 */
    private fun showScanNotice(text: CharSequence) {
        binding.tvScanNotice.text = text
        binding.tvScanNotice.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideScanNoticeRunnable)
        mainHandler.postDelayed(hideScanNoticeRunnable, SCAN_NOTICE_HIDE_MS)
    }

    private fun hideScanNotice() {
        mainHandler.removeCallbacks(hideScanNoticeRunnable)
        binding.tvScanNotice.visibility = View.GONE
    }

    /** 计算可删除条目：单条复核，无法确认时走宽限期。 */
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
            // 有可移除卷未挂载时不能据此立即删除
            canConfirmImmediately = externalVolumesFullyMounted(),
            missingInfo = PlayerRepository::getMissingInfo,
            confirmedDeleted = ::isConfirmedDeleted,
            now = System.currentTimeMillis(),
            graceMs = PlayerRepository.MISSING_DELETE_GRACE_MS,
        )
        // 缺失记录更新是线程安全的，无变化不写库
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
        // API 30+ 用「当前 + 最近可用」卷；低版本只能看到仍挂载的卷
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
                    snack(R.string.toast_brightness_follow_system)
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

    private fun confirmResetLocalData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_local_data)
            .setMessage(R.string.reset_data_message)
            .setPositiveButton(R.string.reset_data_confirm) { _, _ -> resetLocalData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetLocalData() {
        // 取消扫描与待保存任务，避免旧列表写回新库
        cancelScans()
        mainHandler.removeCallbacks(savePlaylistRunnable)
        // 待撤销的删除随重置一并作废
        deleteSnackbar?.dismiss()
        pendingDeletion = null
        hideScanNotice()
        // 重置期间禁止服务侧写进度，避免旧进度写回
        PlayerService.beginReset()
        controller?.stop()
        controller?.clearMediaItems()
        lifecycleScope.launch {
            val ok = try {
                PlayerRepository.resetAllData(this@MainActivity)
            } finally {
                // 无论成功与否都恢复落盘
                PlayerService.endReset()
            }
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                snackLong(R.string.reset_data_failed)
                return@launch
            }
            // 重置期间的回调可能重新填充队列，再清一次
            controller?.clearMediaItems()
            playlist.clear()
            playlistLoaded = true
            refreshPlaylist()
            snack(R.string.reset_data_done)
            if (hasStoragePermission()) {
                startScan(silent = false)
            } else {
                requestStoragePermission()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 深色界面固定浅色系统栏图标；insets 由 FullscreenPipHelper 消费
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 仅在界面可见时收集提示事件；文件缺失登记已在 PlayerService
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var lastShownMs = 0L
                    PlayerRepository.writeFailures.collect {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastShownMs < WRITE_FAILURE_TOAST_MIN_INTERVAL_MS) return@collect
                        lastShownMs = now
                        snackLong(R.string.toast_repository_write_failed)
                    }
                }

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
                        snackMessage(message)
                    }
                }
            }
        }

        updateManager = UpdateManager(this)
        gestures = GestureController(this, binding.playerView, binding.gestureOverlay) { controller }
        fullscreenPip = FullscreenPipHelper(this, binding) { controller }

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
                MaterialAlertDialogBuilder(this)
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

        binding.btnScan.setOnClickListener {
            if (hasStoragePermission()) {
                startScan(silent = false)
            } else {
                requestStoragePermission()
            }
        }
        binding.btnPip.setOnClickListener { fullscreenPip.enterPipMode() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.tvScanNotice.setOnClickListener { hideScanNotice() }
        binding.playerView.setFullscreenButtonClickListener { enabled ->
            fullscreenPip.setFullscreen(enabled)
        }
        // 全屏时先退出全屏，否则退出界面
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
        // 启动即为「用影音盒打开」时，先暂存，等控制器与列表就绪后播放
        handleIntent(intent)

        // Android 13+ 需动态申请通知权限（前台服务通知）
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 处理外部打开请求（ACTION_VIEW / ACTION_SEND），控制器就绪后再入队播放。 */
    private fun handleIntent(intent: Intent?) {
        val uri = extractPlayableUri(intent) ?: return
        // 尽力保留跨应用读权限，否则下次启动时该条目可能已不可读
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        pendingOpenUri = uri
        pendingOpenName = resolveDisplayName(uri)
        openPendingMedia()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val queried = runCatching {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /** 把外部媒体加入列表与队列并立即播放；控制器未就绪时保留待处理状态。 */
    private fun openPendingMedia() {
        val ctrl = controller ?: return
        if (!playlistLoaded) return
        val uri = pendingOpenUri ?: return
        val name = pendingOpenName ?: getString(R.string.unknown_file)
        pendingOpenUri = null
        pendingOpenName = null

        var index = indexOfUri(uri.toString())
        if (index < 0) {
            playlist.add(MediaItemData(uri, name))
            index = indexOfUri(uri.toString())
            refreshPlaylist()
            savePlaylist()
        }
        if (index < 0) return
        rebuildQueueIfNeeded(ctrl)
        PlayerService.resetErrorCount()
        ctrl.seekTo(index, 0)
        ctrl.play()
        // 已消费外部意图：避免界面重建（如切换字体大小）时重复播放
        val current = intent
        if (current?.action == Intent.ACTION_VIEW || current?.action == Intent.ACTION_SEND) {
            intent = Intent(this, MainActivity::class.java)
        }
    }

    private fun isCurrentController(
        future: ListenableFuture<MediaController>,
        ctrl: MediaController
    ): Boolean = controllerFuture === future && controller === ctrl

    override fun onStart() {
        super.onStart()
        // 全屏状态可能跨后台保留
        fullscreenPip.onStarted()
        updateManager.registerReceivers()
        updateManager.resumePendingDownload()
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

            if (controllerFuture !== future) return@addListener
            val ctrl = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "连接播放服务失败", e)
                if (controllerFuture === future) {
                    snackLong(R.string.toast_player_not_connected)
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

                    !loaded -> snackLong(R.string.toast_repository_load_failed)

                    !playlistLoaded -> {
                        loadPlaylist()
                        playlistLoaded = true
                    }

                    else -> adapter.refreshAllProgress()
                }

                if (!isCurrentController(future, ctrl)) return@launch
                // 先把列表显示出来并建立队列，扫描在后台继续
                refreshPlaylist()
                rebuildQueueAndClearPending(ctrl)
                restoreLastPlayed()
                // 外部打开请求优先于「恢复上次播放」
                openPendingMedia()

                startScan(silent = true).join()

                if (!isCurrentController(future, ctrl)) return@launch
                // 仅处理当前项被删除后重新定位上次播放项
                restoreLastPlayed()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        updateManager.unregisterReceivers()
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        mainHandler.removeCallbacks(debouncedScanRunnable)
        cancelScans()
        mainHandler.removeCallbacks(savePlaylistRunnable)
        savePlaylistNow()
        // 离开界面即视为确认删除，撤销窗口不再保留
        deleteSnackbar?.dismiss()
        commitPendingDeletion()
        hideScanNotice()
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
        PlayerService.flushCurrentPosition()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ 由 setAutoEnterEnabled 自动进入小窗，低版本在 onUserLeaveHint 处理
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

// 播放队列差分：只描述增/删/移/换，不依赖 Media3，便于单测。

/** 从 ACTION_VIEW / ACTION_SEND 意图中取出待播放的媒体 URI。 */
internal fun extractPlayableUri(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}

internal data class QueueItemSnapshot(val uri: String, val title: String?)

/** 队列差异操作；索引均为执行该操作时的实时下标。 */
internal sealed interface QueueEdit {
    data class Add(val index: Int) : QueueEdit

    data class Remove(val index: Int) : QueueEdit

    data class Move(val fromIndex: Int, val toIndex: Int) : QueueEdit

    data class Replace(val index: Int) : QueueEdit
}

/** [requiresFullReset] 为 true 时应整体重建。 */
internal data class QueueDiffPlan(
    val edits: List<QueueEdit>,
    val requiresFullReset: Boolean,
)

/**
 * 计算把 [current] 变成 [target] 的最小操作序列，按执行顺序返回。
 * 依次处理：删除多余项 → 前移/插入目标项 → 截断尾部 → 替换标题。
 */
internal fun planQueueDiff(
    current: List<QueueItemSnapshot>,
    target: List<QueueItemSnapshot>,
    currentUri: String?,
): QueueDiffPlan {
    // 当前项被移除时无法保住播放位置，整体重建
    if (currentUri != null && target.none { it.uri == currentUri }) {
        return QueueDiffPlan(emptyList(), requiresFullReset = true)
    }

    val working = current.toMutableList()
    val edits = mutableListOf<QueueEdit>()
    val targetUris = target.map { it.uri }
    val targetUriSet = targetUris.toSet()

    // 从后往前删除目标中不存在的项，避免下标漂移
    for (index in working.indices.reversed()) {
        if (working[index].uri !in targetUriSet) {
            working.removeAt(index)
            edits += QueueEdit.Remove(index)
        }
    }

    // 逐位对齐：能移动就移动，不能移动就插入
    for (index in target.indices) {
        if (index < working.size && working[index].uri == target[index].uri) continue

        val found = (index + 1 until working.size)
            .firstOrNull { working[it].uri == target[index].uri }
        if (found != null) {
            val moved = working.removeAt(found)
            working.add(index, moved)
            edits += QueueEdit.Move(found, index)
        } else {
            working.add(index, target[index])
            edits += QueueEdit.Add(index)
        }
    }

    while (working.size > target.size) {
        val index = working.lastIndex
        working.removeAt(index)
        edits += QueueEdit.Remove(index)
    }

    if (working.map { it.uri } != targetUris) {
        // 出现重复 URI 等异常时整体重建
        return QueueDiffPlan(emptyList(), requiresFullReset = true)
    }

    // 结构一致后只替换标题变化的条目，避免重建媒体源
    for (index in target.indices) {
        if (working[index].title != target[index].title) {
            working[index] = target[index]
            edits += QueueEdit.Replace(index)
        }
    }

    return QueueDiffPlan(edits, requiresFullReset = false)
}
