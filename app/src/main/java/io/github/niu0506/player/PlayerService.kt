/** 后台播放服务：托管 ExoPlayer 与 MediaSession。 */
package io.github.niu0506.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

internal val MediaItem?.uriString: String?
    get() = this?.localConfiguration?.uri?.toString()

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val player: Player?
        get() = mediaSession?.player

    private val mainHandler = Handler(Looper.getMainLooper())

    private var destroyed = false

    private var consecutiveErrorCount = 0

    private val progress = ProgressCache()

    /** 上一媒体项 uri，用于恢复断点与选中上次播放的条目。 */
    private var lastPlayedUri: String? = null

    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previousUri = lastPlayedUri
            // 上一项的完成标记由 onPositionDiscontinuity 按旧条目的位置与时长补记：
            // 换项回调里播放器已经是新条目，取不到旧条目的时长。
            lastPlayedUri = mediaItem.uriString
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            // 在服务侧续播：界面退后台会释放控制器
            if (shouldRestoreResumeOnTransition(reason, previousUri, mediaItem.uriString)) {
                restoreResumePosition(mediaItem, reason)
            }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                // 暂停立即落盘，ticker 不再续排
                persistProgress()
            } else {
                consecutiveErrorCount = 0
                scheduleProgressTicker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val player = this@PlayerService.player ?: return
            // 顺序播放到末尾后主动暂停
            if (player.repeatMode == Player.REPEAT_MODE_OFF) player.pause()
            // 播完本身就是完成标记，必须在这里显式落盘：onIsPlayingChanged(false) 只在
            // isPlaying 真的发生变化时回调（被音频焦点抑制、从缓冲直接到 ENDED 时不会），
            // 而 pause() 之后 playWhenReady 变 false，5 秒轮询也不再续期。
            persistProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            if (isExplicitSeekToZero(
                    reason,
                    sameItem,
                    oldPosition.positionMs,
                    newPosition.positionMs
                )
            ) {
                this@PlayerService.player
                    ?.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
                    ?.let(progress::clear)
            }
            // 跨条目跳变（seek / 自动连播）：先按旧条目的位置与时长补记。
            // 自动连播时这正是上一项的精确末尾（旧位置已切到新条目），据此写入完成标记。
            if (!sameItem && shouldCacheOldItemOnDiscontinuity(reason)) {
                cacheOldPosition(oldPosition)
            }
            // 同条目非 seek 的位置变化不产生进度
            if (reason != Player.DISCONTINUITY_REASON_SEEK && sameItem) return
            persistProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "播放出错: code=${error.errorCode}", error)
            val player = this@PlayerService.player ?: return
            val errorUri = player.currentMediaItem.uriString
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
                errorUri != null
            ) {
                // 后台也记录缺失，供扫描宽限期判定
                PlayerRepository.recordMissing(errorUri)
            }

            consecutiveErrorCount++
            // 错误次数达到队列长度，视为整份列表都失败
            val exhausted = player.mediaItemCount in 1..consecutiveErrorCount
            val hasNext = !exhausted && player.hasNextMediaItem()

            if (hasNext) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                player.pause()
            }

            errorEventsInternal.tryEmit(
                PlaybackErrorEvent(errorUri, exhausted, hasNext, error.errorCode)
            )
        }
    }

    private var tickerScheduled = false

    private val progressTicker = Runnable {
        tickerScheduled = false
        if (resetting) return@Runnable
        // 只要用户还想播、或还有没落盘的进度就继续轮询。缓冲、音频焦点暂失、小窗/息屏
        // 切换都会让 isPlaying 短暂为 false，用它当条件会让轮询就此停摆，后台播放的进度
        // 再也落不了盘；保留待落盘项也让暂停/播完后的失败写库能继续重试。
        if (player?.playWhenReady == true || progress.hasPendingChanges()) {
            persistProgress()
            scheduleProgressTicker()
        }
    }

    private fun scheduleProgressTicker() {
        if (destroyed || resetting || mediaSession == null) return
        if (tickerScheduled) return
        tickerScheduled = true
        mainHandler.postDelayed(progressTicker, PROGRESS_TICKER_INTERVAL_MS)
    }

    private fun cancelProgressTicker() {
        if (!tickerScheduled) return
        tickerScheduled = false
        mainHandler.removeCallbacks(progressTicker)
    }

    private fun clearProgressForReset() {
        cancelProgressTicker()
        progress.clearAll()
        lastPlayedUri = null
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            // 本地播放期间保持 CPU 唤醒（无需 Wi-Fi 锁）
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 拔出耳机时自动暂停
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        player.addListener(playerEventListener)
        mediaSession = buildMediaSession(player)
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaSession(player: Player): MediaSession =
        MediaSession.Builder(this, player)
            // 点击通知 / 锁屏控件时回到界面
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.ConnectionResult> {
                    if (controller.isTrusted) {
                        return super.onConnectAsync(session, controller)
                    }
                    Log.w(TAG, "拒绝不可信媒体控制器: ${controller.packageName}")
                    return Futures.immediateFuture(MediaSession.ConnectionResult.reject())
                }
            })
            .build()

    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        // 无播放意图时结束服务
        val player = this.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    private fun cacheCurrentPosition(player: Player) {
        cachePosition(
            player.currentMediaItem.uriString,
            player.currentPosition,
            player.duration
        )
    }

    /**
     * 缓存一条进度。
     *
     * [durationMs] 是播放器解析出的时长；完成标记则按列表（扫描）时长口径写入，
     * 见 [completionMarkerDurationMs]。
     */
    private fun cachePosition(uri: String?, positionMs: Long, durationMs: Long) {
        val markerDurationMs = completionMarkerDurationMs(uri, positionMs, durationMs)
        progress.cache(uri, positionMs, durationMs, markerDurationMs)
    }

    /**
     * 完成标记写入的时长。
     *
     * 播放器解析时长与列表（MediaStore / 扫描）时长可能相差数秒，而读侧一律用列表时长
     * 判断「已播完」（列表进度、续播判定）。只要任一来源判定播完，标记就按列表时长写入：
     * 否则后台播完的条目会因为两个时长口径不一致被当成未播完，还留出一个续播点。
     */
    private fun completionMarkerDurationMs(
        uri: String?,
        positionMs: Long,
        playerDurationMs: Long,
    ): Long {
        val playlistMs = playlistDurationMs(uri)
        if (playlistMs <= 0) return playerDurationMs
        val finished = isProgressFinished(positionMs, playerDurationMs) ||
            isProgressFinished(positionMs, playlistMs)
        return if (finished) playlistMs else playerDurationMs
    }

    /** 列表（扫描）时长；没有记录时返回 0。 */
    private fun playlistDurationMs(uri: String?): Long = uri
        ?.let { key ->
            PlayerRepository.getPlaylist().firstOrNull { it.uri.toString() == key }?.duration
        }
        ?.takeIf { it > 0 }
        ?: 0L

    private fun restoreResumePosition(mediaItem: MediaItem?, reason: Int) {
        val player = this.player ?: return
        if (!shouldRestoreResume(reason, player.currentPosition, RESUME_START_TOLERANCE_MS)) return
        val uri = mediaItem.uriString ?: return
        // 新条目尚未 READY，优先用扫描到的时长判断已看完
        val duration = playlistDurationMs(uri).takeIf { it > 0 }
            ?: player.duration.takeIf { it > 0 }
            ?: 0L
        val position = resumablePositionMs(PlayerRepository.getProgress(uri), duration) ?: return
        player.seekTo(player.currentMediaItemIndex, position)
    }

    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = this.player ?: return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        cachePosition(
            window.mediaItem.uriString,
            oldPosition.positionMs,
            window.durationMs
        )
    }

    private fun persistProgress() {
        // 重置期间不写库
        if (resetting) return
        val player = this.player ?: return
        // URI 离开队列后解除抑制，避免集合无限增长
        if (progress.hasPendingDrops()) {
            val present = (0 until player.mediaItemCount)
                .mapNotNull { player.getMediaItemAt(it).uriString }
                .toSet()
            progress.retainPending(present)
        }
        cacheCurrentPosition(player)
        val removals = progress.pendingRemovals()
        val writes = progress.writes()
        if (writes.isEmpty() && removals.isEmpty()) return
        writeToDisk(writes, removals)
    }

    private fun writeToDisk(writes: Map<String, Long>, removals: Set<String>) {
        val snapshot = progress.snapshot()
        PlayerRepository.applyProgressUpdates(writes, removals) { persisted ->
            mainHandler.post {
                // 重置已开始，不再更新
                if (resetting) return@post
                if (persisted) {
                    progress.markPersisted(snapshot, removals)
                } else {
                    progress.markFailed()
                    if (!destroyed) scheduleProgressTicker()
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        persistProgress()
    }

    override fun onDestroy() {
        destroyed = true
        cancelProgressTicker()
        persistProgress()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        instance = null

        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlayerService"

        private const val PROGRESS_TICKER_INTERVAL_MS = 5000L

        private const val RESUME_START_TOLERANCE_MS = 1_000L

        @Volatile
        private var instance: PlayerService? = null

        /** 重置进行中：禁止进度写库。 */
        @Volatile
        private var resetting = false

        data class PlaybackErrorEvent(
            val uri: String?,
            val exhausted: Boolean,
            val hasNext: Boolean,
            val errorCode: Int,
        )

        private val errorEventsInternal = MutableSharedFlow<PlaybackErrorEvent>(
            extraBufferCapacity = 16
        )
        val errorEvents: SharedFlow<PlaybackErrorEvent> = errorEventsInternal

        fun resetErrorCount() {
            instance?.consecutiveErrorCount = 0
        }

        fun dropProgress(uris: Collection<String>) {
            if (uris.isEmpty()) return
            val keys = uris.toSet()
            PlayerRepository.applyProgressUpdates(emptyMap(), keys)
            val svc = instance ?: return
            // 只有当前项会被 ticker 重新缓存
            val currentUri = svc.player?.currentMediaItem?.uriString
            // 当前项进入 pendingDrops，离开队列后才允许重新缓存
            svc.progress.drop(keys, currentUri)
        }

        fun dropProgress(uri: String) = dropProgress(listOf(uri))

        fun clearAllPendingDrops() {
            instance?.progress?.clearAllPendingDrops()
        }

        fun flushCurrentPosition() {
            instance?.persistProgress()
        }

        fun beginReset() {
            resetting = true
            instance?.clearProgressForReset()
        }

        fun endReset() {
            instance?.clearProgressForReset()
            resetting = false
        }
    }
}

/** 待落盘进度的内存状态（纯逻辑，便于测试）。 */
internal class ProgressCache {

    private val cached = mutableMapOf<String, Long>()

    private val removals = mutableSetOf<String>()

    /** 已删除但仍在队列中的 uri，不得重新缓存。 */
    private val pendingDrops = mutableSetOf<String>()

    private var lastPersisted: Map<String, Long> = emptyMap()

    /**
     * 缓存一条进度。
     *
     * [durationMs] 是播放器解析出的时长，[markerDurationMs] 是已播完时写入的完成标记
     * 时长（按列表时长口径，见 [decideProgressWrite]），默认与 [durationMs] 相同。
     */
    fun cache(
        uri: String?,
        positionMs: Long,
        durationMs: Long,
        markerDurationMs: Long = durationMs,
    ) {
        if (uri == null || uri in pendingDrops) return
        when (val decision = decideProgressWrite(positionMs, durationMs, markerDurationMs)) {
            is ProgressWriteDecision.Store -> cached[uri] = decision.positionMs
            ProgressWriteDecision.Skip -> Unit
        }
    }

    fun clear(uri: String?) {
        if (uri == null) return
        cached.remove(uri)
        // 先从快照移除，避免落盘回调把进度复活
        lastPersisted = lastPersisted - uri
        removals.add(uri)
    }

    fun drop(uris: Collection<String>, currentUri: String?) {
        for (key in uris) {
            cached.remove(key)
            lastPersisted = lastPersisted - key
            if (key == currentUri) pendingDrops.add(key)
        }
    }

    fun retainPending(present: Set<String>) {
        pendingDrops.retainAll(present)
    }

    fun hasPendingDrops(): Boolean = pendingDrops.isNotEmpty()

    fun hasPendingRemovals(): Boolean = removals.isNotEmpty()

    /** 是否还有未落盘的进度（待删除项或值有变化的写入）。 */
    fun hasPendingChanges(): Boolean = removals.isNotEmpty() || writes().isNotEmpty()

    fun pendingRemovals(): Set<String> = removals.toSet()

    fun writes(): Map<String, Long> =
        cached.filter { (uri, position) -> lastPersisted[uri] != position }

    fun snapshot(): Map<String, Long> = cached.toMap()

    fun markPersisted(snapshot: Map<String, Long>, writtenRemovals: Set<String>) {
        removals.removeAll(writtenRemovals)
        // 不借旧快照复活写入期间被 clear/drop 的 key。
        lastPersisted = snapshot.filterKeys { it in cached }
    }

    /** 落盘失败：保留删除项并清空快照，下轮全量重写。 */
    fun markFailed() {
        lastPersisted = emptyMap()
    }

    fun clearAllPendingDrops() {
        pendingDrops.clear()
    }

    fun clearAll() {
        cached.clear()
        removals.clear()
        pendingDrops.clear()
        lastPersisted = emptyMap()
    }
}

/** 是否显式 seek 回 0（仅此时清除断点）。 */
internal fun isExplicitSeekToZero(
    reason: Int,
    sameItem: Boolean,
    oldPositionMs: Long,
    newPositionMs: Long,
): Boolean = reason == Player.DISCONTINUITY_REASON_SEEK &&
    sameItem &&
    oldPositionMs > 0L &&
    newPositionMs == 0L

/**
 * 跨条目位置跳变时是否要按旧条目补记进度。
 *
 * 用户 seek 与自动连播都会换到新条目：换项那一刻播放器已经是新条目，旧条目的位置与
 * 时长只剩 [Player.PositionInfo] 里可靠（自动连播时旧位置正是上一项的精确末尾），
 * 据此写入上一项的完成标记。
 */
internal fun shouldCacheOldItemOnDiscontinuity(reason: Int): Boolean =
    reason == Player.DISCONTINUITY_REASON_SEEK ||
        reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION

internal fun shouldRestoreResume(
    reason: Int,
    currentPositionMs: Long,
    toleranceMs: Long,
): Boolean = reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
    currentPositionMs <= toleranceMs

/**
 * 换项后是否恢复断点。
 *
 * 播放列表变化也会产生换项：当前项被删除/替换时新条目需要恢复断点；
 * 启动建队等场景 [previousUri] 为空或与 [newUri] 相同，保持原位不动。
 */
internal fun shouldRestoreResumeOnTransition(
    reason: Int,
    previousUri: String?,
    newUri: String?,
): Boolean = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> true

    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ->
        previousUri != null && newUri != null && previousUri != newUri

    else -> false
}
