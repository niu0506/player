/**
 * 后台播放前台服务：托管 ExoPlayer 与 MediaSession，负责进度落盘、
 * 播放错误自动跳项，并拒绝不可信控制器。播放参数由服务侧持有。
 */
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

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 会话托管的播放器；未创建或已销毁时为 null。 */
    private val player: Player?
        get() = mediaSession?.player

    private val mainHandler = Handler(Looper.getMainLooper())

    private var destroyed = false

    /** 连续播放错误次数：用于判断是否整份列表都播放失败。 */
    private var consecutiveErrorCount = 0

    /** 待落盘的进度缓存；把时间线事件与写库裁决收在一个可单测的状态对象里。 */
    private val progress = ProgressCache()

    /** 当前媒体项的 uri，用于自动切歌时清除上一首的进度。 */
    private var lastPlayedUri: String? = null

    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 自动连播：上一首视为自然播完，清除断点
                lastPlayedUri?.let(progress::clear)
            }
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                // 暂停：立即落盘，ticker 不再续排
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
                    ?.let(progress::clear)
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
            // 队列非空且错误次数不小于队列长度，视为整份列表都播放失败
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
        if (player?.isPlaying == true || progress.hasPendingRemovals()) {
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
            // 本地媒体播放期间保持 CPU 唤醒；只持有本地唤醒锁，不需要 Wi-Fi 锁
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 拔掉耳机（音频路由变为 noisy）时自动暂停
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        player.addListener(playerEventListener)
        mediaSession = buildMediaSession(player)
    }

    /** 构建媒体会话：只允许可信控制器连接，避免第三方应用读取媒体信息。 */
    @OptIn(UnstableApi::class)
    private fun buildMediaSession(player: Player): MediaSession =
        MediaSession.Builder(this, player)
            // 通知栏 / 锁屏 / 系统媒体控件的点击都依赖 session activity 才能回到界面
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
        // 从最近任务划掉且没有播放意图（playWhenReady=false）时结束服务
        val player = this.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /** 按裁决结果缓存某个条目的播放位置（当前项与已切走的旧项共用）。 */
    private fun cachePosition(uri: String?, positionMs: Long, durationMs: Long) {
        progress.cache(uri, positionMs, durationMs)
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

    /** 收集当前变更并落盘（无变化则跳过写库）。 */
    private fun persistProgress() {
        val player = this.player ?: return
        // URI 已真正离开播放队列后解除抑制，避免集合无限增长
        if (progress.hasPendingDrops()) {
            val present = (0 until player.mediaItemCount)
                .mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
                .toSet()
            progress.retainPending(present)
        }
        cacheCurrentPosition(player)
        val removals = progress.pendingRemovals()
        val writes = progress.writes()
        if (writes.isEmpty() && removals.isEmpty()) return
        writeToDisk(writes, removals)
    }

    /** 提交一次数据库写入；成功更新快照，失败则保留待写数据并重新排期。 */
    private fun writeToDisk(writes: Map<String, Long>, removals: Set<String>) {
        val snapshot = progress.snapshot()
        PlayerRepository.applyProgressUpdates(writes, removals) { persisted ->
            mainHandler.post {
                if (persisted) {
                    progress.markPersisted(snapshot, removals)
                } else {
                    progress.markFailed(removals)
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
            // 连快照一并移除，避免下一轮落盘按旧快照重复提交；当前项还会进入
            // pendingDrops，直到播放器真正把它移出队列才允许重新缓存进度。
            svc.progress.drop(keys, currentUri)
        }

        /** 删除单条进度。 */
        fun dropProgress(uri: String) = dropProgress(listOf(uri))

        /** 队列与列表已一致时清空全部抑制项（用于条目在控制器未连接时被删除的兜底）。 */
        fun clearAllPendingDrops() {
            instance?.progress?.clearAllPendingDrops()
        }

        /** 立即把当前播放位置落盘（暂停 / 切后台 / 点播前调用）。 */
        fun flushCurrentPosition() {
            instance?.persistProgress()
        }

        /** 重置本地数据时清空服务侧进度缓存，避免旧快照重新写回数据库。 */
        fun clearProgressCache() {
            instance?.progress?.clearAll()
        }
    }
}

/** 进度内存状态：待写位置、待删除 URI、删除抑制与落盘快照（纯逻辑，便于测试）。 */
internal class ProgressCache {

    /** 待落盘位置（uri → 毫秒）。 */
    private val cached = mutableMapOf<String, Long>()

    /** 需要从数据库删除进度的 uri。 */
    private val removals = mutableSetOf<String>()

    /**
     * 已删除但播放器时间线里可能还存在的 uri。
     * 在它真正离开队列前，ticker 不得把旧位置重新缓存回来。
     */
    private val pendingDrops = mutableSetOf<String>()

    /** 上一次成功落盘时的缓存快照，用于跳过无变化的写入。 */
    private var lastPersisted: Map<String, Long> = emptyMap()

    /** 按末尾容差裁决缓存一个位置；[uri] 为 null 时忽略。 */
    fun cache(uri: String?, positionMs: Long, durationMs: Long) {
        if (uri == null || uri in pendingDrops) return
        when (val decision = decideProgressWrite(positionMs, durationMs)) {
            ProgressWriteDecision.Clear -> clear(uri)
            is ProgressWriteDecision.Store -> cached[uri] = decision.positionMs
            ProgressWriteDecision.Skip -> Unit
        }
    }

    /** 显式清除某条进度（自动连播结束、seek 回 0）。 */
    fun clear(uri: String?) {
        if (uri == null) return
        cached.remove(uri)
        // 删除请求可能先于旧快照落盘回调，先从快照移除，避免进度被“复活”
        lastPersisted = lastPersisted - uri
        removals.add(uri)
    }

    /** 删除条目时清理缓存；当前播放项需进入 pendingDrops，防止 ticker 回写。 */
    fun drop(uris: Collection<String>, currentUri: String?) {
        for (key in uris) {
            cached.remove(key)
            lastPersisted = lastPersisted - key
            if (key == currentUri) pendingDrops.add(key)
        }
    }

    /** URI 真正离开播放队列后解除删除抑制。 */
    fun retainPending(present: Set<String>) {
        pendingDrops.retainAll(present)
    }

    fun hasPendingDrops(): Boolean = pendingDrops.isNotEmpty()

    fun hasPendingRemovals(): Boolean = removals.isNotEmpty()

    fun pendingRemovals(): Set<String> = removals.toSet()

    /** 只返回相对上次成功落盘有变化的写入。 */
    fun writes(): Map<String, Long> =
        cached.filter { (uri, position) -> lastPersisted[uri] != position }

    fun snapshot(): Map<String, Long> = cached.toMap()

    /** 落盘成功：更新快照并移除本次已提交的删除项。 */
    fun markPersisted(snapshot: Map<String, Long>, writtenRemovals: Set<String>) {
        removals.removeAll(writtenRemovals)
        // 写入飞行期间被 clear/drop 的 key 已不在 cached 中，
        // 不能再借旧快照复活到 lastPersisted。
        lastPersisted = snapshot.filterKeys { it in cached }
    }

    /** 落盘失败：保留本次删除项并清空快照，下一轮重写全部内容。 */
    fun markFailed(writtenRemovals: Set<String>) {
        removals.addAll(writtenRemovals)
        lastPersisted = emptyMap()
    }

    fun clearAllPendingDrops() {
        pendingDrops.clear()
    }

    /** 清空全部内存状态（重置本地数据时使用）。 */
    fun clearAll() {
        cached.clear()
        removals.clear()
        pendingDrops.clear()
        lastPersisted = emptyMap()
    }
}

/** 是否为当前条目从非零位置显式 seek 回 0（只有这种情况清除旧断点）。 */
internal fun isExplicitSeekToZero(
    reason: Int,
    sameItem: Boolean,
    oldPositionMs: Long,
    newPositionMs: Long,
): Boolean = reason == Player.DISCONTINUITY_REASON_SEEK &&
    sameItem &&
    oldPositionMs > 0L &&
    newPositionMs == 0L
