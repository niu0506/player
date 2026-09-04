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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

// ==================== 全屏与画中画控制 ====================

/** 全屏与画中画（PiP）切换：顶栏/列表显隐、横竖屏、沉浸式系统栏、PiP 宽高比自适应 */
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

    /** PiP 模式变化：调整 UI 显隐与视频表面尺寸 */
    fun onPipModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        setChromeVisible(!isInPipMode && !isFullscreen)
        binding.playerView.useController = !isInPipMode
        applyPipVideoSurface(isInPictureInPictureMode)
    }

    /** 顶栏与播放列表面板的显隐切换 */
    private fun setChromeVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.toolbarLayout.visibility = visibility
        binding.playlistContainer.visibility = visibility
    }

    /** 依据是否 PiP / 全屏调整视频容器样式 */
    private fun applyPipVideoSurface(inPip: Boolean) {
        when {
            // PiP：ZOOM 填满小窗
            inPip -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            // 全屏：FIT 保留完整画面，超出留黑边
            isFullscreen -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            else -> applyNormalVideoSurfaceStyle()
        }
    }

    /** 全屏/PiP 共用样式：无圆角无边距 + 指定缩放模式 */
    private fun applyEdgeToEdgeSurface(resizeMode: Int) {
        binding.playerView.setResizeMode(resizeMode)
        binding.playerCard.radius = 0f
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins(0, 0, 0, 0)
        binding.playerCard.layoutParams = lp
    }

    /** 普通非全屏、非 PiP 的卡片样式：FIT + 12dp 边距 + 18dp 圆角 */
    private fun applyNormalVideoSurfaceStyle() {
        binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        val m = (12 * activity.resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m / 3)
        binding.playerCard.radius = 18 * activity.resources.displayMetrics.density
        binding.playerCard.layoutParams = lp
    }

    /** 切换全屏：显隐顶栏与列表、横竖屏、系统栏 */
    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        if (enabled) {
            setChromeVisible(false)
            applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
        } else {
            setChromeVisible(true)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
            applyNormalVideoSurfaceStyle()
        }
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

    /** 进入 PiP 小窗，失败时回退并提示 */
    fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(activity, "系统不支持小窗播放", Toast.LENGTH_SHORT).show()
            return
        }
        val vs = controllerProvider()?.videoSize
        if (vs == null || vs.width <= 0) {
            Toast.makeText(activity, "当前没有正在播放的视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (isFullscreen) {
            setFullscreen(false)
        }
        setChromeVisible(false)
        binding.playerView.useController = false
        applyPipVideoSurface(true)
        try {
            // 以视频宽高比作为小窗比例，解析失败时回退 16:9
            val ratio = try {
                Rational(vs.width, vs.height)
            } catch (_: Exception) {
                Rational(16, 9)
            }
            if (activity.enterPictureInPictureMode(buildPipParams(ratio))) return
        } catch (_: Exception) {
        }
        // 进入失败：恢复原有 UI
        setChromeVisible(true)
        binding.playerView.useController = true
        applyPipVideoSurface(false)
        Toast.makeText(activity, "无法进入小窗模式", Toast.LENGTH_SHORT).show()
    }

    /** 构建指定宽高比的 PiP 参数；seamless resize 仅 API 31+ 支持 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(ratio: Rational): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** PiP 中视频分辨率变化时同步更新小窗比例，避免黑边 */
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

    /** onStop 时若处于 PiP：暂停播放并复位状态 */
    fun onStopped() {
        if (isInPipMode) {
            controllerProvider()?.pause()
            isInPipMode = false
        }
    }
}

// ==================== 播放器手势控制 ====================

