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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台播放媒体服务：持有 ExoPlayer 并经 MediaSession 暴露给前端。
 * 音频焦点由播放器内建处理；进度 2 秒周期落盘、销毁时后台短暂等待；
 * 顺序模式连播整个列表，播完（STATE_ENDED）才停；用 URI（而非下标）跟踪上一项。
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 收尾用协程作用域：onDestroy 时后台尽力等待落盘（不在主线程原地等磁盘 I/O） */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 内存进度缓存（uri → 位置毫秒） */
    private val progressCache = mutableMapOf<String, Long>()
    /** 需从磁盘删除进度记录的 uri 集合（防播完后旧进度合并「复活」） */
    private val removedUris = mutableSetOf<String>()
    /** 上次已持久化的进度快照，未变化时跳过写盘 */
    private var lastPersistedSnapshot: Map<String, Long> = emptyMap()
    /** 上一个播放项 uri，用于自动切换时得知「哪一项刚播完」（用 uri 防下标悬空） */
    private var lastPlayedUri: String? = null

    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 清掉上一项（播完）旧进度，防合并写盘「复活」
                val prevUri = lastPlayedUri
                if (prevUri != null) {
                    progressCache.remove(prevUri)
                    removedUris.add(prevUri)
                }
                // 此处不暂停：AUTO 只在切到下一项时触发，恰是连播时刻
            }
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 列表播完的可靠信号是 STATE_ENDED（AUTO 只在切到下一项时触发）；
            // 复核「当下仍是 ENDED」，防前台 clearMediaItems 产生的瞬时 ENDED 误停
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
            // 切项时 currentMediaItem 已指向新项，oldPosition 才记着旧项最终位置
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                cacheOldPosition(oldPosition)
            }
            persistProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 后台托底：只记日志（前台负责提示与跳转）；IDLE 下定位不再前进，快照防抖天然防重写
            Log.w(TAG, "播放出错: code=${error.errorCode}", error)

            val player = mediaSession?.player ?: return
            val errorUri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
            // 同一 uri 再度出错：先清上一次可能残留的「前台已处理」标记，防误吞本次兜底
            foregroundHandledErrorUris.remove(errorUri)
            // 延迟片刻仍无前台处理信号（典型：后台播放），自动跳下一项续播。
            // 双保险：前台处理完会调 notifyErrorHandled 打标记，到点先消费标记（正常路径）；
            // 极端卡顿致标记未及时打上时，仍靠「当前 uri 是否仍停在错误项 + STATE_IDLE」兜底，
            // 防止对已切到的不相关项误跳（用 uri 而非下标比对，防前台删项致下标回退误判）
            mainHandler.postDelayed({
                if (foregroundHandledErrorUris.remove(errorUri)) return@postDelayed
                val p = mediaSession?.player ?: return@postDelayed
                val stillOnError =
                    errorUri == p.currentMediaItem?.localConfiguration?.uri?.toString()
                if (stillOnError && p.playbackState == Player.STATE_IDLE && p.hasNextMediaItem()) {
                    Log.i(TAG, "后台托底：自动跳到下一项")
                    p.seekToNextMediaItem()
                    // 出错后处于 IDLE：seek 只移动位置，需 prepare 清错重试
                    p.prepare()
                    p.play()
                }
            }, 500)
        }
    }

    /** 每 2 秒持久化一次（异步提交，快照未变自动跳过）：进度落盘周期性兜底 */
    private val progressTicker = object : Runnable {
        override fun run() {
            persistProgress()
            mainHandler.postDelayed(this, 2000)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 音频焦点由 ExoPlayer 内建托管（暂时丢失暂停续播/闪避降噪、永久丢失暂停）；
        // 拔耳机自动暂停；快退快进各 15 秒；影音类显式声明 CONTENT_TYPE_MOVIE 便于空间音频路由
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

        mainHandler.post(progressTicker)
    }

    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /** 从最近任务移除：异步提交进度后停止自身（该回调须快速返回） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /** 当前进度写入内存缓存（裁决统一走 decideProgressWrite）；清除时显式标记 [removedUris] */
    private fun cacheCurrentPosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        when (val decision = decideProgressWrite(player.currentPosition, player.duration)) {
            is ProgressWriteDecision.Clear -> {
                // 播到末尾：清内存并标记磁盘删除，防合并写盘「复活」
                progressCache.remove(uri)
                removedUris.add(uri)
            }
            is ProgressWriteDecision.Store -> progressCache[uri] = decision.positionMs
            ProgressWriteDecision.Skip -> Unit // 零位置不覆盖旧进度
        }
    }

    /** 切换前被换掉项的精确进度写入缓存（oldPosition 仍记着旧项 index 与位置）；裁决与 cacheCurrentPosition 统一走 decideProgressWrite */
    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = mediaSession?.player ?: return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        val uri = window.mediaItem.localConfiguration?.uri?.toString() ?: return
        // 时长未知（TIME_UNSET 等 <=0 值）时裁决退化为「位置 >0 即 Store」，即原兜底语义
        when (val decision = decideProgressWrite(oldPosition.positionMs, window.durationMs)) {
            is ProgressWriteDecision.Clear -> {
                // 旧项已播到近末尾：按播完处理，清内存并标记磁盘删除，防合并写盘「复活」
                progressCache.remove(uri)
                removedUris.add(uri)
            }
            is ProgressWriteDecision.Store -> progressCache[uri] = decision.positionMs
            ProgressWriteDecision.Skip -> Unit // 零位置不覆盖旧进度
        }
    }

    /** 持久化当前进度（异步提交）；快照未变且无待删项时跳过 */
    private fun persistProgress() {
        val player = mediaSession?.player ?: return
        cacheCurrentPosition(player)
        val snapshot = progressCache.toMap()
        if (snapshot == lastPersistedSnapshot && removedUris.isEmpty()) return
        writeToDisk(snapshot)
    }

    /**
     * 进度写入 Room（经 [PlayerRepository]），进度落盘唯一写点。
     * 语义为合并：先移除 [removedUris]，再写入仅 >0 的值。一律异步提交（不可 runBlocking 等待）。
     */
    private fun writeToDisk(progress: Map<String, Long>) {
        PlayerRepository.applyProgressUpdates(progress, removedUris)
        removedUris.clear()
        lastPersistedSnapshot = progress
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存告急须尽快返回：只异步提交快照，绝不同步等磁盘 I/O
        persistProgress()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        persistProgress()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        instance = null
        // 后台给写库任务 ≤300ms 完成窗口（真正写库在仓库进程级单线程作用域，进程存活即完成）；
        // 超时剩余数据由周期落盘 + 下次启动对账兜底
        serviceScope.launch {
            withTimeoutOrNull(300.milliseconds) { PlayerRepository.flush() }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlayerService"

        /** 当前存活的 Service 实例，供静态方法直接操作其内存缓存 */
        @Volatile
        private var instance: PlayerService? = null

        /** 已由前台（MainActivity）处理的错误 uri 标记；后台 500ms 兜底到点消费，防双跳（均主线程访问） */
        private val foregroundHandledErrorUris = mutableSetOf<String>()

        /** 前台处理完播放错误后立即调用：后台延迟兜底据此明确让位，不再靠状态推断 */
        fun notifyErrorHandled(uri: String) {
            foregroundHandledErrorUris.add(uri)
        }

        /** 供外部（MainActivity）请求删除某个 uri 的进度记录 */
        fun dropProgress(uri: String) {
            val svc = instance ?: return
            svc.progressCache.remove(uri)
            svc.removedUris.add(uri)
        }

        /** 供前端切项前立即持久化当前项精确进度（transition 后读到的是新项位置） */
        fun flushCurrentPosition() {
            val svc = instance ?: return
            val player = svc.mediaSession?.player ?: return
            svc.cacheCurrentPosition(player)
            svc.persistProgress()
        }
    }
}