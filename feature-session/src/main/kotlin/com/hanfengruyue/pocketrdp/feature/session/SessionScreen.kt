package com.hanfengruyue.pocketrdp.feature.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.data.preferences.DEFAULT_SIMULATED_CURSOR_SCALE
import com.hanfengruyue.pocketrdp.core.data.preferences.DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS
import com.hanfengruyue.pocketrdp.core.data.preferences.MAX_SIMULATED_CURSOR_SCALE
import com.hanfengruyue.pocketrdp.core.data.preferences.MAX_FUNCTION_TOOLBAR_QUICK_IDS
import com.hanfengruyue.pocketrdp.core.data.preferences.MIN_SIMULATED_CURSOR_SCALE
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpClient
import com.hanfengruyue.pocketrdp.core.rdp.RdpCursor
import com.hanfengruyue.pocketrdp.core.ui.findActivity
import com.hanfengruyue.pocketrdp.feature.session.input.RdpInputController
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.SessionImeBridge
import com.hanfengruyue.pocketrdp.feature.session.input.UserTransform
import com.hanfengruyue.pocketrdp.feature.session.input.sessionGestures
import com.hanfengruyue.pocketrdp.feature.session.render.RdpSurface
import com.hanfengruyue.pocketrdp.feature.session.ui.FloatingGlassBackground
import com.hanfengruyue.pocketrdp.feature.session.ui.FloatingGlassContext
import com.hanfengruyue.pocketrdp.feature.session.ui.GlassBackdropFrame
import com.hanfengruyue.pocketrdp.feature.session.ui.GlassChromeBackground
import com.hanfengruyue.pocketrdp.feature.session.ui.GlassChromeEdge
import com.hanfengruyue.pocketrdp.feature.session.ui.SessionStatusTitle
import com.hanfengruyue.pocketrdp.feature.session.ui.ToolbarGlassChrome
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun shouldDismissIme(event: Lifecycle.Event): Boolean = event == Lifecycle.Event.ON_PAUSE

internal fun shouldShowConnectingOverlay(
    status: SessionConnectionStatus,
    hasPublishedFrame: Boolean,
): Boolean = when (status) {
    SessionConnectionStatus.Connecting,
    SessionConnectionStatus.Reconnecting,
    -> true
    SessionConnectionStatus.Connected -> !hasPublishedFrame
    SessionConnectionStatus.Idle,
    is SessionConnectionStatus.Disconnected,
    is SessionConnectionStatus.Failed,
    -> false
}

internal fun shouldShowTerminalStatusOverlay(
    status: SessionConnectionStatus,
    hasActiveModalPrompt: Boolean,
): Boolean {
    val isTerminal = status is SessionConnectionStatus.Failed ||
        status is SessionConnectionStatus.Disconnected
    return !hasActiveModalPrompt && isTerminal
}

internal data class ImeInsetSyncState(
    val wasShown: Boolean = false,
    val shouldDismiss: Boolean = false,
)

internal fun stepImeInsetSync(
    imeVisible: Boolean,
    imeBottomPx: Int,
    wasShown: Boolean,
): ImeInsetSyncState = when {
    !imeVisible -> ImeInsetSyncState(wasShown = false, shouldDismiss = false)
    imeBottomPx > 0 -> ImeInsetSyncState(wasShown = true, shouldDismiss = false)
    wasShown -> ImeInsetSyncState(wasShown = false, shouldDismiss = true)
    else -> ImeInsetSyncState(wasShown = false, shouldDismiss = false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    connectionId: Long,
    onClose: () -> Unit,
    onDisconnect: () -> Unit = onClose,
    toolbarAlpha: Float = CHROME_ALPHA,
    controlAlpha: Float = CHROME_ALPHA,
    cursorScale: Float = DEFAULT_SIMULATED_CURSOR_SCALE,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rdpClient = viewModel.rdpClient
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toolbarBg = Color.Black.copy(alpha = toolbarAlpha.coerceIn(MIN_CHROME_ALPHA, MAX_CHROME_ALPHA))
    val controlBg = Color.Black.copy(alpha = controlAlpha.coerceIn(MIN_CHROME_ALPHA, MAX_CHROME_ALPHA))
    val simulatedCursorScale = cursorScale.coerceIn(MIN_SIMULATED_CURSOR_SCALE, MAX_SIMULATED_CURSOR_SCALE)
    val hasPublishedFrame by rdpClient.buffer.hasPublishedFrame.collectAsStateWithLifecycle(initialValue = false)
    val glassBackdropState = remember { mutableStateOf<GlassBackdropFrame?>(null) }
    val chromeViewportSize = remember { mutableStateOf(IntSize.Zero) }
    val chromeViewportTopInRoot = remember { mutableFloatStateOf(0f) }
    val chromeViewportPositionOnScreen = remember { mutableStateOf(Offset.Zero) }
    val topChromeTopInRoot = remember { mutableFloatStateOf(0f) }
    BackHandler { onClose() }
    var showLocalNetworkPermissionDialog by remember(connectionId) { mutableStateOf(false) }
    var allFilesPromptShown by remember(connectionId) { mutableStateOf(false) }
    var showAllFilesDialog by remember(connectionId) { mutableStateOf(false) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showLocalNetworkPermissionDialog = false
            viewModel.ensureStarted(connectionId)
        } else {
            showLocalNetworkPermissionDialog = true
        }
    }
    LaunchedEffect(connectionId) {
        if (Build.VERSION.SDK_INT < ANDROID_API_37 ||
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.ensureStarted(connectionId)
        } else {
            localNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
        }
    }
    val anyGlassVisible = toolbarBg.alpha > 0f || controlBg.alpha > 0f
    val canSampleGlassBackdrop = anyGlassVisible &&
        hasPublishedFrame &&
        state.status is SessionConnectionStatus.Connected
    LaunchedEffect(rdpClient, canSampleGlassBackdrop) {
        if (!canSampleGlassBackdrop) {
            glassBackdropState.value = null
            return@LaunchedEffect
        }
        suspend fun refreshBackdrop() {
            val snap = withContext(Dispatchers.Default) {
                rdpClient.buffer.snapshot(GLASS_BACKDROP_MAX_DIM)
            }
            glassBackdropState.value = snap?.let { GlassBackdropFrame(it.width, it.height, it.asImageBitmap()) }
        }
        // Decorative low-frequency snapshot captured off the main thread so glass chrome never
        // retains a live double-buffer bitmap across frame swaps or after disconnect.
        refreshBackdrop()
        rdpClient.frameUpdates.collect {
            refreshBackdrop()
            delay(GLASS_SAMPLE_INTERVAL_MS)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, connectionId) {
        val observer = LifecycleEventObserver { _, event ->
            when {
                shouldDismissIme(event) -> viewModel.setImeVisible(false)
                event == Lifecycle.Event.ON_RESUME -> {
                    if (viewModel.retryAfterAllFilesAccess()) {
                        // Returning from Settings without granting access must not strand the
                        // session in Idle with a permanently consumed one-shot prompt.
                        allFilesPromptShown = true
                        showAllFilesDialog = true
                    }
                    if (Build.VERSION.SDK_INT < ANDROID_API_37 ||
                        ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK_PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        showLocalNetworkPermissionDialog = false
                        viewModel.ensureStartedOnResume(connectionId)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val controller = remember(rdpClient) {
        RdpInputController(
            sendCursor = rdpClient::sendCursorEvent,
            sendTouch = rdpClient::sendTouch,
        )
    }
    LaunchedEffect(state.mode) { controller.setMode(state.mode) }
    LaunchedEffect(state.remoteWidth, state.remoteHeight) {
        controller.setRemoteSize(state.remoteWidth, state.remoteHeight)
        // Resetting local zoom on resize keeps the picture inside the visible area when the
        // server changes resolution (e.g. RDP session reconnect after monitor-layout retry).
        controller.resetUserTransform()
    }
    LaunchedEffect(state.status) {
        val current = state.status
        if (current is SessionConnectionStatus.Failed) {
            snackbarHostState.showSnackbar(
                message = current.error,
                duration = SnackbarDuration.Long,
                withDismissAction = true,
            )
        }
    }

    // Local zoom/pan is owned by the controller and exposed as a StateFlow (userTransform).
    // Collected ONCE here and passed down as a State<UserTransform> so GPU layers read it
    // deferred and controls derive only minimal flags without recomposing on every pan frame.
    val chromeTransformState = controller.userTransform.collectAsStateWithLifecycle()
    val floatingGlassContext = remember(
        glassBackdropState,
        chromeViewportSize,
        chromeViewportPositionOnScreen,
        chromeTransformState,
    ) {
        FloatingGlassContext(
            backdropState = glassBackdropState,
            viewportSizeState = chromeViewportSize,
            viewportPositionOnScreenState = chromeViewportPositionOnScreen,
            transformState = chromeTransformState,
        )
    }
    val toolbarGlassChrome = remember(
        glassBackdropState,
        chromeViewportSize,
        chromeViewportTopInRoot,
        chromeTransformState,
        toolbarBg,
    ) {
        ToolbarGlassChrome(
            backdropState = glassBackdropState,
            viewportSizeState = chromeViewportSize,
            viewportTopInRootState = chromeViewportTopInRoot,
            transformState = chromeTransformState,
            containerColor = toolbarBg,
        )
    }
    val stickyLabels = remember(state.stickyModifiers) { stickyModifierLabels(state.stickyModifiers) }
    val latestLastError = rememberUpdatedState(state.lastError)
    val onStatusErrorClick = remember(scope, snackbarHostState) {
        {
            val err = latestLastError.value
            if (err != null) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = err,
                        duration = SnackbarDuration.Long,
                        withDismissAction = true,
                    )
                }
            }
        }
    }

    // Drive the system-bars (immersive) state.
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    val insetsController = remember(view, window) {
        window?.let { WindowCompat.getInsetsController(it, view) }
    }
    // The Android system bars (status + navigation) stay HIDDEN for the entire session so the
    // remote picture reclaims the space the status bar used to eat — most painful in landscape
    // (issue #1). They remain swipe-revealable transiently via BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE.
    // This is now INDEPENDENT of the app's own top bar (state.chromeVisible): the full-screen button
    // toggles only the TopAppBar, never the Android system bars.
    LaunchedEffect(insetsController) {
        val ic = insetsController ?: return@LaunchedEffect
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic.hide(WindowInsetsCompat.Type.systemBars())
    }
    // Restore system bars and dismiss the IME when SessionScreen leaves composition.
    DisposableEffect(insetsController, viewModel) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsController?.hide(WindowInsetsCompat.Type.ime())
            viewModel.setImeVisible(false)
        }
    }

    // --- Background keep-alive permissions (M7) ---
    // The keep-alive foreground service needs (1) POST_NOTIFICATIONS (API 33+) for its ongoing
    // notification to be visible, and (2) — to survive Doze with the screen off — a battery-
    // optimization exemption. Both are requested here, the moment a real session is up, so the
    // ask is contextual ("keep THIS session from dropping") rather than a cold-start permission wall.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored — the FGS runs regardless; this only governs notification visibility */ }
    var notificationPromptShown by remember(connectionId) { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status is SessionConnectionStatus.Connected &&
            !notificationPromptShown &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPromptShown = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var batteryPromptShown by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status is SessionConnectionStatus.Connected && !batteryPromptShown) {
            batteryPromptShown = true
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                showBatteryDialog = true
            }
        }
    }
    if (showBatteryDialog) {
        BatteryOptimizationDialog(
            onConfirm = {
                showBatteryDialog = false
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }.onFailure {
                    // Some ROMs hide the direct dialog — fall back to the generic list.
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            },
            onDismiss = { showBatteryDialog = false },
        )
    }

    if (showLocalNetworkPermissionDialog) {
        LocalNetworkPermissionDialog(
            onOpenSettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
            onContinueWithoutLan = {
                showLocalNetworkPermissionDialog = false
                viewModel.ensureStarted(connectionId)
            },
            onDismiss = onClose,
        )
    }

    state.pendingCertificate?.let { pending ->
        CertificateTrustDialog(
            certificate = pending,
            onAccept = viewModel::acceptPendingCertificate,
            onReject = viewModel::rejectPendingCertificate,
        )
    }

    state.pendingCredentialsPrompt?.let { prompt ->
        CredentialsPromptDialog(
            prompt = prompt,
            onSubmit = viewModel::submitCredentials,
            onCancel = viewModel::cancelCredentials,
        )
    }

    // --- All Files Access for folder redirection (整盘共享) ---
    // When the connection has 文件夹重定向 on but the app lacks MANAGE_EXTERNAL_STORAGE, the remote
    // "PocketRDP" drive (= the whole internal storage) shows empty. Prompt contextually once per
    // session to grant it. Returning after granting automatically resumes connecting; choosing
    // "Not now" continues connecting without folder redirection.
    LaunchedEffect(state.status, state.filesRedirectEnabled, state.allFilesAccessRequired) {
        if ((state.allFilesAccessRequired || state.status is SessionConnectionStatus.Connected) &&
            state.filesRedirectEnabled && !allFilesPromptShown &&
            !Environment.isExternalStorageManager()
        ) {
            allFilesPromptShown = true
            showAllFilesDialog = true
        }
    }
    if (showAllFilesDialog) {
        AllFilesAccessDialog(
            onConfirm = {
                showAllFilesDialog = false
                openAllFilesAccessSettings(context)
            },
            onDismiss = {
                showAllFilesDialog = false
                viewModel.dismissAllFilesAccessAndContinueWithoutDrive()
            },
        )
    }

    Scaffold(
        // 会话背景恒为纯黑：Scaffold 默认 containerColor = colorScheme.background（本机是 Material You
        // 动态深灰，深色回退 Neutral10、浅色近白，都不是黑）。半透明黑 TopAppBar(TOOLBAR_BG, 0.7 alpha)
        // 主动丢弃顶部 inset 铺满顶部约 64dp，叠在这层非黑背景上 → 顶部露出一条灰色。画面区 SessionCanvas
        // 是不透明 Color.Black 且被工具栏顶到下方，所以只有工具栏那条露灰。底色改纯黑后该条变纯黑
        // （0.7黑叠纯黑=纯黑），TOOLBAR_BG 的半透明值保持不变 → 叠在画面上的浮动控件仍是黑色半透明玻璃，
        // 配色风格不变（用户：要的是黑色半透明，不要纯不透明黑）。
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = state.chromeVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                Box(
                    modifier = Modifier.onGloballyPositioned {
                        topChromeTopInRoot.floatValue = it.positionInRoot().y
                    },
                ) {
                    GlassChromeBackground(
                        backdropState = glassBackdropState,
                        viewportSizeState = chromeViewportSize,
                        transformState = chromeTransformState,
                        chromeTopPx = { topChromeTopInRoot.floatValue - chromeViewportTopInRoot.floatValue },
                        containerColor = toolbarBg,
                        edge = GlassChromeEdge.Bottom,
                        modifier = Modifier
                            .matchParentSize(),
                    )
                    TopAppBar(
                        title = {
                            // Title slot doubles as the status-info opener. SessionStatusTitle renders
                            // [● connectionName ⏷] and shows the dropdown with the session metrics
                            // (resolution, FPS, control latency, transport, sticky mods, last error).
                            // Keeping the chip wall out of TopAppBar.actions prevents the title from
                            // being pushed off-screen in portrait orientation.
                            SessionStatusTitle(
                                status = state.status,
                                connectionName = state.connectionName,
                                connectionId = connectionId,
                                remoteWidth = state.remoteWidth,
                                remoteHeight = state.remoteHeight,
                                metricsFlow = viewModel.metricsState,
                                host = state.connectionHost,
                                stickyModifierLabels = stickyLabels,
                                lastError = state.lastError,
                                menuContainerColor = toolbarBg,
                                menuContentColor = TOOLBAR_CONTENT,
                                glass = floatingGlassContext,
                                onExpandedChange = viewModel::onStatusMenuExpandedChange,
                                onReconnect = viewModel::reconnect,
                                onErrorClick = onStatusErrorClick,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.session_cd_back))
                            }
                        },
                        actions = {
                            if (state.status is SessionConnectionStatus.Failed ||
                                state.status is SessionConnectionStatus.Disconnected
                            ) {
                                IconButton(onClick = viewModel::reconnect) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.session_action_reconnect),
                                    )
                                }
                            }
                            IconButton(
                                onClick = viewModel::toggleIme,
                                enabled = state.status is SessionConnectionStatus.Connected,
                            ) {
                                Icon(
                                    imageVector = if (state.imeVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                                    contentDescription = stringResource(R.string.session_cd_keyboard),
                                )
                            }
                            IconButton(onClick = viewModel::toggleMode) {
                                Icon(
                                    imageVector = if (state.mode == InputMode.TRACKPAD) Icons.Default.Mouse else Icons.Default.TouchApp,
                                    contentDescription = stringResource(R.string.session_cd_switch_input_mode),
                                )
                            }
                            IconButton(onClick = viewModel::toggleImmersive) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = stringResource(R.string.session_cd_fullscreen),
                                )
                            }
                            IconButton(onClick = { viewModel.disconnect(); onDisconnect() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.session_cd_disconnect))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                            titleContentColor = TOOLBAR_CONTENT,
                            navigationIconContentColor = TOOLBAR_CONTENT,
                            actionIconContentColor = TOOLBAR_CONTENT,
                        ),
                        // Deliberately DROP the top inset so the bar draws right up into the camera /
                        // notch row at the very top of the screen, reclaiming that strip for the toolbar
                        // and pushing the remote picture (which sits below the bar) higher — bigger
                        // picture (用户需求: 工具栏显示在全面屏顶部摄像头位置，加大画面空间). The bar is 64dp
                        // tall and its title/icons are start-/end-aligned, so a top-centre hole-punch
                        // sits in the bar's empty middle and never covers them. We KEEP the horizontal
                        // displayCutout inset so a landscape (shortEdges) side-notch still can't clip the
                        // title or action icons.
                        windowInsets = WindowInsets.displayCutout
                            .only(WindowInsetsSides.Horizontal),
                    )
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets(padding) marks the Scaffold insets (status/nav bars + top app bar) as
        // consumed so the toolbar's imePadding() below nets out the navigation-bar inset and sits
        // flush above the keyboard (no gap). A plain padding(padding) does NOT consume, which would
        // leave a nav-bar-height gap.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { chromeViewportSize.value = it }
                .onGloballyPositioned {
                    chromeViewportTopInRoot.floatValue = it.positionInRoot().y
                    chromeViewportPositionOnScreen.value = it.positionOnScreen()
                }
                .consumeWindowInsets(padding),
        ) {
            // The framebuffer fills the ENTIRE content area at all times and is NEVER shrunk by the
            // soft keyboard. Keeping the canvas a fixed size means showing the IME never fires
            // onSizeChanged, so zoom/pan are fully preserved; the keyboard and toolbar overlay the
            // lower part of the picture while ImeLiftEffect translates the layer up.
            SessionCanvas(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                rdpClient = rdpClient,
                transformState = chromeTransformState,
                config = SessionCanvasConfig(
                    remoteWidth = state.remoteWidth,
                    remoteHeight = state.remoteHeight,
                    mode = state.mode,
                    targetFrameRate = state.targetFrameRate,
                    cursorScale = simulatedCursorScale,
                ),
                onViewSizeChanged = viewModel::onSurfaceResized,
            )

            val hasModalPrompt = state.pendingCertificate != null ||
                state.pendingCredentialsPrompt != null ||
                showAllFilesDialog
            val hasAnyPrompt = hasModalPrompt || showLocalNetworkPermissionDialog
            if (shouldShowConnectingOverlay(state.status, hasPublishedFrame)) {
                ConnectingFrameOverlay(
                    isReconnecting = state.status is SessionConnectionStatus.Reconnecting,
                )
            } else if (shouldShowTerminalStatusOverlay(state.status, hasAnyPrompt)) {
                SessionTerminalStatusOverlay(
                    status = state.status,
                    onReconnect = viewModel::reconnect,
                    onClose = onClose,
                )
            }

            // Immersive exit affordance: a translucent FAB sitting above the framebuffer in
            // the top-right corner. Only visible when system bars are hidden — otherwise
            // the TopAppBar already has the full-screen toggle.
            AnimatedVisibility(
                visible = !state.chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Keep the FAB clear of the camera notch now that the status bar is hidden.
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(12.dp),
            ) {
                GlassControlContainer(
                    glass = floatingGlassContext,
                    containerColor = toolbarBg,
                    shape = EXIT_FULLSCREEN_CONTROL_SHAPE,
                ) {
                    SmallFloatingActionButton(
                        onClick = viewModel::toggleImmersive,
                        shape = EXIT_FULLSCREEN_CONTROL_SHAPE,
                        containerColor = Color.Transparent,
                        contentColor = TOOLBAR_CONTENT,
                    ) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = stringResource(R.string.session_cd_exit_fullscreen),
                        )
                    }
                }
            }

            val functionToolbarHeightPx = remember { mutableIntStateOf(0) }
            val toolbarVisible = state.imeVisible && state.chromeVisible
            FloatingSessionControlsOverlay(
                controller = controller,
                mode = state.mode,
                containerColor = controlBg,
                glass = floatingGlassContext,
                toolbarVisible = toolbarVisible,
                toolbarHeightPx = { functionToolbarHeightPx.intValue },
            )

            AnimatedVisibility(
                visible = toolbarVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                SessionToolbar(
                    viewModel = viewModel,
                    state = state,
                    glassChrome = toolbarGlassChrome,
                    onBarHeightChanged = { functionToolbarHeightPx.intValue = it },
                )
            }

            // Feeds the soft-keyboard (+ function-key toolbar) height into the controller so the
            // framebuffer is lifted just clear of it while typing, and observes external IME
            // dismissal (Back / keyboard collapse button) to keep imeVisible in sync.
            ImeLiftEffect(
                controller = controller,
                chromeVisible = state.chromeVisible,
                imeVisible = state.imeVisible,
                toolbarHeightPx = { functionToolbarHeightPx.intValue },
                onExternalImeDismiss = { viewModel.setImeVisible(false) },
            )

            val captureHardwareKeys = state.status is SessionConnectionStatus.Connected &&
                !hasAnyPrompt &&
                !showBatteryDialog
            // Hidden IME + hardware keyboard bridge: BasicTextField owns focus only while
            // state.imeVisible is true; when hidden, a plain non-text focusable node captures
            // physical Bluetooth/USB keyboards without opening the soft keyboard.
            SessionImeBridge(
                visible = state.imeVisible,
                onUnicodeText = viewModel::typeText,
                onVkKey = viewModel::sendVkRaw,
                stickyModifiers = state.stickyModifiers,
                captureHardwareKeys = captureHardwareKeys,
            )
        }
    }
}

