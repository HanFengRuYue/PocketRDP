package com.hanfengruyue.pocketrdp.feature.session

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.feature.session.input.RdpInputController
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.SessionImeBridge
import com.hanfengruyue.pocketrdp.feature.session.input.UserTransform
import com.hanfengruyue.pocketrdp.feature.session.input.sessionGestures
import com.hanfengruyue.pocketrdp.feature.session.render.RdpSurface
import com.hanfengruyue.pocketrdp.feature.session.ui.SessionStatusTitle
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    connectionId: Long,
    onClose: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rdpClient = viewModel.rdpClient
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val controller = remember(rdpClient) {
        RdpInputController(
            sendCursor = rdpClient::sendCursorEvent,
            sendKey = rdpClient::sendKeyEvent,
            sendUnicode = rdpClient::sendUnicodeKey,
            sendTouch = rdpClient::sendTouch,
            isUnicodeSupported = rdpClient::isUnicodeInputSupported,
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

    // Local pinch zoom/pan is owned by the controller and exposed as a StateFlow (userTransform).
    // SessionCanvas collects it into a State that is read DEFERRED inside the graphicsLayer{}
    // lambda, so a gesture frame re-records only the GPU layer instead of recomposing the canvas.
    // (The old approach hoisted zoom/pan into Compose state via a virtualPosition sampler that
    // never fired during a pinch — the transform froze until the gesture ended.)

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
    // Restore system bars when SessionScreen leaves composition (back to connection list).
    DisposableEffect(insetsController) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsController?.show(WindowInsetsCompat.Type.ime())
        }
    }

    // --- Background keep-alive permissions (M7) ---
    // The keep-alive foreground service needs (1) POST_NOTIFICATIONS (API 33+) for its ongoing
    // notification to be visible, and (2) — to survive Doze with the screen off — a battery-
    // optimization exemption. Both are requested here, the moment a real session is up, so the
    // ask is contextual ("keep THIS session from dropping") rather than a cold-start permission wall.
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored — the FGS runs regardless; this only governs notification visibility */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
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

    // --- All Files Access for folder redirection (整盘共享) ---
    // When the connection has 文件夹重定向 on but the app lacks MANAGE_EXTERNAL_STORAGE, the remote
    // "PocketRDP" drive (= the whole internal storage) shows empty. Prompt contextually once per
    // session to grant it. The drive is mounted from the /drive flag at connect time, so granting
    // mid-session needs a reconnect — the dialog says so.
    var allFilesPromptShown by remember { mutableStateOf(false) }
    var showAllFilesDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.status, state.filesRedirectEnabled) {
        if (state.status is SessionConnectionStatus.Connected &&
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
            onDismiss = { showAllFilesDialog = false },
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
                            fps = state.fps,
                            controlLatencyMs = state.controlLatencyMs,
                            presentLagMs = state.presentLagMs,
                            networkRttMs = state.latencyMs,
                            latencyAccepted = state.latencyAccepted,
                            latencyDiscarded = state.latencyDiscarded,
                            transport = state.transport,
                            transportStats = state.transportStats,
                            host = state.connectionHost,
                            stickyModifierLabels = stickyModifierLabels(state.stickyModifiers),
                            lastError = state.lastError,
                            onErrorClick = {
                                val err = state.lastError ?: return@SessionStatusTitle
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = err,
                                        duration = SnackbarDuration.Long,
                                        withDismissAction = true,
                                    )
                                }
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::toggleIme) {
                            Icon(
                                imageVector = if (state.imeVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                                contentDescription = "键盘",
                            )
                        }
                        IconButton(onClick = viewModel::toggleMode) {
                            Icon(
                                imageVector = if (state.mode == InputMode.TRACKPAD) Icons.Default.Mouse else Icons.Default.TouchApp,
                                contentDescription = "切换输入模式",
                            )
                        }
                        IconButton(onClick = viewModel::toggleImmersive) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "全屏",
                            )
                        }
                        IconButton(onClick = { viewModel.disconnect(); onClose() }) {
                            Icon(Icons.Default.Close, contentDescription = "断开")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        // Solid black bar, white content (用户需求: 工具栏改黑底白字，不要半透明灰玻璃).
                        containerColor = TOOLBAR_BG,
                        scrolledContainerColor = TOOLBAR_BG,
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
                .consumeWindowInsets(padding),
        ) {
            // The framebuffer fills the ENTIRE content area at all times and is NEVER shrunk by the
            // soft keyboard. Previously the canvas lived in a Column with weight(1f) above the
            // function-key toolbar, whose imePadding() grew with the keyboard and squeezed the canvas
            // — that shrink re-ran SessionCanvas's fit-to-view scale + RdpInputController.clampPan and
            // visually wiped the user's zoom/pan, so after zooming in to read a field you couldn't
            // see what you typed (用户需求: 调出键盘不要重置画面缩放和位置). Keeping the canvas a fixed
            // size means showing the IME never fires onSizeChanged, so zoom/pan are fully preserved;
            // the keyboard (and the toolbar below) simply overlay the lower part of the picture.
            //
            // WHY the IME does NOT resize this canvas (do NOT "fix" this with adjustPan/adjustNothing):
            // MainActivity calls enableEdgeToEdge() → setDecorFitsSystemWindows(false), so the window
            // is drawn edge-to-edge and the keyboard arrives ONLY as WindowInsets.ime — it does not
            // resize the window content (that auto-resize is the LEGACY decorFitsSystemWindows=true
            // behaviour). The Scaffold's contentWindowInsets is systemBars-only (excludes ime), so the
            // content Box is not padded down by the keyboard either. (This is the same reason the old
            // design needed a manual Column+weight to lift content — see CLAUDE.md soft-keyboard
            // section: Scaffold.bottomBar+imePadding would leave the keyboard overlapping.) Net: the
            // window keeps `adjustResize` purely so WindowInsets.ime REPORTS (the toolbar's imePadding
            // below needs it), while the content stays full-size. RdpSurface.onDraw fits to its own
            // (unchanged) measured size, so the picture neither re-fits nor reflows when typing.
            SessionCanvas(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                buffer = rdpClient.buffer,
                remoteWidth = state.remoteWidth,
                remoteHeight = state.remoteHeight,
                mode = state.mode,
                onViewSizeChanged = viewModel::onSurfaceResized,
                targetFrameRate = state.targetFrameRate,
                // Decode→present latency feedback: RdpSurface reports each drawn frame's commit→draw
                // wait so the status panel can show the display-pipeline lag the input→decode metric
                // can't see (METRIC-1). Passive — see RdpSurface.onFramePresented / recordPresentLag.
                onFramePresented = rdpClient::recordPresentLag,
            )

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
                SmallFloatingActionButton(
                    onClick = viewModel::toggleImmersive,
                    containerColor = TOOLBAR_BG,
                    contentColor = TOOLBAR_CONTENT,
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏")
                }
            }

            // Local zoom pill (BOTH input modes) + move handle (native-touch only — see below).
            // Wrapped in a fillMaxSize box with imePadding() so that when the soft keyboard is up they
            // relocate into the still-visible strip ABOVE the keyboard (and the function-key toolbar)
            // instead of being hidden — the user can still zoom the picture WHILE typing, and pan it
            // too: in TOUCH mode via the move handle, in TRACKPAD mode via the keyboard-aware cursor-
            // follow (用户需求: 呼出键盘时也能缩放和移动画面). imePadding() collapses to 0 when no keyboard is shown, so the off-keyboard
            // layout is unchanged; it is a layout-phase inset read (not a composable-body read) so the
            // keyboard animation re-lays-out only this overlay box, never SessionCanvas. The box has no
            // pointerInput, so its empty area doesn't intercept gestures — only the controls do (each
            // consumes its own pointer). Each control collects userTransform itself, so a zoom/pan
            // recomposes only the small overlay, never the framebuffer.
            // Track the overlay box's (imePadding-shrunk) size so the relocatable zoom pill can be
            // clamped to stay reachable inside it even after a keyboard/rotation reflow.
            var overlaySize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier.fillMaxSize().imePadding().onSizeChanged { overlaySize = it },
            ) {
                ZoomControls(
                    controller = controller,
                    containerSize = overlaySize,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(12.dp),
                )

                // Move handle: drag it to pan the magnified picture; long-press it then drag to relocate
                // the handle itself (so it doesn't cover what you want to tap). Shown ONLY in native-touch
                // mode — including while the keyboard is up. In TRACKPAD mode the picture instead auto-
                // follows the virtual cursor (RdpInputController.followCursorWhenZoomed), which is now
                // keyboard-aware: it pans even at 100% zoom while the keyboard has lifted the picture and
                // keeps the cursor in the visible band above the keyboard, so dragging the cursor already
                // pans and the handle would just fight it. Keep it hidden in TRACKPAD, keyboard or not
                // (用户需求: 模拟鼠标模式下呼出键盘仍用光标跟随移动画面，不要切成移动杆). TOUCH mode has no
                // cursor to follow, so the handle stays the way to pan there (PanHandle shows itself only
                // while zoomed or keyboard-lifted).
                if (state.mode == InputMode.TOUCH) {
                    PanHandle(
                        controller = controller,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // Function-key toolbar (a single scrollable row: Esc / Ctrl / Alt / Shift / Win / Tab /
            // arrows / Home·End·PgUp·PgDn / Ins·Del / CAD / F1–F12): shown only while the soft keyboard is up. It
            // FLOATS as a BOTTOM OVERLAY (imePadding lifts it just above the keyboard) instead of being
            // a Column sibling — being a sibling is exactly what shrank the canvas and reset the
            // zoom/pan (see the SessionCanvas comment above). As an overlay it sits on top of the
            // full-screen gesture surface, so SessionToolbar consumes its own touches to stop taps (and
            // the gaps between chips) leaking through to the canvas beneath.
            // Its measured visual height feeds ImeLiftEffect so the keyboard-lift clears the toolbar
            // too. Read via a lambda inside ImeLiftEffect so a height change doesn't recompose
            // SessionCanvas (keeps the per-frame ime read isolated).
            val functionToolbarHeightPx = remember { mutableIntStateOf(0) }
            AnimatedVisibility(
                visible = state.imeVisible && state.chromeVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                SessionToolbar(
                    viewModel = viewModel,
                    state = state,
                    onBarHeightChanged = { functionToolbarHeightPx.intValue = it },
                )
            }

            // Feeds the soft-keyboard (+ function-key toolbar) height into the controller so the
            // framebuffer is lifted just clear of it while typing — see ImeLiftEffect.
            ImeLiftEffect(
                controller = controller,
                chromeVisible = state.chromeVisible,
                toolbarHeightPx = { functionToolbarHeightPx.intValue },
            )

            // Hidden IME bridge — Compose tree always contains it, but it only requests
            // focus when state.imeVisible is true.
            SessionImeBridge(
                visible = state.imeVisible,
                onUnicodeText = viewModel::typeText,
                onVkKey = viewModel::sendVkRaw,
            )
        }
    }
}

