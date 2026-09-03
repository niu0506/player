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

/**
 * 全屏与画中画（PiP）控制器：从 MainActivity 拆出的显示模式切换逻辑。
 *
 * 职责：
 * - 全屏切换（顶栏/播放列表显隐、横竖屏、沉浸式系统栏）
 * - PiP 进入与宽高比动态调整（贴合视频分辨率，避免黑边）
 * - PiP 模式变化时的 UI 调整（视频表面尺寸、圆角、边距）
 *
 * @param activity 宿主 Activity
 * @param binding 主界面视图绑定
 * @param controllerProvider 当前已连接的 [MediaController] 提供者（可能为 null）
 */
@OptIn(UnstableApi::class)
class FullscreenPipHelper(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val controllerProvider: () -> MediaController?
) {
    /** 是否处于全屏状态 */
    var isFullscreen = false
        private set

    /** 是否处于画中画（PiP）模式 */
    var isInPipMode = false
        private set

    /** PiP 模式变化回调：根据是否在小窗/全屏调整 UI 显隐与视频表面尺寸 */
    fun onPipModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        val uiVisible = !isInPipMode && !isFullscreen
        binding.toolbarLayout.visibility = if (uiVisible) View.VISIBLE else View.GONE
        binding.playlistContainer.visibility = if (uiVisible) View.VISIBLE else View.GONE
        binding.playerView.useController = !isInPipMode
        applyPipVideoSurface(isInPictureInPictureMode)
    }

    /** 依据当前是否 PiP / 全屏，调整视频容器边距、圆角与缩放模式，避免黑边 */
    private fun applyPipVideoSurface(inPip: Boolean) {
        if (inPip) {
            // PiP：ZOOM 填满 + 无圆角无边距
            binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            binding.playerCard.radius = 0f
            val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.layoutParams = lp
        } else if (isFullscreen) {
            // 全屏：ZOOM 等比放大铺满屏幕，无圆角无边距，避免黑边
            binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            binding.playerCard.radius = 0f
            val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.layoutParams = lp
        } else {
            // 普通横屏/竖屏：加圆角与边距的卡片样式
            applyNormalVideoSurfaceStyle()
        }
    }

    /** 普通非全屏、非 PiP 的卡片样式：FIT 缩放 + 12dp 边距 + 18dp 圆角 */
    private fun applyNormalVideoSurfaceStyle() {
        binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        val m = (12 * activity.resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m / 3)
        binding.playerCard.radius = 18 * activity.resources.displayMetrics.density
        binding.playerCard.layoutParams = lp
    }

    /** 切换全屏：隐藏/显示顶栏与播放列表，横屏/竖屏，隐藏/显示系统栏 */
    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        if (enabled) {
            val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
            binding.toolbarLayout.visibility = View.GONE
            binding.playlistContainer.visibility = View.GONE
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.radius = 0f
            binding.playerCard.layoutParams = lp
            // 全屏：ZOOM 等比放大铺满屏幕，裁剪溢出部分，避免黑边
            binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
        } else {
            binding.toolbarLayout.visibility = View.VISIBLE
            binding.playlistContainer.visibility = View.VISIBLE
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
            applyNormalVideoSurfaceStyle()
        }
    }

    /** 隐藏系统状态栏/导航栏（沉浸式全屏） */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val insetsController = WindowInsetsControllerCompat(activity.window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 恢复显示系统状态栏/导航栏 */
    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        WindowInsetsControllerCompat(activity.window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** 进入画中画（PiP）小窗模式，失败时回退并提示 */
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
        // 隐藏 UI、关闭控制器、调整为 PiP 视频尺寸
        binding.toolbarLayout.visibility = View.GONE
        binding.playlistContainer.visibility = View.GONE
        binding.playerView.useController = false
        applyPipVideoSurface(true)
        try {
            // 以视频宽高比作为小窗比例，解析失败时回退 16:9
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(try {
                    Rational(vs.width, vs.height)
                } catch (_: Exception) {
                    Rational(16, 9)
                })
            // seamless resize 仅 API 31+ 支持；低版本省略该选项即可(此前误用 TODO 抛异常崩溃)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(true)
            }
            val params = builder.build()
            if (activity.enterPictureInPictureMode(params)) return
        } catch (_: Exception) {
        }
        // 进入失败：恢复原有 UI
        binding.toolbarLayout.visibility = View.VISIBLE
        binding.playlistContainer.visibility = View.VISIBLE
        binding.playerView.useController = true
        applyPipVideoSurface(false)
        Toast.makeText(activity, "无法进入小窗模式", Toast.LENGTH_SHORT).show()
    }

    /**
     * 用当前视频的真实宽高比动态更新 PiP 小窗比例，使小窗贴合视频、避免黑边。
     * 在 [enterPipMode] 之后由 onVideoSizeChanged 触发：视频分辨率在进入小窗后
     * 才确定（或中途切换清晰度）时，窗口会跟随比例平滑伸缩。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipAspectRatio(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        if (!isInPipMode) return
        val ratio = try {
            Rational(videoSize.width, videoSize.height)
        } catch (_: Exception) {
            return
        }
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        try {
            activity.setPictureInPictureParams(builder.build())
        } catch (_: Exception) {
        }
    }

    /** onStop 时若处于 PiP：暂停播放并复位 PiP 状态 */
    fun onStopped() {
        if (isInPipMode) {
            controllerProvider()?.pause()
            isInPipMode = false
        }
    }
}

