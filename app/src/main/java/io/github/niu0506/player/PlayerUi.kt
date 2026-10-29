/** 界面组件：全屏/PiP、手势与播放列表适配器。 */
package io.github.niu0506.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
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
import com.google.android.material.snackbar.Snackbar
import io.github.niu0506.player.databinding.ActivityMainBinding
import io.github.niu0506.player.databinding.DialogSelectionBinding
import io.github.niu0506.player.databinding.ItemMediaBinding
import io.github.niu0506.player.databinding.ItemSelectionBinding
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.content.edit

/** 统一的短提示入口（Material Snackbar）。 */
internal fun Activity.snack(@StringRes resId: Int, vararg args: Any?) {
    snackMessage(getString(resId, *args))
}

internal fun Activity.snackLong(@StringRes resId: Int, vararg args: Any?) {
    snackMessage(getString(resId, *args), long = true)
}

/** 已拼好的文案直接提示；界面已销毁时静默忽略。 */
internal fun Activity.snackMessage(message: CharSequence, long: Boolean = false) {
    if (isFinishing || isDestroyed) return
    // 小窗下界面不可见，Snackbar 会被窗口裁剪，退回系统 Toast
    if (isInPictureInPictureMode) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        return
    }
    val root = findViewById<View>(android.R.id.content) ?: return
    Snackbar.make(root, message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).show()
}

/** 居中面板宽度：左右各留 24dp，宽屏（横屏 / 平板）上限 480dp。 */
private fun selectionPanelWidth(metrics: DisplayMetrics): Int = min(
    metrics.widthPixels - (48 * metrics.density).toInt(),
    (480 * metrics.density).toInt(),
)

/**
 * 倍速 / 音轨 / 字幕共用的居中面板：圆角卡片 + 单选列表，高亮当前选中项。
 * 每次打开都重建选项，避免轨道在播放过程中变化导致显示过期状态。
 *
 * @param immersive 全屏播放中打开时为 true，面板窗口保持沉浸，
 *   避免系统栏重新出现把画面顶动，也让卡片在可视区域里真正居中。
 */
