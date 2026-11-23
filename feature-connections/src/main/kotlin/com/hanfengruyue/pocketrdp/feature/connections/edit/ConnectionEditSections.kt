package com.hanfengruyue.pocketrdp.feature.connections.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.feature.connections.R
import kotlin.math.roundToInt

@Immutable
internal data class DisplaySettingsState(
    val colorDepth: Int,
    val desktopScaleFactor: Int,
    val targetFrameRate: Int,
    val defaultInputMode: Int,
    val useH264: Boolean,
    val preferAvc420: Boolean,
    val useGfx: Boolean,
    val performanceFlags: Int,
    val dynamicResolution: Boolean,
    val dynamicResMax: Int,
    val useCustomResolution: Boolean,
    val customWidth: String,
    val customHeight: String,
    val useMultitransport: Boolean,
)

@Immutable
internal data class DisplaySettingsCallbacks(
    val onColorDepthChange: (Int) -> Unit,
    val onScaleFactorChange: (Int) -> Unit,
    val onFrameRateChange: (Int) -> Unit,
    val onDefaultInputModeChange: (Int) -> Unit,
    val onToggleH264: (Boolean) -> Unit,
    val onPreferAvc420Change: (Boolean) -> Unit,
    val onToggleGfx: (Boolean) -> Unit,
    val onToggleLowLatencyVisuals: (Boolean) -> Unit,
    val onToggleDynamicRes: (Boolean) -> Unit,
    val onDynamicResMaxChange: (Int) -> Unit,
    val onToggleCustomResolution: (Boolean) -> Unit,
    val onCustomWidthChange: (String) -> Unit,
    val onCustomHeightChange: (String) -> Unit,
    val onToggleMultitransport: (Boolean) -> Unit,
)

