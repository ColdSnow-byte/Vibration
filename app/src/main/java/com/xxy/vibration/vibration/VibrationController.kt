package com.xxy.vibration.vibration

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** 当前的震动模式。 */
enum class VibrationMode {
    Idle,
    Intermittent,
    Continuous,
    /** 按函数曲线调制振幅。 */
    Function
}

/**
 * 持续震动的实现策略。不同设备的马达驱动（HAL）对波形循环、长时单次的响应差异很大，
 * 因此两种策略都保留，可在界面上切换以适配具体设备。
 */
enum class ContinuousStrategy {
    /** 循环波形：交给系统无限重复，单段时长即循环周期。 */
    Waveform,

    /** 单次长震 + 定时续期：每次重新调用 vibrate 续上一段。 */
    OneShotRenew
}

/** 持续震动的可调参数。 */
data class ContinuousConfig(
    val strategy: ContinuousStrategy = ContinuousStrategy.Waveform,
    /** 单段震动时长（毫秒）。 */
    val segmentMs: Long = DEFAULT_SEGMENT_MS,
    /** true 用最大振幅 255，false 用系统默认振幅（走 on() 路径，不带振幅控制）。 */
    val useMaxAmplitude: Boolean = true
) {
    companion object {
        const val DEFAULT_SEGMENT_MS = 60_000L
        const val MIN_SEGMENT_MS = 1_000L
        const val MAX_SEGMENT_MS = 60_000L
    }
}

/**
 * 函数震动的参数（与 ImGui 面板中的函数图像一致）。
 *
 * 马达只能按「分段常量振幅」输出，所以实现上是把一个周期离散成若干段，
 * 每段的振幅取该时刻的函数值，再整段循环播放。
 */
data class FunctionConfig(
    /** 0 sin / 1 cos / 2 方波 / 3 锯齿 / 4 三角。 */
    val type: Int = 0,
    /** 振幅系数 0..1。 */
    val amplitude: Float = 0.8f,
    /** 频率（Hz），即每秒重复多少个周期。 */
    val frequencyHz: Float = 1f,
    /** 图像滚动速度（仅影响绘制，不影响马达）。 */
    val speed: Float = 0.5f
)

private const val TWO_PI = 6.28318530718f

/** 计算函数在 phase（弧度）处的取值，范围 -1..1，与 native 端实现保持一致。 */
private fun evalFunction(type: Int, phase: Float): Float {
    val cycles = phase / TWO_PI
    return when (type) {
        1 -> cos(phase)
        2 -> if (sin(phase) >= 0f) 1f else -1f
        3 -> {
            val t = cycles - floor(cycles)
            2f * t - 1f
        }

        4 -> {
            val t = cycles - floor(cycles)
            4f * abs(t - 0.5f) - 1f
        }

        else -> sin(phase)
    }
}

/**
 * 马达震动控制器：封装 [Vibrator]，对外只暴露「间歇 / 持续 / 停止」三个动作，
 * 并以 [mode] 的形式向 Compose 界面提供可观察的状态。
 */