@Composable
private fun BatteryOptimizationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许后台保活？") },
        text = {
            Text(
                "为了在你切到其他应用或锁屏时保持这个远程桌面连接不断开，建议把 PocketRDP 加入电池优化白名单。" +
                    "否则系统在省电时可能会切断后台连接。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("去允许") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不") } },
    )
}

@Composable
private fun AllFilesAccessDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许访问所有文件？") },
        text = {
            Text(
                "「文件夹重定向」会把你手机的整个存储挂载到被控电脑（资源管理器里的「PocketRDP」盘）。" +
                    "这需要授予 PocketRDP「所有文件访问」权限。授权后请重新连接以挂载磁盘。",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("去授权") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不") } },
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

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun stickyModifierLabels(mask: Int): List<String> = buildList {
    if (mask and ScancodeMap.Modifier.CTRL != 0) add("Ctrl")
    if (mask and ScancodeMap.Modifier.ALT != 0) add("Alt")
    if (mask and ScancodeMap.Modifier.SHIFT != 0) add("Shift")
    if (mask and ScancodeMap.Modifier.WIN != 0) add("Win")
}

// Session chrome palette (用户需求: 黑色半透明 — 先前的纯不透明黑被否，要半透明；浮动的缩放药丸/移动杆/
// 退出按钮也一并改黑). Translucent BLACK so the picture shows through the floating function-key bar and the
// pills like dark glass, while the white content stays legible. Shared by the TopAppBar, the function-key
// bar, the zoom pill, the move handle and the exit FAB. 0.7 keeps it clearly see-through yet readable.
private const val CHROME_ALPHA = 0.7f
private val TOOLBAR_BG = Color.Black.copy(alpha = CHROME_ALPHA)
private val TOOLBAR_CONTENT = Color.White
// Outline alpha for an inactive (un-pressed) key pill on the translucent black bar.
private const val KEY_BORDER_ALPHA = 0.55f

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
    toolbarHeightPx: () -> Int,
) {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    // Add the function-key toolbar's height only while it actually shows (keyboard up AND chrome
    // visible) — it floats just above the keyboard, so the picture must clear both.
    val toolbarPx = if (imeBottomPx > 0 && chromeVisible) toolbarHeightPx().toFloat() else 0f
    SideEffect { controller.setBottomOcclusion(imeBottomPx + toolbarPx) }
}

/**
 * Applies the local pinch zoom/pan as a graphicsLayer transform. The [transform] State is read
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

/**
 * 给横向滚动条加左右边缘渐隐：哪一侧还有可滑到的内容，该侧内容就淡出成透明，作为"还能继续滑动"的
 * 视觉提示（用户反馈：功能键栏一排显示不全、看不出能左右滑）。用 CompositingStrategy.Offscreen 离屏
 * 图层 + BlendMode.DstIn 蒙版实现：只把"内容层"（滚动的键）在边缘抹成透明；更外层单独绘制的背景黑条
 * 不在这个离屏层里、不受影响，所以呈现"键滑入边缘暗处"的干净效果。纯绘制层、不新增命中测试节点，所以
 * 既不拦截点击/滚动，也不破坏 consumeAllPointerInput 对键间空隙触摸的兜底。必须放在 horizontalScroll
 * 之前，渐隐才固定在视口左右边缘而非随内容一起滚走。
 */
private fun Modifier.scrollEdgeFade(state: ScrollState, fadeWidth: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = fadeWidth.toPx()
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startX = 0f,
                    endX = w,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startX = size.width - w,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun SessionCanvas(
    modifier: Modifier,
    controller: RdpInputController,
    buffer: com.hanfengruyue.pocketrdp.core.rdp.BitmapBuffer,
    remoteWidth: Int,
    remoteHeight: Int,
    mode: InputMode,
    onViewSizeChanged: (width: Int, height: Int) -> Unit,
    targetFrameRate: Int,
    onFramePresented: (presentLagMs: Long) -> Unit,
) {
    val current by buffer.current.collectAsStateWithLifecycle(initialValue = null)
    var surfaceRef by remember { mutableStateOf<RdpSurface?>(null) }
    var viewSize by remember { mutableStateOf(0 to 0) }
    // Keep these as State<...> (NOT `by`): read .value ONLY inside the graphicsLayer / Canvas draw
    // lambdas below so a pinch/pan/cursor move re-records the layer/draw phase without recomposing
    // SessionCanvas (which would re-run the AndroidView update lambda every frame).
    val transformState = controller.userTransform.collectAsStateWithLifecycle()
    val virtualPosState = controller.virtualPosition.collectAsStateWithLifecycle()

    // Same fit-to-view math RdpSurface.onDraw uses. Kept in sync so touch coordinates and
    // the on-screen cursor overlay all share one transform.
    val viewW = viewSize.first.toFloat()
    val viewH = viewSize.second.toFloat()
    val bmW = remoteWidth.toFloat()
    val bmH = remoteHeight.toFloat()
    val scale = if (viewW > 0f && viewH > 0f && bmW > 0f && bmH > 0f) {
        minOf(viewW / bmW, viewH / bmH)
    } else 1f
    val dx = if (bmW > 0f) (viewW - bmW * scale) / 2f else 0f
    val dy = if (bmH > 0f) (viewH - bmH * scale) / 2f else 0f

    LaunchedEffect(scale, dx, dy) {
        controller.setViewport(scale, dx, dy)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size ->
                viewSize = size.width to size.height
                controller.setViewSize(size.width, size.height)
                onViewSizeChanged(size.width, size.height)
            }
            // sessionGestures sits ABOVE the graphicsLayer so pointer events arrive in
            // un-transformed view coordinates — that lets the controller's toRemote math
            // undo the fit-to-view + the user zoom/pan and land on the right remote pixel.
            // It reads the input mode live from the controller (kept in sync with state.mode by
            // the LaunchedEffect above), so it doesn't take a mode arg — see sessionGestures' doc
            // for why a captured `{ mode }` lambda would go stale here.
            .sessionGestures(controller = controller),
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("等待远端帧…", color = Color.White)
            }
        }

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
                    surface.onFramePresented = onFramePresented
                    surfaceRef = surface
                }
            },
            update = { surface ->
                surface.targetFrameRate = targetFrameRate
            },
        )

        // Trackpad-mode cursor overlay. The native FreeRDP cursor pixels aren't currently
        // routed up to the UI (OnPointerSet is unused) so without this the user has no
        // visual reference for where a click will land in trackpad mode.
        if (mode == InputMode.TRACKPAD && current != null && bmW > 0f && bmH > 0f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // Crosshair sits on the same transformed layer as the framebuffer so it
                    // tracks pinch-zoom/pan correctly.
                    .userTransformLayer(transformState),
            ) {
                val vp = virtualPosState.value
                val cx = vp.first * scale + dx
                val cy = vp.second * scale + dy
                val arm = 12f
                val stroke = 2f
                // Outline (black) — visible against light backgrounds.
                drawLine(Color.Black, Offset(cx - arm - 1f, cy), Offset(cx + arm + 1f, cy), strokeWidth = stroke + 2f)
                drawLine(Color.Black, Offset(cx, cy - arm - 1f), Offset(cx, cy + arm + 1f), strokeWidth = stroke + 2f)
                // Inner (white) — visible against dark backgrounds.
                drawLine(Color.White, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = stroke)
                drawLine(Color.White, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = stroke)
            }
        }
    }

    LaunchedEffect(buffer, surfaceRef) {
        val surface = surfaceRef ?: return@LaunchedEffect
        buffer.dirty.collect { rect -> surface.markDirty(rect) }
    }
}

