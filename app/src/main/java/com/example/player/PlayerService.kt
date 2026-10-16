package com.example.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

/**
 * 播放服务：基于 Media3 的 [MediaSessionService]。
 *
 * 职责：
 * 1. 创建 [ExoPlayer] 与 [MediaSession]，在后台持续播放，即使界面退出也不中断；
 * 2. 在切歌、暂停、进度跳变等关键节点，把播放进度写入 [PlayerRepository]
 *    （先更新内存镜像，再异步落盘到数据库）。
 */
class PlayerService : MediaSessionService() {

    /** 当前媒体会话，服务销毁后置空。 */
    private var mediaSession: MediaSession? = null

    /** 主线程 Handler，用于定时任务和把回调切回主线程执行。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 服务是否已销毁：销毁后不再重挂进度轮询、不再执行错误托底。 */
    private var destroyed = false

    /** 连续播放错误计数：整队失败时停止自动跳转，避免无限重试。 */
    private var consecutiveErrorCount = 0

    /** 播放进度内存缓存：uri -> 位置(毫秒)，作为落盘前的缓冲区。 */
    private val progressCache = mutableMapOf<String, Long>()

    /** 待从进度中删除的 uri 集合，落盘成功后清空。 */
    private val removedUris = mutableSetOf<String>()

    /** 上一次已成功落盘的进度快照，用于判断是否有变化、避免重复写盘。 */
    private var lastPersistedSnapshot: Map<String, Long> = emptyMap()

    /** 上一次播放的媒体 uri，用于自动切歌时清理上一项的进度。 */
    private var lastPlayedUri: String? = null

