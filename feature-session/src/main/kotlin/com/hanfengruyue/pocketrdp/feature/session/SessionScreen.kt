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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
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

    Scaffold(
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
                            controlLatencyMs = state.controlLatencyMs,
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
                    // With the status bar hidden its inset collapses to 0, so the bar sits flush at
                    // the very top (reclaiming the space — issue #1). union(displayCutout) still keeps
                    // the title/icons clear of the camera notch: top inset in portrait, side inset in
                    // landscape (shortEdges). statusBars stays in the union so a transient swipe-reveal
                    // doesn't briefly overlap the bar.
                    windowInsets = WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
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
                    visible = state.imeVisible && state.chromeVisible,
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
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(12.dp),
                )

                // Move handle (only while zoomed in): drag it to pan the magnified picture; long-press
                // it then drag to relocate the handle itself (so it doesn't cover what you want to tap).
                // Shown ONLY in native-touch mode. In TRACKPAD mode the view auto-follows the virtual
                // cursor when zoomed (RdpInputController.followCursorWhenZoomed), so dragging the cursor
                // already pans the picture and the handle is redundant — per user request 模拟鼠标模式
                // 隐藏控制杆、用手势拖动画面. Native touch has no follow, so it keeps the handle.
                if (state.mode == InputMode.TOUCH) {
                    PanHandle(
                        controller = controller,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
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
            // undo the fit-to-view + the user zoom/pan and land on the right remote pixel.
            .sessionGestures(
                controller = controller,
                modeProvider = { mode },
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

// Zoom-handle drag tuning (issue #2).
private const val ZOOM_DRAG_SLOP_PX = 8f      // px of finger travel before a press becomes a drag
private const val ZOOM_DRAG_BASE = 2f         // zoom multiplies by this over one TRAVEL_PX of drag
private const val ZOOM_DRAG_TRAVEL_PX = 180f  // finger px that doubles / halves the zoom
private const val ZOOM_DOUBLE_TAP_MS = 280L   // double-tap window for reset-to-100%

/**
 * Floating zoom control — a SINGLE compact pill (issue #2): drag it UP to zoom in, DOWN to zoom
 * out (continuous), double-tap to reset to 100%. Replaces the old ＋ / － / reset button stack that
 * ran a tall strip down the right edge: being a large touch dead-zone it kept swallowing the
 * two-finger scroll/pinch gestures (worst in TOUCH mode, where every gesture is multi-finger and
 * one finger often landed on a button). The pill is a much smaller target, so the canvas gets the
 * whole right edge back.
 *
 * Collects userTransform internally so a zoom change recomposes ONLY this small overlay — the
 * framebuffer AndroidView reads the transform deferred inside its graphicsLayer and never
 * recomposes. The pointerInput consumes its own pointer, so dragging the pill never leaks into the
 * canvas gesture surface behind it.
 */
@Composable
private fun ZoomControls(controller: RdpInputController, modifier: Modifier = Modifier) {
    val transform by controller.userTransform.collectAsStateWithLifecycle()
    val pct = (transform.zoom * 100).roundToInt()
    val zoomed = pct > 100
    var dragging by remember { mutableStateOf(false) }
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)

    Surface(
        modifier = modifier.pointerInput(controller) {
            var lastTapMs = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val startZoom = controller.userZoom()
                var totalDy = 0f
                var isDrag = false
                var tracking = true
                while (tracking) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        // Our pointer lifted (or vanished) → end this gesture.
                        change?.consume()
                        tracking = false
                    } else {
                        totalDy += change.positionChange().y
                        if (!isDrag && abs(totalDy) > ZOOM_DRAG_SLOP_PX) {
                            isDrag = true
                            dragging = true
                        }
                        if (isDrag) {
                            // Drag UP (negative dy) magnifies. Multiplicative mapping → a fixed
                            // finger travel changes the zoom by a constant ratio at any level.
                            val factor = ZOOM_DRAG_BASE.pow(-totalDy / ZOOM_DRAG_TRAVEL_PX)
                            controller.setUserZoom(startZoom * factor)
                        }
                        change.consume()
                    }
                }
                dragging = false
                if (isDrag) {
                    // A drag breaks the double-tap chain so a stray tap right after a quick
                    // drag-to-zoom can't be read as a double-tap and wipe the zoom just set.
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
        },
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
                imageVector = Icons.Default.ZoomIn,
                contentDescription = "缩放：上滑放大、下滑缩小，双击复位",
                tint = MaterialTheme.colorScheme.onSurface,
            )
            // Live % only while dragging or zoomed in, so at 100% the handle stays minimal.
            if (dragging || zoomed) {
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// Move-handle tuning (issue: a dedicated button to pan the magnified picture, plus long-press to
// relocate the button so it can't sit over what you want to tap).
private const val PAN_HANDLE_SIZE_DP = 60
private const val PAN_HANDLE_SLOP_PX = 6f          // travel before a press counts as a drag
private const val PAN_HANDLE_LONG_PRESS_MS = 350L  // hold this long → drag the handle itself

/**
 * Floating MOVE handle, shown ONLY while the picture is zoomed in (issue: pan the magnified picture
 * without any gesture, so it can't fight the touch-control gestures). Drag it to pan the framebuffer
 * the same direction (gain = current zoom, so a small handle drag still reaches the edges at 4×).
 * Long-press it, then drag, to relocate the handle out of the way of whatever you need to tap.
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
    if (!zoomed) return

    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
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
                    val gain = controller.userZoom()
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
                tint = MaterialTheme.colorScheme.onSurface,
            )
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
