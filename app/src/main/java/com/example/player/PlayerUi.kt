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

/**
 * 全屏与画中画（PiP）辅助类。
 *
 * 统一管理界面在“普通 / 全屏 / 画中画”三种状态下的视觉表现：
 * 控制工具栏与列表显隐、系统栏显隐、屏幕方向、播放画面缩放模式与圆角等。
 */
@OptIn(UnstableApi::class)
class FullscreenPipHelper(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val controllerProvider: () -> MediaController?
) {
    /** 是否处于全屏模式。 */
    var isFullscreen = false
        private set

    /** 是否处于画中画模式。 */
    var isInPipMode = false
        private set

    /** 画中画模式切换回调：更新视觉状态。 */
    fun onPipModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        applyVisualState()
    }

    /** 控制工具栏与播放列表容器的显隐。 */
    private fun setChromeVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.toolbarLayout.visibility = visibility
        binding.playlistContainer.visibility = visibility
    }

    /** 根据当前模式统一应用界面样式。 */
    private fun applyVisualState() {
        setChromeVisible(!isInPipMode && !isFullscreen)
        binding.playerView.useController = !isInPipMode
        when {
            isInPipMode -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            isFullscreen -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            else -> applyNormalVideoSurfaceStyle()
        }
    }

    /** 进入画中画前的过渡状态：隐藏控件、铺满屏幕（避免切换闪烁）。 */
    private fun applyPipPreparingState() {
        setChromeVisible(false)
        binding.playerView.useController = false
        applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    }

    /** 让播放画面边到边铺满：无圆角、无外边距。 */
    private fun applyEdgeToEdgeSurface(resizeMode: Int) {
        binding.playerView.setResizeMode(resizeMode)
        binding.playerCard.radius = 0f
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins(0, 0, 0, 0)
        binding.playerCard.layoutParams = lp
    }

    /** 普通模式下的播放画面样式：固定比例、带外边距与圆角。 */
    private fun applyNormalVideoSurfaceStyle() {
        binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        val m = (12 * activity.resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m / 3)
        binding.playerCard.radius = 18 * activity.resources.displayMetrics.density
        binding.playerCard.layoutParams = lp
    }

    /** 切换全屏：进入时锁定横屏并隐藏系统栏，退出时恢复方向与系统栏。 */
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

    /** 隐藏系统栏（沉浸式，滑动可临时唤出）。 */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val insetsController = WindowInsetsControllerCompat(activity.window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 显示系统栏。 */
    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        WindowInsetsControllerCompat(activity.window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** 正在播放视频时自动进入画中画（用于 onUserLeaveHint）。 */
    fun enterPipWhenPlayingVideo(): Boolean {
        val ctrl = controllerProvider() ?: return false
        return !(!ctrl.isPlaying || !hasVideo(ctrl.videoSize)) && enterPipMode(showError = false)
    }

    /** 进入画中画模式，返回是否成功。 */
    fun enterPipMode(showError: Boolean = true): Boolean {
        // Android 8.0 以下不支持画中画
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (showError) {
                Toast.makeText(activity, R.string.toast_pip_unsupported, Toast.LENGTH_SHORT).show()
            }
            return false
        }
        // 无视频画面时不进入画中画
        val vs = controllerProvider()?.videoSize
        if (vs == null || !hasVideo(vs)) {
            if (showError) {
                Toast.makeText(activity, R.string.toast_pip_no_video, Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (isFullscreen) {
            setFullscreen(false)
        }
        applyPipPreparingState()
        try {

            // 画中画窗口比例：按视频宽高比，异常时回退为 16:9
            val ratio = try {
                Rational(vs.width, vs.height)
            } catch (_: Exception) {
                Rational(16, 9)
            }
            if (activity.enterPictureInPictureMode(buildPipParams(ratio))) return true
        } catch (_: Exception) {
        }
        // 进入失败：恢复原视觉状态并提示
        applyVisualState()
        if (showError) {
            Toast.makeText(activity, R.string.toast_pip_failed, Toast.LENGTH_SHORT).show()
        }
        return false
    }

    /** 视频尺寸是否有效（宽高均大于 0）。 */
    private fun hasVideo(videoSize: VideoSize): Boolean =
        videoSize.width > 0 && videoSize.height > 0

    /** 构建画中画参数（比例，Android 12+ 开启无缝缩放）。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(ratio: Rational): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** 画中画模式下根据视频尺寸更新窗口比例。 */
    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipAspectRatio(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        if (!isInPipMode) return
        val ratio = try {
            Rational(videoSize.width, videoSize.height)
        } catch (_: Exception) {
            return
        }
        try {
            activity.setPictureInPictureParams(buildPipParams(ratio))
        } catch (_: Exception) {
        }
    }

    /** 界面停止时：若在画中画中则暂停播放并退出画中画标记。 */
    fun onStopped() {
        if (isInPipMode) {
            controllerProvider()?.pause()
            isInPipMode = false
        }
    }
}

/**
 * 手势控制器。
 *
 * 支持：单击切换控件显隐、双击左右两侧快退/快进、左右滑动拖动进度、
 * 左侧上下滑动调节亮度、右侧上下滑动调节音量，并显示短暂的手势提示浮层。
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
        const val BRIGHTNESS_PREFS = "gesture_settings"
        const val KEY_BRIGHTNESS = "manual_brightness"
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 亮度偏好存储，用于记住用户手动设置的亮度。 */
    private val brightnessPrefs by lazy {
        activity.getSharedPreferences(BRIGHTNESS_PREFS, Context.MODE_PRIVATE)
    }
    private val hideOverlayRunnable = Runnable { overlay.visibility = View.GONE }

    /** 当前手势模式（无/进度/亮度/音量）。 */
    private var gestureMode = GESTURE_NONE
    private var seekStartPosition = 0L
    private var seekTargetPosition = 0L
    private var brightnessStart = 0f
    private var volumeStart = 0
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** 初始化手势监听：应用已保存的亮度，并安装触摸/手势检测。 */
    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        // 恢复上次手动设置的亮度
        if (brightnessPrefs.contains(KEY_BRIGHTNESS)) {
            applyBrightness(brightnessPrefs.getFloat(KEY_BRIGHTNESS, 0.5f), persist = false)
        }
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            /** 单击：切换播放控件的显示/隐藏。 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            /** 双击：左半屏快退、右半屏快进。 */
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

            /** 滑动：首次滑动时判定方向与区域（进度/亮度/音量），随后持续调整。 */
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float
            ): Boolean {
                val start = e1 ?: return false
                val ctrl = controllerProvider() ?: return false

                // 手势开始：根据水平/垂直位移及起手位置确定模式
                if (gestureMode == GESTURE_NONE) {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                    }
                    gestureMode = if (abs(e2.x - start.x) > abs(e2.y - start.y)) {
                        // 横向为主 -> 拖动进度
                        seekStartPosition = ctrl.currentPosition
                        seekTargetPosition = seekStartPosition
                        GESTURE_SEEK
                    } else if (start.x < playerView.width / 2f) {
                        // 左半屏纵向 -> 亮度
                        brightnessStart = currentBrightness()
                        GESTURE_BRIGHTNESS
                    } else {
                        // 右半屏纵向 -> 音量
                        volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        GESTURE_VOLUME
                    }
                }
                when (gestureMode) {
                    GESTURE_SEEK -> {
                        val duration = ctrl.duration
                        if (duration == C.TIME_UNSET || duration <= 0) {
                            gestureMode = GESTURE_NONE
                            return false
                        }
                        // 每像素对应的毫秒数，限制最小灵敏度
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
                        // 向上滑增大亮度
                        val delta = (start.y - e2.y) / playerView.height
                        applyBrightness(brightnessStart + delta)
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
                            ((start.y - e2.y) / playerView.height * maxVolume).toInt()
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

            // 手指抬起/取消时结束手势（拖动进度在此刻才真正 seek）
            if (event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endGesture()
            }
            true
        }
    }

    /** 取消待执行的浮层隐藏任务。 */
    fun cancelPendingHide() {
        handler.removeCallbacks(hideOverlayRunnable)
    }

    /** 结束手势：若为进度手势则执行 seek。 */
    private fun endGesture() {
        if (gestureMode == GESTURE_SEEK) {
            controllerProvider()?.seekTo(seekTargetPosition)
        }
        gestureMode = GESTURE_NONE
    }

    /** 获取当前窗口亮度（系统未设置时为 0.5）。 */
    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        return if (b < 0f) 0.5f else b
    }

    /** 应用窗口亮度（限制在 0.02~1.0），可选持久化。 */
    private fun applyBrightness(value: Float, persist: Boolean = true) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        activity.window.attributes = lp
        if (persist) brightnessPrefs.edit {putFloat(KEY_BRIGHTNESS, lp.screenBrightness) }
    }

    /** 显示手势提示浮层，800ms 后自动隐藏。 */
    private fun showGestureOverlay(text: String) {
        handler.removeCallbacks(hideOverlayRunnable)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.postDelayed(hideOverlayRunnable, 800)
    }
}