/**
 * 播放器手势控制：单击切换控制栏，双击快退/快进 15 秒，
 * 横滑进度（实时预览），左半屏纵滑调亮度、右半屏调音量。
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { overlay.visibility = View.GONE }
    private var gestureMode = GESTURE_NONE
    private var seekStartPosition = 0L
    private var seekTargetPosition = 0L
    private var brightnessStart = 0f
    private var volumeStart = 0
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            // 按下即消费，保证后续手势均由此处理
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val ctrl = controllerProvider() ?: return true
                if (e.x < playerView.width / 2f) {
                    if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
                        ctrl.seekBack()
                        showGestureOverlay("快退 15 秒")
                    }
                } else {
                    if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
                        ctrl.seekForward()
                        showGestureOverlay("快进 15 秒")
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float
            ): Boolean {
                val start = e1 ?: return false
                val ctrl = controllerProvider() ?: return false
                // 手势刚开始时确定模式
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
                            gestureMode = GESTURE_NONE
                            return false
                        }
                        val msPerPx = max(300f, duration / playerView.width * 1.2f)
                        val target = (seekStartPosition + (e2.x - start.x) * msPerPx)
                            .toLong().coerceIn(0L, duration)
                        seekTargetPosition = target
                        val deltaSec = (target - seekStartPosition) / 1000
                        val action = if (deltaSec >= 0) "快进" else "快退"
                        showGestureOverlay(
                            "$action ${abs(deltaSec)} 秒\n" +
                                "${formatTime(target)} / ${formatTime(duration)}"
                        )
                    }
                    GESTURE_BRIGHTNESS -> {
                        val delta = (start.y - e2.y) / playerView.height
                        applyBrightness(brightnessStart + delta)
                        showGestureOverlay("亮度 ${(currentBrightness() * 100).toInt()}%")
                    }
                    GESTURE_VOLUME -> {
                        val maxVolume =
                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta =
                            ((start.y - e2.y) / playerView.height * maxVolume).toInt()
                        val target = (volumeStart + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        val pct = if (maxVolume > 0) target * 100 / maxVolume else 0
                        showGestureOverlay("音量 $pct%")
                    }
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            // 抬起/取消时结束手势，seek 手势跳转到目标位置
            if (event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endGesture()
            }
            true
        }
    }

    /** 取消尚未执行的浮层隐藏任务（onStop 中调用，避免泄漏） */
    fun cancelPendingHide() {
        handler.removeCallbacks(hideOverlayRunnable)
    }

    private fun endGesture() {
        if (gestureMode == GESTURE_SEEK) {
            controllerProvider()?.seekTo(seekTargetPosition)
        }
        gestureMode = GESTURE_NONE
    }

    /** 当前窗口亮度（跟随系统时返回 0.5） */
    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        return if (b < 0f) 0.5f else b
    }

    private fun applyBrightness(value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        activity.window.attributes = lp
    }

    /** 显示手势提示浮层，800ms 后自动隐藏 */
    private fun showGestureOverlay(text: String) {
        handler.removeCallbacks(hideOverlayRunnable)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.postDelayed(hideOverlayRunnable, 800)
    }
}

// ==================== 播放列表适配器 ====================

/**
 * 播放列表适配器。交互回调回传条目 uri 而非下标：
 * items 经 submitList 异步 diff 后才回写，存在下标错位窗口，uri 是稳定身份。
 */
class MediaListAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<MediaListAdapter.VH>() {

    private val items = mutableListOf<MediaItemData>()
    /** 各条目进度（uri -> 毫秒），独立于条目数据，跨 submitList 存活 */
    private val progressMap = mutableMapOf<String, Long>()
    private var currentPlayingIndex = -1
    private var isPlaying = false
    private val diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var diffJob: Job? = null

    /**
     * 用新列表刷新数据。DiffUtil 在后台线程计算、结果回主线程 dispatch，
     * 避免大列表在主线程同步计算掉帧。
     */
    fun submitList(list: List<MediaItemData>) {
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
                if (myJob?.isActive != true) return@withContext // 已被更新的提交取消，丢弃结果
                items.clear()
                items.addAll(list)
                diff.dispatchUpdatesTo(this@MediaListAdapter)
            }
        }
    }

    /**
     * 设置当前播放项，只刷新新旧两个位置。
     * 传入下标来自 ExoPlayer 队列，items 可能尚未跟上 diff 回写，
     * 因此与 updateDuration/updateProgress 一样做双向边界检查，越界时仅记录状态。
     */
    fun setCurrentPlaying(index: Int, playing: Boolean = false) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        isPlaying = playing
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    /** 更新某项时长（仅原先为 0 时写入） */
    fun updateDuration(index: Int, duration: Long) {
        if (index in items.indices && items[index].duration == 0L) {
            items[index] = items[index].copy(duration = duration)
            notifyItemChanged(index)
        }
    }

    /** 批量同步进度（冷启动/回前台刷进列表），只收录 >0 的有效值 */
    fun setProgress(progress: Map<String, Long>) {
        progressMap.clear()
        for ((uri, pos) in progress) {
            if (pos > 0) progressMap[uri] = pos
        }
    }

    /** 更新某项进度并刷新该项 */
    fun updateProgress(index: Int, position: Long) {
        if (index in items.indices) {
            progressMap[items[index].uri.toString()] = position
            notifyItemChanged(index)
        }
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
        val isActive = position == currentPlayingIndex && isPlaying
        holder.binding.imgPlaying.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.binding.tvIndex.visibility = if (isActive) View.GONE else View.VISIBLE
        holder.binding.tvName.setTextColor(
            context.getColor(if (isActive) R.color.accent else R.color.text_primary)
        )

        // 有进度时展示进度条；播放中的项显示「已播时长」，否则显示「已播/总时长」
        val progress = progressMap[item.uri.toString()] ?: 0L
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

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        diffScope.cancel()
    }

    /** inner 以便在 init 中绑定一次点击监听，避免每次绑定重建 lambda */
    inner class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        /** 取点击位置对应条目的 uri；位置无效（含 NO_POSITION）时返回 null */
        private fun uriAt(position: Int): String? =
            if (position == RecyclerView.NO_POSITION) null
            else items.getOrNull(position)?.uri?.toString()

        init {
            binding.itemRoot.setOnClickListener { uriAt(bindingAdapterPosition)?.let(onClick) }
            binding.btnDelete.setOnClickListener { uriAt(bindingAdapterPosition)?.let(onDelete) }
        }
    }
}
