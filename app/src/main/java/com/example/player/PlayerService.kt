package com.example.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台播放媒体服务：持有 ExoPlayer 并经 MediaSession 暴露给前端控制器。
 * 音频焦点由 MediaSession 会话托管的播放器内建处理；周期（2 秒）+关键时刻落盘进度；
 * 用 URI（而非下标）跟踪上一播放项，避免删除列表项导致下标悬空。
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 内存进度缓存（uri -> 位置毫秒） */
    private val progressCache = mutableMapOf<String, Long>()
    /**
     * 需要从磁盘「删除」进度记录的 uri 集合。
     * writeToDisk 是合并逻辑，播放到末尾的项必须显式标记删除，
     * 否则旧进度会被重新合并回磁盘（残留进度复活）。
     */
    private val removedUris = mutableSetOf<String>()
    /** 上次已持久化的进度快照，未变化时跳过写盘 */
    private var lastPersistedSnapshot: Map<String, Long> = emptyMap()
    /**
     * 上一个播放项的 uri，用于自动切换(播完)时得知「哪一项刚播完」。
     * 用 uri 而非下标：删除前项不触发 transition，下标会悬空。
     */
    private var lastPlayedUri: String? = null

    private val playerEventListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = mediaSession?.player
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 清理上一项(播完)的旧进度，防止合并写盘时「复活」
                val prevUri = lastPlayedUri
                if (prevUri != null) {
                    progressCache.remove(prevUri)
                    removedUris.add(prevUri)
                }
                // 顺序播放(REPEAT_MODE_OFF)时每个文件播完即停
                if (player != null && player.repeatMode == Player.REPEAT_MODE_OFF) {
                    player.pause()
                }
            }
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // 切到别的条目时 player.currentMediaItem 已指向新项，
            // 只有 oldPosition 还记着旧项的最终位置，据此精确抓取旧项进度
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                cacheOldPosition(oldPosition)
            }
            persistProgress()
        }
    }

    /** 每 2 秒持久化一次（快照未变化时自动跳过） */
    private val progressTicker = object : Runnable {
        override fun run() {
            persistProgress(sync = false)
            mainHandler.postDelayed(this, 2000)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 音频焦点由 MediaSession 会话托管的播放器内建处理
        // （暂时丢失暂停并续播、可闪避时降音量、永久丢失只暂停）；
        // 拔出耳机自动暂停；快退/快进各 15 秒。
        // 影音类播放显式声明 CONTENT_TYPE_MOVIE，便于系统路由于空间音频处理
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

    /** 从最近任务移除：立即落盘；未在播放则停止自身 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress(sync = true)
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /**
     * 当前进度写入内存缓存。
     * 播到末尾改为「删除该进度」；位置为 0 且已有旧进度则忽略（防覆写）。
     */
    private fun cacheCurrentPosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val position = player.currentPosition
        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0 && position >= duration) {
            progressCache.remove(uri)
            removedUris.add(uri)
            return
        }
        if (position <= 0 && (progressCache[uri] ?: 0L) > 0L) return
        progressCache[uri] = position
    }

    /** 切换前被换掉项的精确进度写入缓存（oldPosition 仍记着旧项的 index 与位置） */
    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = mediaSession?.player ?: return
        if (oldPosition.positionMs <= 0) return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        val uri = window.mediaItem.localConfiguration?.uri?.toString() ?: return
        progressCache[uri] = oldPosition.positionMs
    }

    /** 持久化当前进度；快照未变且无待删除项时跳过 */
    private fun persistProgress(sync: Boolean = false) {
        val player = mediaSession?.player ?: return
        cacheCurrentPosition(player)
        val snapshot = progressCache.toMap()
        if (snapshot == lastPersistedSnapshot && removedUris.isEmpty()) return
        writeToDisk(snapshot, sync)
    }

    /**
     * 进度写入 Room（经 [PlayerRepository]），进度落盘的唯一写入点。
     * 语义为「合并」（mergeProgressMap 保证）：先移除 [removedUris]，再写入仅 >0 的值。
     */
    private fun writeToDisk(progress: Map<String, Long>, sync: Boolean = true) {
        // 仓库同步捕获本次写入/删除集合并更新内存镜像，之后 clear 不会影响已提交内容
        PlayerRepository.applyProgressUpdates(progress, removedUris)
        removedUris.clear()
        lastPersistedSnapshot = progress
        if (sync) {
            runBlocking { withTimeoutOrNull(2_000.milliseconds) { PlayerRepository.flush() } }
        }
    }

    /** 内存不足时同步落盘（进程可能随后被杀，排队的异步写会丢） */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        persistProgress(sync = true)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        persistProgress(sync = true)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        instance = null
        super.onDestroy()
    }

    companion object {
        /** 当前存活的 Service 实例，供静态方法直接操作其内存缓存 */
        @Volatile
        private var instance: PlayerService? = null

        /** 供外部（MainActivity）请求删除某个 uri 的进度记录 */
        fun dropProgress(uri: String) {
            val svc = instance ?: return
            svc.progressCache.remove(uri)
            svc.removedUris.add(uri)
        }

        /**
         * 供前端在「切到别的条目」之前调用，立即持久化当前项的精确进度
         * （transition 触发后读到的是新项位置）。异步落盘：进度值已同步进缓存
         * 按序排队，磁盘可靠性由周期上报 + seek 落盘多层兜底。
         */
        fun flushCurrentPosition() {
            val svc = instance ?: return
            val player = svc.mediaSession?.player ?: return
            svc.cacheCurrentPosition(player)
            svc.persistProgress(sync = false)
        }
    }
}
