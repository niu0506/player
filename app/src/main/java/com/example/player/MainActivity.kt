package com.example.player

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.example.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as M3MediaItem
import androidx.media3.common.MediaMetadata as M3MediaMetadata

/** 应用入口：进程启动时提前触发仓库加载。 */
class PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerRepository.ensureLoaded(this)
    }
}

/**
 * 主界面。
 *
 * 负责：连接 [PlayerService]（通过 MediaController）、展示与维护播放列表、
 * 扫描本地媒体、恢复上次播放与进度、以及组装全屏/画中画、手势、更新等辅助模块。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaListAdapter

    /** 当前播放列表（界面侧内存副本）。 */
    private val playlist = mutableListOf<MediaItemData>()

    /** 控制器构建的 Future，用于校验回调是否针对最新一次连接。 */
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    /** 与 PlayerService 的媒体控制器。 */
    private var controller: MediaController? = null

    /** 是否已从仓库加载过播放列表（用于 onStart 中的分支判断）。 */
    private var playlistLoaded = false

    /**
     * 连续播放错误计数：播放成功或用户主动切歌时清零，
     * 达到队列长度时停止自动跳转，避免整队坏文件时无限重试。
     */
    private var consecutiveErrorCount = 0

    private val scanner = MediaStoreScanner(this)
    private lateinit var updateManager: UpdateManager
    private lateinit var gestures: GestureController
    private lateinit var fullscreenPip: FullscreenPipHelper

    private companion object {
        const val MENU_ID_CHECK_UPDATE = 100

        /** 写盘失败提示的最小间隔，避免连续失败时反复弹出 Toast。 */
        const val WRITE_FAILURE_TOAST_MIN_INTERVAL_MS = 15_000L
    }

    /** 通知权限请求结果回调（当前无需额外处理）。 */
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    /** 存储权限请求结果回调。 */
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants: Map<String, Boolean> ->
        if (grants.values.any { it }) {
            Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show()
        }
    }

    /** 从播放列表与播放器队列中移除指定下标（不处理持久化与提示）。 */
    private fun removeItemAt(index: Int) {
        if (index !in playlist.indices) return
        // 按下标同步删除前先保证索引含义一致。启动恢复/重排期间，服务队列可能仍是旧顺序。
        controller?.let(::rebuildQueueIfNeeded)
        clearProgress(playlist[index].uri)
        playlist.removeAt(index)
        controller?.removeMediaItem(index)
    }

    /**
     * 从播放列表删除一项：移除、同步状态、刷新并持久化，最后提示。
     * @param showToast 是否弹出“已删除”提示；错误流程中已另行提示时传 false 以免重复弹窗。
     */
    private fun removeItemFromPlaylist(index: Int, showToast: Boolean = true) {
        if (index !in playlist.indices) return
        removeItemAt(index)
        syncAdapterPlayingState()
        refreshPlaylist()
        savePlaylist()
        if (showToast) {
            Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
        }
    }

    /** 把当前播放列表提交给适配器（触发差异刷新）。 */
    private fun submitPlaylist() {
        adapter.submitList(playlist.toList())
    }

    /** 刷新列表相关 UI：列表、数量文案、空状态。 */
    private fun refreshPlaylist() {
        submitPlaylist()
        binding.tvCount.text = if (playlist.isEmpty()) getString(R.string.list_count_empty)
        else getString(R.string.list_count, playlist.size)
        binding.tvEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 把播放器当前播放项与状态同步到适配器。 */
    private fun syncAdapterPlayingState() {
        adapter.setCurrentPlaying(
            controller?.currentMediaItem?.localConfiguration?.uri?.toString(),
            controller?.isPlaying == true
        )
    }

    /** 更新指定项的时长（来自播放器解码信息，比扫描结果更准确）。 */
    private fun updateDurationAt(index: Int, duration: Long) {
        if (index !in playlist.indices || playlist[index].duration == duration) return
        playlist[index] = playlist[index].copy(duration = duration)
        submitPlaylist()
        // 用解码得到的精确时长覆盖扫描结果并落盘，避免下次启动回退到旧时长
        savePlaylist()
    }

    /** 持久化当前播放列表。 */
    private fun savePlaylist() {
        // 仓库尚未成功加载（如数据库打开失败）时禁止落盘，
        // 避免用不完整的列表覆盖库中本已完好的数据
        if (!PlayerRepository.isLoaded()) return
        PlayerRepository.savePlaylist(playlist.toList())
    }

    /** 返回当前播放列表中所有条目的规范化 uri 键，用于去重。 */
    private fun existingUriKeys(): Set<String> = playlist.map { normalizeUri(it.uri) }.toSet()

    /** 从仓库加载播放列表到界面（按规范化 uri 去重合并）。 */
    private fun loadPlaylist() {
        val existingKeys = existingUriKeys().toMutableSet()
        for (item in PlayerRepository.getPlaylist()) {
            val key = normalizeUri(item.uri)
            if (key in existingKeys) continue
            existingKeys.add(key)
            playlist.add(item)
        }
    }

    /** 清除某项的播放进度（内存缓存 + 持久化）。 */
    private fun clearProgress(uri: Uri) {
        val key = uri.toString()
        PlayerService.dropProgress(key)
        PlayerRepository.applyProgressUpdates(emptyMap(), setOf(key))
    }

    /** 界面侧播放器事件监听：同步列表状态、恢复进度、处理错误与倍速等。 */
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: M3MediaItem?, reason: Int) {

            syncAdapterPlayingState()

            // 非循环重复切换时，尝试恢复目标项的进度
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                restoreProgressIfNeeded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncAdapterPlayingState()
            if (!isPlaying) {
                // 暂停时立即落盘，并刷新当前项进度显示
                PlayerService.flushCurrentPosition()
                adapter.refreshProgress(controller?.currentMediaItemIndex ?: -1)
            } else {
                // 播放成功：重置连续错误计数
                consecutiveErrorCount = 0
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 就绪后用真实时长更新列表项
            if (playbackState == Player.STATE_READY) {
                val index = controller?.currentMediaItemIndex ?: -1
                val duration = controller?.duration ?: C.TIME_UNSET
                if (duration != C.TIME_UNSET && duration > 0 && index in playlist.indices) {
                    updateDurationAt(index, duration)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val ctrl = controller ?: return

            // 定位出错项
            val errorUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
            // 尽早确认前台接管错误，避免服务的延迟托底先于异步 Controller 命令执行。
            errorUri?.let { PlayerService.notifyErrorHandled(it) }
            val index = errorUri?.let { uri ->
                playlist.indexOfFirst { it.uri.toString() == uri }
            } ?: -1
            val name = playlist.getOrNull(index)?.name ?: "未知文件"

            // 文件不存在属于可自动跳过的错误
            val isFileGone = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

            // 连续错误计数：整队都失败时停止自动跳转，避免 REPEAT_MODE_ALL 下无限 seek
            consecutiveErrorCount++
            val exhausted =
                ctrl.mediaItemCount in 1..consecutiveErrorCount
            val hasNext = !exhausted && ctrl.hasNextMediaItem()

            if (hasNext) {
                // 有下一项且未达到整队上限：自动跳到下一项继续播放
                ctrl.seekToNextMediaItem()
                ctrl.prepare()
                ctrl.play()
            } else {
                // 无下一项或整队连续出错：只暂停。不再 prepare()，否则会对同一个坏文件
                // 反复重新加载、再次报错，与 onPlayerError 形成无限循环
                ctrl.pause()
            }

            if (isFileGone && errorUri != null) {
                // 文件丢失：只记录一次缺失（条目与进度都保留），真正删除交给扫描流程按
                // “多次缺失 + 超过宽限期”判定，避免存储卷瞬时不可用时误删
                PlayerRepository.recordMissing(errorUri)
            }

            // 整队失败只提示一次；否则按是否有下一项给出对应提示（不再删除任何条目）
            val message = when {
                exhausted -> getString(R.string.toast_playback_error_all_failed)
                isFileGone -> getString(
                    if (hasNext) R.string.toast_file_unreadable
                    else R.string.toast_file_unreadable_last,
                    name
                )
                else -> getString(
                    if (hasNext) R.string.toast_playback_error
                    else R.string.toast_playback_error_last,
                    name
                )
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {

            // 因瞬时音频焦点丢失（如被其他应用抢占）导致的暂停：提示用户
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                Toast.makeText(
                    this@MainActivity, R.string.toast_playback_interrupted, Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {

            // 倍速变化时同步按钮上的倍速文案
            syncSpeedLabel()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // 画中画模式下视频尺寸变化时更新窗口比例
            if (fullscreenPip.isInPipMode) fullscreenPip.updatePipAspectRatio(videoSize)
        }
    }

    /** 把界面数据模型转换为 Media3 的 MediaItem。 */
    private fun buildMediaItem(item: MediaItemData): M3MediaItem =
        M3MediaItem.Builder()
            .setUri(item.uri)
            .setMediaMetadata(
                M3MediaMetadata.Builder().setTitle(item.name).build()
            )
            .build()

    /** 判断播放器当前队列是否与界面播放列表一致。 */
    private fun queueMatchesPlaylist(ctrl: MediaController): Boolean {
        if (ctrl.mediaItemCount != playlist.size) return false
        for (i in playlist.indices) {
            val queueUri = ctrl.getMediaItemAt(i).localConfiguration?.uri?.toString() ?: return false
            if (queueUri != playlist[i].uri.toString()) return false
        }
        return true
    }

    /**
     * 队列与播放列表不一致时重建队列，并尽量保持当前播放项与位置。
     *
     * 重排后下标必然变化，因此当前项一律按 **uri** 反查，不按旧下标恢复；
     * 反查不到（该项已不在列表中）时保持默认起始位置，与重建前行为一致。
     */
    private fun rebuildQueueIfNeeded(ctrl: MediaController) {
        if (playlist.isEmpty() || queueMatchesPlaylist(ctrl)) return

        val curUri = ctrl.currentMediaItem?.localConfiguration?.uri?.toString()
        val curPos = ctrl.currentPosition
        val curIdx = curUri?.let { u -> playlist.indexOfFirst { it.uri.toString() == u } } ?: -1

        // 重建前先把当前位置落盘，避免清空队列时丢失进度
        PlayerService.flushCurrentPosition()

        // 原子替换整个队列：避免 clearMediaItems 让播放器短暂进入 STATE_ENDED
        // 而被 PlayerService 暂停播放，也避免逐项 addMediaItem 产生一串中间切换
        val items = playlist.map(::buildMediaItem)
        if (curIdx >= 0) {
            ctrl.setMediaItems(items, curIdx, curPos)
        } else {
            ctrl.setMediaItems(items)
        }
    }

    /** 解析某项的续播位置（来自仓库进度，默认 0）。 */
    private fun resolveResumePosition(item: MediaItemData): Long =
        PlayerRepository.getProgress(item.uri.toString()) ?: 0L

    /** 返回可用的续播位置；无有效进度（未开始或已播完）时返回 null。 */
    private fun resumePositionOrNull(index: Int): Long? {
        if (index !in playlist.indices) return null
        val item = playlist[index]
        val pos = resolveResumePosition(item)
        return if (pos > 0 && !isProgressFinished(pos, item.duration)) pos else null
    }

    /** 跳转到指定项的续播位置；无进度则跳到默认起始位置。 */
    private fun seekToResume(index: Int) {
        val ctrl = controller ?: return
        val pos = resumePositionOrNull(index)
        if (pos != null) ctrl.seekTo(index, pos) else ctrl.seekToDefaultPosition(index)
    }

    /** 切歌后如需恢复进度则执行 seek。 */
    private fun restoreProgressIfNeeded() {
        val ctrl = controller ?: return
        resumePositionOrNull(ctrl.currentMediaItemIndex)?.let { ctrl.seekTo(it) }
    }

    /** 恢复上次播放项（仅在未播放状态下），并定位到续播位置。 */
    private fun restoreLastPlayed() {
        val ctrl = controller ?: return
        if (ctrl.playWhenReady || ctrl.isPlaying) return
        val lastUri = PlayerRepository.getLastItem() ?: return
        val idx = playlist.indexOfFirst { it.uri.toString() == lastUri }
        if (idx !in playlist.indices) return

        seekToResume(idx)
        syncAdapterPlayingState()
    }

    /** 可选倍速档位。 */
    private val speedLevels = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    /** 配置播放器控件的额外按钮：循环模式、随机播放、倍速、音轨/字幕选择等。 */
    @OptIn(UnstableApi::class)
    private fun setupControllerExtras() {
        binding.playerView.setRepeatToggleModes(
            RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
        )
        binding.playerView.setShowShuffleButton(true)
        binding.playerView.keepScreenOn = true

        binding.playerView.findViewById<View>(R.id.btn_speed)?.setOnClickListener {
            showSpeedSelection()
        }

        binding.playerView.findViewById<View>(R.id.btn_audio)?.setOnClickListener {
            showTrackSelection(C.TRACK_TYPE_AUDIO, R.string.track_select_audio_title)
        }
        binding.playerView.findViewById<View>(R.id.btn_subtitle)?.setOnClickListener {
            showTrackSelection(C.TRACK_TYPE_TEXT, R.string.track_select_subtitle_title)
        }
    }

    /** 同步倍速按钮上的文案。 */
    private fun syncSpeedLabel() {
        val speed = controller?.playbackParameters?.speed ?: return
        binding.playerView.findViewById<TextView>(R.id.btn_speed)?.text = formatSpeed(speed)
    }

    /** 返回当前倍速在档位数组中的下标（非标准值时回退到 1.0x）。 */
    private fun speedsIndex(speed: Float): Int {
        val idx = speedLevels.indexOfFirst { it == speed }
        if (idx >= 0) return idx
        val one = speedLevels.indexOfFirst { it == 1.0f }
        return if (one >= 0) one else 0
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

    /** 格式化倍速文案，如 “1x”“1.5x”。 */
    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    }

    /** 弹出音轨/字幕选择对话框。 */
    @OptIn(UnstableApi::class)
    private fun showTrackSelection(trackType: Int, @StringRes titleRes: Int) {
        val ctrl = controller ?: run {
            Toast.makeText(this, R.string.toast_player_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            TrackSelectionDialogBuilder(this, getString(titleRes), ctrl, trackType)
                .setShowDisableOption(false)
                .build()
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_no_tracks, Toast.LENGTH_SHORT).show()
        }
    }

    /** 当前扫描任务（用于去重，避免并发扫描）。 */
    @Volatile
    private var scanJob: Job? = null

    /** 媒体库内容变化监听：去抖 800ms 后触发重新扫描。 */
    private val mediaChangeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scanHandler.removeCallbacks(debouncedScanRunnable)
            scanHandler.postDelayed(debouncedScanRunnable, 800)
        }
    }
    private val scanHandler = Handler(Looper.getMainLooper())
    private val debouncedScanRunnable = Runnable {
        // 媒体库变化是被动事件（系统或其他应用写文件均可触发），因此只做静默清理与重排：
        // 不新增条目、不弹任何 Toast，避免反复骚扰用户；新增条目仍由用户手动点“扫描”触发。
        if (hasStoragePermission()) scanLocalMedia(silent = true)
    }

    /** 触发扫描本地媒体（异步）。 */
    private fun scanLocalMedia(silent: Boolean = false) {
        ensureScanJob(silent)
    }

    /** 触发扫描并等待完成（用于 onStart 流程中需要等待结果时）。 */
    private suspend fun scanLocalMediaSuspend(silent: Boolean = false) {
        ensureScanJob(silent).join()
    }

    /** 保证同一时刻只有一个扫描任务在运行。 */
    private fun ensureScanJob(silent: Boolean): Job {
        scanJob?.takeIf { it.isActive }?.let { return it }
        return lifecycleScope.launch { scanLocalMediaInternal(silent) }.also { scanJob = it }
    }

    /** 在 IO 线程扫描视频/音频，回到主线程合并结果。 */
    private suspend fun scanLocalMediaInternal(silent: Boolean) {
        if (silent && !hasStoragePermission()) return
        val (videos, audios) = withContext(Dispatchers.IO) {
            scanner.queryVideos() to scanner.queryAudios()
        }
        withContext(Dispatchers.Main) {
            reconcileScanResult(videos, audios, silent)
        }
    }

    /**
     * 合并扫描结果：删除已不存在的媒体，（非静默时）追加时长合格的新媒体，
     * 并按「文件夹分组 + 组内集数自然序」整理整个播放列表。
     * @param silent 静默模式下只做删除清理与整理，不新增、不提示
     */
    private fun reconcileScanResult(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?,
        silent: Boolean
    ) {
        // 两次查询都失败：无法判断，静默时直接返回
        if (scannedVideos == null && scannedAudios == null) {
            if (!silent) {
                Toast.makeText(this, R.string.toast_scan_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 先删除已消失的项：此时队列与列表仍同序，按下标删除才安全
        val removedCount = pruneDeletedMedia(scannedVideos, scannedAudios)
        val newItems = if (silent) emptyList() else {
            // 合并扫描结果，过滤掉时长过短(<5s)与已存在的项
            val scanned = mutableListOf<MediaItemData>()
            scannedVideos?.let(scanned::addAll)
            scannedAudios?.let(scanned::addAll)
            val existingKeys = existingUriKeys()
            scanned.filter {
                it.duration >= 5000 && normalizeUri(it.uri) !in existingKeys
            }
        }

        // 新项先追加再统一重排，避免出现「已插入但队列未同步」的下标错位窗口
        val before = playlist.toList()
        playlist.addAll(newItems)
        val appended = playlist.toList()
        val ordered = sortPlaylist(backfillFolderKeys(appended, buildFolderMap(scannedVideos, scannedAudios)))
        val orderReorganized = ordered != appended

        // 无变化则不处理
        if (ordered == before && removedCount == 0) {
            if (!silent) {
                Toast.makeText(this, R.string.toast_no_new_media, Toast.LENGTH_SHORT).show()
            }
            return
        }
        playlist.clear()
        playlist.addAll(ordered)
        refreshPlaylist()
        savePlaylist()
        // 与上面的重排处于同一主线程执行块（中间无挂起点），
        // 保证队列下标与列表下标不会出现短暂不一致
        controller?.let { rebuildQueueIfNeeded(it) }
        if (!silent) {
            // 组装“删除 x 项 / 新增 y 项 / 已按文件夹整理”的提示
            val parts = mutableListOf<String>()
            if (removedCount > 0) parts.add(getString(R.string.toast_scan_removed, removedCount))
            if (newItems.isNotEmpty()) parts.add(getString(R.string.toast_scan_added, newItems.size))
            if (orderReorganized) parts.add(getString(R.string.toast_scan_reordered))
            Toast.makeText(
                this,
                parts.reduce { acc, part -> getString(R.string.toast_scan_join, acc, part) },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 用本次扫描结果构造「规范化 uri 键 -> 文件夹分组键」映射，跳过无分组信息的项。 */
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

    /** 删除播放列表中已不存在的媒体项，返回删除数量。 */
    private fun pruneDeletedMedia(
        scannedVideos: List<MediaItemData>?,
        scannedAudios: List<MediaItemData>?
    ): Int {
        // null 表示查询失败或权限不足；成功返回空列表则表示该类型当前没有可见媒体。
        // 缺失记录需要重复扫描并经过宽限期，避免一次空结果就误删。
        val videoKeys = if (scannedVideos != null &&
            scanner.hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_VIDEO)
        ) {
            scannedVideos.map { normalizeUri(it.uri) }.toSet()
        } else null
        val audioKeys = if (scannedAudios != null &&
            scanner.hasFullMediaAccess(android.Manifest.permission.READ_MEDIA_AUDIO)
        ) {
            scannedAudios.map { normalizeUri(it.uri) }.toSet()
        } else null
        val videoPrefix = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()
        val audioPrefix = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString()
        var removed = 0

        // 本次扫描中确认缺失的项（原始 uri）。删除判定改为读取持久化的缺失记录：
        // 只有“此前至少缺失过一次”且“距首次缺失超过宽限期”才真正删除，
        // 从而抵御存储卷暂时不可用/媒体库重建导致的瞬时漏扫，且跨启动有效。
        val missingNow = mutableSetOf<String>()
        val observedNow = mutableSetOf<String>()
        val removedNow = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        // 倒序遍历，便于安全删除
        for (i in playlist.indices.reversed()) {
            val item = playlist[i]
            val uriStr = item.uri.toString()
            // 根据 uri 前缀判断属于视频还是音频，只对可判定的类型做清理
            val keys = when {
                uriStr.startsWith(videoPrefix) -> videoKeys
                uriStr.startsWith(audioPrefix) -> audioKeys
                else -> null
            }
            if (keys == null) continue
            observedNow.add(uriStr)
            val key = normalizeUri(item.uri)
            if (key in keys) continue
            // 本次扫描确认缺失：记入缺失集合
            missingNow.add(uriStr)
            val prev = PlayerRepository.getMissingInfo(uriStr)
            val missingBefore = prev != null && prev.missCount >= 1
            val oldEnough = prev != null &&
                now - prev.firstMissingMs >= PlayerRepository.MISSING_DELETE_GRACE_MS
            if (missingBefore && oldEnough) {
                removeItemAt(i)
                removedNow.add(uriStr)
                removed++
            }
        }
        // 本轮确认缺失的累加次数；重新发现或已从播放列表移除的清除；未检查的类型保留原记录。
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
        // 监听仓库写盘失败并提示（带节流，避免连续失败时刷屏）
        lifecycleScope.launch {
            var lastShownMs = 0L
            PlayerRepository.writeFailures.collect {
                val now = SystemClock.elapsedRealtime()
                if (now - lastShownMs < WRITE_FAILURE_TOAST_MIN_INTERVAL_MS) return@collect
                lastShownMs = now
                Toast.makeText(
                    this@MainActivity,
                    R.string.toast_repository_write_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        updateManager = UpdateManager(this)
        gestures = GestureController(this, binding.playerView, binding.gestureOverlay) { controller }
        fullscreenPip = FullscreenPipHelper(this, binding) { controller }

        // 列表适配器：点击续播、长按/按钮删除、读取进度
        adapter = MediaListAdapter(
            onClick = { uri ->
                val index = playlist.indexOfFirst { it.uri.toString() == uri }
                if (index >= 0) {

                    // 用户主动切歌：重置连续错误计数
                    consecutiveErrorCount = 0

                    // 切歌前先把当前播放位置落盘
                    PlayerService.flushCurrentPosition()

                    seekToResume(index)
                    controller?.play()
                }
            },
            onDelete = { uri ->
                val item = playlist.firstOrNull { it.uri.toString() == uri }
                    ?: return@MediaListAdapter
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_dialog_title)
                    .setMessage(getString(R.string.delete_dialog_message, item.name))
                    .setPositiveButton(R.string.delete) { _, _ ->

                        playlist.indexOfFirst { it.uri.toString() == uri }
                            .takeIf { it >= 0 }?.let { removeItemFromPlaylist(it) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },

            getProgress = { uri -> PlayerRepository.getProgress(uri) ?: 0L }
        )
        binding.recyclerPlaylist.adapter = adapter
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(this)

        // 扫描按钮：有权限直接扫描，否则先申请权限
        binding.btnScan.setOnClickListener {
            if (hasStoragePermission()) {
                scanLocalMedia()
            } else {
                requestStoragePermission()
            }
        }
        binding.btnPip.setOnClickListener { fullscreenPip.enterPipMode() }
        // “更多”菜单：检查更新
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
        // 返回键：全屏时先退出全屏，否则关闭界面
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

        // Android 13+ 申请通知权限（用于媒体通知）
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

    }

    /** 校验回调是否仍属于最新一次控制器连接（防止过期回调）。 */
    private fun isCurrentController(
        future: com.google.common.util.concurrent.ListenableFuture<MediaController>,
        ctrl: MediaController
    ): Boolean = controllerFuture === future && controller === ctrl

    override fun onStart() {
        super.onStart()
        updateManager.registerReceivers()
        updateManager.resumePendingInstall()
        // 监听媒体库变化
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaChangeObserver
        )
        // 建立与 PlayerService 的 MediaController 连接
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
            lifecycleScope.launch {

                // 等待仓库（数据库）加载完成
                val loaded = PlayerRepository.awaitLoaded(this@MainActivity)

                if (!isCurrentController(future, ctrl)) return@launch
                when {

                    // 加载失败：提示用户
                    !loaded -> Toast.makeText(
                        this@MainActivity,
                        R.string.toast_repository_load_failed,
                        Toast.LENGTH_LONG
                    ).show()

                    // 首次进入：从仓库加载播放列表
                    !playlistLoaded -> {
                        loadPlaylist()
                        playlistLoaded = true
                    }

                    // 非首次：仅刷新进度显示
                    else -> adapter.refreshAllProgress()
                }

                if (!isCurrentController(future, ctrl)) return@launch
                // 静默扫描，清理已删除的媒体
                scanLocalMediaSuspend(silent = true)

                if (!isCurrentController(future, ctrl)) return@launch

                // 若播放器队列与列表不一致（例如刚刚按文件夹重排过），则重建队列
                rebuildQueueIfNeeded(ctrl)
                restoreLastPlayed()
                refreshPlaylist()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        updateManager.unregisterReceivers()
        contentResolver.unregisterContentObserver(mediaChangeObserver)
        scanHandler.removeCallbacks(debouncedScanRunnable)
        // 停止后不再需要后台扫描：取消进行中的扫描任务，避免其在停止后弹 Toast
        scanJob?.cancel()
        scanJob = null
        fullscreenPip.onStopped()
        gestures.cancelPendingHide()
        // 断开控制器连接并释放资源
        binding.playerView.player = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()

        // 界面进入后台：先落盘当前播放位置
        PlayerService.flushCurrentPosition()
    }

    override fun onResume() {
        super.onResume()
        updateManager.resumePendingUpdateDialog()
    }

    /** 用户离开界面（如按 Home）时：正在播放视频则自动进入画中画。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        fullscreenPip.enterPipWhenPlayingVideo()
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

    /** 是否拥有读取媒体的权限（含部分授权）。 */
    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return hasMediaReadAccess(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                includePartial = true
            )
        }
        return hasMediaReadAccess(this, android.Manifest.permission.READ_MEDIA_VIDEO, includePartial = true)
            || hasMediaReadAccess(this, android.Manifest.permission.READ_MEDIA_AUDIO, includePartial = true)
    }

    /** 按系统版本申请对应的媒体读取权限。 */
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
