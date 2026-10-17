/**
 * 界面组件集合（与业务无关的纯 UI 逻辑）：
 * - [FullscreenPipHelper]：全屏 / 画中画切换与系统栏、视频表面样式管理；
 * - [GestureController]：播放器手势（单击显隐控制栏、双击快退快进、横滑快进快退、上下滑调亮度 / 音量）；
 * - [MediaListAdapter]：播放列表 RecyclerView 适配器（DiffUtil 后台差分刷新）。
 */
package com.example.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.player.databinding.ActivityMainBinding
import com.example.player.databinding.ItemMediaBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import androidx.core.content.edit

/** 统一的短提示入口。 */
internal fun Context.toast(@StringRes resId: Int, vararg args: Any?) {
    Toast.makeText(this, getString(resId, *args), Toast.LENGTH_SHORT).show()
}

/** 统一的长提示入口。 */
internal fun Context.toastLong(@StringRes resId: Int, vararg args: Any?) {
    Toast.makeText(this, getString(resId, *args), Toast.LENGTH_LONG).show()
}

/** 已拼好的文案直接提示。 */
internal fun Context.toastMessage(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * 全屏与画中画（PiP）辅助类：统一管理视频表面样式、系统栏显隐与方向。
 *
 * @param controllerProvider 惰性获取当前 MediaController（可能为 null）
 */
@OptIn(UnstableApi::class)
class FullscreenPipHelper(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val controllerProvider: () -> MediaController?
) {
    var isFullscreen = false
        private set

    var isInPipMode = false
        private set

    /** 系统 PiP 模式切换回调：退出 PiP 且非全屏时恢复系统栏。 */
    fun onPipModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !isFullscreen) {
            showSystemBars()
        }
        applyVisualState()
    }

    /** 控制标题栏与播放列表容器的显隐。 */
    private fun setChromeVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.toolbarLayout.visibility = visibility
        binding.playlistContainer.visibility = visibility
    }

    /** 根据当前（普通 / 全屏 / PiP）状态统一套用界面样式。 */
    private fun applyVisualState(pipPreparing: Boolean = false) {
        val pipLike = isInPipMode || pipPreparing
        setChromeVisible(!pipLike && !isFullscreen)
        binding.playerView.useController = !pipLike
        when {
            // PiP（或进入前的过渡态）：按真实比例缩放铺满小窗
            pipLike -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            // 全屏：等比放大铺满，无黑边
            isFullscreen -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            else -> applyNormalVideoSurfaceStyle()
        }
    }

    /** 边到边视频表面：去除卡片圆角与边距。 */
    private fun applyEdgeToEdgeSurface(resizeMode: Int) {
        binding.playerView.setResizeMode(resizeMode)
        binding.playerCard.radius = 0f
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins(0, 0, 0, 0)
        binding.playerCard.layoutParams = lp
    }

    /** 普通内嵌样式：恢复卡片圆角与内边距。 */
    private fun applyNormalVideoSurfaceStyle() {
        binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        val m = (12 * activity.resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m / 3)
        binding.playerCard.radius = 18 * activity.resources.displayMetrics.density
        binding.playerCard.layoutParams = lp
    }

    /** 切换全屏（横屏 + 隐藏系统栏）。 */
    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
        }
        applyVisualState()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val insetsController = WindowInsetsControllerCompat(activity.window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        WindowInsetsControllerCompat(activity.window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** 仅在正在播放视频时自动进入 PiP（Home 键触发），失败不提示。 */
    fun enterPipWhenPlayingVideo(): Boolean {
        val ctrl = controllerProvider() ?: return false
        return !(!ctrl.isPlaying || !hasVideo(ctrl.videoSize)) && enterPipMode(showError = false)
    }

    /**
     * 尝试进入 PiP 模式，按视频真实宽高比设置小窗比例。
     * @param showError 失败时是否 Toast 提示
     */
    fun enterPipMode(showError: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (showError) activity.toast(R.string.toast_pip_unsupported)
            return false
        }
        val vs = controllerProvider()?.videoSize
        if (vs == null || !hasVideo(vs)) {
            if (showError) activity.toast(R.string.toast_pip_no_video)
            return false
        }
        val wasFullscreen = isFullscreen
        if (wasFullscreen) setFullscreen(false)
        applyVisualState(pipPreparing = true)
        try {
            val ratio = aspectRatioOf(vs) ?: Rational(16, 9)
            if (activity.enterPictureInPictureMode(buildPipParams(ratio))) return true
        } catch (_: Exception) {
        }
        // 进入失败：回滚全屏状态与界面样式
        if (wasFullscreen) setFullscreen(true) else applyVisualState()
        if (showError) activity.toast(R.string.toast_pip_failed)
        return false
    }

    private fun hasVideo(videoSize: VideoSize): Boolean =
        videoSize.width > 0 && videoSize.height > 0

    /** 视频尺寸转系统比例；非法尺寸返回 null。 */
    private fun aspectRatioOf(videoSize: VideoSize): Rational? =
        try {
            Rational(videoSize.width, videoSize.height)
        } catch (_: Exception) {
            null
        }

    /** 构造 PiP 参数；API 31+ 开启无缝缩放。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(ratio: Rational): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** 视频尺寸变化时更新 PiP 小窗比例（仅 PiP 中生效）。 */
    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipAspectRatio(videoSize: VideoSize) {
        if (!isInPipMode || !hasVideo(videoSize)) return
        val ratio = aspectRatioOf(videoSize) ?: return
        try {
            activity.setPictureInPictureParams(buildPipParams(ratio))
        } catch (_: Exception) {
        }
    }

    /** Activity onStop：若仍在 PiP 则暂停并退出 PiP 标记。 */
    fun onStopped() {
        if (isInPipMode) {
            controllerProvider()?.pause()
            isInPipMode = false
        }
    }
}