internal fun showSelectionPanel(
    activity: AppCompatActivity,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    immersive: Boolean,
    onSelected: (Int) -> Unit,
) {
    if (options.isEmpty()) return
    val density = activity.resources.displayMetrics.density
    val panelWidth = selectionPanelWidth(activity.resources.displayMetrics)

    val dialog = Dialog(activity, R.style.Theme_Player_SelectionDialog)
    // 内容按对话框主题创建，卡片背景才不会叠加 Activity 的 elevation 着色
    val panelInflater = LayoutInflater.from(dialog.context)
    val binding = DialogSelectionBinding.inflate(panelInflater)
    binding.tvPanelTitle.text = title
    // 高度必须显式 wrap_content：卡片按内容收拢后窗口才能在屏幕中间悬浮
    dialog.setContentView(
        binding.root,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )

    options.forEachIndexed { index, label ->
        val row = ItemSelectionBinding.inflate(
            panelInflater, binding.optionsContainer, false
        )
        val selected = index == selectedIndex
        row.tvOption.text = label
        row.tvOption.setTextColor(
            activity.getColor(if (selected) R.color.accent else R.color.text_primary)
        )
        // 选中标记放在文字末尾，避免额外一层 ImageView
        row.tvOption.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (selected) R.drawable.ic_check else 0, 0
        )
        row.root.isSelected = selected
        row.root.setOnClickListener {
            dialog.dismiss()
            onSelected(index)
        }
        binding.optionsContainer.addView(row.root)
    }

    // 量出列表的自然高度；旋转 / 分屏时只需按新的屏幕尺寸重算上限
    binding.optionsContainer.measure(
        View.MeasureSpec.makeMeasureSpec(panelWidth - (24 * density).toInt(), View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val listContentHeight = binding.optionsContainer.measuredHeight

    dialog.window?.apply {
        setGravity(Gravity.CENTER)
        // 与 Activity 的 enableEdgeToEdge 保持一致：横屏刘海下不让窗口被整体挤到一侧
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attributes = attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }
        if (immersive) {
            WindowInsetsControllerCompat(this, decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // 选项过多时在卡片内部滚动，让卡片始终完整居中
    fun applyPanelWindow() {
        val metrics = activity.resources.displayMetrics
        binding.optionsScroll.layoutParams = binding.optionsScroll.layoutParams.apply {
            height = listContentHeight.coerceAtMost((metrics.heightPixels * 0.6f).toInt())
        }
        dialog.window?.setLayout(
            selectionPanelWidth(metrics),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    applyPanelWindow()
    // 面板打开期间旋转 / 分屏：按新尺寸重新布局，避免用旧宽度顶出屏幕
    val configListener = Consumer<Configuration> { applyPanelWindow() }
    activity.addOnConfigurationChangedListener(configListener)
    dialog.setOnDismissListener {
        activity.removeOnConfigurationChangedListener(configListener)
    }
    dialog.show()
}

internal fun VideoSize?.hasVideo(): Boolean =
    this != null && width > 0 && height > 0

internal fun MediaController?.isPlayingVideo(): Boolean =
    this?.isPlaying == true && videoSize.hasVideo()

/** 系统亮度（0..255）转 0..1；未知时回退 0.5。 */
internal fun systemBrightnessToFraction(raw: Int?): Float =
    raw?.coerceIn(0, 255)?.div(255f) ?: 0.5f

@OptIn(UnstableApi::class)
class FullscreenPipHelper(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val controllerProvider: () -> MediaController?
) {
    private companion object {
        const val INFO_TICK_MS = 30_000L
    }

    var isFullscreen = false
        private set

    var isInPipMode = false
        private set

    private var pipPreparing = false

    private var applyingFullscreen = false

    private val infoHandler = Handler(Looper.getMainLooper())
    private var infoTickerRunning = false
    private val infoTick = object : Runnable {
        override fun run() {
            refreshInfoBarContent()
            infoHandler.postDelayed(this, INFO_TICK_MS)
        }
    }

    init {
        // Android 15+ 强制 edge-to-edge：普通界面消费 insets，全屏/PiP 铺满窗口
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val apply = !isFullscreen && !isInPipMode && !pipPreparing
            view.setPadding(
                if (apply) bars.left else 0,
                if (apply) bars.top else 0,
                if (apply) bars.right else 0,
                if (apply) bars.bottom else 0,
            )
            insets
        }
        // 信息条跟随控制栏显隐
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (isFullscreen && !isInPipMode) {
                    applyInfoBar(visibility == View.VISIBLE)
                }
            }
        )
    }

    private fun requestContentInsets() {
        ViewCompat.requestApplyInsets(binding.root)
    }

    fun onPipModeChanged(isInPictureInPictureMode: Boolean) {
        isInPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !isFullscreen) {
            showSystemBars()
        }
        applyVisualState()
    }

    private fun setChromeVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.toolbarLayout.visibility = visibility
        binding.playlistContainer.visibility = visibility
    }

    private fun setFullscreenBackVisible(visible: Boolean) {
        binding.playerView.findViewById<View>(R.id.btn_fullscreen_back)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

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

    private fun refreshInfoBar() {
        applyInfoBar(
            isFullscreen && !isInPipMode && !pipPreparing &&
                binding.playerView.isControllerFullyVisible
        )
    }

    fun refreshInfoBarContent() {
        // 仅全屏且非 PiP 时读取时间/电量
        if (!isFullscreen || isInPipMode) return
        binding.tvFullscreenName.text = currentItemTitle().orEmpty()
        binding.tvFullscreenClock.text = DateFormat.getTimeFormat(activity).format(Date())
        val percent = batteryPercent()
        binding.tvFullscreenBattery.text = if (percent != null) {
            activity.getString(R.string.fullscreen_battery_percent, percent)
        } else {
            ""
        }
    }

    private fun currentItemTitle(): String? {
        val title = controllerProvider()?.currentMediaItem?.mediaMetadata?.title?.toString()
        return title?.takeIf { it.isNotBlank() }
    }

    private fun batteryPercent(): Int? {
        val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    private fun applyVisualState(preparing: Boolean = false) {
        this.pipPreparing = preparing
        val pipLike = isInPipMode || preparing
        setChromeVisible(!pipLike && !isFullscreen)
        setFullscreenBackVisible(isFullscreen && !pipLike)
        binding.playerView.useController = !pipLike
        refreshInfoBar()
        requestContentInsets()
        when {
            // PiP（或进入前的过渡态）：按真实比例缩放铺满小窗
            pipLike -> applyCardStyle(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, marginDp = 0, radiusDp = 0f)
            // 全屏：按真实比例完整显示（不裁剪画面，可能留黑边）
            isFullscreen -> applyCardStyle(AspectRatioFrameLayout.RESIZE_MODE_FIT, marginDp = 0, radiusDp = 0f)
            else -> applyCardStyle(
                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                marginDp = 12,
                radiusDp = 18f,
            )
        }
    }

    private fun applyCardStyle(resizeMode: Int, marginDp: Int, radiusDp: Float) {
        binding.playerView.setResizeMode(resizeMode)
        val density = activity.resources.displayMetrics.density
        val margin = (marginDp * density).toInt()
        val lp = binding.playerCard.layoutParams as ViewGroup.MarginLayoutParams
        lp.setMargins(margin, margin, margin, margin / 3)
        binding.playerCard.radius = radiusDp * density
        binding.playerCard.layoutParams = lp
    }

    fun setFullscreen(enabled: Boolean) {
        if (applyingFullscreen) return
        applyingFullscreen = true
        try {
            isFullscreen = enabled
            // 非按钮路径也要同步状态，否则图标可能与实际状态不符
            binding.playerView.setFullscreenButtonState(enabled)
            if (enabled) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemBars()
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                showSystemBars()
            }
            applyVisualState()
        } finally {
            applyingFullscreen = false
        }
    }

    private fun hideSystemBars() {
        val insetsController = WindowInsetsControllerCompat(activity.window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(activity.window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    }

    fun enterPipWhenPlayingVideo(): Boolean =
        controllerProvider().isPlayingVideo() && enterPipMode(showError = false)

    fun enterPipMode(showError: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (showError) activity.snack(R.string.toast_pip_unsupported)
            return false
        }
        val vs = controllerProvider()?.videoSize
        if (vs == null || !vs.hasVideo()) {
            if (showError) activity.snack(R.string.toast_pip_no_video)
            return false
        }
        val wasFullscreen = isFullscreen
        if (wasFullscreen) setFullscreen(false)
        applyVisualState(preparing = true)
        try {
            val ratio = aspectRatioOf(vs) ?: Rational(16, 9)
            if (activity.enterPictureInPictureMode(buildPipParams(ratio, autoEnter = false))) return true
        } catch (_: Exception) {
        }
        // 进入失败：回滚全屏状态与界面样式
        if (wasFullscreen) setFullscreen(true) else applyVisualState()
        if (showError) activity.snack(R.string.toast_pip_failed)
        return false
    }

    private fun aspectRatioOf(videoSize: VideoSize): Rational? =
        try {
            Rational(videoSize.width, videoSize.height)
        } catch (_: Exception) {
            null
        }

    fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctrl = controllerProvider()
        val vs = ctrl?.videoSize
        val autoEnter = ctrl.isPlayingVideo()
        val ratio = vs?.let(::aspectRatioOf) ?: Rational(16, 9)
        try {
            activity.setPictureInPictureParams(
                buildPipParams(ratio, autoEnter = autoEnter)
            )
        } catch (_: Exception) {
        }
    }

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

    private fun sourceRectHint(): Rect? {
        val card = binding.playerCard
        if (card.width <= 0 || card.height <= 0) return null
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        return Rect(
            location[0], location[1], location[0] + card.width, location[1] + card.height
        )
    }

    fun onStarted() {
        if (isFullscreen && !isInPipMode) {
            hideSystemBars()
        }
        refreshInfoBar()
        requestContentInsets()
    }

    fun onStopped() {
        if (isInPipMode) {
            controllerProvider()?.pause()
            isInPipMode = false
            pipPreparing = false
        }
        applyInfoBar(false)
    }
}

/**
 * 播放器手势：单击显隐控制栏；双击左/中/右区域快退/播放暂停/快进；
 * 横滑快进快退；左半屏调亮度、右半屏调音量。
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
        const val OVERLAY_HIDE_DELAY_MS = 800L
    }

    /** 本轮手势类型；IGNORED 表示忽略本轮。 */
    private enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME, IGNORED }

    private val handler = Handler(Looper.getMainLooper())

    private val brightnessPrefs by lazy {
        activity.getSharedPreferences(BRIGHTNESS_PREFS, Context.MODE_PRIVATE)
    }
    private var hideScheduled = false
    private val hideOverlayRunnable = Runnable {
        hideScheduled = false
        overlay.visibility = View.GONE
    }

    private var gestureMode = Gesture.NONE
    private var seekStartPosition = 0L
    private var seekTargetPosition = 0L
    private var brightnessStart = 0f
    private var volumeStart = 0
    private val audioManager by lazy {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        // 存在手动亮度值即表示手动模式；恢复“跟随系统”时会删除该键。
        if (brightnessPrefs.contains(KEY_BRIGHTNESS)) {
            applyBrightness(brightnessPrefs.getFloat(KEY_BRIGHTNESS, 0.5f), persist = false)
        }
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

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

    /**
     * 退后台时取消待执行的隐藏回调。浮层此刻可能正处于「即将隐藏」状态，
     * 若不处理，回到前台后隐藏回调已不存在，浮层会一直停留。
     * 控制栏仍可见时补排一次隐藏，已隐藏时立即收起。
     */
    fun cancelPendingHide() {
        handler.removeCallbacks(hideOverlayRunnable)
        if (!hideScheduled) return
        if (playerView.isControllerFullyVisible) {
            handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
        } else {
            hideOverlayRunnable.run()
        }
    }

    private fun endGesture() {
        when (gestureMode) {
            Gesture.SEEK -> controllerProvider()?.seekTo(seekTargetPosition)
            Gesture.BRIGHTNESS -> applyBrightness(currentBrightness(), persist = true)
            Gesture.NONE, Gesture.VOLUME, Gesture.IGNORED -> Unit
        }
        gestureMode = Gesture.NONE
    }

    private fun currentBrightness(): Float {
        val b = activity.window.attributes.screenBrightness
        if (b >= 0f) return b
        val systemValue = runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        return systemBrightnessToFraction(systemValue)
    }

    private fun applyBrightness(value: Float, persist: Boolean) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.02f, 1f)
        activity.window.attributes = lp
        if (persist) brightnessPrefs.edit {
            putFloat(KEY_BRIGHTNESS, lp.screenBrightness)
        }
    }

    fun resetBrightnessToSystem() {
        val lp = activity.window.attributes
        lp.screenBrightness = -1f
        activity.window.attributes = lp
        brightnessPrefs.edit {
            remove(KEY_BRIGHTNESS)
        }
    }

    private fun showGestureOverlay(text: String) {
        handler.removeCallbacks(hideOverlayRunnable)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        hideScheduled = true
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }
}

