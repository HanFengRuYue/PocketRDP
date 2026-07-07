package com.hanfengruyue.pocketrdp.feature.connections.edit

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.feature.connections.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(
    onClose: () -> Unit,
    viewModel: ConnectionEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inputModeOptions = listOf(
        0 to stringResource(R.string.connection_input_mode_trackpad),
        1 to stringResource(R.string.connection_input_mode_touch),
    )
    val codecTierOptions = listOf(
        false to stringResource(R.string.connection_quality_first),
        true to stringResource(R.string.connection_smooth_first),
    )
    val soundModeOptions = listOf(
        1 to stringResource(R.string.connection_audio_client),
        2 to stringResource(R.string.connection_audio_server),
        0 to stringResource(R.string.connection_audio_disabled),
    )

    LaunchedEffect(state.saved) {
        if (state.saved) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.id == null) {
                            stringResource(R.string.connection_edit_title_new)
                        } else {
                            stringResource(R.string.connection_edit_title_edit)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.connection_action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.connection_section_basic), Icons.Default.Badge)
            BasicInfoFields(state = state, viewModel = viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionTitle(stringResource(R.string.connection_section_display), Icons.Default.Tune)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(Icons.Default.Palette, stringResource(R.string.connection_color_depth), Modifier.weight(1f))
                listOf(16, 24, 32).forEach { depth ->
                    AssistChip(
                        onClick = { viewModel.updateColorDepth(depth) },
                        label = { Text(stringResource(R.string.connection_color_depth_bits, depth)) },
                        leadingIcon = null,
                        enabled = state.colorDepth != depth,
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
                    onValueChange = { viewModel.updateScaleFactor(it.toInt()) },
                    valueRange = 100f..300f,
                    steps = 7,
                )
                Text(
                    stringResource(R.string.connection_desktop_scale_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(Icons.Default.Speed, stringResource(R.string.connection_fixed_frame_rate), Modifier.weight(1f))
                frameRateOptions.forEach { fps ->
                    AssistChip(
                        onClick = { viewModel.updateFrameRate(fps) },
                        label = { Text(if (fps == 0) stringResource(R.string.connection_auto) else "$fps") },
                        leadingIcon = null,
                        enabled = state.targetFrameRate != fps,
                    )
                }
            }
            Text(
                stringResource(R.string.connection_fixed_frame_rate_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(Icons.Default.TouchApp, stringResource(R.string.connection_default_input_mode), Modifier.weight(1f))
                inputModeOptions.forEach { (value, label) ->
                    AssistChip(
                        onClick = { viewModel.updateDefaultInputMode(value) },
                        label = { Text(label) },
                        enabled = state.defaultInputMode != value,
                    )
                }
            }
            Text(
                stringResource(R.string.connection_default_input_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SwitchRow(
                title = stringResource(R.string.connection_h264_title),
                subtitle = stringResource(R.string.connection_h264_desc),
                checked = state.useH264,
                onChange = viewModel::toggleH264,
                icon = Icons.Default.Videocam,
            )
            if (state.useH264) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowLabel(Icons.Default.HighQuality, stringResource(R.string.connection_h264_tier), Modifier.weight(1f))
                    codecTierOptions.forEach { (avc420, label) ->
                        AssistChip(
                            onClick = { viewModel.updatePreferAvc420(avc420) },
                            label = { Text(label) },
                            enabled = state.preferAvc420 != avc420,
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
                // When H.264 is on, the H264 branch in buildCommandLine always emits /gfx:AVC444 and
                // this toggle is bypassed — so lock it ON+disabled to reflect reality instead of
                // showing a switch that silently does nothing (field bug: 关掉 GFX 却仍生效).
                checked = state.useGfx || state.useH264,
                enabled = !state.useH264,
                onChange = viewModel::toggleGfx,
                icon = Icons.Default.AutoAwesome,
            )
            SwitchRow(
                title = stringResource(R.string.connection_low_latency_visuals_title),
                subtitle = stringResource(R.string.connection_low_latency_visuals_desc),
                checked = (state.performanceFlags and ConnectionEntity.PERF_LOW_LATENCY_VISUALS) != 0,
                onChange = viewModel::toggleLowLatencyVisuals,
                icon = Icons.Default.Bolt,
            )
            SwitchRow(
                title = stringResource(R.string.connection_dynamic_resolution_title),
                subtitle = stringResource(R.string.connection_dynamic_resolution_desc),
                checked = state.dynamicResolution,
                enabled = !state.useCustomResolution,
                onChange = viewModel::toggleDynamicRes,
                icon = Icons.Default.AspectRatio,
            )
            if (state.dynamicResolution && !state.useCustomResolution) {
                RowLabel(Icons.Default.AspectRatio, stringResource(R.string.connection_dynamic_resolution_max))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dynamicResMaxOptions.forEach { value ->
                        AssistChip(
                            onClick = { viewModel.updateDynamicResMax(value) },
                            label = {
                                Text(
                                    if (value == 0) {
                                        stringResource(R.string.connection_follow_device)
                                    } else {
                                        "${value}p"
                                    },
                                )
                            },
                            enabled = state.dynamicResMax != value,
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
                onChange = viewModel::toggleCustomResolution,
                icon = Icons.Default.FitScreen,
            )
            if (state.useCustomResolution) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.customWidth,
                        onValueChange = viewModel::updateCustomWidth,
                        label = { Text(stringResource(R.string.connection_width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text("×", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.customHeight,
                        onValueChange = viewModel::updateCustomHeight,
                        label = { Text(stringResource(R.string.connection_height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1920 to 1080, 1280 to 720, 2560 to 1440, 3840 to 2160).forEach { (w, h) ->
                        AssistChip(
                            onClick = {
                                viewModel.updateCustomWidth(w.toString())
                                viewModel.updateCustomHeight(h.toString())
                            },
                            label = { Text("${w}×$h") },
                        )
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.connection_multitransport_title),
                subtitle = stringResource(R.string.connection_multitransport_desc),
                checked = state.useMultitransport,
                onChange = viewModel::toggleMultitransport,
                icon = Icons.Default.Bolt,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionTitle(stringResource(R.string.connection_section_redirection), Icons.Default.Cable)

            SwitchRow(
                title = stringResource(R.string.connection_clipboard_title),
                subtitle = stringResource(R.string.connection_clipboard_desc),
                checked = state.redirectClipboard,
                onChange = viewModel::toggleClipboard,
                icon = Icons.Default.ContentPaste,
            )
            SwitchRow(
                title = stringResource(R.string.connection_files_title),
                subtitle = stringResource(R.string.connection_files_desc),
                checked = state.redirectFiles,
                onChange = { on ->
                    viewModel.toggleFiles(on)
                    // Grant the special permission at setup time so the drive is already readable on the
                    // first connect (the /drive path is parsed once at connect — granting later needs a
                    // reconnect). minSdk 31 ≥ 30, so isExternalStorageManager is always available.
                    if (on && !android.os.Environment.isExternalStorageManager()) {
                        requestAllFilesAccess(context)
                    }
                },
                icon = Icons.Default.Folder,
            )
            if (state.redirectFiles && !Environment.isExternalStorageManager()) {
                Text(
                    stringResource(R.string.connection_files_permission_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { requestAllFilesAccess(context) }) {
                    Text(stringResource(R.string.connection_files_permission_action))
                }
            }

            RowLabel(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.connection_audio_title))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                soundModeOptions.forEach { (value, label) ->
                    AssistChip(
                        onClick = { viewModel.updateSoundMode(value) },
                        label = { Text(label) },
                        enabled = state.soundMode != value,
                    )
                }
            }
            Text(
                stringResource(R.string.connection_audio_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.errors.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        state.errors.forEach { msg ->
                            Text(
                                stringResource(R.string.connection_error_bullet, connectionEditErrorText(msg)),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.saving) {
                        stringResource(R.string.connection_action_saving)
                    } else {
                        stringResource(R.string.connection_action_save)
                    },
                )
            }
            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun BasicInfoFields(state: ConnectionEditUiState, viewModel: ConnectionEditViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 480.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text(stringResource(R.string.connection_field_name)) },
                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::updateHost,
                label = { Text(stringResource(R.string.connection_field_host)) },
                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (compact) {
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::updatePort,
                    label = { Text(stringResource(R.string.connection_field_port)) },
                    leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::updateUsername,
                    label = { Text(stringResource(R.string.connection_field_username)) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.domain,
                    onValueChange = viewModel::updateDomain,
                    label = { Text(stringResource(R.string.connection_field_domain)) },
                    leadingIcon = { Icon(Icons.Default.Domain, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = viewModel::updatePort,
                        label = { Text(stringResource(R.string.connection_field_port)) },
                        leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                    )
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::updateUsername,
                        label = { Text(stringResource(R.string.connection_field_username)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = state.domain,
                    onValueChange = viewModel::updateDomain,
                    label = { Text(stringResource(R.string.connection_field_domain)) },
                    leadingIcon = { Icon(Icons.Default.Domain, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = {
                    Text(
                        if (state.hasExistingPassword) {
                            stringResource(R.string.connection_field_password_keep)
                        } else {
                            stringResource(R.string.connection_field_password)
                        },
                    )
                },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Open the system "All files access" settings page for this app (folder redirection 整盘共享). */
private fun requestAllFilesAccess(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ),
        )
    }.onFailure {
        // Some ROMs reject the per-app intent — fall back to the generic all-files-access list.
        val action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        runCatching { context.startActivity(android.content.Intent(action)) }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
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

/** A row label with a leading muted icon — used for the chip-row option labels. */
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

@Composable
private fun connectionEditErrorText(error: ConnectionEditError): String = stringResource(
    when (error) {
        ConnectionEditError.NAME_REQUIRED -> R.string.connection_error_name_required
        ConnectionEditError.HOST_REQUIRED -> R.string.connection_error_host_required
        ConnectionEditError.PORT_INVALID -> R.string.connection_error_port_invalid
        ConnectionEditError.USERNAME_REQUIRED -> R.string.connection_error_username_required
        ConnectionEditError.PASSWORD_REQUIRED -> R.string.connection_error_password_required
        ConnectionEditError.CUSTOM_WIDTH_INVALID -> R.string.connection_error_custom_width_invalid
        ConnectionEditError.CUSTOM_HEIGHT_INVALID -> R.string.connection_error_custom_height_invalid
    },
)

/** Fixed frame-rate presets shown as chips; 0 = 自动 (follow the device screen refresh rate). */
private val frameRateOptions = listOf(0, 30, 60, 120)

/** Dynamic-resolution short-edge cap presets; 0 = 跟随设备 / uncapped. */
private val dynamicResMaxOptions = listOf(0, 720, 900, 1080, 1200, 1440, 1600, 2160)
