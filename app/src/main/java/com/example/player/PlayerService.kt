/**
 * 后台播放前台服务。
 *
 * 基于 Media3 的 [MediaSessionService] 托管一个 ExoPlayer：
 * 1. 提供媒体会话（通知栏与外部控制器通过它操控播放）；
 * 2. 负责播放进度的采集与持久化（每 5 秒一次快照，暂停 / seek / 内存吃紧 / 销毁时立即落盘）；
 * 3. 播放出错时自动跳到下一个可播放项，并对外广播 [com.example.player.PlayerService.Companion.PlaybackErrorEvent]。
 *
 * 注意：倍速等播放参数由本服务的播放器持有，界面重建后需从会话重新读取以保持同步。
 */
package com.example.player

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var destroyed = false

    /** 连续播放错误次数：用于判断是否整份列表都播放失败。 */
    private var consecutiveErrorCount = 0

    /** 待落盘的进度缓存（uri → 毫秒），尚未写入数据库。 */
    private val progressCache = mutableMapOf<String, Long>()

    /** 待落盘的删除集合（进度需从数据库移除的 uri）。 */
    private val removedUris = mutableSetOf<String>()

    /** 上一次成功落盘时的缓存快照，用于跳过无变化的写入。 */
    private var lastPersistedSnapshot: Map<String, Long> = emptyMap()

    /** 当前正在播放的 uri，用于自动切歌时清除上一首的进度。 */
    private var lastPlayedUri: String? = null

    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 自动连播到下一首：上一首视为自然播完，清除其断点

                val prevUri = lastPlayedUri
                if (prevUri != null) {
                    progressCache.remove(prevUri)
                    removedUris.add(prevUri)
                }

            }
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                // 暂停：立即落盘，同时停止周期性 ticker
                persistProgress()
            } else {
                consecutiveErrorCount = 0
                scheduleProgressTicker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val player = mediaSession?.player ?: return
            // 顺序播放到末尾后主动暂停，避免停在 ENDED 状态
            if (player.repeatMode == Player.REPEAT_MODE_OFF) player.pause()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) return
            // 跨条目 seek 时先记录旧位置，再统一落盘
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                cacheOldPosition(oldPosition)
            }
            persistProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "播放出错: code=${error.errorCode}", error)
            val player = mediaSession?.player ?: return
            val errorUri = player.currentMediaItem?.localConfiguration?.uri?.toString()

            consecutiveErrorCount++
            // 错误次数不小于队列长度，说明整份列表都不够跳过，视为全部失败
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

    /** 周期落盘任务：播放中或仍有待删除项时，每次执行后重新排期。 */
    private val progressTicker = Runnable {
        tickerScheduled = false
        if (mediaSession?.player?.isPlaying == true || removedUris.isNotEmpty()) {
            persistProgress()
            scheduleProgressTicker()
        }
    }

    /** 排期下一次周期落盘（幂等，已排期时不重复）。 */
    private fun scheduleProgressTicker() {
        if (destroyed || mediaSession == null) return
        if (tickerScheduled) return
        tickerScheduled = true
        mainHandler.postDelayed(progressTicker, PROGRESS_TICKER_INTERVAL_MS)
    }

    /** 取消已排期的周期落盘。 */
    private fun cancelProgressTicker() {
        if (!tickerScheduled) return
        tickerScheduled = false
        mainHandler.removeCallbacks(progressTicker)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            // 拔掉耳机（音频路由变为 noisy）时自动暂停
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        player.addListener(playerEventListener)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        val player = mediaSession?.player
        // 从最近任务划掉且未在播放时结束服务
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /** 按裁决结果把变更写入进度缓存 / 删除集合。 */
    private fun applyToProgressCache(decision: ProgressWriteDecision, uri: String) {
        when (decision) {
            ProgressWriteDecision.Clear -> {
                progressCache.remove(uri)
                removedUris.add(uri)
            }
            is ProgressWriteDecision.Store -> progressCache[uri] = decision.positionMs
            ProgressWriteDecision.Skip -> Unit
        }
    }

    /** 缓存当前播放位置。 */
    private fun cacheCurrentPosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        applyToProgressCache(decideProgressWrite(player.currentPosition, player.duration), uri)
    }

    /** 缓存某个旧条目的位置（已切走后仍能补记其进度）。 */
    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = mediaSession?.player ?: return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        val uri = window.mediaItem.localConfiguration?.uri?.toString() ?: return

        applyToProgressCache(decideProgressWrite(oldPosition.positionMs, window.durationMs), uri)
    }

    /** 收集当前变更并落盘（无变化则直接返回，避免零空闲 IO）。 */
    private fun persistProgress() {
        val player = mediaSession?.player ?: return
        cacheCurrentPosition(player)
        val removals = removedUris.toSet()
        val writes = progressCache.filter { (uri, pos) -> lastPersistedSnapshot[uri] != pos }
        if (writes.isEmpty() && removals.isEmpty()) return
        writeToDisk(writes, removals)
    }

    /** 提交一次数据库写入；成功更新快照，失败则把删除项放回并重新排期重试。 */
    private fun writeToDisk(writes: Map<String, Long>, removals: Set<String>) {
        val snapshot = progressCache.toMap()
        PlayerRepository.applyProgressUpdates(writes, removals) { persisted ->
            mainHandler.post {
                if (persisted) {
                    removedUris.removeAll(removals)
                    lastPersistedSnapshot = snapshot
                } else {
                    removedUris.addAll(removals)
                    lastPersistedSnapshot = emptyMap()
                    if (!destroyed) scheduleProgressTicker()
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存吃紧：尽快落盘
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

        /** 周期落盘间隔：5 秒。 */
        private const val PROGRESS_TICKER_INTERVAL_MS = 5000L

        @Volatile
        private var instance: PlayerService? = null

        /**
         * 播放错误事件。
         * @property uri 出错的媒体 uri
         * @property exhausted 是否整份列表都失败（已停止自动跳过）
         * @property hasNext 出错后是否已自动跳到下一项
         * @property errorCode Media3 的错误码
         */
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

        /** 用户主动点播时重置错误计数，恢复自动跳过能力。 */
        fun resetErrorCount() {
            instance?.consecutiveErrorCount = 0
        }

        /** 批量删除进度：仓库一次写入，服务缓存一并清理（扫描清理已消失文件时用）。 */
        fun dropProgress(uris: Collection<String>) {
            if (uris.isEmpty()) return
            val keys = uris.toSet()
            PlayerRepository.applyProgressUpdates(emptyMap(), keys)
            val svc = instance ?: return
            for (key in keys) svc.progressCache.remove(key)
            svc.removedUris.addAll(keys)
            svc.scheduleProgressTicker()
        }

        /** 删除单条进度。 */
        fun dropProgress(uri: String) = dropProgress(listOf(uri))

        /** 立即把当前播放位置落盘（暂停 / 切后台 / 点播前调用）。 */
        fun flushCurrentPosition() {
            instance?.persistProgress()
        }
    }
}
