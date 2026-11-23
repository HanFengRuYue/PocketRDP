package com.hanfengruyue.pocketrdp.feature.connections.edit

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.feature.connections.R

@Immutable
private data class BasicInfoState(
    val name: String,
    val host: String,
    val port: String,
    val username: String,
    val domain: String,
    val password: String,
    val hasExistingPassword: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(
    onClose: () -> Unit,
    viewModel: ConnectionEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAllFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val displayCallbacks = remember(viewModel) {
        DisplaySettingsCallbacks(
            onColorDepthChange = viewModel::updateColorDepth,
            onScaleFactorChange = viewModel::updateScaleFactor,
            onFrameRateChange = viewModel::updateFrameRate,
            onDefaultInputModeChange = viewModel::updateDefaultInputMode,
            onToggleH264 = viewModel::toggleH264,
            onPreferAvc420Change = viewModel::updatePreferAvc420,
            onToggleGfx = viewModel::toggleGfx,
            onToggleLowLatencyVisuals = viewModel::toggleLowLatencyVisuals,
            onToggleDynamicRes = viewModel::toggleDynamicRes,
            onDynamicResMaxChange = viewModel::updateDynamicResMax,
            onToggleCustomResolution = viewModel::toggleCustomResolution,
            onCustomWidthChange = viewModel::updateCustomWidth,
            onCustomHeightChange = viewModel::updateCustomHeight,
            onToggleMultitransport = viewModel::toggleMultitransport,
        )
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onClose()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
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
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.failure == ConnectionEditFailure.LOAD_FAILED ||
                state.failure == ConnectionEditFailure.NOT_FOUND -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = connectionEditFailureText(requireNotNull(state.failure)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.connection_section_basic), Icons.Default.Badge)
                BasicInfoFields(
                    state = BasicInfoState(
                        name = state.name,
                        host = state.host,
                        port = state.port,
                        username = state.username,
                        domain = state.domain,
                        password = state.password,
                        hasExistingPassword = state.hasExistingPassword,
                    ),
                    viewModel = viewModel,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DisplaySettingsSection(
                    state = DisplaySettingsState(
                        colorDepth = state.colorDepth,
                        desktopScaleFactor = state.desktopScaleFactor,
                        targetFrameRate = state.targetFrameRate,
                        defaultInputMode = state.defaultInputMode,
                        useH264 = state.useH264,
                        preferAvc420 = state.preferAvc420,
                        useGfx = state.useGfx,
                        performanceFlags = state.performanceFlags,
                        dynamicResolution = state.dynamicResolution,
                        dynamicResMax = state.dynamicResMax,
                        useCustomResolution = state.useCustomResolution,
                        customWidth = state.customWidth,
                        customHeight = state.customHeight,
                        useMultitransport = state.useMultitransport,
                    ),
                    callbacks = displayCallbacks,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                RedirectionSettingsSection(
                    state = RedirectionSettingsState(
                        redirectClipboard = state.redirectClipboard,
                        redirectFiles = state.redirectFiles,
                        hasAllFilesAccess = hasAllFilesAccess,
                        soundMode = state.soundMode,
                    ),
                    onToggleClipboard = viewModel::toggleClipboard,
                    onToggleFiles = remember(viewModel, context) {
                        { on ->
                            viewModel.toggleFiles(on)
                            val granted = Environment.isExternalStorageManager()
                            hasAllFilesAccess = granted
                            if (on && !granted) {
                                requestAllFilesAccess(context)
                            }
                        }
                    },
                    onRequestAllFilesAccess = remember(context) {
                        { requestAllFilesAccess(context) }
                    },
                    onSoundModeChange = viewModel::updateSoundMode,
                )

                EditFeedbackSection(
                    errors = state.errors,
                    failure = state.failure,
                    saving = state.saving,
                    onConsumeErrors = viewModel::consumeErrors,
                    onSave = viewModel::save,
                )
            }
        }
    }
}

@Composable
private fun EditFeedbackSection(
    errors: List<ConnectionEditError>,
    failure: ConnectionEditFailure?,
    saving: Boolean,
    onConsumeErrors: () -> Unit,
    onSave: () -> Unit,
) {
    if (errors.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    errors.forEach { msg ->
                        Text(
                            stringResource(R.string.connection_error_bullet, connectionEditErrorText(msg)),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                IconButton(onClick = onConsumeErrors) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.connection_action_ignore),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
    failure?.let { activeFailure ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = connectionEditFailureText(activeFailure),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                IconButton(onClick = onConsumeErrors) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.connection_action_ignore),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }

    Spacer(Modifier.size(8.dp))
    Button(
        onClick = onSave,
        enabled = !saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (saving) {
                stringResource(R.string.connection_action_saving)
            } else {
                stringResource(R.string.connection_action_save)
            },
        )
    }
    Spacer(Modifier.size(40.dp))
}

@Composable
private fun BasicInfoFields(state: BasicInfoState, viewModel: ConnectionEditViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < compactBreakpointDp
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = viewModel::updatePort,
                        label = { Text(stringResource(R.string.connection_field_port)) },
                        leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(portFieldWidthDp),
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
private fun requestAllFilesAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "package:${context.packageName}".toUri(),
            ),
        )
    }.onFailure {
        // Some ROMs reject the per-app intent — fall back to the generic all-files-access list.
        val action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        runCatching { context.startActivity(Intent(action)) }
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
        ConnectionEditError.FRAMEBUFFER_TOO_LARGE -> R.string.connection_error_framebuffer_too_large
    },
)

@Composable
private fun connectionEditFailureText(failure: ConnectionEditFailure): String = stringResource(
    when (failure) {
        ConnectionEditFailure.LOAD_FAILED -> R.string.connection_error_load_failed
        ConnectionEditFailure.NOT_FOUND -> R.string.connection_error_not_found
        ConnectionEditFailure.SAVE_FAILED -> R.string.connection_error_save_failed
    },
)

private val compactBreakpointDp = 480.dp
private val portFieldWidthDp = 150.dp
