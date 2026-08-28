package com.example.player

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONObject

/**
 * 后台播放媒体服务。
 *
 * 继承 [MediaSessionService]，让播放能力在 App 离开前台后依然存活（例如锁屏、后台、
 * 画中画），并通过 [MediaSession] 与前端（MainActivity 的 MediaController）通信。
 *
 * 职责划分：
 * - 持有真正的播放器 [ExoPlayer]，向前端暴露控制能力
 * - 音频焦点交给 ExoPlayer 内建托管（handleAudioFocus=true）：暂时丢失暂停、可闪避时降音量、
 *   永久丢失仅暂停不含自动抢播，避免手动焦点管理带来的"双 App 同放"等边角问题
 * - 周期性把播放进度写入磁盘（每 2 秒），并在关键时刻立即落盘
 * - 处理「播放到末尾自动切换」时清理上一项旧进度，防止残留进度复活
 * - 用 URI（而非下标）跟踪上一播放项，避免删除列表项导致的下标悬空
 */
class PlayerService : MediaSessionService() {

    /** 当前媒体会话，通过它暴露 player 给前端控制器 */
    private var mediaSession: MediaSession? = null
    /** 主线程 Handler，用于排定周期的进度上报任务 */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 内存中的播放进度缓存（uri -> 播放位置毫秒） */
    private val progressCache = mutableMapOf<String, Long>()
    /**
     * 需要从磁盘「删除」进度记录的 uri 集合。
     * 见项目记忆：writeToDisk 是合并非追加逻辑，播放到末尾的项必须显式标记删除，
     * 否则旧的近末尾进度会被重新合并回磁盘（残留进度复活）。
     */
    private val removedUris = mutableSetOf<String>()
    /** 上次已持久化的进度快照，用于比较是否需要写盘（避免空闲时的无谓 IO） */
    private var lastPersistedSnapshot: Map<String, Long> = emptyMap()
    /**
     * 上一个播放项的 uri，用于在自动切换(播放到末尾)时得知「哪一项刚播完」。
     * 用 uri 而非下标跟踪：删除当前项之前的条目会使下标前移但不触发 transition，
     * 下标跟踪会悬空，导致误删/漏删其它条目的进度。
     */
    private var lastPlayedUri: String? = null
    /** 进度/列表等数据存储的 SharedPreferences，懒加载复用 */
    private val playerPrefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    /** 播放器事件监听：处理进度清理、落盘与「上一播放项」记录 */
    private val playerEventListener = object : Player.Listener {
        /**
         * 发生媒体切换时：若为自然播放到末尾自动切换，则清理上一项(lastPlayedUri)的旧进度，
         * 防止其近末尾进度在下次合并写盘时「复活」。用 uri 定位，避免删除前项导致的下标悬空。
         * 同时在切换时把当前播放项 uri 持久化，供前端冷启动恢复。
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = mediaSession?.player
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 清理上一项(播完)的旧进度，防止合并写盘时「复活」
                val prevUri = lastPlayedUri
                if (prevUri != null) {
                    progressCache.remove(prevUri)
                    removedUris.add(prevUri)
                }
                // 顺序播放(不循环 REPEAT_MODE_OFF)：每个文件播完即停，不自动播下一个。
                // 单曲循环/列表循环时重复模式非 OFF，仍按各自规则循环。
                if (player != null && player.repeatMode == Player.REPEAT_MODE_OFF) {
                    player.pause()
                }
            }
            lastPlayedUri = mediaItem?.localConfiguration?.uri?.toString()
            if (lastPlayedUri != null) {
                playerPrefs.edit {
                    putString("lastItem", lastPlayedUri)
                }
            }
            persistProgress()
        }

        /** 播放状态变化：停止播放时立即落盘 */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistProgress()
        }

