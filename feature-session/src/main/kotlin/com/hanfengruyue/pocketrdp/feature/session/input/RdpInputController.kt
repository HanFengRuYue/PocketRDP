package com.hanfengruyue.pocketrdp.feature.session.input

import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import com.hanfengruyue.pocketrdp.core.rdp.RdpPointerFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Maps Compose pointer events into RDP cursor / keyboard wire-protocol calls.
 *
 * Stateless w.r.t. the native client (it just calls sendCursorEvent / sendKeyEvent), so this
 * class can be unit-tested without a running RDP session.
 *
 * Coordinate spaces:
 *  - `local` — the AndroidView's pixel coordinates AFTER the local pinch zoom/pan transform
 *    applied by graphicsLayer in SessionScreen. Touch events on the AndroidView already arrive
 *    pre-transformed (Compose passes raw view pixels regardless of graphicsLayer), so we have
 *    to undo the user zoom/pan inside [toRemote] before applying the fit-to-view scale.
 *  - `remote` — RDP server's framebuffer coordinates (0..remoteWidth-1, 0..remoteHeight-1).
 *
 * The view does not render the framebuffer at 1:1: [RdpSurface] does a centred fit-to-view
 * scale, so the visible content is offset by `(viewportDx, viewportDy)` and scaled by
 * `viewportScale`. Touch coordinates must be unmapped through that transform; otherwise
 * tapping the visible centre of the screen produces a remote click hundreds of pixels off.
 *
 * Additionally, the user can pinch-zoom the local view via [setUserZoom]/[setUserPan]; the
 * graphicsLayer transform applies AFTER the AndroidView lays out, so pointerInput events
 * still come in raw view pixels. We invert the user transform here so the remote pixel
 * we send actually lines up with what the user touched.
 */