@Composable
private fun FloatingSessionControlsOverlay(
    controller: RdpInputController,
    mode: InputMode,
    containerColor: Color,
    glass: FloatingGlassContext,
    toolbarVisible: Boolean,
    toolbarHeightPx: () -> Int,
) {
    val density = LocalDensity.current
    val activeToolbarHeightPx = if (toolbarVisible) toolbarHeightPx() else 0
    val toolbarBottomPadding = with(density) { activeToolbarHeightPx.toDp() }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(bottom = toolbarBottomPadding)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(FLOATING_CONTROLS_EDGE_PADDING)
            .onSizeChanged { overlaySize = it },
    ) {
        ZoomControls(
            controller = controller,
            containerSize = overlaySize,
            containerColor = containerColor,
            glass = glass,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        if (mode == InputMode.TOUCH) {
            PanHandle(
                controller = controller,
                containerSize = overlaySize,
                containerColor = containerColor,
                glass = glass,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ConnectingFrameOverlay(isReconnecting: Boolean) {
    val transition = rememberInfiniteTransition(label = "session-frame-loading")
    val pulseAlphaState = transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(animation = tween(680), repeatMode = RepeatMode.Reverse),
        label = "session-frame-loading-alpha",
    )
    val pulseScaleState = transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(680), repeatMode = RepeatMode.Reverse),
        label = "session-frame-loading-scale",
    )
    val messageRes = if (isReconnecting) {
        R.string.session_reconnecting_remote_frame
    } else {
        R.string.session_loading_remote_frame
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer {
                                scaleX = pulseScaleState.value
                                scaleY = pulseScaleState.value
                                alpha = pulseAlphaState.value
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Icon(
                        imageVector = Icons.Default.SettingsEthernet,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = Color.White,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(72.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
                Text(
                    text = stringResource(messageRes),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SessionTerminalStatusOverlay(
    status: SessionConnectionStatus,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
) {
    val isFailed = status is SessionConnectionStatus.Failed
    val title = if (isFailed) {
        stringResource(R.string.session_status_failed)
    } else {
        val reason = (status as? SessionConnectionStatus.Disconnected)?.reason
        val reasonLabel = when (reason) {
            "user" -> stringResource(R.string.session_status_disconnected_user)
            null -> stringResource(R.string.session_status_unknown_reason)
            else -> reason
        }
        stringResource(R.string.session_status_disconnected, reasonLabel)
    }
    val detail = (status as? SessionConnectionStatus.Failed)?.error
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = TERMINAL_OVERLAY_SCRIM_ALPHA))
            .consumeAllPointerInput()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = TERMINAL_OVERLAY_MAX_WIDTH),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = TERMINAL_OVERLAY_CARD_ALPHA),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = TERMINAL_OVERLAY_BORDER_ALPHA)),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (isFailed) Icons.Default.ErrorOutline else Icons.Default.LinkOff,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (isFailed) MaterialTheme.colorScheme.error else Color.White,
                )
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        color = Color.White.copy(alpha = TERMINAL_OVERLAY_DETAIL_ALPHA),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onClose) {
                        Text(
                            text = stringResource(R.string.session_action_close),
                            color = Color.White,
                        )
                    }
                    Button(onClick = onReconnect) {
                        Text(text = stringResource(R.string.session_action_reconnect))
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsPromptDialog(
    prompt: CredentialsPromptState,
    onSubmit: (username: String, domain: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    var username by remember(prompt) { mutableStateOf(prompt.initialUsername) }
    var domain by remember(prompt) { mutableStateOf(prompt.initialDomain) }
    var password by remember(prompt) { mutableStateOf("") }
    var passwordVisible by remember(prompt) { mutableStateOf(false) }
    val message = if (prompt.host.isNotBlank()) {
        stringResource(R.string.session_credentials_message, prompt.host)
    } else {
        stringResource(R.string.session_credentials_message_generic)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.session_credentials_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(message)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.session_credentials_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.session_credentials_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.session_credentials_password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = stringResource(
                                    if (passwordVisible) {
                                        R.string.session_credentials_hide_password
                                    } else {
                                        R.string.session_credentials_show_password
                                    },
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(username.trim(), domain.trim(), password) }) {
                Text(stringResource(R.string.session_credentials_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.session_toolbar_cancel))
            }
        },
    )
}

@Composable
private fun BatteryOptimizationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_battery_title)) },
        text = {
            Text(stringResource(R.string.session_battery_message))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.session_action_allow)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_action_not_now)) } },
    )
}

@Composable
private fun AllFilesAccessDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_all_files_title)) },
        text = {
            Text(stringResource(R.string.session_all_files_message))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.session_action_grant)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_action_not_now)) } },
    )
}

@Composable
private fun LocalNetworkPermissionDialog(
    onOpenSettings: () -> Unit,
    onContinueWithoutLan: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_local_network_title)) },
        text = { Text(stringResource(R.string.session_local_network_message)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.session_action_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onContinueWithoutLan) {
                Text(stringResource(R.string.session_action_continue_without_lan))
            }
        },
    )
}