/**
 * 播放器手势控制。
 *
 * 手势：
 * - 单击 → 显隐控制栏；
 * - 双击左半屏 → 快退 15s；双击右半屏 → 快进 15s；
 * - 横向滑动（滑动结束时才 seek）→ 快进/快退，浮层实时预览目标进度；
 * - 左半屏上下滑动 → 亮度；右半屏上下滑动 → 音量。
 *
 * @param overlay 手势反馈浮层
 * @param controllerProvider 惰性获取当前 MediaController
 */
@OptIn(UnstableApi::class)
class GestureController(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val overlay: TextView,
    private val controllerProvider: () -> MediaController?
) {
    private companion object {
        const val GESTURE_NONE = 0
        const val GESTURE_SEEK = 1
        const val GESTURE_BRIGHTNESS = 2
        const val GESTURE_VOLUME = 3

        /** 时长未知等无法处理的滑动：本轮手势忽略。 */
        const val GESTURE_IGNORED = 4
        const val BRIGHTNESS_PREFS = "gesture_settings"
        const val KEY_BRIGHTNESS = "manual_brightness"
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 记忆用户手动设置的亮度（退出重进后沿用）。 */
    private val brightnessPrefs by lazy {
        activity.getSharedPreferences(BRIGHTNESS_PREFS, Context.MODE_PRIVATE)
    }
    private val hideOverlayRunnable = Runnable { overlay.visibility = View.GONE }

    private var gestureMode = GESTURE_NONE
    private var seekStartPosition = 0L
    private var seekTargetPosition = 0L
    private var brightnessStart = 0f
    private var volumeStart = 0
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** 安装手势检测器与触摸监听。 */
    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        // 恢复上次的亮度设置
        if (brightnessPrefs.contains(KEY_BRIGHTNESS)) {
            applyBrightness(brightnessPrefs.getFloat(KEY_BRIGHTNESS, 0.5f), persist = false)
        }
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            // 单击：显隐控制栏
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            // 双击：左快退 / 右快进 15s
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val ctrl = controllerProvider() ?: return true
                if (e.x < playerView.width / 2f) {
                    if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
                        ctrl.seekBack()
                        showGestureOverlay(activity.getString(R.string.gesture_seek_back))
                    }
                } else {
                    if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
                        ctrl.seekForward()
                        showGestureOverlay(activity.getString(R.string.gesture_seek_forward))
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float
            ): Boolean {
                val start = e1 ?: return false
                val ctrl = controllerProvider() ?: return false

                // 首次滑动：按主轴方向与起点位置判定本次手势类型
                if (gestureMode == GESTURE_NONE) {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                    }
                    gestureMode = if (abs(e2.x - start.x) > abs(e2.y - start.y)) {
                        seekStartPosition = ctrl.currentPosition
                        seekTargetPosition = seekStartPosition
                        GESTURE_SEEK
                    } else if (start.x < playerView.width / 2f) {
                        brightnessStart = currentBrightness()
                        GESTURE_BRIGHTNESS
                    } else {
                        volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        GESTURE_VOLUME
                    }
                }
                when (gestureMode) {
                    GESTURE_SEEK -> {
                        val duration = ctrl.duration
                        if (duration == C.TIME_UNSET || duration <= 0) {
                            gestureMode = GESTURE_IGNORED
                            return true
                        }
                        // 每秒对应的像素：让整屏滑动约等于 1.2 倍时长
                        val msPerPx = max(300f, duration / playerView.width * 1.2f)
                        val target = (seekStartPosition + (e2.x - start.x) * msPerPx)
                            .toLong().coerceIn(0L, duration)
                        seekTargetPosition = target
                        val deltaSec = (target - seekStartPosition) / 1000
                        val actionRes = if (deltaSec >= 0) R.string.gesture_seek_forward_action
                        else R.string.gesture_seek_back_action
                        showGestureOverlay(
                            activity.getString(
                                R.string.gesture_seek_text,
                                activity.getString(actionRes),
                                abs(deltaSec),
                                formatTime(target),
                                formatTime(duration)
                            )
                        )
                    }
                    GESTURE_BRIGHTNESS -> {
                        val delta = (start.y - e2.y) / playerView.height.coerceAtLeast(1)
                        applyBrightness(brightnessStart + delta, persist = false)
                        showGestureOverlay(
                            activity.getString(
                                R.string.gesture_brightness, (currentBrightness() * 100).toInt()
                            )
                        )
                    }
                    GESTURE_VOLUME -> {
                        val maxVolume =
                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta =
                            ((start.y - e2.y) / playerView.height.coerceAtLeast(1) * maxVolume).toInt()
                        val target = (volumeStart + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        val pct = if (maxVolume > 0) target * 100 / maxVolume else 0
                        showGestureOverlay(activity.getString(R.string.gesture_volume, pct))
                    }
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)

            // 抬手 / 取消时结束手势（seek 与亮度持久化在此提交）
            if (event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endGesture()
            }
            true
        }
    }

    /** 取消浮层的延迟隐藏（onStop 调用，避免残留回调）。 */
    fun cancelPendingHide() {
        handler.removeCallbacks(hideOverlayRunnable)
    }

    /** 手势结束：seek 落到目标位置，亮度才持久化。 */
    private fun endGesture() {
        when (gestureMode) {
            GESTURE_SEEK -> controllerProvider()?.seekTo(seekTargetPosition)
            GESTURE_BRIGHTNESS -> applyBrightness(currentBrightness(), persist = true)
        }
        gestureMode = GESTURE_NONE
    }

    /** 当前亮度；系统未手动设置过（<0）时按 0.5 处理。 */
    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        return if (b < 0f) 0.5f else b
    }

    /** 应用屏幕亮度（限制在 2%~100%），可选持久化。 */
    private fun applyBrightness(value: Float, persist: Boolean = true) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        activity.window.attributes = lp
        if (persist) brightnessPrefs.edit {putFloat(KEY_BRIGHTNESS, lp.screenBrightness) }
    }

    /** 显示手势反馈浮层，800ms 后自动隐藏。 */
    private fun showGestureOverlay(text: String) {
        handler.removeCallbacks(hideOverlayRunnable)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.postDelayed(hideOverlayRunnable, 800)
    }
}

