package com.example.player.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.example.player.model.formatTime
import kotlin.math.abs
import kotlin.math.max

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
