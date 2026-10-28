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

    /** 上一媒体项 uri，用于标记已看完。 */
    private var lastPlayedUri: String? = null

    private var lastPlayedDurationMs = C.TIME_UNSET

    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 自动连播视为自然播完
                val finishedUri = lastPlayedUri
                val finishedDuration = lastPlayedDurationMs
                if (finishedUri != null && finishedDuration > 0) {
                    progress.cache(finishedUri, finishedDuration, finishedDuration)
                }
            }
            lastPlayedUri = mediaItem.uriString
            lastPlayedDurationMs = this@PlayerService.player
                ?.duration
                ?.takeIf { it > 0 }
                ?: C.TIME_UNSET
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            // 在服务侧续播：界面退后台会释放控制器
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
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
                // 拖回开头：清除旧断点
                this@PlayerService.player
                    ?.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
                    ?.let(progress::clear)
            }
            // 跨条目 seek：先补记旧条目
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !sameItem) {
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
        if (player?.isPlaying == true || progress.hasPendingRemovals()) {
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
        lastPlayedDurationMs = C.TIME_UNSET
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
        val duration = player.duration
        if (duration > 0) lastPlayedDurationMs = duration
        progress.cache(
            player.currentMediaItem.uriString,
            player.currentPosition,
            duration
        )
    }

    private fun restoreResumePosition(mediaItem: MediaItem?, reason: Int) {
        val player = this.player ?: return
        if (!shouldRestoreResume(reason, player.currentPosition, RESUME_START_TOLERANCE_MS)) return
        val uri = mediaItem.uriString ?: return
        // 新条目尚未 READY，优先用扫描到的时长判断已看完
        val playlistDuration = PlayerRepository.getPlaylist()
            .firstOrNull { it.uri.toString() == uri }
            ?.duration
            ?: 0L
        val duration = playlistDuration.takeIf { it > 0 }
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
        progress.cache(
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
            val currentUri = svc.player
                ?.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.toString()
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

    fun cache(uri: String?, positionMs: Long, durationMs: Long) {
        if (uri == null || uri in pendingDrops) return
        when (val decision = decideProgressWrite(positionMs, durationMs)) {
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

    /** 解除已离开队列的抑制项。 */
    fun retainPending(present: Set<String>) {
        pendingDrops.retainAll(present)
    }

    fun hasPendingDrops(): Boolean = pendingDrops.isNotEmpty()

    fun hasPendingRemovals(): Boolean = removals.isNotEmpty()

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

internal fun shouldRestoreResume(
    reason: Int,
    currentPositionMs: Long,
    toleranceMs: Long,
): Boolean = reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
    currentPositionMs <= toleranceMs