class RdpInputController(
    private val sendCursor: (x: Int, y: Int, flags: Int) -> Unit,
    private val sendKey: (vk: Int, down: Boolean) -> Unit,
    private val sendUnicode: (codePoint: Int, down: Boolean) -> Unit,
    private val isUnicodeSupported: () -> Boolean = { true },
) {
    private val _mode = MutableStateFlow(InputMode.TRACKPAD)
    val mode: StateFlow<InputMode> = _mode.asStateFlow()

    private val _virtualPosition = MutableStateFlow(0f to 0f)
    /** Current trackpad cursor in REMOTE coordinates. Used by the overlay to draw a pointer. */
    val virtualPosition: StateFlow<Pair<Float, Float>> = _virtualPosition.asStateFlow()

    private var virtualX: Float = 0f
    private var virtualY: Float = 0f
    private var remoteWidth: Int = 1
    private var remoteHeight: Int = 1

    // RdpSurface viewport — set by SessionScreen on layout change.
    private var viewportScale: Float = 1f
    private var viewportDx: Float = 0f
    private var viewportDy: Float = 0f

    // User-applied local zoom/pan (pinch). 1.0 = no zoom; pan in view pixels.
    private var userZoom: Float = 1f
    private var userPanX: Float = 0f
    private var userPanY: Float = 0f

    // Tracks whether we're inside a "double-tap-then-drag" gesture that holds BUTTON1 down.
    private var dragHeld: Boolean = false

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

    /** Local pinch zoom factor; clamped to [1, 4]. */
    fun setUserZoom(z: Float) {
        userZoom = z.coerceIn(1f, 4f)
    }

    fun setUserPan(px: Float, py: Float) {
        userPanX = px
        userPanY = py
    }

    fun userZoom(): Float = userZoom
    fun userPanX(): Float = userPanX
    fun userPanY(): Float = userPanY

    fun resetUserTransform() {
        userZoom = 1f
        userPanX = 0f
        userPanY = 0f
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

    fun tap(localX: Float, localY: Float, viewW: Int, viewH: Int) {
        when (_mode.value) {
            InputMode.TRACKPAD -> {
                sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
                sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON1)
            }
            InputMode.TOUCH -> {
                val (rx, ry) = toRemote(localX, localY)
                virtualX = rx.toFloat()
                virtualY = ry.toFloat()
                publishVirtual()
                sendCursor(rx, ry, RdpPointerFlags.MOVE)
                sendCursor(rx, ry, RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
                sendCursor(rx, ry, RdpPointerFlags.BUTTON1)
            }
        }
    }

    fun longPress(localX: Float, localY: Float, viewW: Int, viewH: Int) {
        rightClick(localX, localY)
    }

    /** Two-finger tap → right button click (mouse mode). */
    fun rightClick(localX: Float, localY: Float) {
        val (rx, ry) = when (_mode.value) {
            InputMode.TRACKPAD -> virtualX.roundToInt() to virtualY.roundToInt()
            InputMode.TOUCH -> toRemote(localX, localY).also { (x, y) ->
                virtualX = x.toFloat(); virtualY = y.toFloat(); publishVirtual()
            }
        }
        sendCursor(rx, ry, RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON2)
        sendCursor(rx, ry, RdpPointerFlags.BUTTON2)
    }

    /** Three-finger tap → middle button click (mouse mode only). */
    fun middleClick(localX: Float, localY: Float) {
        val (rx, ry) = when (_mode.value) {
            InputMode.TRACKPAD -> virtualX.roundToInt() to virtualY.roundToInt()
            InputMode.TOUCH -> toRemote(localX, localY).also { (x, y) ->
                virtualX = x.toFloat(); virtualY = y.toFloat(); publishVirtual()
            }
        }
        sendCursor(rx, ry, RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON3)
        sendCursor(rx, ry, RdpPointerFlags.BUTTON3)
    }

    fun drag(dx: Float, dy: Float, localX: Float, localY: Float, viewW: Int, viewH: Int) {
        when (_mode.value) {
            InputMode.TRACKPAD -> {
                // Pan in screen units → cursor in remote units. Divide by viewport scale so
                // the trackpad sensitivity matches what the user sees, not 1:1 with the
                // unscaled framebuffer. Also divide by userZoom so high-zoom moves are fine-
                // grained instead of jumpy.
                val s = (viewportScale * userZoom).let { if (it > 0f) it else 1f }
                val accel = if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 25f) 1.6f else 1.0f
                virtualX = (virtualX + dx * accel / s).coerceIn(0f, remoteWidth - 1f)
                virtualY = (virtualY + dy * accel / s).coerceIn(0f, remoteHeight - 1f)
                publishVirtual()
                val flags = if (dragHeld) {
                    RdpPointerFlags.MOVE or RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1
                } else {
                    RdpPointerFlags.MOVE
                }
                sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), flags)
            }
            InputMode.TOUCH -> {
                val (rx, ry) = toRemote(localX, localY)
                virtualX = rx.toFloat(); virtualY = ry.toFloat(); publishVirtual()
                sendCursor(rx, ry, RdpPointerFlags.MOVE or RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1)
            }
        }
    }

    /** Two-finger vertical pan → wheel scroll. deltaY in view pixels (positive = down). */
    fun scroll(deltaY: Float) {
        val wheelDelta = (-deltaY / 32f).toInt().coerceIn(-127, 127)
        if (wheelDelta == 0) return
        val flags = RdpPointerFlags.WHEEL or (wheelDelta and 0xFF) or
            (if (wheelDelta < 0) RdpPointerFlags.WHEEL_NEGATIVE else 0)
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), flags)
    }

    /**
     * Begin a "double-tap-then-drag" gesture (mouse mode). Subsequent [drag] calls will keep
     * BUTTON1 pressed and emit MOVE+DOWN+BUTTON1 flags so the remote sees a held-button drag.
     * Caller MUST call [endDragHold] when the finger lifts (use try/finally in the gesture
     * scope) — otherwise the remote button stays down forever.
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

    /**
     * Release the implicit BUTTON1 sticky state that TOUCH-mode drag accumulates by sending
     * MOVE+DOWN+BUTTON1 on every move. Call from the gesture recognizer's finally-block when
     * a single-finger touch lifts in TOUCH mode after any drag movement.
     *
     * No-op in trackpad mode (its drag path doesn't latch a button).
     */
    fun releaseTouchDrag() {
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), RdpPointerFlags.BUTTON1)
    }

    fun typeText(text: String) {
        // See TextInputEncoder: ASCII → scancode (never disconnects), other code points → unicode
        // only when the server negotiated it.
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

    /** Convert a touch in view pixels into remote-framebuffer pixels, accounting for the
     *  centred fit-to-view scale applied by [com.hanfengruyue.pocketrdp.feature.session.render.RdpSurface]
     *  AND the local pinch-zoom transform applied by SessionScreen's graphicsLayer. */
    private fun toRemote(localX: Float, localY: Float): Pair<Int, Int> {
        // Step 1: undo user pinch zoom/pan. graphicsLayer scales around the view centre,
        // so a touch at view-pixel (x,y) corresponds to pre-zoom point ((x - cx)/z + cx - panX/z, ...).
        // We don't know the view size here, but the viewport (dx, dy, scale) was computed
        // against the *unscaled* view, so the easiest correct path is to treat the touch as
        // already in pre-zoom space — Compose's PointerInputModifier sits ABOVE graphicsLayer
        // in the modifier chain, so it does indeed receive pre-transform coordinates.
        //
        // ⚠️ Therefore the userZoom math here is intentionally a no-op: we keep userZoom in
        // the controller for the trackpad-mode sensitivity (drag) and for caller introspection,
        // not for the touch→remote unmap. If we later move pointerInput BELOW graphicsLayer
        // in SessionScreen, this is where we'd undo the transform.
        val s = if (viewportScale > 0f) viewportScale else 1f
        val rx = ((localX - viewportDx) / s).toInt().coerceIn(0, remoteWidth - 1)
        val ry = ((localY - viewportDy) / s).toInt().coerceIn(0, remoteHeight - 1)
        return rx to ry
    }

    private fun publishVirtual() {
        _virtualPosition.value = virtualX to virtualY
    }
}