@Composable
internal fun DisplaySettingsSection(
    state: DisplaySettingsState,
    callbacks: DisplaySettingsCallbacks,
) {
    val trackpadLabel = stringResource(R.string.connection_input_mode_trackpad)
    val touchLabel = stringResource(R.string.connection_input_mode_touch)
    val inputModeOptions = remember(trackpadLabel, touchLabel) {
        listOf(0 to trackpadLabel, 1 to touchLabel)
    }

    SectionTitle(stringResource(R.string.connection_section_display), Icons.Default.Tune)

    RowLabel(Icons.Default.Palette, stringResource(R.string.connection_color_depth))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colorDepthOptions.forEach { depth ->
            SelectableOptionChip(
                selected = state.colorDepth == depth,
                label = stringResource(R.string.connection_color_depth_bits, depth),
                onClick = { callbacks.onColorDepthChange(depth) },
            )
        }
    }

    Column {
        Text(
            stringResource(R.string.connection_desktop_scale, state.desktopScaleFactor),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = state.desktopScaleFactor.toFloat(),
            onValueChange = { callbacks.onScaleFactorChange(it.roundToInt()) },
            valueRange = SCALE_RANGE_MIN..SCALE_RANGE_MAX,
            steps = SCALE_SLIDER_STEPS,
        )
        Text(
            stringResource(R.string.connection_desktop_scale_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    RowLabel(Icons.Default.Speed, stringResource(R.string.connection_fixed_frame_rate))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        frameRateOptions.forEach { fps ->
            SelectableOptionChip(
                selected = state.targetFrameRate == fps,
                label = if (fps == 0) stringResource(R.string.connection_auto) else "$fps",
                onClick = { callbacks.onFrameRateChange(fps) },
            )
        }
    }
    Text(
        stringResource(R.string.connection_fixed_frame_rate_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    RowLabel(Icons.Default.TouchApp, stringResource(R.string.connection_default_input_mode))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        inputModeOptions.forEach { (value, label) ->
            SelectableOptionChip(
                selected = state.defaultInputMode == value,
                label = label,
                onClick = { callbacks.onDefaultInputModeChange(value) },
            )
        }
    }
    Text(
        stringResource(R.string.connection_default_input_mode_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    CodecControls(state = state, callbacks = callbacks)
    ResolutionControls(state = state, callbacks = callbacks)
}

@Composable
private fun CodecControls(
    state: DisplaySettingsState,
    callbacks: DisplaySettingsCallbacks,
) {
    val qualityLabel = stringResource(R.string.connection_quality_first)
    val smoothLabel = stringResource(R.string.connection_smooth_first)
    val codecTierOptions = remember(qualityLabel, smoothLabel) {
        listOf(false to qualityLabel, true to smoothLabel)
    }

    SwitchRow(
        title = stringResource(R.string.connection_h264_title),
        subtitle = stringResource(R.string.connection_h264_desc),
        checked = state.useH264,
        onChange = callbacks.onToggleH264,
        icon = Icons.Default.Videocam,
    )
    if (state.useH264) {
        RowLabel(Icons.Default.HighQuality, stringResource(R.string.connection_h264_tier))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            codecTierOptions.forEach { (avc420, label) ->
                SelectableOptionChip(
                    selected = state.preferAvc420 == avc420,
                    label = label,
                    onClick = { callbacks.onPreferAvc420Change(avc420) },
                )
            }
        }
        Text(
            stringResource(R.string.connection_h264_tier_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SwitchRow(
        title = stringResource(R.string.connection_gfx_title),
        subtitle = stringResource(R.string.connection_gfx_desc),
        checked = state.useGfx || state.useH264,
        enabled = !state.useH264,
        onChange = callbacks.onToggleGfx,
        icon = Icons.Default.AutoAwesome,
    )
    SwitchRow(
        title = stringResource(R.string.connection_low_latency_visuals_title),
        subtitle = stringResource(R.string.connection_low_latency_visuals_desc),
        checked = (state.performanceFlags and ConnectionEntity.PERF_LOW_LATENCY_VISUALS) != 0,
        onChange = callbacks.onToggleLowLatencyVisuals,
        icon = Icons.Default.Bolt,
    )
}

@Composable
private fun ResolutionControls(
    state: DisplaySettingsState,
    callbacks: DisplaySettingsCallbacks,
) {
    SwitchRow(
        title = stringResource(R.string.connection_dynamic_resolution_title),
        subtitle = stringResource(R.string.connection_dynamic_resolution_desc),
        checked = state.dynamicResolution,
        enabled = !state.useCustomResolution,
        onChange = callbacks.onToggleDynamicRes,
        icon = Icons.Default.AspectRatio,
    )
    if (state.dynamicResolution && !state.useCustomResolution) {
        RowLabel(Icons.Default.AspectRatio, stringResource(R.string.connection_dynamic_resolution_max))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dynamicResMaxOptions.forEach { value ->
                SelectableOptionChip(
                    selected = state.dynamicResMax == value,
                    label = if (value == 0) {
                        stringResource(R.string.connection_follow_device)
                    } else {
                        "${value}p"
                    },
                    onClick = { callbacks.onDynamicResMaxChange(value) },
                )
            }
        }
        Text(
            stringResource(R.string.connection_dynamic_resolution_max_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SwitchRow(
        title = stringResource(R.string.connection_custom_resolution_title),
        subtitle = stringResource(R.string.connection_custom_resolution_desc),
        checked = state.useCustomResolution,
        onChange = callbacks.onToggleCustomResolution,
        icon = Icons.Default.FitScreen,
    )
    if (state.useCustomResolution) {
        CustomResolutionFields(
            customWidth = state.customWidth,
            customHeight = state.customHeight,
            onCustomWidthChange = callbacks.onCustomWidthChange,
            onCustomHeightChange = callbacks.onCustomHeightChange,
        )
    }
    SwitchRow(
        title = stringResource(R.string.connection_multitransport_title),
        subtitle = stringResource(R.string.connection_multitransport_desc),
        checked = state.useMultitransport,
        onChange = callbacks.onToggleMultitransport,
        icon = Icons.Default.Bolt,
    )
}

@Composable
private fun CustomResolutionFields(
    customWidth: String,
    customHeight: String,
    onCustomWidthChange: (String) -> Unit,
    onCustomHeightChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = customWidth,
            onValueChange = onCustomWidthChange,
            label = { Text(stringResource(R.string.connection_width)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Text("×", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = customHeight,
            onValueChange = onCustomHeightChange,
            label = { Text(stringResource(R.string.connection_height)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        customResolutionPresets.forEach { (w, h) ->
            AssistChip(
                onClick = {
                    onCustomWidthChange(w.toString())
                    onCustomHeightChange(h.toString())
                },
                label = { Text("${w}×$h") },
            )
        }
    }
}

@Immutable
internal data class RedirectionSettingsState(
    val redirectClipboard: Boolean,
    val redirectFiles: Boolean,
    val hasAllFilesAccess: Boolean,
    val soundMode: Int,
)

@Composable
internal fun RedirectionSettingsSection(
    state: RedirectionSettingsState,
    onToggleClipboard: (Boolean) -> Unit,
    onToggleFiles: (Boolean) -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onSoundModeChange: (Int) -> Unit,
) {
    val audioClientLabel = stringResource(R.string.connection_audio_client)
    val audioServerLabel = stringResource(R.string.connection_audio_server)
    val audioDisabledLabel = stringResource(R.string.connection_audio_disabled)
    val soundModeOptions = remember(audioClientLabel, audioServerLabel, audioDisabledLabel) {
        listOf(
            1 to audioClientLabel,
            2 to audioServerLabel,
            0 to audioDisabledLabel,
        )
    }

    SectionTitle(stringResource(R.string.connection_section_redirection), Icons.Default.Cable)

    SwitchRow(
        title = stringResource(R.string.connection_clipboard_title),
        subtitle = stringResource(R.string.connection_clipboard_desc),
        checked = state.redirectClipboard,
        onChange = onToggleClipboard,
        icon = Icons.Default.ContentPaste,
    )
    SwitchRow(
        title = stringResource(R.string.connection_files_title),
        subtitle = stringResource(R.string.connection_files_desc),
        checked = state.redirectFiles,
        onChange = onToggleFiles,
        icon = Icons.Default.Folder,
    )
    if (state.redirectFiles && !state.hasAllFilesAccess) {
        Text(
            stringResource(R.string.connection_files_permission_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestAllFilesAccess) {
            Text(stringResource(R.string.connection_files_permission_action))
        }
    }

    RowLabel(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.connection_audio_title))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        soundModeOptions.forEach { (value, label) ->
            SelectableOptionChip(
                selected = state.soundMode == value,
                label = label,
                onClick = { onSoundModeChange(value) },
            )
        }
    }
    Text(
        stringResource(R.string.connection_audio_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SectionTitle(text: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SelectableOptionChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun RowLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private const val SCALE_SLIDER_STEPS = 7
private const val SCALE_RANGE_MIN = 100f
private const val SCALE_RANGE_MAX = 300f
private val colorDepthOptions = listOf(16, 24, 32)
private val frameRateOptions = listOf(0, 30, 60, 120)
private val dynamicResMaxOptions = listOf(0, 720, 900, 1080, 1200, 1440, 1600, 2160)
private val customResolutionPresets = listOf(
    1920 to 1080,
    1280 to 720,
    2560 to 1440,
    3840 to 2160,
)