class VibrationController(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val handler = Handler(Looper.getMainLooper())
    private var renewRunnable: Runnable? = null

    /** 当前效果的起始时刻（SystemClock.elapsedRealtime）。 */
    private var effectStartedAtMs = 0L

    /** 单次续期策略下，最近一次 vibrate 的时刻。 */
    private var lastOneShotAtMs = 0L

    /** 自上次取样以来马达是否被（重新）启动过。 */
    @Volatile
    private var pendingRestart = false

    /** 设备是否具备震动马达。 */
    val hasVibrator: Boolean = vibrator.hasVibrator()

    /** 当前正在执行的震动模式，界面会自动随其变化重组。 */
    var mode: VibrationMode by mutableStateOf(VibrationMode.Idle)
        private set

    /** 持续震动的当前参数。 */
    var continuousConfig: ContinuousConfig by mutableStateOf(ContinuousConfig())
        private set

    /** 函数震动的当前参数，由 ImGui 面板的滑杆驱动。 */
    var functionConfig: FunctionConfig by mutableStateOf(FunctionConfig())
        private set

    /**
     * 点击按钮：若点击的正是当前模式则停止，否则切换到该模式。
     */
    @RequiresPermission(Manifest.permission.VIBRATE)
    fun toggle(mode: VibrationMode) {
        if (this.mode == mode) stop() else start(mode)
    }

    /** 修改持续震动参数；若正在持续震动则立即按新参数重启，方便对比效果。 */
    @RequiresPermission(Manifest.permission.VIBRATE)
    fun updateContinuousConfig(config: ContinuousConfig) {
        continuousConfig = config
        if (mode == VibrationMode.Continuous) start(VibrationMode.Continuous)
    }

    /** 修改函数震动参数；若正在函数震动则立即按新参数重启，实现"边调边震"。 */
    @RequiresPermission(Manifest.permission.VIBRATE)
    fun updateFunctionConfig(config: FunctionConfig) {
        functionConfig = config
        if (mode == VibrationMode.Function) start(VibrationMode.Function)
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    fun start(mode: VibrationMode) {
        if (!hasVibrator || mode == VibrationMode.Idle) return

        cancelRenew()
        vibrator.cancel()
        this.mode = mode
        effectStartedAtMs = SystemClock.elapsedRealtime()

        when (mode) {
            VibrationMode.Intermittent -> vibrate(createIntermittentEffect())

            VibrationMode.Continuous -> when (continuousConfig.strategy) {
                ContinuousStrategy.Waveform -> vibrate(createContinuousWaveform())
                ContinuousStrategy.OneShotRenew -> {
                    vibrate(createContinuousOneShot())
                    scheduleRenew()
                }
            }

            VibrationMode.Function -> vibrate(createFunctionEffect())

            VibrationMode.Idle -> Unit
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    fun stop() {
        cancelRenew()
        vibrator.cancel()
        pendingRestart = true
        mode = VibrationMode.Idle
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun vibrate(effect: VibrationEffect) {
        pendingRestart = true
        if (mode == VibrationMode.Continuous &&
            continuousConfig.strategy == ContinuousStrategy.OneShotRenew
        ) {
            lastOneShotAtMs = SystemClock.elapsedRealtime()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vibrator.vibrate(effect, attributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }

    /**
     * 间歇震动：震 300ms -> 停 700ms 无限循环。
     * 这里的停顿本来就是想要的效果，用循环波形即可。
     */
    private fun createIntermittentEffect(): VibrationEffect = VibrationEffect.createWaveform(
        longArrayOf(0, INTERMITTENT_ON_MS, INTERMITTENT_OFF_MS),
        intArrayOf(0, MAX_AMPLITUDE, 0),
        1
    )

    /**
     * 函数震动：把一个周期离散成若干段常量振幅，再整段循环。
     *
     * 段长约 [FUNCTION_SEGMENT_MS]（马达跟得上又不至于太碎），段数由周期长度决定；
     * 函数值 -1..1 线性映射到振幅 0..255，所以曲线的频率就是马达的调制频率。
     */
    private fun createFunctionEffect(): VibrationEffect {
        val periodMs = 1000f / functionConfig.frequencyHz.coerceIn(0.1f, 20f)
        val segments = (periodMs / FUNCTION_SEGMENT_MS)
            .roundToInt()
            .coerceIn(FUNCTION_MIN_SEGMENTS, FUNCTION_MAX_SEGMENTS)
        val segmentMs = (periodMs / segments).roundToLong().coerceAtLeast(FUNCTION_MIN_SEGMENT_MS)

        val timings = LongArray(segments) { segmentMs }
        val amplitudes = IntArray(segments) { index ->
            amplitudeOf(functionConfig, TWO_PI * index / segments)
        }
        return VibrationEffect.createWaveform(timings, amplitudes, 0)
    }

    /** 函数在某相位处对应的马达振幅 0..255。 */
    private fun amplitudeOf(config: FunctionConfig, phase: Float): Int {
        val y = evalFunction(config.type, phase)
        // -1..1 -> 0..1
        return ((y + 1f) / 2f * config.amplitude * MAX_AMPLITUDE)
            .roundToInt()
            .coerceIn(0, MAX_AMPLITUDE)
    }

    /**
     * 持续震动（循环波形）：起始那一段（时长 0）的振幅同样填 [MAX_AMPLITUDE]。
     *
     * 若把它的振幅写成 0，每轮循环回到开头时振幅都会掉到 0 再拉回 255，
     * 马达需要重新起振（LRA 起振通常几十毫秒），手感上就是每秒一次顿挫。
     * 让循环点两侧振幅恒定，马达就始终维持在目标振幅上。
     */
    private fun createContinuousWaveform(): VibrationEffect {
        val timings = longArrayOf(0, continuousConfig.segmentMs)
        return if (continuousConfig.useMaxAmplitude) {
            VibrationEffect.createWaveform(
                timings,
                intArrayOf(MAX_AMPLITUDE, MAX_AMPLITUDE),
                0
            )
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createWaveform(timings, 0)
        }
    }

    /** 持续震动（单次长震）：单次震动，由 [scheduleRenew] 在到期前续上。 */
    private fun createContinuousOneShot(): VibrationEffect = VibrationEffect.createOneShot(
        continuousConfig.segmentMs,
        if (continuousConfig.useMaxAmplitude) MAX_AMPLITUDE else VibrationEffect.DEFAULT_AMPLITUDE
    )

    private fun scheduleRenew() {
        val interval = renewIntervalMs()
        val runnable = object : Runnable {
            override fun run() {
                if (mode != VibrationMode.Continuous) {
                    renewRunnable = null
                    return
                }
                vibrate(createContinuousOneShot())
                handler.postDelayed(this, interval)
            }
        }
        renewRunnable = runnable
        handler.postDelayed(runnable, interval)
    }

    /** 比单段时长略短就续上下一段；段本身很短时间隔不低于 [MIN_RENEW_MS]。 */
    private fun renewIntervalMs(): Long =
        (continuousConfig.segmentMs - 1_000L).coerceAtLeast(MIN_RENEW_MS)

    private fun cancelRenew() {
        renewRunnable?.let { handler.removeCallbacks(it) }
        renewRunnable = null
    }

    /**
     * 采样当前时刻的振幅 0..255，供波形界面绘制。
     *
     * 注意：这是按当前 [VibrationEffect] 参数推算出的**请求波形**，
     * 不是马达的实测输出（应用层读不到马达真实状态）。
     */
    fun sampleAmplitude(nowMs: Long = SystemClock.elapsedRealtime()): Int = when (mode) {
        VibrationMode.Idle -> 0

        VibrationMode.Intermittent -> {
            val cycle = INTERMITTENT_ON_MS + INTERMITTENT_OFF_MS
            val offset = (nowMs - effectStartedAtMs) % cycle
            Log.d("mada", "---"+cycle);
            if (offset < INTERMITTENT_ON_MS) MAX_AMPLITUDE else 0
        }

        VibrationMode.Continuous -> when (continuousConfig.strategy) {
            // 循环波形：整个循环内振幅恒定
            ContinuousStrategy.Waveform -> MAX_AMPLITUDE

            // 单次续期：段内满幅，段外为 0；续期不及时就会在波形上看到缺口
            ContinuousStrategy.OneShotRenew ->
                if (nowMs - lastOneShotAtMs in 0 until continuousConfig.segmentMs) MAX_AMPLITUDE else 0
        }

        VibrationMode.Function -> {
            val seconds = (nowMs - effectStartedAtMs) / 1000f
            amplitudeOf(functionConfig, TWO_PI * functionConfig.frequencyHz * seconds)
        }
    }

    /** 取出「马达被重启」事件标记，取到即清除。 */
    fun consumeRestartEvent(): Boolean {
        if (!pendingRestart) return false
        pendingRestart = false
        return true
    }

    private companion object {
        /** 公开 SDK 里没有 VibrationEffect.MAX_AMPLITUDE，振幅上限就是 255。 */
        const val MAX_AMPLITUDE = 255

        const val INTERMITTENT_ON_MS = 300L
        const val INTERMITTENT_OFF_MS = 500L
        const val MIN_RENEW_MS = 500L

        /** 函数震动：目标段长、段长下限与段数上下限。 */
        const val FUNCTION_SEGMENT_MS = 20f
        const val FUNCTION_MIN_SEGMENT_MS = 10L
        const val FUNCTION_MIN_SEGMENTS = 4
        const val FUNCTION_MAX_SEGMENTS = 64
    }
}