@Composable
private fun CertificateTrustDialog(
    certificate: PendingCertificate,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val title = if (certificate.isChange) {
        stringResource(R.string.session_certificate_changed_title)
    } else {
        stringResource(R.string.session_certificate_new_title)
    }
    val message = if (certificate.isChange) {
        stringResource(R.string.session_certificate_changed_message)
    } else {
        stringResource(R.string.session_certificate_new_message)
    }
    val formattedFingerprint = remember(certificate.sha256) {
        certificate.sha256.chunked(2).joinToString(":")
    }
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(message)
                Text(
                    stringResource(
                        R.string.session_certificate_endpoint,
                        certificate.host,
                        certificate.port,
                    ),
                )
                Text(
                    stringResource(R.string.session_certificate_fingerprint, formattedFingerprint),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.session_certificate_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.session_certificate_reject))
            }
        },
    )
}

/** Open the system "All files access" settings page for this app (folder redirection 整盘共享). */
private fun openAllFilesAccessSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }.onFailure {
        // Some ROMs reject the per-app intent — fall back to the generic all-files-access list.
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

private fun stickyModifierLabels(mask: Int): List<String> = buildList {
    if (mask and ScancodeMap.Modifier.CTRL != 0) add("Ctrl")
    if (mask and ScancodeMap.Modifier.ALT != 0) add("Alt")
    if (mask and ScancodeMap.Modifier.SHIFT != 0) add("Shift")
    if (mask and ScancodeMap.Modifier.WIN != 0) add("Win")
}

// Session chrome palette: translucent black glass so the remote picture shows through while white
// icons/labels remain legible across TopAppBar, function-key bar, zoom pill, pan handle, exit FAB.
private const val CHROME_ALPHA = 0.7f
private const val ANDROID_API_37 = 37
private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val MIN_CHROME_ALPHA = 0f
private const val MAX_CHROME_ALPHA = 1f
private val TOOLBAR_BG = Color.Black.copy(alpha = CHROME_ALPHA)
private val TOOLBAR_CONTENT = Color.White
private val TOOLBAR_KEY_FILL = Color.White.copy(alpha = 0.12f)
private val TOOLBAR_KEY_BORDER = Color.White.copy(alpha = 0.68f)
private val TOOLBAR_COMBO_FILL = Color(0xFF3569B8).copy(alpha = 0.72f)
private val TOOLBAR_COMBO_BORDER = Color(0xFFA9CBFF)
private val TOOLBAR_ACTION_FILL = Color(0xFF187A68).copy(alpha = 0.78f)
private val TOOLBAR_ACTION_BORDER = Color(0xFF92E5D3)
private val TOOLBAR_MODIFIER_FILL = Color(0xFF51478B).copy(alpha = 0.78f)
private val TOOLBAR_MODIFIER_BORDER = Color(0xFFC6BCFF)
private val TOOLBAR_MODIFIER_ACTIVE = Color(0xFFC6BCFF)
private const val GLASS_SAMPLE_INTERVAL_MS = 500L
private const val GLASS_BACKDROP_MAX_DIM = 480
private const val TERMINAL_OVERLAY_SCRIM_ALPHA = 0.58f
private const val TERMINAL_OVERLAY_CARD_ALPHA = 0.84f
private const val TERMINAL_OVERLAY_BORDER_ALPHA = 0.22f
private const val TERMINAL_OVERLAY_DETAIL_ALPHA = 0.82f
private val TERMINAL_OVERLAY_MAX_WIDTH = 360.dp
private val FLOATING_CONTROLS_EDGE_PADDING = 12.dp
private val EXIT_FULLSCREEN_CONTROL_SHAPE = RoundedCornerShape(12.dp)

private data class SessionCanvasConfig(
    val remoteWidth: Int,
    val remoteHeight: Int,
    val mode: InputMode,
    val targetFrameRate: Int,
    val cursorScale: Float,
)

private const val DEFAULT_CURSOR_HEIGHT = 26f
private const val DEFAULT_CURSOR_BOTTOM_Y = 24f
private const val DEFAULT_CURSOR_TAIL_Y = 15f
private const val DEFAULT_CURSOR_TAIL_LEFT_X = 5f
private const val DEFAULT_CURSOR_SHAFT_LEFT_X = 8f
private const val DEFAULT_CURSOR_TAIL_RIGHT_X = 13f
private const val DEFAULT_CURSOR_TAIL_OUTER_X = 16f
private const val DEFAULT_CURSOR_TAIL_OUTER_Y = 24f
private const val DEFAULT_CURSOR_SHAFT_RIGHT_X = 12f
private const val DEFAULT_CURSOR_RIGHT_X = 19f
private const val DEFAULT_CURSOR_OUTLINE_WIDTH = 2f

/**
 * Drives the keyboard-lift (issue: 呼出键盘后画面被下方键盘挡住看不见). Reads the live soft-keyboard height
 * from `WindowInsets.ime` and (when the function-key toolbar is showing above it) adds the toolbar's
 * MEASURED visual height, then feeds the total bottom occlusion to the controller, which translates the
 * framebuffer up just enough to keep its lower part visible above the keyboard. Using the measured
 * height (via [toolbarHeightPx]) instead of a constant keeps the lift correct regardless of the bar's
 * actual height, so the toolbar never re-covers the picture.
 *
 * Kept as its OWN tiny composable so the per-animation-frame recomposition from reading the (animating)
 * ime inset is confined here and never re-runs SessionCanvas. [toolbarHeightPx] is a lambda so reading
 * the height state happens inside THIS composable's scope — updating it recomposes only here, not the
 * caller. The lift is a pure transform — it does NOT resize the canvas, so it never re-fits the picture
 * or resets the user's zoom/pan (the reason the canvas itself is deliberately kept full-size; see the
 * SessionCanvas comment).
 */
@Composable
private fun ImeLiftEffect(
    controller: RdpInputController,
    chromeVisible: Boolean,
    imeVisible: Boolean,
    toolbarHeightPx: () -> Int,
    onExternalImeDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val currentOnExternalImeDismiss = rememberUpdatedState(onExternalImeDismiss)
    var wasImeActuallyShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, imeBottomPx) {
        val next = stepImeInsetSync(
            imeVisible = imeVisible,
            imeBottomPx = imeBottomPx,
            wasShown = wasImeActuallyShown,
        )
        wasImeActuallyShown = next.wasShown
        if (next.shouldDismiss) {
            currentOnExternalImeDismiss.value()
        }
    }
    // Add the function-key toolbar's height only while it actually shows (keyboard up AND chrome
    // visible) — it floats just above the keyboard, so the picture must clear both.
    val toolbarPx = if (imeBottomPx > 0 && chromeVisible) toolbarHeightPx().toFloat() else 0f
    SideEffect { controller.setBottomOcclusion(imeBottomPx + toolbarPx) }
}

/**
 * Applies the local control-driven zoom/pan as a graphicsLayer transform. The [transform] State is read
 * INSIDE the layer block, so it runs in the layer-record (draw) phase — a transform change
 * re-records only the GPU layer and never recomposes the caller. Used by BOTH the framebuffer
 * AndroidView and the crosshair Canvas so they share one transform and stay in lock-step.
 */
private fun Modifier.userTransformLayer(transform: State<UserTransform>): Modifier =
    graphicsLayer {
        val t = transform.value
        scaleX = t.zoom
        scaleY = t.zoom
        translationX = t.panX
        // offsetY is the keyboard-lift: an extra upward translation so the picture's lower part stays
        // visible above the soft keyboard. Layered on top of panY (both screen-space), so it shifts the
        // already-zoomed/panned picture without re-fitting or resetting the user's zoom/pan.
        translationY = t.panY + t.offsetY
        transformOrigin = TransformOrigin.Center
    }

/**
 * Swallow every pointer event within the node's bounds (down → moves → up) so a floating overlay
 * placed ABOVE the full-screen gesture surface never leaks touches through to it. A bare
 * `pointerInput {}` does NOT do this — it has to explicitly consume() each change.
 * Used by the function-key toolbar overlay (see [SessionToolbar]).
 */
private fun Modifier.consumeAllPointerInput(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        var tracking = true
        while (tracking) {
            val event = awaitPointerEvent()
            for (change in event.changes) change.consume()
            if (event.changes.none { it.pressed }) tracking = false
        }
    }
}

@Composable
private fun SessionCanvas(
    modifier: Modifier,
    controller: RdpInputController,
    rdpClient: RdpClient,
    transformState: State<UserTransform>,
    config: SessionCanvasConfig,
    onViewSizeChanged: (width: Int, height: Int) -> Unit,
) {
    val buffer = rdpClient.buffer
    val hasPublishedFrame by buffer.hasPublishedFrame.collectAsStateWithLifecycle(initialValue = false)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Keep virtualPosState as State<Offset> (NOT `by`) so cursor moves re-record only the Canvas
    // draw lambda without recomposing SessionCanvas or re-running AndroidView.update.
    val virtualPosState = controller.virtualPosition.collectAsStateWithLifecycle()

    // Same fit-to-view math RdpSurface.onDraw uses. Kept in sync so touch coordinates and
    // the on-screen cursor overlay all share one transform.
    val viewW = viewSize.width.toFloat()
    val viewH = viewSize.height.toFloat()
    val bmW = config.remoteWidth.toFloat()
    val bmH = config.remoteHeight.toFloat()
    val hasValidView = viewW > 0f && viewH > 0f
    val hasValidBitmap = bmW > 0f && bmH > 0f
    val scale = if (hasValidView && hasValidBitmap) minOf(viewW / bmW, viewH / bmH) else 1f
    val dx = if (bmW > 0f) (viewW - bmW * scale) / 2f else 0f
    val dy = if (bmH > 0f) (viewH - bmH * scale) / 2f else 0f

    LaunchedEffect(scale, dx, dy) {
        controller.setViewport(scale, dx, dy)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size ->
                viewSize = size
                controller.setViewSize(size.width, size.height)
                onViewSizeChanged(size.width, size.height)
            }
            // sessionGestures sits ABOVE the graphicsLayer so pointer events arrive in
            // un-transformed view coordinates and reads the live controller mode on every touch.
            .sessionGestures(controller = controller),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .userTransformLayer(transformState),
            factory = { ctx ->
                RdpSurface(ctx).also { surface ->
                    surface.setBackgroundColor(Color.Black.toArgb())
                    // Read frames straight from the double buffer's front (complete) buffer; the
                    // render loop pulls peekFront() each tick (no per-frame setBacking/recompose).
                    surface.buffer = buffer
                    // Passive decode→present latency report (METRIC-1) — fired only when a new frame is
                    // drawn; never invalidates or touches the FPS counter.
                    surface.onFramePresented = rdpClient::recordPresentLag
                }
            },
            update = { surface ->
                surface.targetFrameRate = config.targetFrameRate
            },
        )

        // Trackpad-mode cursor overlay. Collects rdpClient.remoteCursor directly inside
        // RemoteCursorOverlay so remote pointer shape changes never recompose SessionCanvas.
        val hasRemoteFrameSize = bmW > 0f && bmH > 0f
        val shouldShowRemoteCursor = config.mode == InputMode.TRACKPAD && hasPublishedFrame && hasRemoteFrameSize
        if (shouldShowRemoteCursor) {
            RemoteCursorOverlay(
                remoteCursorFlow = rdpClient.remoteCursor,
                virtualPositionState = virtualPosState,
                scale = scale,
                dx = dx,
                dy = dy,
                cursorScale = config.cursorScale,
                modifier = Modifier
                    .fillMaxSize()
                    // Cursor sits on the same transformed layer as the framebuffer so it
                    // tracks zoom/pan/keyboard-lift correctly.
                    .userTransformLayer(transformState),
            )
        }
    }
}

