package com.xxy.vibration.imgui

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.xxy.vibration.vibration.FunctionConfig
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * 用 ImGui (OpenGL ES 3) 绘制震动波形 / 函数图像的 [GLSurfaceView]。
 *
 * 采样在主线程按 [samplePeriodMs] 周期进行并写入 native 环形缓冲，
 * 渲染在 GLSurfaceView 的 GL 线程每帧执行，两者通过原子变量交互。
 */
class WaveformSurfaceView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    /** 传给 native 的状态快照。 */
    data class Status(
        val mode: Int,
        val strategy: Int,
        val segmentMs: Int,
        val useMaxAmplitude: Boolean
    )

    /** 采样回调：返回 0..255 的振幅，可或上 [EVENT_FLAG] 标记「马达被重启」。 */
    var onSample: (() -> Int)? = null

    /** 状态同步回调，约每 200ms 取一次。 */
    var onStatus: (() -> Status)? = null

    /** 面板操作回调：拖动 / 缩放增量（像素）与关闭事件，已切到主线程。 */
    var onPanelAction: ((dx: Int, dy: Int, dw: Int, dh: Int, closed: Boolean) -> Unit)? = null

    /** 面板里函数参数（类型 / 振幅 / 频率 / 速度）变化回调，已切到主线程。 */
    var onFunctionConfigChange: ((FunctionConfig) -> Unit)? = null

    /** 采样周期，默认 5ms（200Hz）。 */
    var samplePeriodMs: Long = 5L

    private val handler = Handler(Looper.getMainLooper())
    private val panelDelta = IntArray(4)
    private val functionParams = FloatArray(4)
    private var sampling = false

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private val sampler = object : Runnable {
        override fun run() {
            nativePushSample(onSample?.invoke() ?: 0)
            handler.postDelayed(this, samplePeriodMs)
        }
    }

    private val statusSync = object : Runnable {
        override fun run() {
            onStatus?.invoke()?.let {
                nativeSetStatus(
                    it.mode,
                    it.strategy,
                    it.segmentMs,
                    it.useMaxAmplitude,
                    1000f / samplePeriodMs
                )
            }
            handler.postDelayed(this, 200L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!sampling) {
            sampling = true
            handler.post(sampler)
            handler.post(statusSync)
        }
    }

    override fun onDetachedFromWindow() {
        sampling = false
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(statusSync)
        queueEvent { nativeDestroy() }
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 直接转发给 ImGui 的 IO，不经过 imgui_impl_android（后者面向 NativeActivity）
        nativeTouch(event.x, event.y, event.action)
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        nativeInit(resources.displayMetrics.density)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        nativeResize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        nativeRender()
        val flags = nativeConsumePanelDelta(panelDelta)
        val (dx, dy, dw, dh) = panelDelta
        if (flags != 0 || dx != 0 || dy != 0 || dw != 0 || dh != 0) {
            post { onPanelAction?.invoke(dx, dy, dw, dh, flags and 1 != 0) }
        }

        if (nativeConsumeFunctionParams(functionParams)) {
            val config = FunctionConfig(
                type = functionParams[0].roundToInt(),
                amplitude = functionParams[1],
                frequencyHz = functionParams[2],
                speed = functionParams[3]
            )
            post { onFunctionConfigChange?.invoke(config) }
        }
    }

    private external fun nativeInit(density: Float)
    private external fun nativeResize(width: Int, height: Int)
    private external fun nativeRender()
    private external fun nativeDestroy()
    private external fun nativePushSample(value: Int)
    private external fun nativeSetStatus(
        mode: Int,
        strategy: Int,
        segmentMs: Int,
        useMaxAmplitude: Boolean,
        sampleHz: Float
    )

    private external fun nativeTouch(x: Float, y: Float, action: Int)
    private external fun nativeConsumePanelDelta(out: IntArray): Int
    private external fun nativeConsumeFunctionParams(out: FloatArray): Boolean

    companion object {
        /** 采样值中表示「马达被重启」的标记位。 */
        const val EVENT_FLAG = 0x10000

        init {
            System.loadLibrary("vibration_imgui")
        }
    }
}
