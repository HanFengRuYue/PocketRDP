package com.hanfengruyue.pocketrdp.feature.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.feature.session.input.RdpInputController
import com.hanfengruyue.pocketrdp.feature.session.input.ScancodeMap
import com.hanfengruyue.pocketrdp.feature.session.input.SessionImeBridge
import com.hanfengruyue.pocketrdp.feature.session.input.UserTransform
import com.hanfengruyue.pocketrdp.feature.session.input.sessionGestures
import com.hanfengruyue.pocketrdp.feature.session.render.RdpSurface
import com.hanfengruyue.pocketrdp.feature.session.ui.SessionStatusTitle
import kotlinx.coroutines.launch

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
    LaunchedEffect(state.systemBarsVisible, insetsController) {
        val ic = insetsController ?: return@LaunchedEffect
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.systemBarsVisible) ic.show(WindowInsetsCompat.Type.systemBars())
        else ic.hide(WindowInsetsCompat.Type.systemBars())
    }
    // Restore system bars when SessionScreen leaves composition (back to connection list).
    DisposableEffect(insetsController) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            insetsController?.show(WindowInsetsCompat.Type.ime())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = state.systemBarsVisible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                TopAppBar(
                    title = {
                        // Title slot doubles as the status-info opener. SessionStatusTitle renders
                        // [● connectionName ⏷] and shows the dropdown with full session metrics
                        // (resolution, duration, FPS, mode, sticky mods, last error). Keeping the
                        // chip wall out of TopAppBar.actions prevents the title from being pushed
                        // off-screen in portrait orientation.
                        SessionStatusTitle(
                            status = state.status,
                            connectionName = state.connectionName,
                            connectionId = connectionId,
                            remoteWidth = state.remoteWidth,
                            remoteHeight = state.remoteHeight,
                            durationSec = state.durationSec,
                            fps = state.fps,
                            latencyMs = state.latencyMs,
                            transport = state.transport,
                            mode = state.mode,
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
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    ),
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
            Column(modifier = Modifier.fillMaxSize()) {
                SessionCanvas(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    controller = controller,
                    buffer = rdpClient.buffer,
                    remoteWidth = state.remoteWidth,
                    remoteHeight = state.remoteHeight,
                    mode = state.mode,
                    onViewSizeChanged = viewModel::onSurfaceResized,
                    targetFrameRate = state.targetFrameRate,
                    onFrameRendered = viewModel::onFrameRendered,
                )

                // Function-key toolbar (issues 1 + 8): shown only while the soft keyboard is up,
                // sitting BELOW the framebuffer so the canvas's weight(1f) shrinks above it, and
                // ABOVE the keyboard via SessionToolbar's imePadding(). Because it's a sibling row
                // in the Column (not an overlay), it never leaks taps into the gesture surface.
                AnimatedVisibility(
                    visible = state.imeVisible && state.systemBarsVisible,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    SessionToolbar(viewModel = viewModel, state = state)
                }
            }

            // Immersive exit affordance: a translucent FAB sitting above the framebuffer in
            // the top-right corner. Only visible when system bars are hidden — otherwise
            // the TopAppBar already has the full-screen toggle.
            AnimatedVisibility(
                visible = !state.systemBarsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = viewModel::toggleImmersive,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏")
                }
            }

            // Local zoom controls (issue #3) — shown in BOTH input modes (per user choice). Their
            // own composable collects userTransform so only this small overlay recomposes on zoom,
            // never the framebuffer. Hidden while the soft keyboard is up to avoid clutter.
            if (!state.imeVisible) {
                ZoomControls(
                    controller = controller,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(12.dp),
                )
            }

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
        translationY = t.panY
        transformOrigin = TransformOrigin.Center
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
    onFrameRendered: () -> Unit,
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
            // stay simple (only undo fit-to-view, not the user's pinch).
            .sessionGestures(
                controller = controller,
                modeProvider = { mode },
                onPinchReset = { /* no-op: resetUserTransform() already publishes to userTransform */ },
            ),
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
                    surface.onFrameRendered = onFrameRendered
                    // Read frames straight from the double buffer's front (complete) buffer; the
                    // render loop pulls peekFront() each tick (no per-frame setBacking/recompose).
                    surface.buffer = buffer
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

@Composable
private fun SessionToolbar(viewModel: SessionViewModel, state: SessionUiState) {
    BottomAppBar(
        // imePadding lifts the toolbar to sit directly above the soft keyboard instead of being
        // covered by it — the Scaffold's content padding then also shifts the framebuffer up.
        modifier = Modifier.fillMaxWidth().imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
            ToolbarKey("CAD") { viewModel.sendCtrlAltDel() }
            IconButton(onClick = viewModel::toggleIme) {
                Icon(Icons.Default.KeyboardHide, contentDescription = "收起键盘")
            }
        }
    }
}

/**
 * Floating zoom controls (＋ / －, and a reset that appears only while zoomed). Collects
 * userTransform internally so a zoom change recomposes ONLY this small overlay — the framebuffer
 * AndroidView reads the transform deferred inside its graphicsLayer and never recomposes.
 */
@Composable
private fun ZoomControls(controller: RdpInputController, modifier: Modifier = Modifier) {
    val transform by controller.userTransform.collectAsStateWithLifecycle()
    val zoomed = transform.zoom > 1.01f
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallFloatingActionButton(onClick = controller::zoomIn, containerColor = containerColor) {
            Icon(Icons.Default.Add, contentDescription = "放大")
        }
        Text(
            text = "${(transform.zoom * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SmallFloatingActionButton(onClick = controller::zoomOut, containerColor = containerColor) {
            Icon(Icons.Default.Remove, contentDescription = "缩小")
        }
        if (zoomed) {
            SmallFloatingActionButton(onClick = controller::resetZoom, containerColor = containerColor) {
                Icon(Icons.Default.Refresh, contentDescription = "复位缩放")
            }
        }
    }
}

@Composable
private fun ToolbarKey(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

@Composable
private fun StickyToolbarChip(label: String, active: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onToggle,
        label = { Text(label) },
    )
}