        /** 位置发生不连续跳变（用户拖动进度条 seek，或切到别的条目）时立即落盘 */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                // 切到别的条目(点列表/播放器"下一集"/通知栏下一条)时，
                // onMediaItemTransition 触发后 player.currentMediaItem 已指向新项，
                // 只有 oldPosition 还记着被换掉旧项的最终位置。先精确抓取旧项进度再落盘，
                // 避免「正在播放的进度丢失」。
                cacheOldPosition(oldPosition)
            }
            persistProgress()
        }
    }

    /**
     * 进度上报主循环：每 2 秒尝试持久化一次。
     * persistProgress 内部会先缓存当前进度，且快照未变化时自动跳过写盘，避免空闲 IO。
     */
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

        // 构建播放器：
        // - setAudioAttributes(DEFAULT, true) 把音频焦点交给 ExoPlayer 托管：
        //   获得焦点才播放、暂时丢失暂停并在恢复时续播、可闪避时降音量、永久丢失只暂停不抢播，
        //   比手写 `AUTOFOCUS_LOSS` 也启动轮询恢复更符合系统焦点礼仪。
        // - setHandleAudioBecomingNoisy(true) 拔出耳机等导致音频「变为嘈杂」时自动暂停
        // - 设置快退/快进各为 15 秒，供控制器及手势使用
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.DEFAULT,
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

    /** 把本服务持有的会话返回给请求的控制器 */
    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /** 任务被从最近任务列表移除时：立即落盘；若未在播放则停止自身 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress(sync = true)
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    /**
     * 把播放器的当前进度写入内存缓存。
     * 若已播放到末尾则改为「删除该进度」；若位置为 0 且已有旧进度则忽略（防止覆写）。
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

    /**
     * 切换前被换掉的那一项的精确进度写入缓存。
     * 因为在 seek 到另一个条目后，player.currentMediaItem 已指向新项，常规读取抓不到旧项位置；
     * 而切换前的 [oldPosition] 仍记录着旧项的 mediaItemIndex 与 positionMs，据此精确保存。
     */
    private fun cacheOldPosition(oldPosition: Player.PositionInfo) {
        val player = mediaSession?.player ?: return
        if (oldPosition.positionMs <= 0) return
        if (oldPosition.mediaItemIndex !in 0 until player.currentTimeline.windowCount) return
        val window = Timeline.Window()
        player.currentTimeline.getWindow(oldPosition.mediaItemIndex, window)
        val uri = window.mediaItem.localConfiguration?.uri?.toString() ?: return
        progressCache[uri] = oldPosition.positionMs
    }

    /**
     * 尝试持久化当前进度。
     * 若快照未变化且没有待删除项，则跳过（避免空闲时的磁盘 IO）。
     * @param sync true 用 commit 同步落盘，false 用 apply 异步落盘
     */
    private fun persistProgress(sync: Boolean = false) {
        val player = mediaSession?.player ?: return
        cacheCurrentPosition(player)
        val snapshot = progressCache.toMap()
        if (snapshot == lastPersistedSnapshot && removedUris.isEmpty()) return
        writeToDisk(snapshot, sync)
    }

    /**
     * 把进度写入 SharedPreferences。
     *
     * 注意这是「合并」逻辑而非「追加」逻辑：
     * - 先移除 [removedUris] 中记录（播放到末尾/被删除的项）
     * - 再写入进度，且只写入大于 0 的值（0 不得覆盖已有非零进度的硬约束）
     * - 同步更新 playlist 中各条目的 lastPosition
     */
    private fun writeToDisk(progress: Map<String, Long>, sync: Boolean = true) {
        val prefs = playerPrefs
        // 读出磁盘进度 → 剔除待删除项 → 合并本次进度(仅>0)，保证不覆盖非零旧值
        val merged = mergeProgress(prefs, progress, removedUris)
        val editor = prefs.edit().putString("progress", writeProgressMap(merged))
        if (sync) editor.apply() else editor.apply()
        // 3. 同步更新 playlist 中各条目的 lastPosition，保证前端与磁盘一致
        val playlistJson = prefs.getString("playlist", null)
        if (playlistJson != null) {
            try {
                val arr = org.json.JSONArray(playlistJson)
                val newArr = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val fileUri = obj.getString("uri")
                    obj.put("lastPosition", merged[fileUri] ?: 0L)
                    newArr.put(obj)
                }
                val playlistEditor = prefs.edit().putString("playlist", newArr.toString())
                if (sync) playlistEditor.apply() else playlistEditor.apply()
            } catch (_: Exception) {
            }
        }
        removedUris.clear()
        lastPersistedSnapshot = progress
    }

    /** 内存不足时立即落盘，避免进度丢失 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        persistProgress()
    }

    override fun onDestroy() {
        // 移除定时任务并最终落盘
        mainHandler.removeCallbacks(progressTicker)
        persistProgress(sync = true)
        // 释放播放器与会话
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
         * 供前端在「切到别的条目」之前调用，把当前正在播放项的精确进度立即落盘。
         * 背景：onMediaItemTransition 触发时播放器已经切到新条目，此时按 currentItem 读取
         * 拿到的是新条目位置，旧的正在播放项进度会因此丢失（只能靠 2 秒周期上报兜底）。
         * 因此在 seekTo 切换前先同步抓取当前项位置并立即持久化。
         */
        fun flushCurrentPosition() {
            val svc = instance ?: return
            val player = svc.mediaSession?.player ?: return
            svc.cacheCurrentPosition(player)
            svc.persistProgress(sync = true)
        }

        /** 从 SharedPreferences 读出《uri -> 播放进度毫秒》映射 */
        fun readProgressMap(prefs: SharedPreferences): Map<String, Long> {
            val json = prefs.getString("progress", null) ?: return emptyMap()
            val map = mutableMapOf<String, Long>()
            try {
                val obj = JSONObject(json)
                for (key in obj.keys()) {
                    map[key] = obj.getLong(key)
                }
            } catch (_: Exception) {
            }
            return map
        }

        /** 把进度映射序列化为 JSON 字符串以便存储 */
        fun writeProgressMap(map: Map<String, Long>): String {
            val obj = JSONObject()
            for ((k, v) in map) {
                obj.put(k, v)
            }
            return obj.toString()
        }

        /**
         * 供外部(如 MainActivity)对进度做「合并非覆盖」式批量写入并落盘。
         * 与 [writeToDisk] 采用同一规则：只写入 >0 的值，0 不覆盖已有非零进度。
         * 统一两侧的写入来源，避免前端与后端各自维护的进度互相覆盖。
         */
        fun writeProgressBatch(
            prefs: SharedPreferences,
            writes: Map<String, Long>,
            removes: Set<String> = emptySet()
        ) {
            prefs.edit { putString("progress", writeProgressMap(mergeProgress(prefs, writes, removes))) }
        }
    }
}

/**
 * 进度合并公共逻辑：读磁盘已有进度 → 剔除 removes 中条目 → 合并 writes(仅 >0)。
 * 与 [PlayerService.writeToDisk] 共用同一「合并非覆盖 + 0 值不覆盖」语义。
 */
private fun mergeProgress(
    prefs: SharedPreferences,
    writes: Map<String, Long>,
    removes: Set<String>
): Map<String, Long> {
    val merged = PlayerService.readProgressMap(prefs).toMutableMap()
    for (uri in removes) merged.remove(uri)
    for ((k, v) in writes) {
        if (v > 0) merged[k] = v
    }
    return merged
}