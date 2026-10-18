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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 会话托管的播放器；未创建或已销毁时为 null。 */
    private val player: Player?
        get() = mediaSession?.player

    private val mainHandler = Handler(Looper.getMainLooper())

    private var destroyed = false

    /** 连续播放错误次数：用于判断是否整份列表都播放失败。 */
    private var consecutiveErrorCount = 0

    /** 待落盘的进度缓存（uri → 毫秒），尚未写入数据库。 */
    private val progressCache = mutableMapOf<String, Long>()

    /** 待落盘的删除集合（进度需从数据库移除的 uri）。 */
    private val removedUris = mutableSetOf<String>()

    /**
     * 刚被要求删除进度、但播放器时间线里可能还存在的 uri。
     *
     * 删除条目时 dropProgress 先于播放器真正移除执行；在队列重建/跨进程命令完成前，
     * ticker 仍可能把当前项的进度缓存回来。这里在 URI 真正离开时间线前拒绝重新缓存。
     */
    private val pendingDropUris = mutableSetOf<String>()

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
            val player = this@PlayerService.player ?: return
            // 顺序播放到末尾后主动暂停，避免停在 ENDED 状态
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
                // 用户把当前条目拖回开头：显式删除旧断点。
                // 普通位置 0 仍走 decideProgressWrite 的 Skip，避免初始位置/队列重建误清。
                this@PlayerService.player
                    ?.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
                    ?.let { uri ->
                        progressCache.remove(uri)
                        removedUris.add(uri)
                    }
            }
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !sameItem) {
                // 跨条目 seek：先补记旧条目的位置，再统一落盘
                cacheOldPosition(oldPosition)
            } else if (reason != Player.DISCONTINUITY_REASON_SEEK && sameItem) {
                return
            }
            persistProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "播放出错: code=${error.errorCode}", error)
            val player = this@PlayerService.player ?: return
            val errorUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
                errorUri != null
            ) {
                // 在这里登记而不是在界面收集器里：界面退到后台后仍要记录缺失，
                // 供扫描宽限期判定使用。
                PlayerRepository.recordMissing(errorUri)
            }

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
        if (player?.isPlaying == true || removedUris.isNotEmpty()) {
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
        mediaSession = buildMediaSession(player)
    }

    /**
     * 构建媒体会话：服务对系统导出，但只允许可信控制器连接。
     *
     * Media3 默认对不可信控制器也给只读命令，任何第三方应用都能读取当前媒体项/元数据；
     * 这里直接用 [MediaSession.ControllerInfo.isTrusted] 拒绝不可信控制器。
     * 本应用、system UID、MEDIA_CONTENT_CONTROL / STATUS_BAR_SERVICE、通知监听器仍可控制，
     * 因此不会影响通知栏、蓝牙、Android Auto 等系统入口。
     */
    @OptIn(UnstableApi::class)
    private fun buildMediaSession(player: Player): MediaSession =
        MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.ConnectionResult> {
                    if (controller.isTrusted()) {
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
        // 从最近任务划掉且未在播放时结束服务
        val player = this.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /** 按裁决结果缓存某个条目的播放位置（当前项与已切走的旧项共用）。 */
    private fun cachePosition(uri: String?, positionMs: Long, durationMs: Long) {
        if (uri == null || uri in pendingDropUris) return
        when (val decision = decideProgressWrite(positionMs, durationMs)) {
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
        cachePosition(
            player.currentMediaItem?.localConfiguration?.uri?.toString(),
            player.currentPosition,
            player.duration
        )
    }

    /** 缓存某个旧条目的位置（已切走后仍能补记其进度）。 */
    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = this.player ?: return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        cachePosition(
            window.mediaItem.localConfiguration?.uri?.toString(),
            oldPosition.positionMs,
            window.durationMs
        )
    }

    /** 收集当前变更并落盘（无变化则直接返回，避免零空闲 IO）。 */
    private fun persistProgress() {
        val player = this.player ?: return
        if (pendingDropUris.isNotEmpty()) {
            // URI 已真正离开播放队列后解除抑制，避免集合无限增长
            val present = (0 until player.mediaItemCount)
                .mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
                .toSet()
            pendingDropUris.retainAll(present)
        }
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

        /** 批量删除进度：仓库立即删一次，服务侧缓存与快照同步清理（扫描清理已消失文件时用）。 */
        fun dropProgress(uris: Collection<String>) {
            if (uris.isEmpty()) return
            val keys = uris.toSet()
            PlayerRepository.applyProgressUpdates(emptyMap(), keys)
            val svc = instance ?: return
            // 只有当前播放项会被 ticker 的 cacheCurrentPosition 重新缓存；
            // 非当前项删除后不需要长期抑制，避免它被重新加入列表后进度一直不写入。
            val currentUri = svc.player
                ?.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.toString()
            // 连快照一并移除：否则下一轮落盘会按旧快照把同一次删除再提交一遍
            for (key in keys) svc.progressCache.remove(key)
            svc.lastPersistedSnapshot -= keys
            // 播放器真正移除该条目前，ticker 不得把它的位置重新缓存回来
            for (key in keys) {
                if (key == currentUri) svc.pendingDropUris.add(key)
            }
        }

        /** 删除单条进度。 */
        fun dropProgress(uri: String) = dropProgress(listOf(uri))

        /** 播放队列已按列表重建后，解除对这些 URI 的进度抑制。 */
        fun clearPendingDrops(uris: Collection<String>) {
            instance?.pendingDropUris?.removeAll(uris.toSet())
        }

        /** 队列与列表已一致时清空全部抑制项（用于条目在控制器未连接时被删除的兜底）。 */
        fun clearAllPendingDrops() {
            instance?.pendingDropUris?.clear()
        }

        /** 立即把当前播放位置落盘（暂停 / 切后台 / 点播前调用）。 */
        fun flushCurrentPosition() {
            instance?.persistProgress()
        }
    }
}

/**
 * 是否是「当前条目从非零位置显式 seek 回 0」。
 *
 * 只有这种 seek 才代表用户想从头看并清除旧断点；普通的 positionMs == 0
 * （初始位置、seekToDefaultPosition、队列重建）仍按 [ProgressWriteDecision.Skip] 处理。
 */
internal fun isExplicitSeekToZero(
    reason: Int,
    sameItem: Boolean,
    oldPositionMs: Long,
    newPositionMs: Long,
): Boolean = reason == Player.DISCONTINUITY_REASON_SEEK &&
    sameItem &&
    oldPositionMs > 0L &&
    newPositionMs == 0L