@Composable
private fun RemoteCursorOverlay(
    remoteCursorFlow: StateFlow<RdpCursor>,
    virtualPositionState: State<Offset>,
    scale: Float,
    dx: Float,
    dy: Float,
    cursorScale: Float,
    modifier: Modifier = Modifier,
) {
    val cursor by remoteCursorFlow.collectAsStateWithLifecycle()
    if (cursor == RdpCursor.Hidden) return
    val cursorImage = when (val c = cursor) {
        is RdpCursor.Image -> remember(c.bitmap) { c.bitmap.asImageBitmap() }
        else -> null
    }
    val defaultCursorPath = remember(cursorScale) { buildDefaultWindowsCursorPath(cursorScale) }
    val defaultCursorStroke = remember(cursorScale) {
        Stroke(width = DEFAULT_CURSOR_OUTLINE_WIDTH * cursorScale)
    }

    Canvas(modifier = modifier) {
        val virtualPosition = virtualPositionState.value
        val imageCursor = cursor as? RdpCursor.Image
        val hotX = (imageCursor?.hotX ?: 0).toFloat() * cursorScale
        val hotY = (imageCursor?.hotY ?: 0).toFloat() * cursorScale
        val left = virtualPosition.x * scale + dx - hotX
        val top = virtualPosition.y * scale + dy - hotY

        if (cursorImage != null) {
            drawImage(
                image = cursorImage,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(cursorImage.width, cursorImage.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(
                    width = (cursorImage.width * cursorScale).roundToInt().coerceAtLeast(1),
                    height = (cursorImage.height * cursorScale).roundToInt().coerceAtLeast(1),
                ),
            )
        } else {
            translate(left = left, top = top) {
                drawPath(defaultCursorPath, color = Color.White)
                drawPath(defaultCursorPath, color = Color.Black, style = defaultCursorStroke)
            }
        }
    }
}

private fun buildDefaultWindowsCursorPath(cursorScale: Float): Path = Path().apply {
    moveTo(0f, 0f)
    lineTo(0f, DEFAULT_CURSOR_BOTTOM_Y * cursorScale)
    lineTo(DEFAULT_CURSOR_TAIL_LEFT_X * cursorScale, DEFAULT_CURSOR_TAIL_Y * cursorScale)
    lineTo(DEFAULT_CURSOR_SHAFT_LEFT_X * cursorScale, DEFAULT_CURSOR_TAIL_Y * cursorScale)
    lineTo(DEFAULT_CURSOR_TAIL_RIGHT_X * cursorScale, DEFAULT_CURSOR_HEIGHT * cursorScale)
    lineTo(DEFAULT_CURSOR_TAIL_OUTER_X * cursorScale, DEFAULT_CURSOR_TAIL_OUTER_Y * cursorScale)
    lineTo(DEFAULT_CURSOR_SHAFT_RIGHT_X * cursorScale, DEFAULT_CURSOR_TAIL_Y * cursorScale)
    lineTo(DEFAULT_CURSOR_RIGHT_X * cursorScale, DEFAULT_CURSOR_TAIL_Y * cursorScale)
    close()
}

/** F1–F12 keys for the function-key bar (label → Windows VK). */
private val functionKeyRows: List<List<ToolbarKeySpec>> = listOf(
    listOf("F1", "F2", "F3", "F4").mapIndexed { index, label ->
        ToolbarKeySpec("key_${label.lowercase()}", label, ScancodeMap.VK.F1 + index)
    },
    listOf("F5", "F6", "F7", "F8").mapIndexed { index, label ->
        ToolbarKeySpec("key_${label.lowercase()}", label, ScancodeMap.VK.F5 + index)
    },
    listOf("F9", "F10", "F11", "F12").mapIndexed { index, label ->
        ToolbarKeySpec("key_${label.lowercase()}", label, ScancodeMap.VK.F9 + index)
    },
)

/** Navigation / editing keys for the function-key bar (label → Windows VK). Arrows etc. carry KBDEXT. */
private val navigationKeyRows: List<List<ToolbarKeySpec>> = listOf(
    listOf(
        ToolbarKeySpec("key_up", "↑", ScancodeMap.VK.UP),
        ToolbarKeySpec("key_down", "↓", ScancodeMap.VK.DOWN),
        ToolbarKeySpec("key_left", "←", ScancodeMap.VK.LEFT),
        ToolbarKeySpec("key_right", "→", ScancodeMap.VK.RIGHT),
    ),
    listOf(
        ToolbarKeySpec("key_home", "Home", ScancodeMap.VK.HOME),
        ToolbarKeySpec("key_end", "End", ScancodeMap.VK.END),
        ToolbarKeySpec("key_pgup", "PgUp", ScancodeMap.VK.PAGE_UP),
        ToolbarKeySpec("key_pgdn", "PgDn", ScancodeMap.VK.PAGE_DOWN),
    ),
    listOf(
        ToolbarKeySpec("key_ins", "Ins", ScancodeMap.VK.INSERT),
        ToolbarKeySpec("key_del", "Del", ScancodeMap.VK.DELETE),
    ),
)

private val quickKeyRows: List<List<ToolbarKeySpec>> = listOf(
    listOf(
        ToolbarKeySpec("key_esc", "Esc", ScancodeMap.VK.ESCAPE),
        ToolbarKeySpec("key_tab", "Tab", ScancodeMap.VK.TAB),
        ToolbarKeySpec("key_back", "Back", ScancodeMap.VK.BACK),
        ToolbarKeySpec("key_enter", "Enter", ScancodeMap.VK.RETURN),
    ),
)

private const val VK_A = 0x41
private const val VK_C = 0x43
private const val VK_D = 0x44
private const val VK_F = 0x46
private const val VK_R = 0x52
private const val VK_S = 0x53
private const val VK_V = 0x56
private const val VK_X = 0x58
private const val VK_Z = 0x5A

private val letterKeyRows: List<List<ToolbarKeySpec>> = ('A'..'Z')
    .map { letter -> ToolbarKeySpec("key_${letter.lowercaseChar()}", letter.toString(), letter.code) }
    .chunked(TOOLBAR_GRID_COLUMNS)

private val numberKeyRows: List<List<ToolbarKeySpec>> = ('0'..'9')
    .map { digit -> ToolbarKeySpec("key_$digit", digit.toString(), digit.code) }
    .chunked(TOOLBAR_GRID_COLUMNS)

private val commonChordKeyRows: List<List<ToolbarKeySpec>> = listOf(
    listOf(
        ToolbarKeySpec("key_space", "Space", ScancodeMap.VK.SPACE),
        ToolbarKeySpec("key_esc", "Esc", ScancodeMap.VK.ESCAPE),
        ToolbarKeySpec("key_tab", "Tab", ScancodeMap.VK.TAB),
        ToolbarKeySpec("key_back", "Back", ScancodeMap.VK.BACK),
        ToolbarKeySpec("key_enter", "Enter", ScancodeMap.VK.RETURN),
    ),
)

private val quickComboRows: List<List<ToolbarComboSpec>> = listOf(
    listOf(
        ToolbarComboSpec("combo_ctrl_c", "Ctrl+C", VK_C, ScancodeMap.Modifier.CTRL),
        ToolbarComboSpec("combo_ctrl_v", "Ctrl+V", VK_V, ScancodeMap.Modifier.CTRL),
        ToolbarComboSpec("combo_ctrl_x", "Ctrl+X", VK_X, ScancodeMap.Modifier.CTRL),
        ToolbarComboSpec("combo_ctrl_a", "Ctrl+A", VK_A, ScancodeMap.Modifier.CTRL),
    ),
    listOf(
        ToolbarComboSpec("combo_ctrl_z", "Ctrl+Z", VK_Z, ScancodeMap.Modifier.CTRL),
        ToolbarComboSpec("combo_ctrl_s", "Ctrl+S", VK_S, ScancodeMap.Modifier.CTRL),
        ToolbarComboSpec("combo_alt_tab", "Alt+Tab", ScancodeMap.VK.TAB, ScancodeMap.Modifier.ALT),
        ToolbarComboSpec("combo_win_d", "Win+D", VK_D, ScancodeMap.Modifier.WIN),
    ),
    listOf(
        ToolbarComboSpec("combo_win_r", "Win+R", VK_R, ScancodeMap.Modifier.WIN),
        ToolbarComboSpec("combo_ctrl_f", "Ctrl+F", VK_F, ScancodeMap.Modifier.CTRL),
    ),
    listOf(
        ToolbarComboSpec(
            "combo_ctrl_shift_esc",
            "Ctrl+Shift+Esc",
            ScancodeMap.VK.ESCAPE,
            ScancodeMap.Modifier.CTRL or ScancodeMap.Modifier.SHIFT,
        ),
        ToolbarComboSpec("combo_cad", "CAD", null, 0, isCtrlAltDel = true),
    ),
)

private val toolbarModifierSpecs: List<ToolbarModifierSpec> = listOf(
    ToolbarModifierSpec("mod_ctrl", "Ctrl", ScancodeMap.Modifier.CTRL),
    ToolbarModifierSpec("mod_alt", "Alt", ScancodeMap.Modifier.ALT),
    ToolbarModifierSpec("mod_shift", "Shift", ScancodeMap.Modifier.SHIFT),
    ToolbarModifierSpec("mod_win", "Win", ScancodeMap.Modifier.WIN),
)

private enum class ToolbarPage(@StringRes val labelRes: Int) {
    QUICK(R.string.session_toolbar_page_quick),
    NAVIGATION(R.string.session_toolbar_page_navigation),
    FUNCTION(R.string.session_toolbar_page_function),
}

private data class ToolbarKeySpec(val id: String, val label: String, val vk: Int)

private data class ToolbarComboSpec(
    val id: String,
    val label: String,
    val keyVk: Int?,
    val modifiers: Int,
    val isCtrlAltDel: Boolean = false,
)

private data class ToolbarModifierSpec(val id: String, val label: String, val flag: Int)

private data class ToolbarExpandedActions(
    val onPage: (ToolbarPage) -> Unit,
    val onLongKey: (ToolbarKeySpec) -> Unit,
    val onEditQuickRow: () -> Unit,
    val onAddQuickCombo: () -> Unit,
    val onMomentaryAction: () -> Unit,
)

private data class ToolbarActionSpec(
    val id: String,
    val label: String,
    val vk: Int? = null,
    val modifiers: Int = 0,
    val modifierFlag: Int = 0,
    val isCtrlAltDel: Boolean = false,
)

private val allToolbarActions: List<ToolbarActionSpec> = buildList {
    toolbarModifierSpecs.forEach { spec ->
        add(ToolbarActionSpec(id = spec.id, label = spec.label, modifierFlag = spec.flag))
    }
    (quickKeyRows + navigationKeyRows + functionKeyRows + letterKeyRows + numberKeyRows).flatten().forEach { key ->
        add(ToolbarActionSpec(id = key.id, label = key.label, vk = key.vk))
    }
    quickComboRows.flatten().forEach { combo ->
        add(
            ToolbarActionSpec(
                id = combo.id,
                label = combo.label,
                vk = combo.keyVk,
                modifiers = combo.modifiers,
                isCtrlAltDel = combo.isCtrlAltDel,
            ),
        )
    }
}.distinctBy { it.id }

private val toolbarActionsById: Map<String, ToolbarActionSpec> = allToolbarActions.associateBy { it.id }
private val chordKeyActions: List<ToolbarActionSpec> =
    (commonChordKeyRows + letterKeyRows + numberKeyRows + navigationKeyRows + functionKeyRows)
        .flatten()
        .distinctBy { it.id }
        .map { key -> ToolbarActionSpec(id = key.id, label = key.label, vk = key.vk) }
private val chordKeyActionsById: Map<String, ToolbarActionSpec> = chordKeyActions.associateBy { it.id }

private val modifierToolbarActions: List<ToolbarActionSpec> = toolbarModifierSpecs.map { spec ->
    ToolbarActionSpec(id = spec.id, label = spec.label, modifierFlag = spec.flag)
}
private val quickKeyToolbarActions: List<ToolbarActionSpec> =
    quickKeyRows.flatten().map(::toolbarActionForKey)
private val navigationKeyToolbarActions: List<ToolbarActionSpec> =
    navigationKeyRows.flatten().map(::toolbarActionForKey)
private val functionKeyToolbarActions: List<ToolbarActionSpec> =
    functionKeyRows.flatten().map(::toolbarActionForKey)
private val letterToolbarActions: List<ToolbarActionSpec> =
    letterKeyRows.flatten().map(::toolbarActionForKey)
private val numberToolbarActions: List<ToolbarActionSpec> =
    numberKeyRows.flatten().map(::toolbarActionForKey)
private val commonChordToolbarActions: List<ToolbarActionSpec> =
    commonChordKeyRows.flatten().map(::toolbarActionForKey)
private val quickComboToolbarActions: List<ToolbarActionSpec> = quickComboRows.flatten().map { combo ->
    ToolbarActionSpec(
        id = combo.id,
        label = combo.label,
        vk = combo.keyVk,
        modifiers = combo.modifiers,
        isCtrlAltDel = combo.isCtrlAltDel,
    )
}
private val quickKeyGridRows: List<List<ToolbarKeySpec>> =
    quickKeyRows.flatten().chunked(TOOLBAR_GRID_COLUMNS)
private val navigationKeyGridRows: List<List<ToolbarKeySpec>> =
    navigationKeyRows.flatten().chunked(TOOLBAR_GRID_COLUMNS)
private val functionKeyGridRows: List<List<ToolbarKeySpec>> =
    functionKeyRows.flatten().chunked(TOOLBAR_GRID_COLUMNS)
private val quickComboGridRows: List<List<ToolbarComboSpec>> =
    quickComboRows.flatten().chunked(TOOLBAR_GRID_COLUMNS)

private fun sanitizeQuickToolbarActionIds(ids: List<String>): List<String> {
    val valid = ids
        .filter { resolveToolbarAction(it) != null }
        .distinct()
        .take(MAX_FUNCTION_TOOLBAR_QUICK_IDS)
    return valid.ifEmpty { DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS }
}

private fun resolveToolbarAction(id: String): ToolbarActionSpec? =
    toolbarActionsById[id] ?: parseToolbarChordAction(id)

private fun parseToolbarChordAction(id: String): ToolbarActionSpec? {
    val encoded = decodeToolbarChordId(id) ?: return null
    val key = chordKeyActionsById[encoded.keyId] ?: return null
    val vk = key.vk ?: return null
    return ToolbarActionSpec(
        id = id,
        label = buildChordLabel(encoded.modifierMask, key.label),
        vk = vk,
        modifiers = encoded.modifierMask,
    )
}

internal fun regularQuickPickerKeyIds(): List<String> =
    (quickKeyRows + navigationKeyRows + functionKeyRows).flatten().map { it.id }

internal fun chordBuilderKeyIds(): List<String> =
    (letterKeyRows + numberKeyRows + commonChordKeyRows + navigationKeyRows + functionKeyRows)
        .flatten()
        .map { it.id }

internal fun isKnownToolbarChordId(id: String): Boolean = parseToolbarChordAction(id) != null

/**
 * Positions a sampled framebuffer backdrop behind an individual floating control. Reads
 * [controlPositionOnScreen] strictly in the draw phase so relocating the control never recomposes
 * this container or its backdrop.
 */
@Composable
private fun GlassControlContainer(
    glass: FloatingGlassContext,
    containerColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val controlPositionOnScreen = remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier.onGloballyPositioned {
            controlPositionOnScreen.value = it.positionOnScreen()
        },
    ) {
        FloatingGlassBackground(
            glass = glass,
            controlPositionOnScreen = { controlPositionOnScreen.value },
            containerColor = containerColor,
            shape = shape,
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}

private val TOOLBAR_MOTION_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val toolbarPanelEnter =
    expandVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_PANEL_ENTER_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        expandFrom = Alignment.Bottom,
    ) + slideInVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_PANEL_ENTER_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        initialOffsetY = { height -> height / TOOLBAR_PANEL_SLIDE_DIVISOR },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = TOOLBAR_PANEL_FADE_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
    )

