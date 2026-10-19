/**
 * 界面组件集合（与业务无关的纯 UI 逻辑）：
 * - [FullscreenPipHelper]：全屏 / 画中画切换与系统栏、视频表面样式管理；
 * - [GestureController]：播放器手势（单击显隐控制栏、双击左/中/右区域快退/播放暂停/快进、横滑快进快退、上下滑调亮度 / 音量）；
 * - [MediaListAdapter]：播放列表 RecyclerView 适配器（DiffUtil 后台差分刷新）。
 */
package com.example.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
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
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/** 视频尺寸是否可用（宽高均已解析出来）；纯音频或尺寸未知时为 false。 */
internal fun VideoSize?.hasVideo(): Boolean =
    this != null && width > 0 && height > 0

/** 系统亮度值（0..255）转 0..1；未知时回退 0.5，避免手势第一次跳变。 */
internal fun systemBrightnessToFraction(raw: Int?): Float =
    raw?.coerceIn(0, 255)?.div(255f) ?: 0.5f

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
    private companion object {
        /** 全屏顶部时钟 / 电量的刷新间隔。 */
        const val INFO_TICK_MS = 1000L
    }

    var isFullscreen = false
        private set

    var isInPipMode = false
        private set

    /** 全屏顶部信息（文件名 / 系统时间 / 电量）的定时刷新。 */
    private val infoHandler = Handler(Looper.getMainLooper())
    private var infoTickerRunning = false
    private val infoTick = object : Runnable {
        override fun run() {
            refreshInfoBarContent()
            infoHandler.postDelayed(this, INFO_TICK_MS)
        }
    }

    init {
        // 信息条跟随控制栏：控制栏 3 秒后自动隐藏时，顶部信息一并隐藏；点按唤出时再显示。
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (isFullscreen && !isInPipMode) {
                    applyInfoBar(visibility == View.VISIBLE)
                }
            }
        )
    }

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

    /** 左上角返回键仅全屏时可见（本身在控制栏内，随控制栏一起显隐）。 */
    private fun setFullscreenBackVisible(visible: Boolean) {
        binding.playerView.findViewById<View>(R.id.btn_fullscreen_back)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    /** 顶部信息条显隐：全屏且控制栏可见时显示，并启动时钟 / 电量刷新。 */
    private fun applyInfoBar(visible: Boolean) {
        binding.fullscreenInfoBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            refreshInfoBarContent()
            if (!infoTickerRunning) {
                infoTickerRunning = true
                infoHandler.postDelayed(infoTick, INFO_TICK_MS)
            }
        } else if (infoTickerRunning) {
            infoTickerRunning = false
            infoHandler.removeCallbacks(infoTick)
        }
    }

    /**
     * 刷新顶部信息：文件名取当前媒体标题，时间取系统时钟，电量为百分比。
     * 媒体切换或进入全屏时主动调用；全屏期间由定时器每秒刷新。
     */
    fun refreshInfoBarContent() {
        binding.tvFullscreenName.text = currentItemTitle().orEmpty()
        binding.tvFullscreenClock.text = DateFormat.getTimeFormat(activity).format(Date())
        val percent = batteryPercent()
        binding.tvFullscreenBattery.text = if (percent != null) {
            activity.getString(R.string.fullscreen_battery_percent, percent)
        } else {
            ""
        }
    }

    /** 当前媒体标题；无播放项或标题为空时返回 null。 */
    private fun currentItemTitle(): String? {
        val title = controllerProvider()?.currentMediaItem?.mediaMetadata?.title?.toString()
        return title?.takeIf { it.isNotBlank() }
    }

    /** 读取当前电量百分比；读不到返回 null。 */
    private fun batteryPercent(): Int? {
        val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    /** 根据当前（普通 / 全屏 / PiP）状态统一套用界面样式。 */
    private fun applyVisualState(pipPreparing: Boolean = false) {
        val pipLike = isInPipMode || pipPreparing
        setChromeVisible(!pipLike && !isFullscreen)
        setFullscreenBackVisible(isFullscreen && !pipLike)
        applyInfoBar(isFullscreen && !pipLike)
        binding.playerView.useController = !pipLike
        applyInfoBar(isFullscreen && !pipLike && binding.playerView.isControllerFullyVisible)
        when {
            // PiP（或进入前的过渡态）：按真实比例缩放铺满小窗
            pipLike -> applyEdgeToEdgeSurface(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            // 全屏：按真实比例完整显示（不裁剪画面，可能留黑边）
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
        return !(!ctrl.isPlaying || !ctrl.videoSize.hasVideo()) && enterPipMode(showError = false)
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
        if (vs == null || !vs.hasVideo()) {
            if (showError) activity.toast(R.string.toast_pip_no_video)
            return false
        }
        val wasFullscreen = isFullscreen
        if (wasFullscreen) setFullscreen(false)
        applyVisualState(pipPreparing = true)
        try {
            val ratio = aspectRatioOf(vs) ?: Rational(16, 9)
            if (activity.enterPictureInPictureMode(buildPipParams(ratio, autoEnter = false))) return true
        } catch (_: Exception) {
        }
        // 进入失败：回滚全屏状态与界面样式
        if (wasFullscreen) setFullscreen(true) else applyVisualState()
        if (showError) activity.toast(R.string.toast_pip_failed)
        return false
    }

    /** 视频尺寸转系统比例；非法尺寸返回 null。 */
    private fun aspectRatioOf(videoSize: VideoSize): Rational? =
        try {
            Rational(videoSize.width, videoSize.height)
        } catch (_: Exception) {
            null
        }

    /**
     * 依据视频与播放状态刷新系统 PiP 参数：有视频且正在播放时允许自动进入小窗
     * （API 31+ 的按 Home / 手势），并给出进入 / 退出小窗的动画锚点。
     * 纯音频时关闭自动进入，避免弹出空的黑色小窗。
     */
    fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctrl = controllerProvider()
        val vs = ctrl?.videoSize
        // 与原手动行为一致：仅在「正在播放视频」时才允许系统自动进入小窗
        val autoEnter = vs.hasVideo() && ctrl?.isPlaying == true
        val ratio = vs?.let(::aspectRatioOf) ?: Rational(16, 9)
        try {
            activity.setPictureInPictureParams(
                buildPipParams(ratio, autoEnter = autoEnter)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 构造 PiP 参数。
     * @param autoEnter API 31+ 是否允许系统自动进入小窗（无视频时传 false）
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(ratio: Rational, autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        // 进入 / 退出小窗的动画锚点：播放器卡片在屏幕中的位置
        sourceRectHint()?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    /** 播放器卡片当前的屏幕矩形；尺寸未知（尚未布局）时返回 null。 */
    private fun sourceRectHint(): Rect? {
        val card = binding.playerCard
        if (card.width <= 0 || card.height <= 0) return null
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        return Rect(
            location[0], location[1], location[0] + card.width, location[1] + card.height
        )
    }

    /** Activity onStart：恢复全屏沉浸状态与顶部信息显示（全屏状态可能跨后台保留）。 */
    fun onStarted() {
        if (isFullscreen && !isInPipMode) {
            hideSystemBars()
        }
        applyInfoBar(
            isFullscreen && !isInPipMode && binding.playerView.isControllerFullyVisible
        )
    }

    /** Activity onStop：若仍在 PiP 则暂停并清除 PiP 标记，同时停止顶部信息刷新。 */
    fun onStopped() {
        if (isInPipMode) {
            controllerProvider()?.pause()
            isInPipMode = false
        }
        infoTickerRunning = false
        infoHandler.removeCallbacks(infoTick)
    }
}

/**
 * 播放器手势控制。
 *
 * 手势：
 * - 单击 → 显隐控制栏；
 * - 双击左 1/3 屏 → 快退 15s；双击中间 1/3 屏 → 播放 / 暂停；双击右 1/3 屏 → 快进 15s；
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
        const val BRIGHTNESS_PREFS = "gesture_settings"
        const val KEY_BRIGHTNESS = "manual_brightness"
        const val KEY_FOLLOW_SYSTEM = "follow_system_brightness"
    }

    /** 本轮手势的类型；[Gesture.IGNORED] 表示时长未知等无法处理的滑动，本轮忽略。 */
    private enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME, IGNORED }

    private val handler = Handler(Looper.getMainLooper())

    /** 记忆用户手动设置的亮度（退出重进后沿用）。 */
    private val brightnessPrefs by lazy {
        activity.getSharedPreferences(BRIGHTNESS_PREFS, Context.MODE_PRIVATE)
    }
    private val hideOverlayRunnable = Runnable { overlay.visibility = View.GONE }

    private var gestureMode = Gesture.NONE
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
        // 仅当用户之前手动设置过亮度、且没有恢复“跟随系统”时才恢复手动值。
        // 旧版本没有 KEY_FOLLOW_SYSTEM：有手动亮度就视为手动模式，保持原行为。
        val followSystem = brightnessPrefs.getBoolean(
            KEY_FOLLOW_SYSTEM,
            !brightnessPrefs.contains(KEY_BRIGHTNESS)
        )
        if (!followSystem && brightnessPrefs.contains(KEY_BRIGHTNESS)) {
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

            // 双击：左 1/3 快退 15s / 中间 1/3 播放暂停 / 右 1/3 快进 15s
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val ctrl = controllerProvider() ?: return true
                val third = playerView.width / 3f
                when {
                    e.x < third -> {
                        if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
                            ctrl.seekBack()
                            showGestureOverlay(activity.getString(R.string.gesture_seek_back))
                        }
                    }
                    e.x > third * 2 -> {
                        if (ctrl.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
                            ctrl.seekForward()
                            showGestureOverlay(activity.getString(R.string.gesture_seek_forward))
                        }
                    }
                    else -> {
                        if (ctrl.playWhenReady) {
                            ctrl.pause()
                            showGestureOverlay(activity.getString(R.string.gesture_paused))
                        } else {
                            ctrl.play()
                            showGestureOverlay(activity.getString(R.string.gesture_playing))
                        }
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
                if (gestureMode == Gesture.NONE) {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                    }
                    gestureMode = if (abs(e2.x - start.x) > abs(e2.y - start.y)) {
                        seekStartPosition = ctrl.currentPosition
                        seekTargetPosition = seekStartPosition
                        Gesture.SEEK
                    } else if (start.x < playerView.width / 2f) {
                        brightnessStart = currentBrightness()
                        Gesture.BRIGHTNESS
                    } else {
                        volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        Gesture.VOLUME
                    }
                }
                when (gestureMode) {
                    Gesture.SEEK -> {
                        val duration = ctrl.duration
                        if (duration == C.TIME_UNSET || duration <= 0) {
                            gestureMode = Gesture.IGNORED
                            return true
                        }
                        // 每像素对应的毫秒：让整屏滑动约等于 1.2 倍时长
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
                    Gesture.BRIGHTNESS -> {
                        val delta = (start.y - e2.y) / playerView.height.coerceAtLeast(1)
                        applyBrightness(brightnessStart + delta, persist = false)
                        showGestureOverlay(
                            activity.getString(
                                R.string.gesture_brightness, (currentBrightness() * 100).toInt()
                            )
                        )
                    }
                    Gesture.VOLUME -> {
                        val maxVolume =
                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta =
                            ((start.y - e2.y) / playerView.height.coerceAtLeast(1) * maxVolume).toInt()
                        val target = (volumeStart + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        val pct = if (maxVolume > 0) target * 100 / maxVolume else 0
                        showGestureOverlay(activity.getString(R.string.gesture_volume, pct))
                    }
                    Gesture.NONE, Gesture.IGNORED -> Unit
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
            Gesture.SEEK -> controllerProvider()?.seekTo(seekTargetPosition)
            Gesture.BRIGHTNESS -> applyBrightness(currentBrightness(), persist = true)
            Gesture.NONE, Gesture.VOLUME, Gesture.IGNORED -> Unit
        }
        gestureMode = Gesture.NONE
    }

    /** 当前亮度；跟随系统（<0）时读取系统亮度，读取失败回退 0.5。 */
    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        if (b >= 0f) return b
        val systemValue = runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        return systemBrightnessToFraction(systemValue)
    }

    /** 应用屏幕亮度（限制在 2%~100%）；persist 为 true 时写入偏好并标记为手动模式。 */
    private fun applyBrightness(value: Float, persist: Boolean) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        activity.window.attributes = lp
        if (persist) brightnessPrefs.edit {
            putFloat(KEY_BRIGHTNESS, lp.screenBrightness)
            putBoolean(KEY_FOLLOW_SYSTEM, false)
        }
    }

    /** 恢复跟随系统亮度：清除手动值并把 window 亮度交还给系统。 */
    fun resetBrightnessToSystem() {
        val lp = activity.window.attributes
        lp.screenBrightness = -1f
        activity.window.attributes = lp
        brightnessPrefs.edit {
            remove(KEY_BRIGHTNESS)
            putBoolean(KEY_FOLLOW_SYSTEM, true)
        }
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
 * 仅进度变化通过 payload 局部刷新，其余变化走默认重绑。
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

    /** 当前列表；internal 便于单元测试直接注入（生产代码只经 [submitList] 更新）。 */
    internal val items = mutableListOf<MediaItemData>()

    /** 当前播放项 uri；internal 便于单元测试断言。 */
    internal var currentPlayingUri: String? = null
        private set

    private var isPlaying = false
    private val diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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

    /** 绑定进度行：有进度时显示「播放中 x」或「x / 总时长」，无进度则隐藏。 */
    private fun bindProgress(holder: VH, item: MediaItemData) {
        val context = holder.itemView.context
        val isActive = item.uri.toString() == currentPlayingUri && isPlaying
        val progress = getProgress(item.uri.toString())
        if (progress > 0 && item.duration > 0) {
            val percent = (progress * 100 / item.duration).toInt().coerceIn(0, 100)
            holder.binding.progressRow.visibility = View.VISIBLE
            holder.binding.pbItem.progress = percent
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
