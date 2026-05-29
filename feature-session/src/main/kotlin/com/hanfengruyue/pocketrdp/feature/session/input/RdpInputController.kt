package com.hanfengruyue.pocketrdp.feature.session.input

import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpPointerFlags
import com.hanfengruyue.pocketrdp.core.rdp.RdpTouchAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Maps Compose pointer events into RDP wire-protocol calls.
 *
 * Two input modes (see [InputMode]):
 *  - [InputMode.TRACKPAD] — phone-as-trackpad. Drags move a virtual cursor and emit mouse PDUs
 *    (move / button1-3 / wheel). The on-screen crosshair shows where a click will land.
 *  - [InputMode.TOUCH]   — native Windows multi-touch. Each finger is forwarded verbatim as an
 *    RDPEI touch contact via [sendTouch] (DOWN/MOVE/UP); no mouse emulation at all, so Windows
 *    drives tap/scroll/pinch/rotate exactly like a physical touchscreen.
 *
 * Coordinate spaces:
 *  - `local` — the gesture surface's pixel coordinates. Because `Modifier.sessionGestures` sits
 *    ABOVE the local zoom/pan `graphicsLayer` in the modifier chain, Compose delivers these in
 *    *post-layout, pre-transform* view pixels — i.e. the actual finger position on screen.
 *  - `remote` — RDP framebuffer pixels (0..remoteWidth-1, 0..remoteHeight-1).
 *
 * [toRemote] therefore has to undo BOTH the centred fit-to-view scale that [RdpSurface] applies
 * AND the user's pinch/zoom-button transform (graphicsLayer, origin = view centre) so a touch on a
 * visually-zoomed pixel lands on the correct remote pixel. (This used to be a no-op for the user
 * zoom — fine while touch-mode zoom was unused, but wrong now that the zoom button magnifies the
 * touch surface in both modes.)
 *
 * Local zoom is owned here as a [userTransform] StateFlow; SessionScreen reads it DEFERRED inside a
 * graphicsLayer lambda so a gesture frame re-records only the GPU layer (no recomposition). EVERY
 * mutator MUST call [publishTransform] or the on-screen transform freezes mid-gesture.
 */