    /** 播放器事件监听：处理自动切歌、暂停、播放结束、进度跳变、播放错误等。 */
    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 自动切换到下一项时，把上一项的进度从缓存中移除（视为已播放完毕）
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {

                val prevUri = lastPlayedUri
                if (prevUri != null) {
                    progressCache.remove(prevUri)
                    removedUris.add(prevUri)
                }

            }
            // 记录当前播放项并记到“上次播放”，随后立即持久化进度
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 暂停（含缓冲暂停）时立即落盘，避免进度丢失；恢复播放时按需启动轮询
            if (!isPlaying) {
                persistProgress()
            } else {
                // 播放成功：重置连续错误计数
                consecutiveErrorCount = 0
                scheduleProgressTicker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 播放自然结束、且未开启循环播放时，主动暂停，避免自动重播
            val player = mediaSession?.player ?: return
            if (playbackState == Player.STATE_ENDED
                && player.playbackState == Player.STATE_ENDED
                && player.repeatMode == Player.REPEAT_MODE_OFF
            ) {
                player.pause()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // 若因 seek 导致跨媒体项跳转，先缓存旧位置，再持久化进度
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                cacheOldPosition(oldPosition)
            }
            persistProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 记录错误日志
            Log.w(TAG, "播放出错: code=${error.errorCode}", error)

            val player = mediaSession?.player ?: return
            val errorUri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return

            // 连续错误计数：整队失败时停止自动跳转，避免无限 seek
            consecutiveErrorCount++

            // Controller 与播放器监听回调可能以不同顺序到达；若前台已确认同一错误，
            // 不再安排后台托底。确认记录有时效，避免同 URI 的后续错误误消费旧标记。
            if (consumeForegroundErrorAck(errorUri)) return

            // 延迟 500ms 做后台托底：若前台未处理且仍停留在出错项，则自动跳到下一项或暂停
            mainHandler.postDelayed({
                if (destroyed) return@postDelayed
                if (consumeForegroundErrorAck(errorUri)) return@postDelayed
                val p = mediaSession?.player ?: return@postDelayed
                val stillOnError =
                    errorUri == p.currentMediaItem?.localConfiguration?.uri?.toString()
                if (stillOnError && p.playbackState == Player.STATE_IDLE) {
                    val exhausted =
                        p.mediaItemCount in 1..consecutiveErrorCount
                    if (p.hasNextMediaItem() && !exhausted) {
                        Log.i(TAG, "后台托底：自动跳到下一项")
                        p.seekToNextMediaItem()

                        p.prepare()
                        p.play()
                    } else {
                        // 无下一项或整队连续出错：只暂停。不再 prepare()，否则会对同一个坏文件
                        // 反复重新加载、再次报错，与 onPlayerError 形成无限循环
                        p.pause()
                    }
                }
            }, 500)
        }
    }

    /** 是否已有待执行的进度轮询任务，避免重复挂起。 */
    private var tickerScheduled = false

    /**
     * 进度轮询任务：仅在播放中或有待落盘删除项时落盘并续期；
     * 无任务时不再续期，避免长时间后台常驻时每 2 秒空转唤醒主线程。
     */
    private val progressTicker = Runnable {
        tickerScheduled = false
        if (mediaSession?.player?.isPlaying == true || removedUris.isNotEmpty()) {
            persistProgress()
            scheduleProgressTicker()
        }
    }

    /** 按需挂起进度轮询（幂等）。 */
    private fun scheduleProgressTicker() {
        // 服务已销毁（或已无媒体会话）后不再挂起，避免销毁后每 2 秒空转、泄漏 Service
        if (destroyed || mediaSession == null) return
        if (tickerScheduled) return
        tickerScheduled = true
        mainHandler.postDelayed(progressTicker, 2000)
    }

    /** 取消待执行的进度轮询。 */
    private fun cancelProgressTicker() {
        if (!tickerScheduled) return
        tickerScheduled = false
        mainHandler.removeCallbacks(progressTicker)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 构建 ExoPlayer：设置音频用途、耳机拔出自动暂停、前后快退/快进步长 15 秒
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        player.addListener(playerEventListener)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    /** 向控制器返回当前会话。 */
    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 任务被移除（从最近任务划掉）时：先落盘；若未处于播放状态则结束服务
        persistProgress()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /** 按写入决策把某个 uri 的进度应用到内存缓存（写入或移除）。 */
    private fun applyToProgressCache(decision: ProgressWriteDecision, uri: String) {
        applyProgressDecision(
            decision,
            uri,
            store = { u, pos -> progressCache[u] = pos },
            clear = { u ->

                progressCache.remove(u)
                removedUris.add(u)
            }
        )
    }

    /** 缓存当前播放项的位置（根据“是否已播完”决定写入还是移除）。 */
    private fun cacheCurrentPosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        applyToProgressCache(decideProgressWrite(player.currentPosition, player.duration), uri)
    }

    /** 缓存旧位置（用于 seek 引发的跨项跳转）。 */
    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = mediaSession?.player ?: return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        val uri = window.mediaItem.localConfiguration?.uri?.toString() ?: return

        applyToProgressCache(decideProgressWrite(oldPosition.positionMs, window.durationMs), uri)
    }

    /** 汇总当前进度并落盘；若与上次快照相同且无待删除项，则跳过写盘。 */
    private fun persistProgress() {
        val player = mediaSession?.player ?: return
        cacheCurrentPosition(player)
        val snapshot = progressCache.toMap()
        if (snapshot == lastPersistedSnapshot && removedUris.isEmpty()) return
        writeToDisk(snapshot)
    }

    /** 异步写盘，并根据写盘结果维护 [removedUris] 与 [lastPersistedSnapshot]。 */
    private fun writeToDisk(progress: Map<String, Long>) {
        val removals = removedUris.toSet()
        PlayerRepository.applyProgressUpdates(progress, removals) { persisted ->
            mainHandler.post {
                if (persisted) {
                    // 落盘成功：清空已处理的删除项，并记录本次快照
                    removedUris.removeAll(removals)
                    lastPersistedSnapshot = progress
                } else {
                    // 落盘失败：把删除项重新放回待处理集合，并重新触发轮询以便重试
                    removedUris.addAll(removals)
                    lastPersistedSnapshot = emptyMap()
                    // 服务销毁后不再触碰轮询等外部状态（scheduleProgressTicker 内部也会拦截）
                    if (!destroyed) scheduleProgressTicker()
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // 内存吃紧：先落盘进度
        persistProgress()
    }

    override fun onDestroy() {
        destroyed = true
        cancelProgressTicker()
        persistProgress()
        // 释放播放器与会话
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        instance = null

        // 不再额外等待落盘：进度写任务已经提交到 PlayerRepository 自己的 persistScope
        // （与服务生命周期无关），而服务级作用域从不取消，等待既等不到也不改变结果。
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlayerService"
        private const val FOREGROUND_ERROR_ACK_TTL_MS = 500L

        /** 当前服务实例（单例），供前台/静态方法调用。 */
        @Volatile
        private var instance: PlayerService? = null

        /** 已由前台处理过错误的 uri，用于避免后台托底重复处理。 */
        private val foregroundHandledErrorUris = mutableMapOf<String, Long>()

        /** 标记某个 uri 的错误已由前台处理。 */
        fun notifyErrorHandled(uri: String) {
            foregroundHandledErrorUris[uri] = SystemClock.elapsedRealtime()
        }

        /** 消费尚未过期的前台确认，兼容 Service 与 Controller 回调先后顺序不同的情况。 */
        private fun consumeForegroundErrorAck(uri: String): Boolean {
            val acknowledgedAt = foregroundHandledErrorUris.remove(uri) ?: return false
            return SystemClock.elapsedRealtime() - acknowledgedAt <= FOREGROUND_ERROR_ACK_TTL_MS
        }

        /** 从进度中删除指定 uri（如文件被删除时）。服务未运行时直接走仓库级删除兜底。 */
        fun dropProgress(uri: String) {
            val svc = instance
            if (svc == null) {
                // 服务未运行：没有内存缓存可清理，直接删除仓库中的进度，
                // 保证该函数单独调用（不配合仓库级删除）时语义也完整
                PlayerRepository.applyProgressUpdates(emptyMap(), setOf(uri))
                return
            }
            svc.progressCache.remove(uri)
            svc.removedUris.add(uri)
            svc.scheduleProgressTicker()
        }

        /** 立即把当前播放位置落盘（如退出界面时）。 */
        fun flushCurrentPosition() {
            val svc = instance ?: return
            val player = svc.mediaSession?.player ?: return
            svc.cacheCurrentPosition(player)
            svc.persistProgress()
        }
    }
}