/**
 * 播放列表适配器。
 *
 * 使用 DiffUtil 在后台线程计算差量，避免主线程卡顿；
 * 对「仅进度变化」「仅时长变化」通过 payload 做局部刷新。
 *
 * @param onClick 点击条目回调（传入 uri）
 * @param onDelete 点击删除回调（传入 uri）
 * @param getProgress 读取某 uri 当前进度的回调
 */
class MediaListAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val getProgress: (String) -> Long
) : RecyclerView.Adapter<MediaListAdapter.VH>() {

    /** 局部刷新 payload：仅更新进度，不重绑整个条目。 */
    private val progressPayload = Any()

    private val items = mutableListOf<MediaItemData>()

    private var currentPlayingUri: String? = null
    private var isPlaying = false
    private var diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var diffJob: Job? = null

    /** 提交新列表：取消上一次差分任务，后台计算后回主线程分发更新。 */
    fun submitList(list: List<MediaItemData>) {
        if (items == list) return
        val oldItems = ArrayList(items)
        diffJob?.cancel()
        diffJob = diffScope.launch {
            val myJob = coroutineContext[Job]
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition].uri == list[newItemPosition].uri
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    oldItems[oldItemPosition] == list[newItemPosition]
            })
            withContext(Dispatchers.Main) {
                // 差分期间若又提交了新列表，放弃本次结果
                if (myJob?.isActive != true) return@withContext
                items.clear()
                items.addAll(list)
                diff.dispatchUpdatesTo(this@MediaListAdapter)
            }
        }
    }

    private fun indexOfUri(uri: String?): Int =
        if (uri == null) -1 else items.indexOfFirst { it.uri.toString() == uri }

    /** 设置当前播放项 / 播放状态，并只刷新受影响的条目。 */
    fun setCurrentPlaying(uri: String?, playing: Boolean = false) {
        val old = currentPlayingUri
        val oldPlaying = isPlaying
        if (old == uri && oldPlaying == playing) return
        currentPlayingUri = uri
        isPlaying = playing
        val affected = mutableListOf<Int>()
        if (old != uri) {
            indexOfUri(old).takeIf { it >= 0 }?.let(affected::add)
        }
        indexOfUri(uri).takeIf { it >= 0 }?.let(affected::add)
        for (index in affected) notifyItemChanged(index)
    }

    /** 刷新单条进度显示。 */
    fun refreshProgress(index: Int) {
        if (index in items.indices) notifyItemChanged(index, progressPayload)
    }

    /** 刷新全部条目的进度显示。 */
    fun refreshAllProgress() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, progressPayload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.binding.tvIndex.text =
            context.getString(R.string.list_item_index, position + 1)
        holder.binding.tvName.text = item.name
        bindDuration(holder, item)
        val isActive = item.uri.toString() == currentPlayingUri && isPlaying
        // 播放中隐藏序号、显示均衡器图标并高亮名称
        holder.binding.imgPlaying.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.binding.tvIndex.visibility = if (isActive) View.GONE else View.VISIBLE
        holder.binding.tvName.setTextColor(
            context.getColor(if (isActive) R.color.accent else R.color.text_primary)
        )

        bindProgress(holder, item)
    }

    /** 带 payload 的绑定：仅进度局部刷新走快路径，其余（含时长变化）走默认重绑。 */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        when {
            payloads.isNotEmpty() && payloads.all { it === progressPayload } ->
                bindProgress(holder, items[position])
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindDuration(holder: VH, item: MediaItemData) {
        holder.binding.tvDuration.text =
            if (item.duration > 0) formatTime(item.duration) else ""
    }

    /** 绑定进度条与进度文案：播放中显示「播放中 x」，否则显示「x / 总时长」。 */
    private fun bindProgress(holder: VH, item: MediaItemData) {
        val context = holder.itemView.context
        val isActive = item.uri.toString() == currentPlayingUri && isPlaying
        val progress = getProgress(item.uri.toString())
        if (progress > 0 && item.duration > 0) {
            val percent = (progress * 100 / item.duration).toInt().coerceIn(0, 100)
            holder.binding.progressRow.visibility = View.VISIBLE
            holder.binding.pbItem.progress = percent
            holder.binding.tvProgress.visibility = View.VISIBLE
            if (isActive) {
                holder.binding.tvProgress.text = context.getString(
                    R.string.playing_progress, formatTime(progress)
                )
            } else {
                holder.binding.tvProgress.text = context.getString(
                    R.string.progress_range,
                    formatTime(progress), formatTime(item.duration)
                )
            }
        } else {
            holder.binding.progressRow.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // 重新挂载时重建差分作用域（之前可能已被取消）
        if (!diffScope.isActive) {
            diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        diffScope.cancel()
    }

    inner class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {

        private fun uriAt(position: Int): String? =
            if (position == RecyclerView.NO_POSITION) null
            else items.getOrNull(position)?.uri?.toString()

        init {
            binding.itemRoot.setOnClickListener { uriAt(bindingAdapterPosition)?.let(onClick) }
            binding.btnDelete.setOnClickListener { uriAt(bindingAdapterPosition)?.let(onDelete) }
        }
    }
}