// ==================== 播放器手势控制 ====================

/**
 * 播放器手势控制器：从 MainActivity 拆出的手势交互逻辑。
 *
 * 支持：
 * - 单击：切换控制栏显隐
 * - 双击：左侧快退 15 秒，右侧快进 15 秒
 * - 横向滑动：进度快进/快退（实时预览，松手跳转）
 * - 纵向滑动（左半屏）：亮度调节
 * - 纵向滑动（右半屏）：音量调节
 *
 * @param activity 宿主 Activity（窗口亮度、AudioManager 等系统服务）
 * @param playerView 播放器视图（手势载体与控制栏）
 * @param overlay 手势提示浮层（显示进度/亮度/音量预览）
 * @param controllerProvider 当前已连接的 [MediaController] 提供者（可能为 null）
 */
@OptIn(UnstableApi::class)
class GestureController(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val overlay: TextView,
    private val controllerProvider: () -> MediaController?
) {
    private companion object {
        const val GESTURE_NONE = 0 // 无手势
        const val GESTURE_SEEK = 1 // 左右滑动快进/快退
        const val GESTURE_BRIGHTNESS = 2 // 左半屏上下滑动调亮度
        const val GESTURE_VOLUME = 3 // 右半屏上下滑动调音量
    }

    /** 主线程 Handler，用于手势提示的延时隐藏 */
    private val handler = Handler(Looper.getMainLooper())
    /** 隐藏手势提示的延时任务 */
    private val hideOverlayRunnable = Runnable { overlay.visibility = View.GONE }
    /** 当前正在进行的手势模式 */
    private var gestureMode = GESTURE_NONE
    /** 手势开始时的播放位置（用于 seek） */
    private var seekStartPosition = 0L
    /** 手势结束时最终要跳转到的位置 */
    private var seekTargetPosition = 0L
    /** 手势开始时的亮度 */
    private var brightnessStart = 0f
    /** 手势开始时的音量 */
    private var volumeStart = 0
    /** 音频管理器（懒加载，用于音量调节） */
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** 初始化手势识别（在 Activity 视图就绪后调用一次） */
    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            // 按下即消费事件，保证后续手势均由我们处理
            override fun onDown(e: MotionEvent): Boolean = true

            /** 单击：切换控制栏显隐 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            /** 双击：左侧快退 15 秒，右侧快进 15 秒 */
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

            /** 滑动：
             * 横向手势 -> 进度快进/快退
             * 纵向 + 起点在左半屏 -> 亮度
             * 纵向 + 起点在右半屏 -> 音量
             */
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
                    // 根据横向位移换算毫秒目标位置，并实时预览
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
                    // 纵向位移换算亮度增量并实时预览
                    GESTURE_BRIGHTNESS -> {
                        val delta = (start.y - e2.y) / playerView.height
                        applyBrightness(brightnessStart + delta)
                        showGestureOverlay("亮度 ${(currentBrightness() * 100).toInt()}%")
                    }
                    // 纵向位移换算音量增量并实时预览
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
            // 手指抬起/取消时结束手势，若为 seek 则跳转到目标位置
            if (event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endGesture()
            }
            true
        }
    }

    /** 取消尚未执行的浮层隐藏任务（onStop 中调用，避免泄漏窗口引用） */
    fun cancelPendingHide() {
        handler.removeCallbacks(hideOverlayRunnable)
    }

    /** 手势结束时执行最终动作（seek 手势才需要真正跳转），并复位手势状态 */
    private fun endGesture() {
        if (gestureMode == GESTURE_SEEK) {
            controllerProvider()?.seekTo(seekTargetPosition)
        }
        gestureMode = GESTURE_NONE
    }

    /** 读取当前窗口亮度（跟随系统时返回默认 0.5） */
    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        return if (b < 0f) 0.5f else b
    }

    /** 设置窗口亮度（限制在 0.02~1.0 之间） */
    private fun applyBrightness(value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        activity.window.attributes = lp
    }

    /** 显示手势提示浮层，并在 800ms 后自动隐藏 */
    private fun showGestureOverlay(text: String) {
        handler.removeCallbacks(hideOverlayRunnable)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        handler.postDelayed(hideOverlayRunnable, 800)
    }
}

// ==================== 播放列表适配器 ====================

/**
 * 播放列表的 RecyclerView 适配器。
 *
 * 负责把 [MediaItemData] 列表渲染为 UI，并处理三项用户交互：
 * - 点击列表项播放对应媒体（通过 [onClick] 回调）
 * - 点击删除按钮移除列表项（通过 [onDelete] 回调）
 * - 实时刷新当前播放项 / 进度条 / 时长等信息
 */