/** F1–F12 keys for the function-key bar (label → Windows VK). */
private val functionKeyVks: List<Pair<String, Int>> = listOf(
    "F1" to ScancodeMap.VK.F1, "F2" to ScancodeMap.VK.F2, "F3" to ScancodeMap.VK.F3,
    "F4" to ScancodeMap.VK.F4, "F5" to ScancodeMap.VK.F5, "F6" to ScancodeMap.VK.F6,
    "F7" to ScancodeMap.VK.F7, "F8" to ScancodeMap.VK.F8, "F9" to ScancodeMap.VK.F9,
    "F10" to ScancodeMap.VK.F10, "F11" to ScancodeMap.VK.F11, "F12" to ScancodeMap.VK.F12,
)

/** Navigation / editing keys for the function-key bar (label → Windows VK). Arrows etc. carry KBDEXT. */
private val navKeyVks: List<Pair<String, Int>> = listOf(
    "←" to ScancodeMap.VK.LEFT, "↑" to ScancodeMap.VK.UP, "↓" to ScancodeMap.VK.DOWN,
    "→" to ScancodeMap.VK.RIGHT, "Home" to ScancodeMap.VK.HOME, "End" to ScancodeMap.VK.END,
    "PgUp" to ScancodeMap.VK.PAGE_UP, "PgDn" to ScancodeMap.VK.PAGE_DOWN,
    "Ins" to ScancodeMap.VK.INSERT, "Del" to ScancodeMap.VK.DELETE,
)

