package com.example.player.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.player.databinding.ActivityMainBinding

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
            val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
            lp.setMargins(0, 0, 0, 0)
            binding.playerCard.layoutParams = lp
        } else if (isFullscreen) {
            // 全屏：ZOOM 等比放大铺满屏幕，无圆角无边距，避免黑边
            binding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            binding.playerCard.radius = 0f
            val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
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
        val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val m = (12 * activity.resources.displayMetrics.density).toInt()
        lp.setMargins(m, m, m, m / 3)
        binding.playerCard.radius = 18 * activity.resources.displayMetrics.density
        binding.playerCard.layoutParams = lp
    }

    /** 切换全屏：隐藏/显示顶栏与播放列表，横屏/竖屏，隐藏/显示系统栏 */
    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        if (enabled) {
            val lp = binding.playerCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
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