/** 播放列表适配器：DiffUtil 后台差分，进度变化用 payload 局部刷新。 */
class MediaListAdapter(
    private val onClick: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val getProgress: (String) -> Long
) : RecyclerView.Adapter<MediaListAdapter.VH>() {

    private val progressPayload = Any()

    private val indexPayload = Any()

    internal val items = mutableListOf<MediaItemData>()

    internal var currentPlayingUri: String? = null
        private set

    private var isPlaying = false
    private val diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var diffJob: Job? = null

    internal val isDiffing: Boolean
        get() = diffJob?.isActive == true

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
                // DiffUtil 只刷新增删/移动的条目，后续条目的序号会整体前移/后移，
                // 这里用 payload 再刷一遍序号，避免显示旧下标。
                if (items.isNotEmpty()) {
                    notifyItemRangeChanged(0, items.size, indexPayload)
                }
            }
        }
    }

    private fun indexOfUri(uri: String?): Int {
        if (uri == null) return -1
        val key = normalizeUri(uri)
        return items.indexOfFirst { normalizeUri(it.uri) == key }
    }

    /** 单条更新；false 表示需整表提交。 */
    fun updateItem(item: MediaItemData): Boolean {
        val index = indexOfUri(item.uri.toString())
        if (index < 0) return false
        items[index] = item
        notifyItemChanged(index)
        return true
    }

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

    fun refreshProgress(index: Int) {
        if (index in items.indices) notifyItemChanged(index, progressPayload)
    }

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
        bindIndex(holder, position)
        holder.binding.tvName.text = item.name
        bindDuration(holder, item)
        val active = isItemActive(item)
        holder.binding.imgPlaying.visibility = if (active) View.VISIBLE else View.GONE
        holder.binding.tvIndex.visibility = if (active) View.GONE else View.VISIBLE
        holder.binding.tvName.setTextColor(
            context.getColor(if (active) R.color.accent else R.color.text_primary)
        )

        bindProgress(holder, item, active)
    }

    private fun bindIndex(holder: VH, position: Int) {
        holder.binding.tvIndex.text =
            holder.itemView.context.getString(R.string.list_item_index, position + 1)
    }

    private fun isItemActive(item: MediaItemData): Boolean =
        item.uri.toString() == currentPlayingUri && isPlaying

    /** 带 payload 的绑定：进度 / 序号走快路径，其余重绑。 */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        val item = items[position]
        when {
            payloads.isNotEmpty() && payloads.all { it === progressPayload } ->
                bindProgress(holder, item, isItemActive(item))
            payloads.isNotEmpty() && payloads.all { it === indexPayload } ->
                bindIndex(holder, position)
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindDuration(holder: VH, item: MediaItemData) {
        holder.binding.tvDuration.text =
            if (item.duration > 0) formatTime(item.duration) else ""
    }

    private fun bindProgress(holder: VH, item: MediaItemData, isActive: Boolean) {
        val context = holder.itemView.context
        val progress = getProgress(item.uri.toString())
        if (progress > 0 && item.duration > 0) {
            holder.binding.progressRow.visibility = View.VISIBLE
            if (isProgressFinished(progress, item.duration)) {
                holder.binding.pbItem.progress = 100
                holder.binding.tvProgress.text = context.getString(R.string.progress_finished)
            } else {
                val percent = (progress * 100 / item.duration).toInt().coerceIn(0, 100)
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
            }
        } else {
            holder.binding.progressRow.visibility = View.GONE
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        diffScope.cancel()
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
