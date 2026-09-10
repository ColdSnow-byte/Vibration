package com.xxy.vibration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xxy.vibration.imgui.ImGuiOverlayWindow
import com.xxy.vibration.imgui.WaveformSurfaceView
import com.xxy.vibration.ui.screen.VibrationScreen
import com.xxy.vibration.ui.theme.VibrationTheme
import com.xxy.vibration.vibration.VibrationController

class MainActivity : ComponentActivity() {

    private lateinit var vibrationController: VibrationController
    private lateinit var overlay: ImGuiOverlayWindow
    private var overlayVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrationController = VibrationController(this)
        overlay = ImGuiOverlayWindow(
            activity = this,
            sampleProvider = {
                vibrationController.sampleAmplitude() or
                    if (vibrationController.consumeRestartEvent()) {
                        WaveformSurfaceView.EVENT_FLAG
                    } else {
                        0
                    }
            },
            statusProvider = {
                WaveformSurfaceView.Status(
                    mode = vibrationController.mode.ordinal,
                    strategy = vibrationController.continuousConfig.strategy.ordinal,
                    segmentMs = vibrationController.continuousConfig.segmentMs.toInt(),
                    useMaxAmplitude = vibrationController.continuousConfig.useMaxAmplitude
                )
            },
            onFunctionConfig = { vibrationController.updateFunctionConfig(it) },
            onClosed = { overlayVisible = false }
        )

        enableEdgeToEdge()
        setContent {
            VibrationTheme {
                VibrationScreen(
                    mode = vibrationController.mode,
                    hasVibrator = vibrationController.hasVibrator,
                    continuousConfig = vibrationController.continuousConfig,
                    onToggle = { vibrationController.toggle(it) },
                    onContinuousConfigChange = { vibrationController.updateContinuousConfig(it) },
                    waveformOverlayVisible = overlayVisible,
                    onWaveformOverlayVisibleChange = { visible ->
                        overlayVisible = visible
                        if (visible) overlay.show() else overlay.hide()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (overlayVisible) overlay.show()
    }

    override fun onStop() {
        // 离开页面时停止马达，避免后台持续振动
        if (::vibrationController.isInitialized) vibrationController.stop()
        if (::overlay.isInitialized) overlay.hide()
        super.onStop()
    }
}