/**
 * 播放列表适配器：展示媒体名称、时长、播放进度，并标记当前正在播放项。
 * 列表差异计算放到后台线程执行，避免大数据量时的卡顿。
 */
class MediaListAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val getProgress: (String) -> Long = { 0L }
) : RecyclerView.Adapter<MediaListAdapter.VH>() {

    private val progressPayload = Any()

    private val items = mutableListOf<MediaItemData>()

    /** 当前正在播放的 uri。 */
    private var currentPlayingUri: String? = null
    private var isPlaying = false
    private var diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var diffJob: Job? = null

    /** 提交新列表：在后台计算差异后回到主线程分发更新。 */
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
                // 若期间有新任务提交，则放弃本次结果
                if (myJob?.isActive != true) return@withContext
                items.clear()
                items.addAll(list)
                diff.dispatchUpdatesTo(this@MediaListAdapter)
            }
        }
    }

    /** 查找某个 uri 在列表中的位置，找不到返回 -1。 */
    private fun indexOfUri(uri: String?): Int =
        if (uri == null) -1 else items.indexOfFirst { it.uri.toString() == uri }

    /** 更新当前播放项与播放状态，并刷新受影响的行。 */
    fun setCurrentPlaying(uri: String?, playing: Boolean = false) {
        val old = currentPlayingUri
        currentPlayingUri = uri
        isPlaying = playing
        if (old == uri) {
            val idx = indexOfUri(uri)
            if (idx >= 0) notifyItemChanged(idx)
            return
        }
        val oldIdx = indexOfUri(old)
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        val newIdx = indexOfUri(uri)
        if (newIdx >= 0) notifyItemChanged(newIdx)
    }

    /** 刷新指定行的进度显示。 */
    fun refreshProgress(index: Int) {
        if (index in items.indices) notifyItemChanged(index, progressPayload)
    }

    /** 刷新全部行的进度显示。 */
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
        holder.binding.tvDuration.text = if (item.duration > 0) formatTime(item.duration) else ""
        // 当前正在播放项高亮显示（名称变色 + 显示播放图标）
        val isActive = item.uri.toString() == currentPlayingUri && isPlaying
        holder.binding.imgPlaying.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.binding.tvIndex.visibility = if (isActive) View.GONE else View.VISIBLE
        holder.binding.tvName.setTextColor(
            context.getColor(if (isActive) R.color.accent else R.color.text_primary)
        )

        bindProgress(holder, item)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === progressPayload }) {
            bindProgress(holder, items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    /** 仅更新进度相关视图，供 progress payload 使用。 */
    private fun bindProgress(holder: VH, item: MediaItemData) {
        val context = holder.itemView.context
        val isActive = item.uri.toString() == currentPlayingUri && isPlaying
        // 展示观看进度条与进度文本
        val progress = getProgress(item.uri.toString())
        if (progress > 0 && item.duration > 0) {
            val percent = (progress * 100 / item.duration).toInt().coerceIn(0, 100)
            holder.binding.progressRow.visibility = View.VISIBLE
            holder.binding.pbItem.progress = percent
            holder.binding.tvProgress.visibility = View.VISIBLE
            if (isActive) {
                // 正在播放：只展示当前进度
                holder.binding.tvProgress.text = context.getString(
                    R.string.playing_progress, formatTime(progress)
                )
            } else {
                // 未播放：展示“进度 / 总时长”
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
        // 曾被 detach 取消过的作用域在此重建，保证后续 submitList 的差异计算仍能执行
        if (!diffScope.isActive) {
            diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        // 解绑时取消差异计算协程，避免泄漏
        diffScope.cancel()
    }

    inner class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {

        /** 安全获取某位置对应的 uri（处理 NO_POSITION 越界）。 */
        private fun uriAt(position: Int): String? =
            if (position == RecyclerView.NO_POSITION) null
            else items.getOrNull(position)?.uri?.toString()

        init {
            // 点击条目：播放；点击删除按钮：删除
            binding.itemRoot.setOnClickListener { uriAt(bindingAdapterPosition)?.let(onClick) }
            binding.btnDelete.setOnClickListener { uriAt(bindingAdapterPosition)?.let(onDelete) }
        }
    }
}