private val toolbarPanelExit =
    shrinkVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_PANEL_EXIT_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        shrinkTowards = Alignment.Bottom,
    ) + slideOutVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_PANEL_EXIT_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        targetOffsetY = { height -> height / 8 },
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = TOOLBAR_PANEL_EXIT_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
    )

private val toolbarCompactRowEnter =
    expandVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_COMPACT_ROW_ENTER_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        expandFrom = Alignment.Bottom,
    ) + slideInVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_COMPACT_ROW_ENTER_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        initialOffsetY = { height -> height / 2 },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = TOOLBAR_COMPACT_ROW_FADE_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
    )

private val toolbarCompactRowExit =
    shrinkVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_COMPACT_ROW_EXIT_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        shrinkTowards = Alignment.Bottom,
    ) + slideOutVertically(
        animationSpec = tween(
            durationMillis = TOOLBAR_COMPACT_ROW_EXIT_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        targetOffsetY = { height -> height / 3 },
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = TOOLBAR_COMPACT_ROW_EXIT_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
    )

private data class SessionToolbarModeState(
    val page: ToolbarPage,
    val editingQuickRow: Boolean,
    val addingQuickCombo: Boolean,
    val comboTarget: ToolbarKeySpec?,
    val comboMask: Int,
)

private data class SessionToolbarModeCallbacks(
    val onPage: (ToolbarPage) -> Unit,
    val onOpenCombo: (ToolbarKeySpec) -> Unit,
    val onToggleComboModifier: (Int) -> Unit,
    val onSetEditingQuickRow: (Boolean) -> Unit,
    val onSetAddingQuickCombo: (Boolean) -> Unit,
    val onClearComboTarget: () -> Unit,
    val onCollapse: () -> Unit,
)

@Composable
private fun SessionToolbarExpandedBody(
    viewModel: SessionViewModel,
    state: SessionUiState,
    quickIds: List<String>,
    modeState: SessionToolbarModeState,
    callbacks: SessionToolbarModeCallbacks,
) {
    val target = modeState.comboTarget
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = TOOLBAR_EXPANDED_PANEL_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(bottom = TOOLBAR_SECTION_SPACING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            modeState.addingQuickCombo -> SavedToolbarChordBuilder(
                existingIds = quickIds,
                onAdd = { id ->
                    if (quickIds.size < MAX_FUNCTION_TOOLBAR_QUICK_IDS && id !in quickIds) {
                        viewModel.setFunctionToolbarQuickIds((quickIds + id).distinct())
                        callbacks.onSetAddingQuickCombo(false)
                        callbacks.onCollapse()
                    }
                },
                onCancel = { callbacks.onSetAddingQuickCombo(false) },
            )
            modeState.editingQuickRow -> QuickToolbarEditor(
                selectedIds = quickIds,
                onApply = { ids ->
                    viewModel.setFunctionToolbarQuickIds(ids)
                    callbacks.onSetEditingQuickRow(false)
                },
                onDone = { callbacks.onSetEditingQuickRow(false) },
            )
            target != null -> ToolbarComboBuilder(
                target = target,
                selectedMask = modeState.comboMask,
                onToggleModifier = callbacks.onToggleComboModifier,
                onSend = {
                    viewModel.sendKeyWithModifiers(target.vk, modeState.comboMask)
                    callbacks.onCollapse()
                },
                onCancel = callbacks.onClearComboTarget,
            )
            else -> ToolbarExpandedPanel(
                page = modeState.page,
                state = state,
                viewModel = viewModel,
                actions = ToolbarExpandedActions(
                    onPage = callbacks.onPage,
                    onLongKey = callbacks.onOpenCombo,
                    onEditQuickRow = { callbacks.onSetEditingQuickRow(true) },
                    onAddQuickCombo = { callbacks.onSetAddingQuickCombo(true) },
                    onMomentaryAction = callbacks.onCollapse,
                ),
            )
        }
    }
}

