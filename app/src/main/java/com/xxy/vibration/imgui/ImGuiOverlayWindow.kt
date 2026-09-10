package com.xxy.vibration.imgui

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.xxy.vibration.vibration.FunctionConfig
import kotlin.math.roundToInt

/**
 * 用 [WindowManager] 把 ImGui 面板挂成一个悬浮窗。
 *
 * 使用 TYPE_APPLICATION_PANEL：跟随本 Activity 的窗口，**不需要任何权限**（区别于
 * TYPE_APPLICATION_OVERLAY 需要 SYSTEM_ALERT_WINDOW）。面板之外的触摸会正常落到
 * 下面的 Compose 界面上，所以这是个可以真正"浮"在界面上的窗口。
 *
 * 拖动 / 缩放由面板内的 ImGui 控件产生增量，这里收到后更新布局参数。
 */
class ImGuiOverlayWindow(
    private val activity: Activity,
    private val sampleProvider: () -> Int,
    private val statusProvider: () -> WaveformSurfaceView.Status,
    private val onFunctionConfig: (FunctionConfig) -> Unit,
    private val onClosed: () -> Unit
) {

    private val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density: Float get() = activity.resources.displayMetrics.density

    private var view: WaveformSurfaceView? = null

    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        format = PixelFormat.OPAQUE
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = (PANEL_WIDTH_DP * density).roundToInt()
        height = (PANEL_HEIGHT_DP * density).roundToInt()
        x = (12f * density).roundToInt()
        y = (120f * density).roundToInt()
    }

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        // 需要 Activity 窗口的 token，onCreate 阶段可能还没挂上
        val token = activity.window.decorView.windowToken
        if (token == null) {
            activity.window.decorView.post { show() }
            return
        }
        params.token = token

        val created = WaveformSurfaceView(activity).apply {
            onSample = sampleProvider
            onStatus = statusProvider
            onPanelAction = { dx, dy, dw, dh, closed ->
                handlePanelAction(dx, dy, dw, dh, closed)
            }
            onFunctionConfigChange = onFunctionConfig
        }
        view = created
        runCatching { windowManager.addView(created, params) }
            .onFailure { view = null }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun handlePanelAction(dx: Int, dy: Int, dw: Int, dh: Int, closed: Boolean) {
        if (closed) {
            hide()
            onClosed()
            return
        }
        if (view == null) return

        val bounds = windowManager.currentWindowMetrics.bounds
        val minW = (220f * density).roundToInt()
        val minH = (180f * density).roundToInt()

        params.width = (params.width + dw).coerceIn(minW, bounds.width())
        params.height = (params.height + dh).coerceIn(minH, bounds.height())
        params.x = (params.x + dx).coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))

        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    private companion object {
        const val PANEL_WIDTH_DP = 360f
        const val PANEL_HEIGHT_DP = 300f
    }
}