class RdpInputController(
    private val sendCursor: (x: Int, y: Int, flags: Int) -> Unit,
    private val sendKey: (vk: Int, down: Boolean) -> Unit,
    private val sendUnicode: (codePoint: Int, down: Boolean) -> Unit,
    private val sendTouch: (contactId: Int, x: Int, y: Int, action: Int) -> Unit = { _, _, _, _ -> },
    private val isUnicodeSupported: () -> Boolean = { true },
) {
    private val _mode = MutableStateFlow(InputMode.TRACKPAD)
    val mode: StateFlow<InputMode> = _mode.asStateFlow()

    private val _virtualPosition = MutableStateFlow(0f to 0f)
    /** Current trackpad cursor in REMOTE coordinates. Used by the overlay to draw a pointer. */
    val virtualPosition: StateFlow<Pair<Float, Float>> = _virtualPosition.asStateFlow()

    private val _userTransform = MutableStateFlow(UserTransform())
    /** Local pinch/zoom-button transform, published on EVERY change so the graphicsLayer tracks it. */
    val userTransform: StateFlow<UserTransform> = _userTransform.asStateFlow()

    private var virtualX: Float = 0f
    private var virtualY: Float = 0f
    private var remoteWidth: Int = 1
    private var remoteHeight: Int = 1

    // RdpSurface viewport (centred fit-to-view) — set by SessionScreen on layout change.
    private var viewportScale: Float = 1f
    private var viewportDx: Float = 0f
    private var viewportDy: Float = 0f

    // User-applied local zoom/pan. 1.0 = no zoom; pan in view pixels (graphicsLayer translation).
    private var userZoom: Float = 1f
    private var userPanX: Float = 0f
    private var userPanY: Float = 0f

    // View size in px (set on layout). Lets clampPan() keep the scaled framebuffer covering the view.
    private var viewW: Float = 0f
    private var viewH: Float = 0f

    // Trackpad double-tap-then-drag / long-press-drag: holds BUTTON1 down across moves.
    private var dragHeld: Boolean = false

    // Two-finger scroll accumulator (px). Sub-notch finger motion accumulates here so a slow drag
    // still scrolls — the old `( -dy/32 ).toInt()` truncated every small per-frame delta to 0 and
    // also emitted a ±1 (1/120-of-a-notch) rotation, so the wheel "用不了". (issue #7)
    private var scrollAccumPx: Float = 0f

    fun setRemoteSize(width: Int, height: Int) {
        remoteWidth = width.coerceAtLeast(1)
        remoteHeight = height.coerceAtLeast(1)
        virtualX = virtualX.coerceIn(0f, remoteWidth - 1f)
        virtualY = virtualY.coerceIn(0f, remoteHeight - 1f)
        publishVirtual()
    }

    /** Update the fit-to-view transform. dx/dy are letterbox offsets in view pixels. */
    fun setViewport(scale: Float, dx: Float, dy: Float) {
        viewportScale = if (scale > 0f) scale else 1f
        viewportDx = dx
        viewportDy = dy
    }

    /** Local pinch/zoom factor; clamped to [1, MAX_ZOOM]. Publishes so graphicsLayer tracks it live. */
    fun setUserZoom(z: Float) {
        userZoom = z.coerceIn(1f, MAX_ZOOM)
        clampPan()
        publishTransform()
    }

    fun setUserPan(px: Float, py: Float) {
        userPanX = px
        userPanY = py
        clampPan()
        publishTransform()
    }

    fun userZoom(): Float = userZoom
    fun userPanX(): Float = userPanX
    fun userPanY(): Float = userPanY

    /** Zoom-button helpers (issue #3): step the local magnification, keeping the view centred. */
    fun zoomIn() = setUserZoom(userZoom * ZOOM_STEP)
    fun zoomOut() = setUserZoom(userZoom / ZOOM_STEP)
    fun resetZoom() = resetUserTransform()
    fun isZoomed(): Boolean = userZoom > 1.01f

    /** Record the view size so [clampPan] / [followRemotePoint] can keep content on-screen. */
    fun setViewSize(width: Int, height: Int) {
        viewW = width.toFloat()
        viewH = height.toFloat()
        clampPan()
        publishTransform()
    }

    fun resetUserTransform() {
        userZoom = 1f
        userPanX = 0f
        userPanY = 0f
        publishTransform()
    }

    /**
     * When locally zoomed, pan the view so the given REMOTE point stays inside a central comfort
     * zone — this is the "画面与鼠标一起移动" behaviour (issue #3): as the cursor/finger nears an
     * edge the framebuffer scrolls to keep it visible, instead of the cursor disappearing off the
     * magnified viewport. No-op at 1× zoom.
     */
    fun followRemotePoint(rx: Float, ry: Float) {
        if (userZoom <= 1.01f || viewW <= 0f || viewH <= 0f) return
        val cx = viewW / 2f
        val cy = viewH / 2f
        // Base (pre-userZoom) screen position of the point, then apply the current layer transform.
        val baseX = rx * viewportScale + viewportDx
        val baseY = ry * viewportScale + viewportDy
        val screenX = cx + userZoom * (baseX - cx) + userPanX
        val screenY = cy + userZoom * (baseY - cy) + userPanY
        val marginX = viewW * FOLLOW_MARGIN_FRAC
        val marginY = viewH * FOLLOW_MARGIN_FRAC
        var newPanX = userPanX
        var newPanY = userPanY
        if (screenX < marginX) newPanX += (marginX - screenX)
        else if (screenX > viewW - marginX) newPanX -= (screenX - (viewW - marginX))
        if (screenY < marginY) newPanY += (marginY - screenY)
        else if (screenY > viewH - marginY) newPanY -= (screenY - (viewH - marginY))
        if (newPanX != userPanX || newPanY != userPanY) setUserPan(newPanX, newPanY)
    }

    fun resetScrollAccum() { scrollAccumPx = 0f }

    /**
     * Clamp pan so the framebuffer (scaled about the view centre by [userZoom]) always covers the
     * view. graphicsLayer uses TransformOrigin.Center, so the max translation per axis is half the
     * overflow: (zoom - 1) * size / 2.
     */
    private fun clampPan() {
        val maxX = ((userZoom - 1f) * viewW / 2f).coerceAtLeast(0f)
        val maxY = ((userZoom - 1f) * viewH / 2f).coerceAtLeast(0f)
        userPanX = userPanX.coerceIn(-maxX, maxX)
        userPanY = userPanY.coerceIn(-maxY, maxY)
    }

    private fun publishTransform() {
        _userTransform.value = UserTransform(userZoom, userPanX, userPanY)
    }

    fun toggleMode() {
        // Mode switch with dragHeld active would leave the remote button stuck — release first.
        if (dragHeld) endDragHold()
        _mode.value = if (_mode.value == InputMode.TRACKPAD) InputMode.TOUCH else InputMode.TRACKPAD
    }

    fun setMode(mode: InputMode) {
        if (dragHeld && mode != _mode.value) endDragHold()
        _mode.value = mode
    }

    // ============================================================
    // TRACKPAD (mouse-emulation) gestures
    // ============================================================

    fun tap(localX: Float, localY: Float, viewW: Int, viewH: Int) {
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON1)
    }

    fun longPress(localX: Float, localY: Float, viewW: Int, viewH: Int) {
        rightClick(localX, localY)
    }

    /** Right button click at the current virtual cursor. */
    fun rightClick(localX: Float, localY: Float) {
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON2)
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON2)
    }

    /** Middle button click at the current virtual cursor. */
    fun middleClick(localX: Float, localY: Float) {
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON3)
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON3)
    }

    /**
     * Move the virtual cursor by a screen-space delta (trackpad). Divides by viewportScale*userZoom
     * so sensitivity matches what the user sees. When zoomed, the view follows the cursor so it
     * never slides off the magnified viewport (issue #3 — previously a zoomed single-finger drag
     * was repurposed to PAN the image, so "放大了画面鼠标就不能动了").
     */
    fun drag(dx: Float, dy: Float, localX: Float, localY: Float, viewW: Int, viewH: Int) {
        val s = (viewportScale * userZoom).let { if (it > 0f) it else 1f }
        val accel = if (abs(dx) + abs(dy) > 25f) 1.6f else 1.0f
        virtualX = (virtualX + dx * accel / s).coerceIn(0f, remoteWidth - 1f)
        virtualY = (virtualY + dy * accel / s).coerceIn(0f, remoteHeight - 1f)
        publishVirtual()
        followRemotePoint(virtualX, virtualY)
        val flags = if (dragHeld) {
            RdpPointerFlags.MOVE or RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1
        } else {
            RdpPointerFlags.MOVE
        }
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), flags)
    }

    /** Two-finger vertical pan → wheel scroll. deltaY in view pixels (positive = fingers move down). */
    fun scroll(deltaY: Float) {
        scrollAccumPx += deltaY
        while (abs(scrollAccumPx) >= SCROLL_NOTCH_PX) {
            val down = scrollAccumPx > 0f
            emitWheel(down)
            scrollAccumPx -= if (down) SCROLL_NOTCH_PX else -SCROLL_NOTCH_PX
        }
    }

    private fun emitWheel(down: Boolean) {
        // RDP wheel: one notch = 120. Negative (scroll down) sets PTR_FLAGS_WHEEL_NEGATIVE and puts
        // the magnitude's low byte (0x100 - 120 = 0x88) in the low 8 bits.
        val flags = if (down) {
            RdpPointerFlags.WHEEL or RdpPointerFlags.WHEEL_NEGATIVE or ((0x100 - WHEEL_NOTCH) and 0xFF)
        } else {
            RdpPointerFlags.WHEEL or WHEEL_NOTCH
        }
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), flags)
    }

    /**
     * Begin a held-button drag (double-tap-then-drag OR long-press-then-drag). Subsequent [drag]
     * calls keep BUTTON1 pressed. Caller MUST call [endDragHold] when the finger lifts (use
     * try/finally) — otherwise the remote button stays down forever.
     */
    fun beginDragHold() {
        if (dragHeld) return
        dragHeld = true
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
    }

    fun endDragHold() {
        if (!dragHeld) return
        dragHeld = false
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON1)
    }

    fun isDragHeld(): Boolean = dragHeld

    // ============================================================
    // TOUCH (native Windows multi-touch via RDPEI) gestures
    // ============================================================

    /** Finger down → RDPEI TouchBegin at the mapped remote pixel. [fingerId] tracks it across moves. */
    fun touchDown(fingerId: Int, localX: Float, localY: Float) {
        val (rx, ry) = toRemote(localX, localY)
        // Only the primary finger drives the zoom-follow pan; otherwise a 2-finger Windows pinch
        // would oscillate the local view between both contacts.
        if (fingerId == 0) followRemotePoint(rx.toFloat(), ry.toFloat())
        sendTouch(fingerId, rx, ry, RdpTouchAction.DOWN)
    }

    /** Finger move → RDPEI TouchUpdate. */
    fun touchMove(fingerId: Int, localX: Float, localY: Float) {
        val (rx, ry) = toRemote(localX, localY)
        if (fingerId == 0) followRemotePoint(rx.toFloat(), ry.toFloat())
        sendTouch(fingerId, rx, ry, RdpTouchAction.MOVE)
    }

    /** Finger up → RDPEI TouchEnd. */
    fun touchUp(fingerId: Int, localX: Float, localY: Float) {
        val (rx, ry) = toRemote(localX, localY)
        sendTouch(fingerId, rx, ry, RdpTouchAction.UP)
    }

    // ============================================================
    // Keyboard
    // ============================================================

    fun typeText(text: String) {
        TextInputEncoder.type(
            text = text,
            unicodeSupported = isUnicodeSupported(),
            sendKey = sendKey,
            sendUnicode = sendUnicode,
        )
    }

    fun pressKey(vk: Int) {
        sendKey(vk, true)
        sendKey(vk, false)
    }

    fun ctrlAltDel() {
        val ctrl = ScancodeMap.VK.LCONTROL
        val alt = ScancodeMap.VK.LMENU
        val del = ScancodeMap.VK.DELETE
        sendKey(ctrl, true)
        sendKey(alt, true)
        sendKey(del, true)
        sendKey(del, false)
        sendKey(alt, false)
        sendKey(ctrl, false)
    }

    /**
     * Convert a touch in view pixels into remote-framebuffer pixels, undoing BOTH the centred
     * fit-to-view scale ([RdpSurface]) AND the local pinch/zoom-button transform (graphicsLayer,
     * origin = view centre). The gesture modifier sits above graphicsLayer, so [localX]/[localY]
     * arrive as the actual on-screen finger position; we invert the layer transform to find which
     * displayed (and possibly magnified) framebuffer pixel is under the finger.
     */
    private fun toRemote(localX: Float, localY: Float): Pair<Int, Int> {
        // Step 1: undo the user zoom/pan (screen → base/pre-zoom view pixels). graphicsLayer maps a
        // base point B to screen S = C + z*(B - C) + pan, so B = C + (S - C - pan)/z, C = view centre.
        var bx = localX
        var by = localY
        if (userZoom > 0f && viewW > 0f && viewH > 0f) {
            val cx = viewW / 2f
            val cy = viewH / 2f
            bx = cx + (localX - cx - userPanX) / userZoom
            by = cy + (localY - cy - userPanY) / userZoom
        }
        // Step 2: undo the fit-to-view letterbox + scale (base view pixels → remote pixels).
        val s = if (viewportScale > 0f) viewportScale else 1f
        val rx = ((bx - viewportDx) / s).toInt().coerceIn(0, remoteWidth - 1)
        val ry = ((by - viewportDy) / s).toInt().coerceIn(0, remoteHeight - 1)
        return rx to ry
    }

    private fun publishVirtual() {
        _virtualPosition.value = virtualX to virtualY
    }

    companion object {
        private const val MAX_ZOOM = 4f
        private const val ZOOM_STEP = 1.5f
        // Keep the followed point inside the central (1 - 2*frac) of the viewport before scrolling.
        private const val FOLLOW_MARGIN_FRAC = 0.25f
        // View pixels of two-finger drag per wheel notch.
        private const val SCROLL_NOTCH_PX = 28f
        // RDP wheel rotation magnitude for one notch.
        private const val WHEEL_NOTCH = 0x78 // 120
    }
}

/** Local view transform applied by SessionScreen's graphicsLayer (pinch zoom + pan, in view px). */
data class UserTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)