@Composable
private fun ToolbarBackdropLayer(
    glassChrome: ToolbarGlassChrome,
    modifier: Modifier = Modifier,
) {
    val toolbarTopInRoot = remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .onGloballyPositioned { toolbarTopInRoot.floatValue = it.positionInRoot().y }
            .consumeAllPointerInput(),
    ) {
        GlassChromeBackground(
            backdropState = glassChrome.backdropState,
            viewportSizeState = glassChrome.viewportSizeState,
            transformState = glassChrome.transformState,
            chromeTopPx = { toolbarTopInRoot.floatValue - glassChrome.viewportTopInRootState.value },
            containerColor = glassChrome.containerColor,
            edge = GlassChromeEdge.Top,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun SessionToolbar(
    viewModel: SessionViewModel,
    state: SessionUiState,
    glassChrome: ToolbarGlassChrome,
    onBarHeightChanged: (Int) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        var page by remember { mutableStateOf(ToolbarPage.QUICK) }
        var expanded by remember { mutableStateOf(false) }
        var editingQuickRow by remember { mutableStateOf(false) }
        var addingQuickCombo by remember { mutableStateOf(false) }
        var comboTarget by remember { mutableStateOf<ToolbarKeySpec?>(null) }
        var comboMask by remember { mutableIntStateOf(ScancodeMap.Modifier.CTRL) }
        val openCombo: (ToolbarKeySpec) -> Unit = { target ->
            expanded = true
            editingQuickRow = false
            addingQuickCombo = false
            comboTarget = target
            comboMask = state.stickyModifiers.takeIf { it != 0 } ?: ScancodeMap.Modifier.CTRL
        }
        val quickActions = remember(state.functionToolbarQuickIds) {
            sanitizeQuickToolbarActionIds(state.functionToolbarQuickIds)
                .mapNotNull(::resolveToolbarAction)
        }
        val quickIds = remember(quickActions) { quickActions.map { it.id } }

        ToolbarBackdropLayer(
            glassChrome = glassChrome,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onBarHeightChanged(it.height) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            // weight(1f, fill = false) ensures CompactToolbarRow (with its collapse button) is
            // always measured first and stays visible even in short landscape IME viewports.
            AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.weight(1f, fill = false),
                enter = toolbarPanelEnter,
                exit = toolbarPanelExit,
            ) {
                SessionToolbarExpandedBody(
                    viewModel = viewModel,
                    state = state,
                    quickIds = quickIds,
                    modeState = SessionToolbarModeState(
                        page = page,
                        editingQuickRow = editingQuickRow,
                        addingQuickCombo = addingQuickCombo,
                        comboTarget = comboTarget,
                        comboMask = comboMask,
                    ),
                    callbacks = SessionToolbarModeCallbacks(
                        onPage = { page = it },
                        onOpenCombo = openCombo,
                        onToggleComboModifier = { flag -> comboMask = comboMask xor flag },
                        onSetEditingQuickRow = { editingQuickRow = it },
                        onSetAddingQuickCombo = { addingQuickCombo = it },
                        onClearComboTarget = { comboTarget = null },
                        onCollapse = { expanded = false },
                    ),
                )
            }
            AnimatedVisibility(
                visible = !editingQuickRow,
                enter = toolbarCompactRowEnter,
                exit = toolbarCompactRowExit,
            ) {
                CompactToolbarRow(
                    actions = quickActions,
                    state = state,
                    viewModel = viewModel,
                    expanded = expanded,
                    onLongKey = openCombo,
                    onToggleExpanded = {
                        if (expanded) {
                            expanded = false
                        } else {
                            comboTarget = null
                            editingQuickRow = false
                            addingQuickCombo = false
                            expanded = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactToolbarRow(
    actions: List<ToolbarActionSpec>,
    state: SessionUiState,
    viewModel: SessionViewModel,
    expanded: Boolean,
    onLongKey: (ToolbarKeySpec) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val quickScroll = rememberScrollState()
    val toggleRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(
            durationMillis = TOOLBAR_TOGGLE_ICON_DURATION_MS,
            easing = TOOLBAR_MOTION_EASING,
        ),
        label = "FunctionToolbarToggleRotation",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(quickScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.forEach { action ->
                ToolbarActionButton(
                    action = action,
                    state = state,
                    viewModel = viewModel,
                    onLongKey = onLongKey,
                    modifier = Modifier.widthIn(min = compactCellWidth(action.label)),
                )
            }
        }
        IconButton(
            onClick = onToggleExpanded,
            modifier = Modifier.size(42.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ExpandLess,
                contentDescription = stringResource(
                    if (expanded) R.string.session_toolbar_collapse else R.string.session_toolbar_more,
                ),
                modifier = Modifier.graphicsLayer { rotationZ = toggleRotation },
                tint = TOOLBAR_CONTENT,
            )
        }
    }
}

@Composable
private fun ToolbarExpandedPanel(
    page: ToolbarPage,
    state: SessionUiState,
    viewModel: SessionViewModel,
    actions: ToolbarExpandedActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolbarPage.entries.forEach { item ->
            ToolbarToggleChip(
                label = stringResource(item.labelRes),
                active = page == item,
                onClick = { actions.onPage(item) },
                modifier = Modifier.weight(1f),
                useModifierPalette = false,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolbarKey(
            label = stringResource(R.string.session_toolbar_add_combo),
            onClick = actions.onAddQuickCombo,
            modifier = Modifier.weight(TOOLBAR_PRIMARY_ACTION_WEIGHT),
            tone = ToolbarKeyTone.Action,
        )
        ToolbarKey(
            label = stringResource(R.string.session_toolbar_edit_quick),
            onClick = actions.onEditQuickRow,
            modifier = Modifier.weight(1f),
        )
    }
    ToolbarSectionLabel(text = stringResource(R.string.session_toolbar_sticky_hint))
    ToolbarModifierRow(
        stickyMask = state.stickyModifiers,
        onToggle = viewModel::toggleStickyModifier,
    )
    when (page) {
        ToolbarPage.QUICK -> {
            ToolbarKeyRows(
                rows = quickKeyGridRows,
                onKey = {
                    viewModel.sendKey(it)
                    actions.onMomentaryAction()
                },
                onLongKey = actions.onLongKey,
            )
            ToolbarComboRows(
                rows = quickComboGridRows,
                onCombo = { combo ->
                    dispatchToolbarCombo(combo, viewModel)
                    actions.onMomentaryAction()
                },
            )
        }
        ToolbarPage.NAVIGATION -> {
            ToolbarKeyRows(
                rows = navigationKeyGridRows,
                onKey = {
                    viewModel.sendKey(it)
                    actions.onMomentaryAction()
                },
                onLongKey = actions.onLongKey,
            )
        }
        ToolbarPage.FUNCTION -> {
            ToolbarKeyRows(
                rows = functionKeyGridRows,
                onKey = {
                    viewModel.sendKey(it)
                    actions.onMomentaryAction()
                },
                onLongKey = actions.onLongKey,
            )
        }
    }
}

@Composable
private fun QuickToolbarEditor(
    selectedIds: List<String>,
    onApply: (List<String>) -> Unit,
    onDone: () -> Unit,
) {
    var draftIds by remember(selectedIds) { mutableStateOf(selectedIds) }
    var showComboBuilder by remember { mutableStateOf(false) }
    val selectedScroll = rememberLazyListState()
    ToolbarSectionHeader(
        title = stringResource(R.string.session_toolbar_edit_quick_title),
        subtitle = stringResource(R.string.session_toolbar_edit_quick_hint),
    )
    QuickToolbarEditorControls(
        showComboBuilder = showComboBuilder,
        onToggleBuilder = { showComboBuilder = !showComboBuilder },
        onReset = {
            draftIds = DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS
            showComboBuilder = false
        },
        onApply = { onApply(sanitizeQuickToolbarActionIds(draftIds)) },
        onDone = onDone,
    )
    SelectedQuickActionRow(
        ids = draftIds,
        scrollState = selectedScroll,
        onMove = { id, direction ->
            val fromIndex = draftIds.indexOf(id)
            val toIndex = fromIndex + direction
            if (fromIndex >= 0 && toIndex in draftIds.indices) {
                draftIds = draftIds.toMutableList().also { list ->
                    val moved = list.removeAt(fromIndex)
                    list.add(toIndex, moved)
                }
            }
        },
        onDelete = { id ->
            draftIds = draftIds.filterNot { it == id }
                .ifEmpty { DEFAULT_FUNCTION_TOOLBAR_QUICK_IDS }
        },
    )

    if (showComboBuilder) {
        ToolbarChordBuilder(
            existingIds = draftIds,
            onAdd = { id ->
                if (draftIds.size < MAX_FUNCTION_TOOLBAR_QUICK_IDS && id !in draftIds) {
                    draftIds = draftIds + id
                }
            },
        )
    } else {
        QuickToolbarRegularKeyPicker(
            selectedIds = draftIds,
            onAction = { action ->
                if (draftIds.size < MAX_FUNCTION_TOOLBAR_QUICK_IDS) {
                    draftIds = draftIds + action.id
                }
            },
        )
    }
}

@Composable
private fun QuickToolbarEditorControls(
    showComboBuilder: Boolean,
    onToggleBuilder: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarKey(
            label = stringResource(
                if (showComboBuilder) R.string.session_toolbar_add_regular else R.string.session_toolbar_add_combo,
            ),
            onClick = onToggleBuilder,
            modifier = Modifier.weight(TOOLBAR_PRIMARY_ACTION_WEIGHT),
            tone = ToolbarKeyTone.Action,
        )
        ToolbarKey(
            label = stringResource(R.string.session_toolbar_reset),
            onClick = onReset,
            modifier = Modifier.weight(1f),
        )
        ToolbarKey(
            label = stringResource(R.string.session_toolbar_done),
            onClick = onApply,
            modifier = Modifier.weight(1f),
        )
        ToolbarKey(
            label = stringResource(R.string.session_toolbar_cancel),
            onClick = onDone,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class QuickChipDragCallbacks(
    val onSizeChanged: (Int) -> Unit,
    val onDragStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onDragEnd: () -> Unit,
)

internal data class QuickChipMove(val fromIndex: Int, val toIndex: Int)

internal data class QuickChipDragUpdate(val offsetX: Float, val move: QuickChipMove?)

internal fun calculateQuickChipDragUpdate(
    ids: List<String>,
    draggedId: String,
    itemWidthsPx: Map<String, Int>,
    itemSpacingPx: Float,
    currentOffsetX: Float,
    deltaX: Float,
): QuickChipDragUpdate {
    var nextOffsetX = currentOffsetX + deltaX
    val workingIds = ids.toMutableList()
    val initialIndex = workingIds.indexOf(draggedId)
    var currentIndex = initialIndex
    val activeWidth = itemWidthsPx[draggedId]?.toFloat()
    while (activeWidth != null && currentIndex >= 0) {
        val direction = when {
            nextOffsetX > 0f && currentIndex < workingIds.lastIndex -> 1
            nextOffsetX < 0f && currentIndex > 0 -> -1
            else -> 0
        }
        val neighborIndex = currentIndex + direction
        val neighborWidth = if (direction == 0) {
            null
        } else {
            itemWidthsPx[workingIds[neighborIndex]]?.toFloat()
        }
        val swapThreshold = neighborWidth?.let { (activeWidth + it) / 2f + itemSpacingPx }
        val crossedNeighbor = when {
            direction > 0 && swapThreshold != null -> nextOffsetX > swapThreshold
            direction < 0 && swapThreshold != null -> nextOffsetX < -swapThreshold
            else -> false
        }
        if (!crossedNeighbor) break
        val crossedNeighborWidth = checkNotNull(neighborWidth)
        val movedId = workingIds.removeAt(currentIndex)
        workingIds.add(neighborIndex, movedId)
        currentIndex = neighborIndex
        nextOffsetX -= direction * (crossedNeighborWidth + itemSpacingPx)
    }
    val move = if (currentIndex != initialIndex) {
        QuickChipMove(fromIndex = initialIndex, toIndex = currentIndex)
    } else {
        null
    }
    return QuickChipDragUpdate(offsetX = nextOffsetX, move = move)
}

@Composable
private fun SelectedQuickActionRow(
    ids: List<String>,
    scrollState: LazyListState,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val itemWidthsPx = remember { mutableStateMapOf<String, Int>() }
    val itemSpacingPx = with(LocalDensity.current) { TOOLBAR_EDITOR_ITEM_SPACING.toPx() }
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(ids, draggingId) {
        if (draggingId != null && draggingId !in ids) {
            draggingId = null
            dragOffsetX = 0f
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = scrollState,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(TOOLBAR_EDITOR_ITEM_SPACING),
    ) {
        itemsIndexed(
            items = ids,
            key = { _, id -> id },
        ) { _, id ->
            val action = resolveToolbarAction(id) ?: return@itemsIndexed
            val isDragging = draggingId == id
            EditableQuickChip(
                action = action,
                isDragging = isDragging,
                dragOffsetX = if (isDragging) dragOffsetX else 0f,
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(
                        durationMillis = TOOLBAR_REORDER_FADE_DURATION_MS,
                        easing = TOOLBAR_MOTION_EASING,
                    ),
                    placementSpec = if (isDragging) {
                        null
                    } else {
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                    },
                    fadeOutSpec = tween(
                        durationMillis = TOOLBAR_REORDER_FADE_DURATION_MS,
                        easing = TOOLBAR_MOTION_EASING,
                    ),
                ),
                dragCallbacks = QuickChipDragCallbacks(
                    onSizeChanged = { widthPx ->
                        if (itemWidthsPx[id] != widthPx) itemWidthsPx[id] = widthPx
                    },
                    onDragStart = {
                        draggingId = id
                        dragOffsetX = 0f
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = drag@{ deltaX ->
                        if (draggingId != id) return@drag
                        val update = calculateQuickChipDragUpdate(
                            ids = ids,
                            draggedId = id,
                            itemWidthsPx = itemWidthsPx,
                            itemSpacingPx = itemSpacingPx,
                            currentOffsetX = dragOffsetX,
                            deltaX = deltaX,
                        )
                        update.move?.let { move ->
                            val visibleIndex = scrollState.firstVisibleItemIndex
                            val visibleOffset = scrollState.firstVisibleItemScrollOffset
                            onMove(id, move.toIndex - move.fromIndex)
                            scrollState.requestScrollToItem(visibleIndex, visibleOffset)
                        }
                        dragOffsetX = update.offsetX
                    },
                    onDragEnd = {
                        if (draggingId == id) {
                            draggingId = null
                            dragOffsetX = 0f
                        }
                    },
                ),
                onDelete = { onDelete(id) },
            )
        }
    }
}

@Composable
private fun QuickToolbarRegularKeyPicker(
    selectedIds: List<String>,
    onAction: (ToolbarActionSpec) -> Unit,
) {
    val selectedIdSet = remember(selectedIds) { selectedIds.toSet() }
    val isAtLimit = selectedIds.size >= MAX_FUNCTION_TOOLBAR_QUICK_IDS
    ToolbarSectionLabel(text = stringResource(R.string.session_toolbar_add_regular_hint))
    if (isAtLimit) {
        ToolbarQuickLimitNotice()
    }
    ToolbarEditorActionSection(
        title = stringResource(R.string.session_toolbar_modifiers),
        actions = modifierToolbarActions,
        selectedIdSet = selectedIdSet,
        enabled = !isAtLimit,
        onAction = onAction,
    )
    ToolbarEditorActionSection(
        title = stringResource(R.string.session_toolbar_combo_common),
        actions = quickKeyToolbarActions,
        selectedIdSet = selectedIdSet,
        enabled = !isAtLimit,
        onAction = onAction,
    )
    ToolbarEditorActionSection(
        title = stringResource(R.string.session_toolbar_combo_navigation),
        actions = navigationKeyToolbarActions,
        selectedIdSet = selectedIdSet,
        enabled = !isAtLimit,
        onAction = onAction,
    )
    ToolbarEditorActionSection(
        title = stringResource(R.string.session_toolbar_combo_function),
        actions = functionKeyToolbarActions,
        selectedIdSet = selectedIdSet,
        enabled = !isAtLimit,
        onAction = onAction,
    )
    ToolbarEditorActionSection(
        title = stringResource(R.string.session_toolbar_saved_combos),
        actions = quickComboToolbarActions,
        selectedIdSet = selectedIdSet,
        enabled = !isAtLimit,
        onAction = onAction,
    )
}

@Composable
private fun ToolbarEditorActionSection(
    title: String,
    actions: List<ToolbarActionSpec>,
    selectedIdSet: Set<String>,
    enabled: Boolean,
    onAction: (ToolbarActionSpec) -> Unit,
) {
    val availableActions = remember(actions, selectedIdSet) {
        actions.filterNot { it.id in selectedIdSet }
    }
    if (availableActions.isEmpty()) return
    ToolbarSectionLabel(text = title, compact = true)
    ToolbarActionGrid(
        actions = availableActions,
        isEnabled = { enabled },
        onAction = onAction,
    )
}

@Composable
private fun EditableQuickChip(
    action: ToolbarActionSpec,
    isDragging: Boolean,
    dragOffsetX: Float,
    modifier: Modifier = Modifier,
    dragCallbacks: QuickChipDragCallbacks,
    onDelete: () -> Unit,
) {
    val currentDragCallbacks = rememberUpdatedState(dragCallbacks)
    val animatedDragOffsetX by animateFloatAsState(
        targetValue = if (isDragging) dragOffsetX else 0f,
        animationSpec = if (isDragging) {
            snap()
        } else {
            spring(
                dampingRatio = TOOLBAR_REORDER_SETTLE_DAMPING_RATIO,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "QuickToolbarChipDragOffset",
    )
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) TOOLBAR_REORDER_DRAG_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = TOOLBAR_REORDER_PICKUP_DAMPING_RATIO,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "QuickToolbarChipDragScale",
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isDragging) TOOLBAR_REORDER_DRAG_ELEVATION else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "QuickToolbarChipDragElevation",
    )
    Box(
        modifier = modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationX = animatedDragOffsetX
                scaleX = dragScale
                scaleY = dragScale
                shadowElevation = dragElevation.toPx()
                shape = RoundedCornerShape(10.dp)
            }
            .onSizeChanged { dragCallbacks.onSizeChanged(it.width) }
            .quickChipReorderGesture(
                onDragStart = { currentDragCallbacks.value.onDragStart() },
                onDrag = { deltaX -> currentDragCallbacks.value.onDrag(deltaX) },
                onDragEnd = { currentDragCallbacks.value.onDragEnd() },
            ),
    ) {
        ToolbarPreviewChip(
            label = action.label,
            modifier = Modifier.widthIn(min = compactCellWidth(action.label)),
            tone = toolbarToneForAction(action),
        )
        Surface(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 5.dp, y = (-5).dp)
                .size(20.dp),
            shape = CircleShape,
            color = TOOLBAR_CONTENT,
            contentColor = TOOLBAR_BG,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolbarChordBuilder(
    existingIds: List<String>,
    onAdd: (String) -> Unit,
) {
    var modifierMask by remember { mutableIntStateOf(0) }
    val existingIdSet = remember(existingIds) { existingIds.toSet() }
    val isAtLimit = existingIds.size >= MAX_FUNCTION_TOOLBAR_QUICK_IDS
    if (isAtLimit) {
        ToolbarQuickLimitNotice()
    }
    ToolbarSectionLabel(text = stringResource(R.string.session_toolbar_combo_step_modifiers))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        toolbarModifierSpecs.forEach { spec ->
            ToolbarToggleChip(
                label = spec.label,
                active = modifierMask and spec.flag != 0,
                onClick = { modifierMask = modifierMask xor spec.flag },
                modifier = Modifier.weight(1f),
            )
        }
    }
    ToolbarPreviewChip(
        label = if (modifierMask == 0) {
            stringResource(R.string.session_toolbar_combo_preview_empty)
        } else {
            stringResource(R.string.session_toolbar_combo_preview, buildChordLabel(modifierMask, "…"))
        },
        modifier = Modifier.fillMaxWidth(),
        tone = ToolbarKeyTone.Combination,
    )
    ToolbarSectionLabel(text = stringResource(R.string.session_toolbar_combo_step_key))
    ToolbarChordKeySections(
        modifierMask = modifierMask,
        existingIdSet = existingIdSet,
        isAtLimit = isAtLimit,
        onAdd = onAdd,
    )
}

@Composable
private fun ToolbarActionGrid(
    actions: List<ToolbarActionSpec>,
    onAction: (ToolbarActionSpec) -> Unit,
    isSelected: (ToolbarActionSpec) -> Boolean = { false },
    isEnabled: (ToolbarActionSpec) -> Boolean = { true },
) {
    val rows = remember(actions) { actions.chunked(TOOLBAR_GRID_COLUMNS) }
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { action ->
                if (isSelected(action)) {
                    ToolbarPreviewChip(
                        label = action.label,
                        modifier = Modifier.weight(1f),
                        tone = toolbarToneForAction(action),
                    )
                } else {
                    ToolbarKey(
                        label = action.label,
                        onClick = { onAction(action) },
                        modifier = Modifier.weight(1f),
                        enabled = isEnabled(action),
                        tone = toolbarToneForAction(action),
                    )
                }
            }
            repeat(TOOLBAR_GRID_COLUMNS - row.size) {
                Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun Modifier.quickChipReorderGesture(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startMs = SystemClock.uptimeMillis()
        var totalDx = 0f
        var totalDy = 0f
        var dragging = false
        var tracking = true
        try {
            while (tracking) {
                val armLongPress = !dragging && abs(totalDx) + abs(totalDy) <= TOOLBAR_REORDER_SLOP_PX
                val event = if (armLongPress) {
                    val remaining = (
                        TOOLBAR_REORDER_LONG_PRESS_MS - (SystemClock.uptimeMillis() - startMs)
                    ).coerceAtLeast(1L)
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    dragging = true
                    onDragStart()
                    down.consume()
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    if (dragging) change?.consume()
                    tracking = false
                } else {
                    val delta = change.positionChange()
                    totalDx += delta.x
                    totalDy += delta.y
                    if (!dragging && abs(totalDx) + abs(totalDy) > TOOLBAR_REORDER_SLOP_PX) {
                        tracking = false
                    } else if (dragging) {
                        onDrag(delta.x)
                        change.consume()
                    }
                }
            }
        } finally {
            if (dragging) onDragEnd()
        }
    }
}

private fun compactCellWidth(label: String): Dp = when {
    label.length >= 12 -> 112.dp
    label.length >= 8 -> 96.dp
    label.length >= 5 -> 78.dp
    else -> 64.dp
}

private const val TOOLBAR_REORDER_LONG_PRESS_MS = 350L
private const val TOOLBAR_REORDER_SLOP_PX = 8f
private const val TOOLBAR_REORDER_DRAG_SCALE = 1.06f
private const val TOOLBAR_REORDER_PICKUP_DAMPING_RATIO = 0.72f
private const val TOOLBAR_REORDER_SETTLE_DAMPING_RATIO = 0.78f
private const val TOOLBAR_REORDER_FADE_DURATION_MS = 160
private const val TOOLBAR_PANEL_ENTER_DURATION_MS = 280
private const val TOOLBAR_PANEL_FADE_DURATION_MS = 180
private const val TOOLBAR_PANEL_EXIT_DURATION_MS = 220
private const val TOOLBAR_PANEL_SLIDE_DIVISOR = 6
private const val TOOLBAR_COMPACT_ROW_ENTER_DURATION_MS = 220
private const val TOOLBAR_COMPACT_ROW_FADE_DURATION_MS = 160
private const val TOOLBAR_COMPACT_ROW_EXIT_DURATION_MS = 180
private const val TOOLBAR_TOGGLE_ICON_DURATION_MS = 260
private const val TOOLBAR_GRID_COLUMNS = 4
private const val TOOLBAR_PRIMARY_ACTION_WEIGHT = 1.35f
private val TOOLBAR_SECTION_SPACING = 6.dp
private val TOOLBAR_EDITOR_ITEM_SPACING = 8.dp
private val TOOLBAR_REORDER_DRAG_ELEVATION = 10.dp
private val TOOLBAR_KEY_MIN_HEIGHT = 36.dp
private val TOOLBAR_EXPANDED_PANEL_MAX_HEIGHT = 244.dp

@Composable
private fun ToolbarActionButton(
    action: ToolbarActionSpec,
    state: SessionUiState,
    viewModel: SessionViewModel,
    onLongKey: (ToolbarKeySpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modifierFlag = action.modifierFlag
    if (modifierFlag != 0) {
        ToolbarToggleChip(
            label = action.label,
            active = state.stickyModifiers and modifierFlag != 0,
            onClick = { viewModel.toggleStickyModifier(modifierFlag) },
            modifier = modifier,
        )
    } else {
        val keySpec = action.vk
            ?.takeIf { action.modifiers == 0 && !action.isCtrlAltDel }
            ?.let { ToolbarKeySpec(action.id, action.label, it) }
        ToolbarKey(
            label = action.label,
            onClick = { dispatchToolbarAction(action, viewModel) },
            onLongClick = keySpec?.let { { onLongKey(it) } },
            modifier = modifier,
            tone = toolbarToneForAction(action),
        )
    }
}

private fun dispatchToolbarAction(action: ToolbarActionSpec, viewModel: SessionViewModel) {
    when {
        action.isCtrlAltDel -> viewModel.sendCtrlAltDel()
        action.modifiers != 0 -> action.vk?.let { viewModel.sendKeyWithModifiers(it, action.modifiers) }
        action.vk != null -> viewModel.sendKey(action.vk)
    }
}

private fun dispatchToolbarCombo(combo: ToolbarComboSpec, viewModel: SessionViewModel) {
    if (combo.isCtrlAltDel) {
        viewModel.sendCtrlAltDel()
    } else {
        combo.keyVk?.let { viewModel.sendKeyWithModifiers(it, combo.modifiers) }
    }
}

// Zoom-handle drag tuning (issue #2).
private const val ZOOM_DRAG_SLOP_PX = 8f      // px of finger travel before a press becomes a drag
private const val ZOOM_DRAG_BASE = 2f         // zoom multiplies by this over one TRAVEL_PX of drag
private const val ZOOM_DRAG_TRAVEL_PX = 180f  // finger px that doubles / halves the zoom
private const val ZOOM_DOUBLE_TAP_MS = 280L   // double-tap window for reset-to-100%
// Hold the pill still this long, then drag, to relocate it (matches PAN_HANDLE_LONG_PRESS_MS).
private const val ZOOM_RELOCATE_LONG_PRESS_MS = 350L

/**
 * Floating zoom control — a SINGLE compact pill (issue #2): drag it UP to zoom in, DOWN to zoom
 * out (continuous), double-tap to reset to 100%. Replaces the old ＋ / － / reset button stack that
 * ran a tall strip down the right edge: being a large touch dead-zone it kept swallowing the
 * two-finger scroll/native-touch gestures (worst in TOUCH mode, where every gesture is multi-finger and
 * one finger often landed on a button). The pill is a much smaller target, so the canvas gets the
 * whole right edge back.
 *
 * Long-press the pill (hold it still [ZOOM_RELOCATE_LONG_PRESS_MS]), THEN drag, to relocate the pill
 * itself out of the way of whatever it covers — the SAME vocab as the move handle (用户需求: 缩放按钮
 * 也能像移动杆一样长按拖动改位置，防止正好遮挡画面内容). It remembers its dragged-to position, clamped via
 * [clampPillOffset] to [containerSize] so it can never be dragged off-screen and lost (it is the ONLY
 * zoom control — there is no pinch gesture); the clamp is also re-applied when the container reflows
 * (soft keyboard / rotation). The gesture itself lives in [Modifier.zoomPillGestures]; relocate is
 * armed ONLY while the finger stays within [ZOOM_DRAG_SLOP_PX] of the down point, so a vertical drag
 * past that is a zoom instead and a quick tap still falls through to the double-tap reset.
 *
 * Collects userTransform internally so a zoom change recomposes ONLY this small overlay — the
 * framebuffer AndroidView reads the transform deferred inside its graphicsLayer and never
 * recomposes. The gesture consumes its own pointer, so dragging the pill never leaks into the
 * canvas gesture surface behind it.
 */
@Composable
private fun ZoomControls(
    controller: RdpInputController,
    containerSize: IntSize,
    containerColor: Color,
    glass: FloatingGlassContext,
    modifier: Modifier = Modifier,
) {
    val transformState = glass.transformState
    val pct by remember(transformState) {
        derivedStateOf { (transformState.value.zoom * 100).roundToInt() }
    }
    val zoomed = pct > 100
    var dragging by remember { mutableStateOf(false) }
    var movingSelf by remember { mutableStateOf(false) }
    // The pill remembers where it was dragged (long-press-then-drag relocates it, like the move handle).
    var handleOffset by remember { mutableStateOf(Offset.Zero) }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    val latestContainerSize = rememberUpdatedState(containerSize)
    // Re-clamp on container/pill reflow (soft keyboard, rotation) so the pill stays reachable.
    LaunchedEffect(containerSize, pillSize) {
        handleOffset = clampPillOffset(handleOffset, 0f, 0f, containerSize, pillSize)
    }

    GlassControlContainer(
        glass = glass,
        containerColor = containerColor,
        shape = CircleShape,
        modifier = modifier
            .onSizeChanged { pillSize = it }
            .offset { IntOffset(handleOffset.x.roundToInt(), handleOffset.y.roundToInt()) }
            .zoomPillGestures(
                controller = controller,
                containerSize = { latestContainerSize.value },
                pillSize = { pillSize },
                handleOffset = { handleOffset },
                setHandleOffset = { handleOffset = it },
                setDragging = { dragging = it },
                setMovingSelf = { movingSelf = it },
            ),
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (movingSelf) Icons.Default.DragIndicator else Icons.Default.ZoomIn,
                    contentDescription = stringResource(R.string.session_zoom_cd),
                    tint = TOOLBAR_CONTENT,
                )
                // Live % only while dragging or zoomed in, so at 100% the handle stays minimal.
                if (dragging || zoomed) {
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        color = TOOLBAR_CONTENT,
                    )
                }
            }
        }
    }
}

private class ZoomPillGestureState(
    val pointerId: androidx.compose.ui.input.pointer.PointerId,
    val startZoom: Float,
    val startOffset: Offset,
    val startMs: Long,
) {
    var totalDx: Float = 0f
    var totalDy: Float = 0f
    var tapCancelled: Boolean = false
    var endedNormally: Boolean = false
    var isDrag: Boolean = false
    var moveSelf: Boolean = false
}

private fun Modifier.zoomPillGestures(
    controller: RdpInputController,
    containerSize: () -> IntSize,
    pillSize: () -> IntSize,
    handleOffset: () -> Offset,
    setHandleOffset: (Offset) -> Unit,
    setDragging: (Boolean) -> Unit,
    setMovingSelf: (Boolean) -> Unit,
): Modifier = pointerInput(controller) {
    var lastTapMs = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val gesture = ZoomPillGestureState(
            pointerId = down.id,
            startZoom = controller.userZoom(),
            startOffset = handleOffset(),
            startMs = SystemClock.uptimeMillis(),
        )
        var tracking = true
        try {
            while (tracking) {
                val event = awaitZoomPillEvent(gesture)
                if (event == null) {
                    gesture.moveSelf = true
                    setMovingSelf(true)
                } else {
                    tracking = processZoomPillEvent(
                        event = event,
                        gesture = gesture,
                        controller = controller,
                        containerSize = containerSize(),
                        pillSize = pillSize(),
                        setHandleOffset = setHandleOffset,
                        setDragging = setDragging,
                    )
                }
            }
        } finally {
            setDragging(false)
            setMovingSelf(false)
        }
        lastTapMs = resolveZoomPillTapReset(gesture, lastTapMs, controller)
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitZoomPillEvent(
    gesture: ZoomPillGestureState,
): androidx.compose.ui.input.pointer.PointerEvent? {
    val armLong = !gesture.isDrag && !gesture.moveSelf &&
        hypot(gesture.totalDx, gesture.totalDy) <= ZOOM_DRAG_SLOP_PX
    if (!armLong) return awaitPointerEvent()
    val remaining = (ZOOM_RELOCATE_LONG_PRESS_MS - (SystemClock.uptimeMillis() - gesture.startMs))
        .coerceAtLeast(1L)
    return withTimeoutOrNull(remaining) { awaitPointerEvent() }
}

private fun processZoomPillEvent(
    event: androidx.compose.ui.input.pointer.PointerEvent,
    gesture: ZoomPillGestureState,
    controller: RdpInputController,
    containerSize: IntSize,
    pillSize: IntSize,
    setHandleOffset: (Offset) -> Unit,
    setDragging: (Boolean) -> Unit,
): Boolean {
    val change = event.changes.firstOrNull { it.id == gesture.pointerId }
    if (change == null || !change.pressed) {
        change?.consume()
        gesture.endedNormally = change != null
        return false
    }
    val d = change.positionChange()
    gesture.totalDx += d.x
    gesture.totalDy += d.y
    if (hypot(gesture.totalDx, gesture.totalDy) > ZOOM_DRAG_SLOP_PX) gesture.tapCancelled = true
    val verticalDrag = abs(gesture.totalDy) > ZOOM_DRAG_SLOP_PX && abs(gesture.totalDy) >= abs(gesture.totalDx)
    if (!gesture.moveSelf && !gesture.isDrag && verticalDrag) {
        gesture.isDrag = true
        setDragging(true)
    }
    if (gesture.moveSelf) {
        setHandleOffset(clampPillOffset(gesture.startOffset, gesture.totalDx, gesture.totalDy, containerSize, pillSize))
    } else if (gesture.isDrag) {
        val factor = ZOOM_DRAG_BASE.pow(-gesture.totalDy / ZOOM_DRAG_TRAVEL_PX)
        controller.setUserZoom(gesture.startZoom * factor)
    }
    change.consume()
    return true
}

private fun resolveZoomPillTapReset(
    gesture: ZoomPillGestureState,
    lastTapMs: Long,
    controller: RdpInputController,
): Long {
    val moved = gesture.isDrag || gesture.moveSelf || gesture.tapCancelled
    if (!gesture.endedNormally || moved) return 0L
    val now = SystemClock.uptimeMillis()
    return if (now - lastTapMs in 1..ZOOM_DOUBLE_TAP_MS) {
        controller.resetUserTransform()
        0L
    } else {
        now
    }
}

/**
 * Clamp the zoom pill's relocate offset so the pill always stays reachable inside [container]. At
 * offset 0 the pill sits at CenterEnd, so it may move LEFT by up to (container − pill), barely right
 * (clamped to 0), and ± half the vertical slack. No-ops until both sizes are measured.
 */
internal fun clampPillOffset(base: Offset, dx: Float, dy: Float, container: IntSize, pill: IntSize): Offset {
    if (minOf(container.width, container.height, pill.width, pill.height) <= 0) {
        return Offset(base.x + dx, base.y + dy)
    }
    val minX = -(container.width - pill.width).coerceAtLeast(0).toFloat()
    val maxY = (container.height - pill.height).coerceAtLeast(0) / 2f
    return Offset((base.x + dx).coerceIn(minX, 0f), (base.y + dy).coerceIn(-maxY, maxY))
}

@Composable
private fun SavedToolbarChordBuilder(
    existingIds: List<String>,
    onAdd: (String) -> Unit,
    onCancel: () -> Unit,
) {
    ToolbarSectionHeader(
        title = stringResource(R.string.session_toolbar_add_combo_title),
        subtitle = stringResource(R.string.session_toolbar_add_combo_example),
        onClose = onCancel,
    )
    ToolbarChordBuilder(
        existingIds = existingIds,
        onAdd = onAdd,
    )
}

@Composable
private fun ToolbarQuickLimitNotice() {
    Text(
        text = stringResource(R.string.session_toolbar_quick_limit_reached, MAX_FUNCTION_TOOLBAR_QUICK_IDS),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ToolbarChordKeySections(
    modifierMask: Int,
    existingIdSet: Set<String>,
    isAtLimit: Boolean,
    onAdd: (String) -> Unit,
) {
    ToolbarChordKeySection(
        title = stringResource(R.string.session_toolbar_combo_letters),
        actions = letterToolbarActions,
        modifierMask = modifierMask,
        existingIdSet = existingIdSet,
        isAtLimit = isAtLimit,
        onAdd = onAdd,
    )
    ToolbarChordKeySection(
        title = stringResource(R.string.session_toolbar_combo_numbers),
        actions = numberToolbarActions,
        modifierMask = modifierMask,
        existingIdSet = existingIdSet,
        isAtLimit = isAtLimit,
        onAdd = onAdd,
    )
    ToolbarChordKeySection(
        title = stringResource(R.string.session_toolbar_combo_common),
        actions = commonChordToolbarActions,
        modifierMask = modifierMask,
        existingIdSet = existingIdSet,
        isAtLimit = isAtLimit,
        onAdd = onAdd,
    )
    ToolbarChordKeySection(
        title = stringResource(R.string.session_toolbar_combo_navigation),
        actions = navigationKeyToolbarActions,
        modifierMask = modifierMask,
        existingIdSet = existingIdSet,
        isAtLimit = isAtLimit,
        onAdd = onAdd,
    )
    ToolbarChordKeySection(
        title = stringResource(R.string.session_toolbar_combo_function),
        actions = functionKeyToolbarActions,
        modifierMask = modifierMask,
        existingIdSet = existingIdSet,
        isAtLimit = isAtLimit,
        onAdd = onAdd,
    )
}

private fun toolbarActionForKey(key: ToolbarKeySpec): ToolbarActionSpec =
    ToolbarActionSpec(id = key.id, label = key.label, vk = key.vk)

@Composable
private fun ToolbarChordKeySection(
    title: String,
    actions: List<ToolbarActionSpec>,
    modifierMask: Int,
    existingIdSet: Set<String>,
    isAtLimit: Boolean,
    onAdd: (String) -> Unit,
) {
    ToolbarSectionLabel(text = title, compact = true)
    ToolbarActionGrid(
        actions = actions,
        isSelected = { action ->
            modifierMask != 0 && encodeToolbarChordId(modifierMask, action.id) in existingIdSet
        },
        isEnabled = { modifierMask != 0 && !isAtLimit },
        onAction = { action -> onAdd(encodeToolbarChordId(modifierMask, action.id)) },
    )
}

internal fun clampCenteredControlOffset(
    base: Offset,
    dx: Float,
    dy: Float,
    container: IntSize,
    control: IntSize,
): Offset {
    if (minOf(container.width, container.height, control.width, control.height) <= 0) {
        return Offset(base.x + dx, base.y + dy)
    }
    val maxX = ((container.width - control.width).coerceAtLeast(0) / 2f)
    val maxY = ((container.height - control.height).coerceAtLeast(0) / 2f)
    return Offset(
        x = (base.x + dx).coerceIn(-maxX, maxX),
        y = (base.y + dy).coerceIn(-maxY, maxY),
    )
}

// Move-handle tuning (issue: a dedicated button to pan the magnified picture, plus long-press to
// relocate the button so it can't sit over what you want to tap).
private const val PAN_HANDLE_SIZE_DP = 60
private const val PAN_HANDLE_SLOP_PX = 6f          // travel before a press counts as a drag
private const val PAN_HANDLE_LONG_PRESS_MS = 350L  // hold this long → drag the handle itself
// Pan gain used when the handle is shown for the keyboard-lift case at 100% zoom (not magnified).
// At zoom 1 the normal gain (= zoom = 1) would need a full-screen-height drag to reveal the lifted-off
// top; a >1 gain lets a moderate drag pull the picture down to the top (clampPan still bounds it).
private const val PAN_HANDLE_KEYBOARD_GAIN = 2.5f

/**
 * Floating MOVE handle. Shown while the picture is zoomed in OR while it's auto-lifted above the soft
 * keyboard (offsetY < 0) even at 100% — so the user can pull the lifted picture back down to see its
 * top (用户需求: 呼出键盘后能移动画面看上方内容). Drag it to pan the framebuffer the same direction
 * (gain = current zoom when magnified, else [PAN_HANDLE_KEYBOARD_GAIN] for the keyboard case). Long-
 * press it, then drag, to relocate the handle out of the way of whatever you need to tap.
 *
 * Collects userTransform internally so only this small overlay recomposes; its pointerInput consumes
 * its own pointer, so neither panning nor relocating leaks into the canvas gesture surface behind it.
 */
@Composable
private fun PanHandle(
    controller: RdpInputController,
    containerSize: IntSize,
    containerColor: Color,
    glass: FloatingGlassContext,
    modifier: Modifier = Modifier,
) {
    val transformState = glass.transformState
    // remember is called unconditionally (before the early return) so the slot table stays stable;
    // the handle keeps its dragged-to position across hide/show.
    var handleOffset by remember { mutableStateOf(Offset.Zero) }
    var handleSize by remember { mutableStateOf(IntSize.Zero) }
    var movingSelf by remember { mutableStateOf(false) }
    val latestContainerSize = rememberUpdatedState(containerSize)
    LaunchedEffect(containerSize, handleSize) {
        handleOffset = clampCenteredControlOffset(
            base = handleOffset,
            dx = 0f,
            dy = 0f,
            container = containerSize,
            control = handleSize,
        )
    }
    val visible by remember(transformState) {
        derivedStateOf {
            val t = transformState.value
            (t.zoom * 100).roundToInt() > 100 || t.offsetY < 0f
        }
    }
    if (!visible) return

    GlassControlContainer(
        glass = glass,
        containerColor = containerColor,
        shape = CircleShape,
        modifier = modifier
            .offset { IntOffset(handleOffset.x.roundToInt(), handleOffset.y.roundToInt()) }
            .size(PAN_HANDLE_SIZE_DP.dp)
            .onSizeChanged { handleSize = it }
            .panHandleGestures(
                controller = controller,
                containerSize = { latestContainerSize.value },
                handleSize = { handleSize },
                handleOffset = { handleOffset },
                setHandleOffset = { handleOffset = it },
                setMovingSelf = { movingSelf = it },
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = Color.Transparent,
            tonalElevation = 3.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (movingSelf) Icons.Default.DragIndicator else Icons.Default.OpenWith,
                    contentDescription = stringResource(R.string.session_pan_cd),
                    tint = TOOLBAR_CONTENT,
                )
            }
        }
    }
}

private class PanHandleGestureState(
    val pointerId: androidx.compose.ui.input.pointer.PointerId,
    val startPanX: Float,
    val startPanY: Float,
    val gain: Float,
    val startOffset: Offset,
    val startMs: Long,
) {
    var totalDx: Float = 0f
    var totalDy: Float = 0f
    var moveSelf: Boolean = false
}

private fun Modifier.panHandleGestures(
    controller: RdpInputController,
    containerSize: () -> IntSize,
    handleSize: () -> IntSize,
    handleOffset: () -> Offset,
    setHandleOffset: (Offset) -> Unit,
    setMovingSelf: (Boolean) -> Unit,
): Modifier = pointerInput(controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val gain = if (controller.userZoom() > 1f) controller.userZoom() else PAN_HANDLE_KEYBOARD_GAIN
        val gesture = PanHandleGestureState(
            pointerId = down.id,
            startPanX = controller.userPanX(),
            startPanY = controller.userPanY(),
            gain = gain,
            startOffset = handleOffset(),
            startMs = SystemClock.uptimeMillis(),
        )
        var tracking = true
        try {
            while (tracking) {
                val event = awaitPanHandleEvent(gesture)
                if (event == null) {
                    gesture.moveSelf = true
                    setMovingSelf(true)
                } else {
                    tracking = processPanHandleEvent(
                        event = event,
                        gesture = gesture,
                        controller = controller,
                        containerSize = containerSize(),
                        handleSize = handleSize(),
                        setHandleOffset = setHandleOffset,
                    )
                }
            }
        } finally {
            setMovingSelf(false)
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPanHandleEvent(
    gesture: PanHandleGestureState,
): androidx.compose.ui.input.pointer.PointerEvent? {
    val armLong = !gesture.moveSelf && abs(gesture.totalDx) + abs(gesture.totalDy) <= PAN_HANDLE_SLOP_PX
    if (!armLong) return awaitPointerEvent()
    val remaining = (PAN_HANDLE_LONG_PRESS_MS - (SystemClock.uptimeMillis() - gesture.startMs))
        .coerceAtLeast(1L)
    return withTimeoutOrNull(remaining) { awaitPointerEvent() }
}

private fun processPanHandleEvent(
    event: androidx.compose.ui.input.pointer.PointerEvent,
    gesture: PanHandleGestureState,
    controller: RdpInputController,
    containerSize: IntSize,
    handleSize: IntSize,
    setHandleOffset: (Offset) -> Unit,
): Boolean {
    val change = event.changes.firstOrNull { it.id == gesture.pointerId }
    if (change == null || !change.pressed) {
        change?.consume()
        return false
    }
    val d = change.positionChange()
    gesture.totalDx += d.x
    gesture.totalDy += d.y
    if (gesture.moveSelf) {
        setHandleOffset(
            clampCenteredControlOffset(
                base = gesture.startOffset,
                dx = gesture.totalDx,
                dy = gesture.totalDy,
                container = containerSize,
                control = handleSize,
            ),
        )
    } else {
        controller.setUserPan(
            gesture.startPanX + gesture.totalDx * gesture.gain,
            gesture.startPanY + gesture.totalDy * gesture.gain,
        )
    }
    change.consume()
    return true
}

@Composable
private fun ToolbarModifierRow(stickyMask: Int, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        toolbarModifierSpecs.forEach { spec ->
            ToolbarToggleChip(
                label = spec.label,
                active = stickyMask and spec.flag != 0,
                onClick = { onToggle(spec.flag) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ToolbarKeyRows(
    rows: List<List<ToolbarKeySpec>>,
    onKey: (Int) -> Unit,
    onLongKey: (ToolbarKeySpec) -> Unit,
) {
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { key ->
                ToolbarKey(
                    label = key.label,
                    onClick = { onKey(key.vk) },
                    onLongClick = { onLongKey(key) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(TOOLBAR_GRID_COLUMNS - row.size) {
                Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolbarComboRows(rows: List<List<ToolbarComboSpec>>, onCombo: (ToolbarComboSpec) -> Unit) {
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { combo ->
                ToolbarKey(
                    label = combo.label,
                    onClick = { onCombo(combo) },
                    modifier = Modifier.weight(1f),
                    tone = ToolbarKeyTone.Combination,
                )
            }
            repeat(TOOLBAR_GRID_COLUMNS - row.size) {
                Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolbarComboBuilder(
    target: ToolbarKeySpec,
    selectedMask: Int,
    onToggleModifier: (Int) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarPreviewChip(
                label = buildChordLabel(selectedMask, target.label),
                modifier = Modifier.weight(1.4f),
                tone = ToolbarKeyTone.Combination,
            )
            ToolbarKey(
                label = stringResource(R.string.session_toolbar_send),
                onClick = onSend,
                modifier = Modifier.weight(0.9f),
            )
            ToolbarKey(
                label = stringResource(R.string.session_toolbar_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(0.9f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            toolbarModifierSpecs.forEach { spec ->
                ToolbarToggleChip(
                    label = spec.label,
                    active = selectedMask and spec.flag != 0,
                    onClick = { onToggleModifier(spec.flag) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun buildChordLabel(mask: Int, keyLabel: String): String =
    (toolbarModifierSpecs.filter { spec -> mask and spec.flag != 0 }.map { it.label } + keyLabel)
        .joinToString("+")

private enum class ToolbarKeyTone {
    Standard,
    Combination,
    Action,
}

private fun toolbarToneForAction(action: ToolbarActionSpec): ToolbarKeyTone = when {
    action.modifiers != 0 || action.isCtrlAltDel || action.id.startsWith("chord_") -> ToolbarKeyTone.Combination
    action.modifierFlag != 0 -> ToolbarKeyTone.Action
    else -> ToolbarKeyTone.Standard
}

private fun toolbarToneColors(tone: ToolbarKeyTone): Pair<Color, Color> = when (tone) {
    ToolbarKeyTone.Standard -> TOOLBAR_KEY_FILL to TOOLBAR_KEY_BORDER
    ToolbarKeyTone.Combination -> TOOLBAR_COMBO_FILL to TOOLBAR_COMBO_BORDER
    ToolbarKeyTone.Action -> TOOLBAR_ACTION_FILL to TOOLBAR_ACTION_BORDER
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tone: ToolbarKeyTone = ToolbarKeyTone.Standard,
) {
    val (fillColor, borderColor) = toolbarToneColors(tone)
    Surface(
        modifier = modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) fillColor else Color.White.copy(alpha = 0.04f),
        contentColor = if (enabled) TOOLBAR_CONTENT else TOOLBAR_CONTENT.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, if (enabled) borderColor else TOOLBAR_CONTENT.copy(alpha = 0.18f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOOLBAR_KEY_MIN_HEIGHT)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * A latching modifier key (Ctrl/Alt/Shift/Win). It uses a dedicated purple family so modifiers are
 * immediately distinguishable from ordinary keys; the selected state flips to a solid light fill.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    useModifierPalette: Boolean = true,
) {
    val inactiveFill = if (useModifierPalette) TOOLBAR_MODIFIER_FILL else TOOLBAR_KEY_FILL
    val activeFill = if (useModifierPalette) TOOLBAR_MODIFIER_ACTIVE else TOOLBAR_ACTION_BORDER
    val outline = if (useModifierPalette) TOOLBAR_MODIFIER_BORDER else TOOLBAR_KEY_BORDER
    Surface(
        modifier = modifier.combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (active) activeFill else inactiveFill,
        contentColor = if (active) TOOLBAR_BG else TOOLBAR_CONTENT,
        border = BorderStroke(1.dp, outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOOLBAR_KEY_MIN_HEIGHT)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ToolbarPreviewChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: ToolbarKeyTone = ToolbarKeyTone.Standard,
) {
    val (fillColor, borderColor) = toolbarToneColors(tone)
    Surface(
        modifier = modifier.widthIn(min = 96.dp),
        shape = RoundedCornerShape(8.dp),
        color = fillColor,
        contentColor = TOOLBAR_CONTENT,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOOLBAR_KEY_MIN_HEIGHT)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ToolbarSectionHeader(
    title: String,
    subtitle: String,
    onClose: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, TOOLBAR_ACTION_BORDER.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, color = TOOLBAR_CONTENT, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    color = TOOLBAR_CONTENT.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.session_toolbar_cancel),
                        tint = TOOLBAR_CONTENT,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarSectionLabel(text: String, compact: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = if (compact) 2.dp else 5.dp),
        color = TOOLBAR_CONTENT.copy(alpha = if (compact) 0.78f else 0.92f),
        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
    )
}
