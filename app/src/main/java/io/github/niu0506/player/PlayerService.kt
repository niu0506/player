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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val MediaItem?.uriString: String?
    get() = this?.localConfiguration?.uri?.toString()

class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val player: Player?
        get() = mediaSession?.player

    private val mainHandler = Handler(Looper.getMainLooper())

    private var destroyed = false

    /** 本轮失败 run 中已经试过并失败的条目 uri；成功播放或被显式重置时清空。 */
    private val attemptedUris = mutableSetOf<String>()

    /** 本轮失败 run 的尝试次数，作为 uri 缺失时无法靠集合收敛的兜底。 */
    private var errorRunAttempts = 0

    /** 列表时长索引缓存：源列表实例被仓库替换时才重建。 */
    private var durationIndexSource: List<MediaItemData>? = null
    private var durationIndex: Map<String, Long> = emptyMap()

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
                clearErrorRun()
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

            // 按「本轮已经试过哪些条目」判定整份列表失败：从列表中间开始播时，
            // 连续错误次数永远达不到队列长度，那个口径会漏掉大多数场景。
            errorUri?.let(attemptedUris::add)
            errorRunAttempts++
            val itemUris = playerItemUris(player)
            val exhausted = isPlaylistExhausted(
                itemCount = player.mediaItemCount,
                // 只统计仍在当前队列里的条目，避免队列变短时把旧 uri 算进覆盖
                attemptedCount = itemUris.count { it != null && it in attemptedUris },
                attempts = errorRunAttempts,
            )
            val nextIndex = planErrorSkip(
                exhausted = exhausted,
                hasNext = player.hasNextMediaItem(),
                nextIndex = player.nextMediaItemIndex,
                repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
                untriedIndex = firstUntriedIndex(itemUris, attemptedUris),
            )
            val hasNext = nextIndex >= 0

            if (hasNext) {
                // 位置交给默认续播策略（C.TIME_UNSET），与 seekToNextMediaItem 一致
                player.seekTo(nextIndex, C.TIME_UNSET)
                player.prepare()
                player.play()
            } else {
                player.pause()
            }

            val event = PlaybackErrorEvent(errorUri, exhausted, hasNext, error.errorCode)
            PlaybackSession.onPlaybackError(event)
        }
    }

    internal fun clearErrorRun() {
        attemptedUris.clear()
        errorRunAttempts = 0
        // 已经能正常播放，之前记下的致命失败不必再补弹窗
        PlaybackSession.consumeFatalFailure()
    }

    /** 供 [PlaybackSession] 使用：抑制当前项被 ticker 重新缓存。 */
    internal fun dropProgressForSession(keys: Set<String>) {
        progress.drop(keys, player?.currentMediaItem?.uriString)
    }

    /** 供 [PlaybackSession] 使用。 */
    internal fun clearPendingDropsForSession() {
        progress.clearAllPendingDrops()
    }

    /** 供 [PlaybackSession] 使用。 */
    internal fun persistProgressNow() {
        persistProgress()
    }

    private fun playerItemUris(player: Player): List<String?> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).uriString }

    private var tickerScheduled = false

    private val progressTicker = Runnable {
        tickerScheduled = false
        if (PlaybackSession.isResetting) return@Runnable
        // 只要用户还想播、或还有没落盘的进度就继续轮询。缓冲、音频焦点暂失、小窗/息屏
        // 切换都会让 isPlaying 短暂为 false，用它当条件会让轮询就此停摆，后台播放的进度
        // 再也落不了盘；保留待落盘项也让暂停/播完后的失败写库能继续重试。
        if (player?.playWhenReady == true || progress.hasPendingChanges()) {
            persistProgress()
            scheduleProgressTicker()
        }
    }

    private fun scheduleProgressTicker() {
        if (destroyed || PlaybackSession.isResetting || mediaSession == null) return
        if (tickerScheduled) return
        tickerScheduled = true
        mainHandler.postDelayed(progressTicker, PROGRESS_TICKER_INTERVAL_MS)
    }

    private fun cancelProgressTicker() {
        if (!tickerScheduled) return
        tickerScheduled = false
        mainHandler.removeCallbacks(progressTicker)
    }

    internal fun clearProgressForReset() {
        cancelProgressTicker()
        progress.clearAll()
        lastPlayedUri = null
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        PlaybackSession.attach(this)

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

    /**
     * 列表（扫描）时长；没有记录时返回 0。
     *
     * 仓库整表替换列表实例，用身份判断即可命中缓存，避免 ticker 每 5 秒在主线程全表扫描。
     */
    private fun playlistDurationMs(uri: String?): Long {
        if (uri == null) return 0L
        val items = PlayerRepository.getPlaylist()
        if (durationIndexSource !== items) {
            durationIndexSource = items
            durationIndex = playlistDurationIndex(items)
        }
        return playlistDurationFor(durationIndex, uri)
    }

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
        if (PlaybackSession.isResetting) return
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

    private fun writeToDisk(writes: Map<String, Long>, removals: Map<String, Long>) {
        val snapshot = progress.snapshot()
        PlayerRepository.applyProgressUpdates(writes, removals.keys) { persisted ->
            mainHandler.post {
                // 重置已开始，不再更新
                if (PlaybackSession.isResetting) return@post
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
        PlaybackSession.detach(this)

        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlayerService"

        private const val PROGRESS_TICKER_INTERVAL_MS = 5000L

        private const val RESUME_START_TOLERANCE_MS = 1_000L
    }
}

/** 一次播放失败的上报；[exhausted] 表示整份列表都已试过且都失败。 */
internal data class PlaybackErrorEvent(
    val uri: String?,
    val exhausted: Boolean,
    val hasNext: Boolean,
    val errorCode: Int,
)

/**
 * 进程内的播放会话：持有服务实例，并对外提供界面需要的命令与事件。
 *
 * 这些操作原先散落在 [PlayerService] 的 companion 上，谁都能直接摸到服务实例，
 * 而「服务不在时会发生什么」没有任何地方交代。现在服务实例只由本对象持有，
 * 每个命令的注释都写明服务缺席时的语义。
 *
 * 注意这条通道不是 `MediaController`/`MediaSession`：这些命令必须在控制器释放
 * 之后（onStop / onPause）依然有效，所以要靠进程内的直接引用。
 */
internal object PlaybackSession {

    /** 服务实例；onCreate 时注册、onDestroy 时注销。 */
    @Volatile
    private var service: PlayerService? = null

    /** 重置进行中：服务侧禁止写进度。 */
    @Volatile
    var isResetting = false
        private set

    private val errorEventsInternal = MutableSharedFlow<PlaybackErrorEvent>(
        extraBufferCapacity = 16
    )

    /** 播放错误事件；界面不可见时会丢弃，致命失败另见 [fatalFailures]。 */
    val errorEvents: SharedFlow<PlaybackErrorEvent> = errorEventsInternal

    /**
     * 最近一次「整份列表都无法播放」的失败。
     *
     * [errorEvents] 没有重放能力，界面不可见时事件直接丢弃；后台播完失败是主场景，
     * 因此把致命失败另存一份，界面进入 STARTED 后取到并消费。
     */
    private val fatalFailureState = MutableStateFlow<PlaybackErrorEvent?>(null)
    val fatalFailures: StateFlow<PlaybackErrorEvent?> = fatalFailureState.asStateFlow()

    // ---- 服务生命周期 ----

    fun attach(target: PlayerService) {
        service = target
    }

    fun detach(target: PlayerService) {
        // 只在仍是自己时注销，避免旧的 onDestroy 把新实例清掉
        if (service === target) service = null
    }

    // ---- 服务侧上报 ----

    fun onPlaybackError(event: PlaybackErrorEvent) {
        // 记下致命失败：界面不可见时 errorEvents 会丢，回到前台要能补上弹窗
        if (event.exhausted) fatalFailureState.value = event
        errorEventsInternal.tryEmit(event)
    }

    /** 消费掉致命失败，避免回到前台反复弹窗。 */
    fun consumeFatalFailure() {
        fatalFailureState.value = null
    }

    // ---- 界面侧命令 ----

    /**
     * 删除若干条目的播放进度。
     *
     * 写库不依赖服务实例（服务不在也要真的把进度删掉）；只有「抑制当前项被 ticker
     * 重新缓存」这一步需要服务在场 —— 服务不在就没有队列，也就没有立即回写的风险。
     */
    fun dropProgress(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val keys = uris.toSet()
        PlayerRepository.applyProgressUpdates(emptyMap(), keys)
        service?.dropProgressForSession(keys)
    }

    fun dropProgress(uri: String) = dropProgress(listOf(uri))

    /** 释放所有「不得重新缓存」的抑制；服务不在时无事可做。 */
    fun clearAllPendingDrops() {
        service?.clearPendingDropsForSession()
    }

    /** 立刻把当前播放位置落盘；服务不在时无事可做。 */
    fun flushCurrentPosition() {
        service?.persistProgressNow()
    }

    /** 重新开始统计播放错误。 */
    fun resetErrorCount() {
        service?.clearErrorRun()
        // 服务实例尚未建立时上面那行不会执行，这里补一次
        consumeFatalFailure()
    }

    /** 进入重置：禁止服务侧写进度。 */
    fun beginReset() {
        isResetting = true
        service?.clearProgressForReset()
    }

    /** 退出重置：无论成功与否都要恢复落盘。 */
    fun endReset() {
        service?.clearProgressForReset()
        isResetting = false
    }
}

/** 待落盘进度的内存状态（纯逻辑，便于测试）。 */
internal class ProgressCache {

    private val cached = mutableMapOf<String, Long>()

    /**
     * uri → 删除操作的版本号。
     *
     * 版本用来区分「某次提交时的那一笔删除」与提交之后重新产生的同名删除：落盘回调只能
     * 清掉前者，否则新产生的那笔删除会被吃掉，磁盘上留着进度、重启后复活。
     */
    private val removalVersions = mutableMapOf<String, Long>()

    private var removalSeq = 0L

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
        removalVersions[uri] = ++removalSeq
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

    /** 是否还有未落盘的进度（待删除项或值有变化的写入）。 */
    fun hasPendingChanges(): Boolean = removalVersions.isNotEmpty() || writes().isNotEmpty()

    /** 待删除项及其版本；落盘时把版本一并带过去，回调才知道能清哪一版。 */
    fun pendingRemovals(): Map<String, Long> = removalVersions.toMap()

    fun writes(): Map<String, Long> =
        cached.filter { (uri, position) -> lastPersisted[uri] != position }

    fun snapshot(): Map<String, Long> = cached.toMap()

    fun markPersisted(snapshot: Map<String, Long>, writtenRemovals: Map<String, Long>) {
        // 只清掉「提交时就是这一版」的删除：期间的重新删除必须留着，见 removalVersions
        for ((uri, version) in writtenRemovals) {
            if (removalVersions[uri] == version) removalVersions.remove(uri)
        }
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
        removalVersions.clear()
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

/**
 * 本轮失败 run 是否已经覆盖整个队列。
 *
 * 用「试过的条目数」而不是「连续错误次数」：从列表中间开始播时，错误次数永远达不到
 * 队列长度。[attempts] 只在拿不到 uri、集合无法增长时负责收敛。
 */
internal fun isPlaylistExhausted(
    itemCount: Int,
    attemptedCount: Int,
    attempts: Int,
): Boolean = itemCount > 0 && (attemptedCount >= itemCount || attempts >= itemCount)

/**
 * 出错后下一个要尝试的条目下标；返回 -1 表示不再自动跳过。
 *
 * 顺着队列往前后若前面还有没试过的条目，就回到最前面那一条继续，这样「整份列表都
 * 无法播放」才可能真正达成；单曲循环不做这个回绕，保持原有语义。
 */
internal fun planErrorSkip(
    exhausted: Boolean,
    hasNext: Boolean,
    nextIndex: Int,
    repeatOne: Boolean,
    untriedIndex: Int,
): Int = when {
    exhausted -> -1
    hasNext -> nextIndex
    repeatOne -> -1
    else -> untriedIndex
}

/** 队列中第一条本轮还没试过的条目下标；都试过则为 -1。 */
internal fun firstUntriedIndex(itemUris: List<String?>, attempted: Set<String>): Int {
    for (index in itemUris.indices) {
        val uri = itemUris[index]
        if (uri == null || uri !in attempted) return index
    }
    return -1
}

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
