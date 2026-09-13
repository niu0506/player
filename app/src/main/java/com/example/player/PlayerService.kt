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
 * 后台播放媒体服务：持有 ExoPlayer 并经 MediaSession 暴露给前端控制器。
 * 音频焦点由 MediaSession 会话托管的播放器内建处理；进度一律异步提交落盘
 * （2 秒周期 progressTicker 兜底，销毁时在后台短暂等待落盘）；
 * 顺序模式(REPEAT_MODE_OFF)连播整个列表，播完（STATE_ENDED）才停；
 * 用 URI（而非下标）跟踪上一播放项，避免删除列表项导致下标悬空。
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 收尾专用的协程作用域：onDestroy 时在后台「尽力等待」落盘。
     * 生命周期回调（onDestroy/onTaskRemoved/onTrimMemory 等）都跑方位词程，
     * 绝不能在其中 runBlocking 原地等待磁盘 I/O——会卡住主线程造成 ANR/卡顿，
     * 内存告急（onTrimMemory）时尤其危险。
     * 注意：不要在 onDestroy 里 cancel 这个作用域，否则会连带取消等待中的 flush；
     * 真正地写库任务位于 PlayerRepository 自己的进程级作用域，不受这里影响。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 清理上一项(播完)的旧进度，防止合并写盘时「复活」
                val prevUri = lastPlayedUri
                if (prevUri != null) {
                    progressCache.remove(prevUri)
                    removedUris.add(prevUri)
                }
                // 这里不做任何暂停：AUTO 回调只在「已切到下一项」时触发，
                // 恰是连播发生的时刻，暂停会打断每一个文件（历史 bug：
                // REPEAT_MODE_OFF 时在此 pause，导致每个文件播完都停、无法连播）。
                // 「整个列表播完才停」由下方 onPlaybackStateChanged(STATE_ENDED) 处理
            }
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            lastPlayedUri?.let { PlayerRepository.setLastItem(it) }
            persistProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 「整个列表播完」的可靠信号是 STATE_ENDED，而非 onMediaItemTransition(AUTO)：
            // AUTO 只在「已切到下一项」时触发（那一刻必然存在后继项，恰是不该停的
            // 时刻）；最后一项播完不产生 transition，直接从 READY 进入 ENDED。
            // REPEAT_MODE_OFF 下进入 ENDED 即列表播完；单曲/列表循环永不 ENDED，
            // 不受影响。保留 repeatMode 判断是为防「播完瞬间切换循环模式」的
            // 边界竞态：用户刚打开循环就该让它续播，不能强停。
            // 此时播放实际已停，pause() 仅复位 playWhenReady，让通知栏/控制器
            // 按钮回到「可播放」态，不残留「暂停中」的假象
            val player = mediaSession?.player ?: return
            // 复核「当下仍是 ENDED」：事件送达时状态可能已被后续操作改写——
            // 典型是前台队列自愈的 clearMediaItems → 重灌 → seekTo，其中
            // clearMediaItems 会产生一个「瞬时 ENDED」事件，送达时播放器其实已
            // 回到 BUFFERING。只凭事件参数暂停会误停正在进行的播放
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
            // 切到别的条目时 player.currentMediaItem 已指向新项，
            // 只有 oldPosition 还记着旧项的最终位置，据此精确抓取旧项进度
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                cacheOldPosition(oldPosition)
            }
            persistProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 后台托底：只记录日志，不弹 Toast（前台 MainActivity 负责提示与跳转）。
            // 出错后播放器停在 IDLE、位置不再前进，persistProgress 的
            // 「快照未变即跳过」天然防抖，进度持久化不会因此进入异常状态
            Log.w(TAG, "播放出错: code=${error.errorCode}", error)

            val player = mediaSession?.player ?: return
            val errorUri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
            // 前台若在线会立即处理（跳下一项/移除失效项）；延迟片刻后播放器
            // 仍停留在出错的 IDLE 状态，说明无人处理（典型：后台播放），
            // 由服务端自动跳下一项续播，避免用户卡在一个坏文件上。
            // 用 uri（而非下标）比对是否仍停在出错项：前台「跳转后又删除旧项」
            // 会让下标回退到相同数值，按下标判断会误判成「未处理」而重复跳转
            mainHandler.postDelayed({
                val p = mediaSession?.player ?: return@postDelayed
                val stillOnError =
                    errorUri == p.currentMediaItem?.localConfiguration?.uri?.toString()
                if (stillOnError && p.playbackState == Player.STATE_IDLE && p.hasNextMediaItem()) {
                    Log.i(TAG, "后台托底：自动跳到下一项")
                    p.seekToNextMediaItem()
                    // 出错后处于 IDLE：seek 只移动位置不会自动恢复，需 prepare 清错重试
                    p.prepare()
                    p.play()
                }
            }, 500)
        }
    }

    /** 每 2 秒持久化一次（异步提交，快照未变化时自动跳过）：进度落盘的周期性兜底 */
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

    /**
     * 从最近任务移除：异步提交最后进度（此回调要求快速返回，不做任何 I/O 等待）；
     * 未在播放则停止自身，由此触发的 onDestroy 里还有一轮后台落盘兜底
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /**
     * 当前进度写入内存缓存。
     * 写入/清除/跳过的判定统一由 decideProgressWrite 裁决（唯一权威实现），
     * 这里只负责把结果落到 progressCache；清除时额外标记 [removedUris]
     */
    private fun cacheCurrentPosition(player: Player) {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        when (val decision = decideProgressWrite(player.currentPosition, player.duration)) {
            is ProgressWriteDecision.Clear -> {
                // 播到末尾：清内存缓存并显式标记磁盘删除，防止合并写盘时「复活」
                progressCache.remove(uri)
                removedUris.add(uri)
            }
            is ProgressWriteDecision.Store -> progressCache[uri] = decision.positionMs
            ProgressWriteDecision.Skip -> Unit // 零位置不覆盖旧进度
        }
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

    /** 持久化当前进度（异步提交，不等待落盘）；快照未变且无待删除项时跳过 */
    private fun persistProgress() {
        val player = mediaSession?.player ?: return
        cacheCurrentPosition(player)
        val snapshot = progressCache.toMap()
        if (snapshot == lastPersistedSnapshot && removedUris.isEmpty()) return
        writeToDisk(snapshot)
    }

    /**
     * 进度写入 Room（经 [PlayerRepository]），进度落盘的唯一写入点。
     * 语义为「合并」（mergeProgressMap 保证）：先移除 [removedUris]，再写入仅 >0 的值。
     * 一律异步提交，不再区分同步/异步路径：仓库内部按提交顺序串行落库，
     * 磁盘可靠性由 2 秒周期落盘（progressTicker）+ onDestroy 的后台短暂等待兜底。
     * 注意：本方法被多个主线程生命周期回调调用，绝不能在内部 runBlocking 等待
     * flush——原地等磁盘 I/O（旧实现最长 2 秒）会阻塞主线程，有 ANR/卡顿风险
     */
    private fun writeToDisk(progress: Map<String, Long>) {
        // 仓库同步捕获本次写入/删除集合并更新内存镜像，之后 clear 不会影响已提交内容
        PlayerRepository.applyProgressUpdates(progress, removedUris)
        removedUris.clear()
        lastPersistedSnapshot = progress
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存告急时系统要求尽快返回：只异步提交进度快照，绝不同步等磁盘 I/O
        // （旧实现 critical 时 runBlocking 最长卡 2 秒，恰是被杀风险最高的时刻）
        persistProgress()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        // 先异步提交最后一次进度（纯内存快照 + 按序排队写库，立即返回）
        persistProgress()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        instance = null
        // 尽力确保落盘（替代旧的 runBlocking 同步等待）：
        // onDestroy 跑方位词程，绝不能原地等待 flush——旧实现最长卡 2 秒，
        // 有 ANR/卡顿风险。真正地写库任务在 PlayerRepository 的进程级单线程
        // 作用域里按提交顺序执行，只要进程存活就会完成；这里在后台给它们一个
        // ≤300ms 的完成窗口，超时即放弃，剩余数据由周期性落盘和下次启动的
        // 对账逻辑兜底（最多丢最后 2 秒内的进度变化，与周期落盘粒度一致）
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
            svc.persistProgress()
        }
    }
}