/**
 * Function-key toolbar shown while the soft keyboard is up — a SINGLE horizontally-scrollable row on a
 * translucent BLACK bar with WHITE keys (用户需求: 改成 1 排、黑色半透明). No expand/collapse toggle and no
 * hide-keyboard button (用户需求: 展开/收起按钮不要; 收键盘用上方工具栏的键盘按钮即可) — just the keys, swipe
 * left/right to reach them all (Esc / sticky Ctrl·Alt·Shift / Win / Tab / arrows / Home·End·PgUp·PgDn /
 * Ins·Del / CAD / F1–F12). With no trailing fixed buttons, the row scrolls cleanly edge-to-edge so nothing
 * is crowded/clipped the way the earlier toggle+keyboard buttons were (用户反馈: 右侧功能键被遮挡).
 *
 * The bar fill + measured height live on the INNER Row so [onBarHeightChanged] reports the bar's VISUAL
 * height (NOT including the outer imePadding) — that height drives ImeLiftEffect's keyboard-lift. imePadding
 * (outer Box) lifts the whole bar above the keyboard, and consumeAllPointerInput swallows every touch within
 * the bar so taps (and the gaps between keys) don't leak into the full-screen gesture surface beneath this
 * floating overlay.
 */
@Composable
private fun SessionToolbar(
    viewModel: SessionViewModel,
    state: SessionUiState,
    onBarHeightChanged: (Int) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().imePadding().consumeAllPointerInput(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val keyScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TOOLBAR_BG)
                .onSizeChanged { onBarHeightChanged(it.height) }
                // 边缘渐隐：哪一侧还有可滑到的键，那侧就淡出，提示"可左右滑动看更多键"（用户反馈：功能键
                // 栏一排显示不全、看不出能滑）。纯绘制层、不拦截指针，滚动/点击/穿透兜底都不受影响。必须在
                // horizontalScroll 之前，渐隐才固定在视口左右边缘而非随内容滚走。
                .scrollEdgeFade(keyScroll)
                .horizontalScroll(keyScroll)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarKey("Esc") { viewModel.sendKey(ScancodeMap.VK.ESCAPE) }
            StickyToolbarChip(
                label = "Ctrl",
                active = state.stickyModifiers and ScancodeMap.Modifier.CTRL != 0,
                onToggle = { viewModel.toggleStickyModifier(ScancodeMap.Modifier.CTRL) },
            )
            StickyToolbarChip(
                label = "Alt",
                active = state.stickyModifiers and ScancodeMap.Modifier.ALT != 0,
                onToggle = { viewModel.toggleStickyModifier(ScancodeMap.Modifier.ALT) },
            )
            StickyToolbarChip(
                label = "Shift",
                active = state.stickyModifiers and ScancodeMap.Modifier.SHIFT != 0,
                onToggle = { viewModel.toggleStickyModifier(ScancodeMap.Modifier.SHIFT) },
            )
            ToolbarKey("Win") { viewModel.sendKey(ScancodeMap.VK.LWIN) }
            ToolbarKey("Tab") { viewModel.sendKey(ScancodeMap.VK.TAB) }
            navKeyVks.forEach { (label, vk) -> ToolbarKey(label) { viewModel.sendKey(vk) } }
            ToolbarKey("CAD") { viewModel.sendCtrlAltDel() }
            functionKeyVks.forEach { (label, vk) -> ToolbarKey(label) { viewModel.sendKey(vk) } }
        }
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
 * two-finger scroll/pinch gestures (worst in TOUCH mode, where every gesture is multi-finger and
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
    modifier: Modifier = Modifier,
) {
    val transform by controller.userTransform.collectAsStateWithLifecycle()
    val pct = (transform.zoom * 100).roundToInt()
    val zoomed = pct > 100
    var dragging by remember { mutableStateOf(false) }
    var movingSelf by remember { mutableStateOf(false) }
    // The pill remembers where it was dragged (long-press-then-drag relocates it, like the move handle).
    var handleOffset by remember { mutableStateOf(Offset.Zero) }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    val containerColor = TOOLBAR_BG
    // Re-clamp on container/pill reflow (soft keyboard, rotation) so the pill stays reachable.
    LaunchedEffect(containerSize, pillSize) {
        handleOffset = clampPillOffset(handleOffset, 0f, 0f, containerSize, pillSize)
    }

    Surface(
        modifier = modifier
            .onSizeChanged { pillSize = it }
            .offset { IntOffset(handleOffset.x.roundToInt(), handleOffset.y.roundToInt()) }
            .zoomPillGestures(
                controller = controller,
                containerSize = containerSize,
                pillSize = { pillSize },
                handleOffset = { handleOffset },
                setHandleOffset = { handleOffset = it },
                setDragging = { dragging = it },
                setMovingSelf = { movingSelf = it },
            ),
        shape = CircleShape,
        color = containerColor,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (movingSelf) Icons.Default.DragIndicator else Icons.Default.ZoomIn,
                contentDescription = "缩放：上滑放大、下滑缩小，双击复位；长按后拖动可移动此按钮",
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

/**
 * The zoom pill's gesture vocab, factored out of [ZoomControls] so that composable stays small:
 * drag up/down = continuous zoom, double-tap = reset to 100%, long-press-then-drag = relocate the
 * pill (armed only while the finger holds still within the slop, exactly like PanHandle). Pill state
 * lives in the composable and is read/written through getter/setter lambdas so a gesture recomposes
 * ONLY the small overlay. The relocate offset runs through [clampPillOffset] so the pill can never be
 * dragged off-screen and lost — it is the only zoom control (no pinch gesture). Consumes only its own
 * tracked pointer, so it never leaks into the canvas gesture surface beneath.
 */
private fun Modifier.zoomPillGestures(
    controller: RdpInputController,
    containerSize: IntSize,
    pillSize: () -> IntSize,
    handleOffset: () -> Offset,
    setHandleOffset: (Offset) -> Unit,
    setDragging: (Boolean) -> Unit,
    setMovingSelf: (Boolean) -> Unit,
): Modifier = pointerInput(controller, containerSize) {
    var lastTapMs = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val startZoom = controller.userZoom()
        val startOffset = handleOffset()
        val startMs = System.currentTimeMillis()
        var totalDx = 0f
        var totalDy = 0f
        var isDrag = false    // vertical travel past slop → zoom drag
        var moveSelf = false  // long-press fired while still → relocate the pill itself
        var tracking = true
        while (tracking) {
            // While still and not yet zoom-dragging, arm a long-press → relocate mode
            // (same vocab as PanHandle); moving past the slop first makes it a zoom drag.
            val armLong = !isDrag && !moveSelf &&
                abs(totalDx) + abs(totalDy) <= ZOOM_DRAG_SLOP_PX
            val event = if (armLong) {
                val remaining = (ZOOM_RELOCATE_LONG_PRESS_MS - (System.currentTimeMillis() - startMs))
                    .coerceAtLeast(1L)
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                // Held still long enough → relocate the pill for the rest of the gesture.
                moveSelf = true
                setMovingSelf(true)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change != null && change.pressed) {
                val d = change.positionChange()
                totalDx += d.x
                totalDy += d.y
                if (!moveSelf && !isDrag && abs(totalDy) > ZOOM_DRAG_SLOP_PX) {
                    isDrag = true
                    setDragging(true)
                }
                if (moveSelf) {
                    // Relocate the pill itself (follows the finger 1:1), clamped on-screen.
                    setHandleOffset(clampPillOffset(startOffset, totalDx, totalDy, containerSize, pillSize()))
                } else if (isDrag) {
                    // Drag UP (negative dy) magnifies; multiplicative so a fixed travel
                    // is a constant zoom ratio at any level.
                    val factor = ZOOM_DRAG_BASE.pow(-totalDy / ZOOM_DRAG_TRAVEL_PX)
                    controller.setUserZoom(startZoom * factor)
                }
                change.consume()
            } else {
                // Our pointer lifted (or vanished) → end this gesture.
                change?.consume()
                tracking = false
            }
        }
        setDragging(false)
        setMovingSelf(false)
        if (isDrag || moveSelf) {
            // A zoom drag or relocate breaks the double-tap chain so a stray tap after it
            // can't be read as a double-tap and wipe the zoom just set.
            lastTapMs = 0L
        } else {
            // It was a tap; a second tap inside the window resets the zoom to 100%.
            val now = System.currentTimeMillis()
            if (now - lastTapMs in 1..ZOOM_DOUBLE_TAP_MS) {
                controller.resetZoom()
                lastTapMs = 0L
            } else {
                lastTapMs = now
            }
        }
    }
}

/**
 * Clamp the zoom pill's relocate offset so the pill always stays reachable inside [container]. At
 * offset 0 the pill sits at CenterEnd, so it may move LEFT by up to (container − pill), barely right
 * (clamped to 0), and ± half the vertical slack. No-ops until both sizes are measured.
 */
private fun clampPillOffset(base: Offset, dx: Float, dy: Float, container: IntSize, pill: IntSize): Offset {
    if (minOf(container.width, container.height, pill.width, pill.height) <= 0) {
        return Offset(base.x + dx, base.y + dy)
    }
    val minX = -(container.width - pill.width).coerceAtLeast(0).toFloat()
    val maxY = ((container.height - pill.height).coerceAtLeast(0) / 2).toFloat()
    return Offset((base.x + dx).coerceIn(minX, 0f), (base.y + dy).coerceIn(-maxY, maxY))
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
private fun PanHandle(controller: RdpInputController, modifier: Modifier = Modifier) {
    val transform by controller.userTransform.collectAsStateWithLifecycle()
    // remember is called unconditionally (before the early return) so the slot table stays stable;
    // the handle keeps its dragged-to position across hide/show.
    var handleOffset by remember { mutableStateOf(Offset.Zero) }
    var movingSelf by remember { mutableStateOf(false) }
    val zoomed = (transform.zoom * 100).roundToInt() > 100
    // offsetY < 0 means the keyboard auto-lift pushed the picture up — show the handle so the top
    // (now off-screen) can be pulled back into view, even when not magnified.
    val keyboardLifted = transform.offsetY < 0f
    if (!zoomed && !keyboardLifted) return

    val containerColor = TOOLBAR_BG
    Surface(
        modifier = modifier
            .offset { IntOffset(handleOffset.x.roundToInt(), handleOffset.y.roundToInt()) }
            .size(PAN_HANDLE_SIZE_DP.dp)
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startPanX = controller.userPanX()
                    val startPanY = controller.userPanY()
                    val gain = if (controller.userZoom() > 1f) controller.userZoom() else PAN_HANDLE_KEYBOARD_GAIN
                    val startOffset = handleOffset
                    val startMs = System.currentTimeMillis()
                    var totalDx = 0f
                    var totalDy = 0f
                    var moveSelf = false
                    var tracking = true
                    while (tracking) {
                        // While still stationary, arm a long-press that switches to "move the handle".
                        val armLong = !moveSelf && abs(totalDx) + abs(totalDy) <= PAN_HANDLE_SLOP_PX
                        val event = if (armLong) {
                            val remaining = (PAN_HANDLE_LONG_PRESS_MS - (System.currentTimeMillis() - startMs))
                                .coerceAtLeast(1L)
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            moveSelf = true
                            movingSelf = true
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            change?.consume()
                            tracking = false
                        } else {
                            val d = change.positionChange()
                            totalDx += d.x
                            totalDy += d.y
                            if (moveSelf) {
                                // Relocate the handle itself (follows the finger 1:1).
                                handleOffset = Offset(startOffset.x + totalDx, startOffset.y + totalDy)
                            } else {
                                // Pan the picture the same direction the handle is dragged.
                                controller.setUserPan(startPanX + totalDx * gain, startPanY + totalDy * gain)
                            }
                            change.consume()
                        }
                    }
                    movingSelf = false
                }
            },
        shape = CircleShape,
        color = containerColor,
        tonalElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (movingSelf) Icons.Default.DragIndicator else Icons.Default.OpenWith,
                contentDescription = "拖动以移动放大后的画面；长按后拖动可移动此按钮",
                tint = TOOLBAR_CONTENT,
            )
        }
    }
}

/**
 * A momentary key on the black function-key bar: white label inside a thin white-outline pill,
 * transparent fill (用户需求: 黑底白字). Built from a clickable [Surface] (not AssistChip) so we fully
 * control the white-on-black palette.
 */
@Composable
private fun ToolbarKey(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = TOOLBAR_CONTENT,
        border = BorderStroke(1.dp, TOOLBAR_CONTENT.copy(alpha = KEY_BORDER_ALPHA)),
    ) {
        Box(
            modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A latching modifier key (Ctrl/Alt/Shift) on the black bar. Inactive = white-outline pill with white
 * text; active = SOLID white fill with black text, so the latched state reads clearly (用户需求: 黑底白字).
 */
@Composable
private fun StickyToolbarChip(label: String, active: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(8.dp),
        color = if (active) TOOLBAR_CONTENT else Color.Transparent,
        contentColor = if (active) TOOLBAR_BG else TOOLBAR_CONTENT,
        border = if (active) null else BorderStroke(1.dp, TOOLBAR_CONTENT.copy(alpha = KEY_BORDER_ALPHA)),
    ) {
        Box(
            modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
