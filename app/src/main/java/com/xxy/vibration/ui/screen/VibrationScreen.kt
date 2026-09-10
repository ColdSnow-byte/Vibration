package com.xxy.vibration.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xxy.vibration.R
import com.xxy.vibration.ui.theme.VibrationTheme
import com.xxy.vibration.vibration.ContinuousConfig
import com.xxy.vibration.vibration.ContinuousStrategy
import com.xxy.vibration.vibration.VibrationMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibrationScreen(
    mode: VibrationMode,
    hasVibrator: Boolean,
    continuousConfig: ContinuousConfig,
    onToggle: (VibrationMode) -> Unit,
    onContinuousConfigChange: (ContinuousConfig) -> Unit,
    waveformOverlayVisible: Boolean,
    onWaveformOverlayVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.screen_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = { onWaveformOverlayVisibleChange(!waveformOverlayVisible) }
                    ) {
                        Icon(
                            imageVector = if (waveformOverlayVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.ShowChart
                            },
                            contentDescription = stringResource(R.string.cd_toggle_overlay)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            StatusCard(mode = mode, hasVibrator = hasVibrator)

            if (!hasVibrator) {
                Text(
                    text = stringResource(R.string.error_no_vibrator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            VibrationActionButton(
                title = stringResource(R.string.action_intermittent),
                subtitle = stringResource(R.string.action_intermittent_desc),
                icon = Icons.Default.Timelapse,
                active = mode == VibrationMode.Intermittent,
                enabled = hasVibrator,
                onClick = { onToggle(VibrationMode.Intermittent) }
            )

            VibrationActionButton(
                title = stringResource(R.string.action_continuous),
                subtitle = stringResource(R.string.action_continuous_desc),
                icon = Icons.Default.Bolt,
                active = mode == VibrationMode.Continuous,
                enabled = hasVibrator,
                onClick = { onToggle(VibrationMode.Continuous) }
            )

            VibrationActionButton(
                title = stringResource(R.string.action_function),
                subtitle = stringResource(R.string.action_function_desc),
                icon = Icons.Default.Timeline,
                active = mode == VibrationMode.Function,
                enabled = hasVibrator,
                onClick = { onToggle(VibrationMode.Function) }
            )

            AdvancedCard(
                config = continuousConfig,
                onConfigChange = onContinuousConfigChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.hint_toggle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun StatusCard(
    mode: VibrationMode,
    hasVibrator: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val active = mode != VibrationMode.Idle && hasVibrator
    val containerColor = if (active) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = iconFor(mode),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(52.dp)
                    .scale(if (active) pulse else 1f)
            )
            Text(
                text = titleFor(mode, hasVibrator),
                style = MaterialTheme.typography.headlineSmall,
                color = contentColor
            )
            Text(
                text = descriptionFor(mode, hasVibrator),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun VibrationActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 持续震动的调参区：不同设备的马达驱动表现不同，用于现场选出无停顿的组合。 */
@Composable
private fun AdvancedCard(
    config: ContinuousConfig,
    onConfigChange: (ContinuousConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.advanced_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.advanced_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.strategy == ContinuousStrategy.Waveform,
                    onClick = {
                        onConfigChange(config.copy(strategy = ContinuousStrategy.Waveform))
                    },
                    label = { Text(stringResource(R.string.strategy_waveform)) }
                )
                FilterChip(
                    selected = config.strategy == ContinuousStrategy.OneShotRenew,
                    onClick = {
                        onConfigChange(config.copy(strategy = ContinuousStrategy.OneShotRenew))
                    },
                    label = { Text(stringResource(R.string.strategy_oneshot)) }
                )
            }

            var sliderSeconds by remember(config.segmentMs) {
                mutableFloatStateOf(config.segmentMs / 1_000f)
            }
            Text(
                text = stringResource(R.string.label_segment, sliderSeconds.roundToInt()),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = sliderSeconds,
                onValueChange = { sliderSeconds = it },
                onValueChangeFinished = {
                    onConfigChange(config.copy(segmentMs = sliderSeconds.roundToInt() * 1_000L))
                },
                valueRange = ContinuousConfig.MIN_SEGMENT_MS / 1_000f..ContinuousConfig.MAX_SEGMENT_MS / 1_000f,
                steps = (ContinuousConfig.MAX_SEGMENT_MS / 1_000 - ContinuousConfig.MIN_SEGMENT_MS / 1_000 - 1).toInt()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.label_amplitude),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = config.useMaxAmplitude,
                    onCheckedChange = { onConfigChange(config.copy(useMaxAmplitude = it)) }
                )
            }
        }
    }
}

private val StatusIcons = mapOf(
    VibrationMode.Idle to Icons.Default.Timelapse,
    VibrationMode.Intermittent to Icons.Default.Timelapse,
    VibrationMode.Continuous to Icons.Default.Bolt,
    VibrationMode.Function to Icons.Default.Timeline
)

private fun iconFor(mode: VibrationMode): ImageVector = StatusIcons[mode] ?: Icons.Default.Bolt

@Composable
private fun titleFor(mode: VibrationMode, hasVibrator: Boolean): String = when {
    !hasVibrator -> stringResource(R.string.status_unavailable)
    mode == VibrationMode.Intermittent -> stringResource(R.string.status_intermittent)
    mode == VibrationMode.Continuous -> stringResource(R.string.status_continuous)
    mode == VibrationMode.Function -> stringResource(R.string.status_function)
    else -> stringResource(R.string.status_idle)
}

@Composable
private fun descriptionFor(mode: VibrationMode, hasVibrator: Boolean): String = when {
    !hasVibrator -> stringResource(R.string.error_no_vibrator)
    mode == VibrationMode.Intermittent -> stringResource(R.string.status_intermittent_desc)
    mode == VibrationMode.Continuous -> stringResource(R.string.status_continuous_desc)
    mode == VibrationMode.Function -> stringResource(R.string.status_function_desc)
    else -> stringResource(R.string.status_idle_desc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun VibrationScreenPreview() {
    VibrationTheme {
        VibrationScreen(
            mode = VibrationMode.Continuous,
            hasVibrator = true,
            continuousConfig = ContinuousConfig(),
            onToggle = {},
            onContinuousConfigChange = {},
            waveformOverlayVisible = true,
            onWaveformOverlayVisibleChange = {}
        )
    }
}