class MediaListAdapter(
    /** 点击某个列表项时的回调，参数为被点击项的下标 */
    private val onClick: (Int) -> Unit,
    /** 点击删除按钮时的回调，参数为被删除项的下标 */
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<MediaListAdapter.VH>() {

    /** 当前展示的数据副本（与外部 playlist 保持一致但不直接引用） */
    private val items = mutableListOf<MediaItemData>()
    /**
     * 各条目的播放进度（uri -> 位置毫秒），独立于条目数据单独维护。
     * 进度不再放进 [MediaItemData]，这里是列表 UI 展示进度的唯一来源，随 [updateProgress]
     * 局部更新，跨列表刷新(submitList)也保持存活。
     */
    private val progressMap = mutableMapOf<String, Long>()
    /** 当前正在播放的列表项下标，-1 表示无 */
    private var currentPlayingIndex = -1
    /** 当前是否处于播放状态（决定图标展示） */
    private var isPlaying = false
    /** 后台线程作用域：用于把 DiffUtil 计算移出主线程，避免大列表掉帧 */
    private val diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** 正在进行的 diff 任务句柄，新的 submitList 会取消旧的，避免乱序 dispatch 覆盖 */
    private var diffJob: Job? = null

    /**
     * 用新列表刷新数据，并借助 DiffUtil 计算增量后最小化刷新 UI。
     * DiffUtil 计算在 [Dispatchers.Default] 后台线程执行，结果回主线程 dispatch，
     * 避免列表较大时在主线程同步计算导致掉帧。
     * @param list 新的数据列表
     */
    fun submitList(list: List<MediaItemData>) {
        // 仅在主线程调用，此处无需加锁：items 只在下方 withContext(Main) 内修改
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
                if (myJob?.isActive != true) return@withContext // 已被更新的提交取消，丢弃本次结果
                items.clear()
                items.addAll(list)
                diff.dispatchUpdatesTo(this@MediaListAdapter)
            }
        }
    }

    /**
     * 设置当前正在播放的列表项。
     * 只通知「旧项」与「新项」两个位置刷新，避免全表刷新。
     *
     * 调用方传入的下标来自 ExoPlayer 队列，而 [items] 经 submitList 异步 diff 后才回写，
     * 存在「队列已同步、items 未跟上」的窗口（如 Activity 重连时 items 尚为空、
     * 扫描新增后 diff 未完成），因此与 updateDuration/updateProgress 一律做双向边界检查，
     * 越界时仅记录状态，待下次 diff dispatch 或状态调用时自然刷新。
     */
    fun setCurrentPlaying(index: Int, playing: Boolean = false) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        isPlaying = playing
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    /** 更新某个列表项的时长（仅当原先未知时为 0 时写入），并刷新该项 */
    fun updateDuration(index: Int, duration: Long) {
        if (index in items.indices && items[index].duration == 0L) {
            items[index] = items[index].copy(duration = duration)
            notifyItemChanged(index)
        }
    }

    /**
     * 批量同步各 uri 的播放进度（用于冷启动/回前台把内存或磁盘进度刷进列表，供进度条展示）。
     * 只收录 >0 的有效进度，0 视为无进度。
     */
    fun setProgress(progress: Map<String, Long>) {
        progressMap.clear()
        for ((uri, pos) in progress) {
            if (pos > 0) progressMap[uri] = pos
        }
    }

    /** 更新某个列表项的播放进度（毫秒），并刷新该项的进度条 */
    fun updateProgress(index: Int, position: Long) {
        if (index in items.indices) {
            progressMap[items[index].uri.toString()] = position
            notifyItemChanged(index)
        }
    }

    /** 创建单个列表项的视图持有者 */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    /** 绑定当前列表项的数据到视图 */
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        // 列表序号（从 1 开始）
        holder.binding.tvIndex.text =
            context.getString(R.string.list_item_index, position + 1)
        holder.binding.tvName.text = item.name
        holder.binding.tvDuration.text = if (item.duration > 0) formatTime(item.duration) else ""
        // 当前正在播放的项高亮展示，并以「播放动画图标」替代序号
        val isActive = position == currentPlayingIndex && isPlaying
        holder.binding.imgPlaying.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.binding.tvIndex.visibility = if (isActive) View.GONE else View.VISIBLE
        holder.binding.tvName.setTextColor(
            context.getColor(if (isActive) R.color.accent else R.color.text_primary)
        )

        // 已有进度信息时展示进度条；正在播放的项显示「已播时长」，否则显示「已播/总时长」区间
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

    /** 列表项视图持有者（inner 以便在 init 中绑定一次点击监听，避免每次绑定重建 lambda） */
    inner class VH(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemRoot.setOnClickListener { onClick(bindingAdapterPosition) }
            binding.btnDelete.setOnClickListener { onDelete(bindingAdapterPosition) }
        }
    }
}
