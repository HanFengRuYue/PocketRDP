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

    // Soft-keyboard (+ function-key toolbar) occlusion at the BOTTOM of the view, in screen px, and the
    // resulting upward lift applied to the framebuffer so its lower part stays visible above the keyboard
    // (用户需求: 呼出键盘后画面上移、不被键盘挡住). The lift is a pure screen-space translation layered on TOP
    // of zoom/pan — it does NOT resize the canvas, so it never re-fits the picture or resets the user's
    // zoom/pan (which is exactly why we DON'T shrink the canvas: that reset was the prior field bug).
    private var bottomOcclusionPx: Float = 0f
    private var imeOffsetY: Float = 0f

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
        // Re-clamp + republish so the keyboard lift (which depends on the picture's on-screen bottom)
        // and the lift-pan room track a fit change too — e.g. an orientation/resolution switch while
        // the keyboard is up.
        clampPan()
        publishTransform()
    }

    /**
     * Set the total bottom occlusion (soft keyboard + the function-key toolbar that floats above it),
     * in screen px, so the framebuffer is lifted just clear of it. 0 = nothing covering the bottom.
     * Driven by SessionScreen from WindowInsets.ime. See [computeImeOffset].
     */
    fun setBottomOcclusion(px: Float) {
        if (px == bottomOcclusionPx) return
        bottomOcclusionPx = px
        // Re-clamp: appearing keyboard opens extra downward pan room (clampPan reads autoLift); a
        // disappearing keyboard (px→0) must re-dial any leftover downward panY back to 0 so the picture
        // doesn't stay shifted down after the keyboard hides.
        clampPan()
        publishTransform()
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

    /** Reset local magnification to 100% (used by the zoom pill's double-tap). */
    fun resetZoom() = resetUserTransform()

    /** Record the view size so [clampPan] can keep the scaled framebuffer on-screen. */
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
        // When the soft keyboard occludes the bottom, the framebuffer is auto-lifted UP (offsetY, see
        // [autoLift]) which pushes its TOP off-screen. Allow extra DOWNWARD pan (positive panY) up to
        // that lift amount so the user can pull the picture back down and see the top — at zoom 1 maxY
        // is 0, so without this panY is locked and the top is unreachable behind the toolbar (用户需求:
        // 呼出键盘后能把画面拖下来看到上方内容). liftRoom is panY-INDEPENDENT (autoLift ignores panY), so a
        // downward drag actually moves the picture instead of being cancelled by a growing auto-lift.
        // At panY == liftRoom: translationY = panY + offsetY = liftRoom + (-liftRoom) = 0 → fit origin.
        val liftRoom = -autoLift()
        userPanY = userPanY.coerceIn(-maxY, maxY + liftRoom)
    }

    private fun publishTransform() {
        // Recompute the keyboard lift here so it tracks EVERY transform change (zoom/pan/viewport/view
        // size) — all of them flow through publishTransform.
        imeOffsetY = autoLift()
        _userTransform.value = UserTransform(userZoom, userPanX, userPanY, imeOffsetY)
    }

    /**
     * How far (screen px, ≤ 0 = up) to auto-translate the framebuffer so its bottom clears the keyboard.
     * Lifts ONLY as much as needed to bring the picture's on-screen bottom edge up to the top of the
     * occluded band, capped at the occlusion height — so a picture already clear of the keyboard (e.g.
     * a small one with a large bottom letterbox) is not moved, and a zoomed one that runs off the bottom
     * is lifted by the full occlusion. Returns 0 when nothing covers the bottom.
     *
     * Deliberately computed from fit + zoom ONLY — it does NOT add userPanY. That decoupling is what
     * lets the keyboard-pan (the extra downward panY room granted by [clampPan]) actually move the
     * picture: if the lift grew with panY, a downward drag would be exactly cancelled by a deepening
     * auto-lift and the top would stay unreachable.
     */
    private fun autoLift(): Float {
        val occl = bottomOcclusionPx
        if (occl <= 0f || viewH <= 0f) return 0f
        val cy = viewH / 2f
        // Bottom edge of the framebuffer on screen under the current fit + user zoom (pan & ime lift
        // excluded): screen = centre + zoom*(base - centre).
        val pictureBottomBase = viewportDy + remoteHeight * viewportScale
        val pictureBottomScreen = cy + userZoom * (pictureBottomBase - cy)
        val needed = (pictureBottomScreen - (viewH - occl)).coerceIn(0f, occl)
        return -needed
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
     * so sensitivity matches what the user sees. When the picture is zoomed (userZoom > 1) OR the soft
     * keyboard has auto-lifted it the view auto-pans to keep the cursor on-screen — see
     * [followCursorWhenZoomed]. That is the restored "缩放后画面跟随光标" behaviour and is TRACKPAD-ONLY
     * (drag is never called in native-touch mode).
     * It is safe here precisely because the trackpad cursor is decoupled from the finger: panning the
     * view does NOT move where the next click lands (clicks use virtualX/Y in remote space), unlike
     * native touch where view-follow between a tap's down/up turned taps into drags.
     */
    fun drag(dx: Float, dy: Float, localX: Float, localY: Float, viewW: Int, viewH: Int) {
        val s = (viewportScale * userZoom).let { if (it > 0f) it else 1f }
        val accel = if (abs(dx) + abs(dy) > 25f) 1.6f else 1.0f
        virtualX = (virtualX + dx * accel / s).coerceIn(0f, remoteWidth - 1f)
        virtualY = (virtualY + dy * accel / s).coerceIn(0f, remoteHeight - 1f)
        publishVirtual()
        followCursorWhenZoomed()
        val flags = if (dragHeld) {
            RdpPointerFlags.MOVE or RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1
        } else {
            RdpPointerFlags.MOVE
        }
        sendCursor(virtualX.roundToInt(), virtualY.roundToInt(), flags)
    }

    /**
     * Pan the view so the virtual cursor stays visible ("缩放后画面跟随光标") — the TRACKPAD-only auto-pan.
     * Active when there is something to pan: either the picture is magnified (zoom > 1), OR the soft
     * keyboard has auto-lifted it ([imeOffsetY] < 0) so that even at 100% zoom dragging the cursor toward
     * the now-off-screen top pulls it back down into view (用户需求: 模拟鼠标模式下呼出键盘仍用光标跟随移动
     * 画面，而不是切到移动杆). Uses a centred dead-zone: the view only moves once the cursor pushes past a
     * margin band near an edge, so small cursor moves don't jitter the picture. The cursor can still reach
     * the framebuffer's own edges — [clampPan] caps the pan, after which the cursor travels the remaining
     * gap to the true edge. TRACKPAD-only (see [drag]).
     *
     * Keyboard-aware in two ways: (1) the cursor's on-screen Y includes [imeOffsetY], because the
     * crosshair/framebuffer layer draws at translationY = panY + offsetY — without it the dead-zone was
     * computed against the un-lifted frame and a follow could slip the cursor behind the keyboard; and
     * (2) the bottom of the follow band is the keyboard's top edge ([bottomOcclusionPx]), NOT the view
     * bottom, so the cursor is always kept in the strip that's actually visible above the keyboard.
     */
    private fun followCursorWhenZoomed() {
        val lifted = imeOffsetY < 0f
        if ((userZoom <= 1f && !lifted) || viewW <= 0f || viewH <= 0f) return
        val cx = viewW / 2f
        val cy = viewH / 2f
        // Where the cursor currently sits on screen under the viewport + user transform + keyboard lift:
        // screen = centre + zoom*(base - centre) + pan (+ imeOffsetY on Y), base = letterboxed fit pos.
        val sx = cx + userZoom * ((viewportDx + virtualX * viewportScale) - cx) + userPanX
        val sy = cy + userZoom * ((viewportDy + virtualY * viewportScale) - cy) + userPanY + imeOffsetY
        // Keep the cursor inside the VISIBLE band (above the keyboard) — visibleBottom collapses to
        // viewH when nothing occludes the bottom, so off-keyboard behaviour is unchanged.
        val visibleBottom = (viewH - bottomOcclusionPx).coerceAtLeast(1f)
        val marginX = viewW * FOLLOW_MARGIN_FRAC
        // marginY is a fraction of the VISIBLE band (NOT viewH) so the dead-zone is always the central
        // 50% of what's actually visible above the keyboard — same relative feel as off-keyboard. Using
        // viewH here would be a bug: under a tall keyboard (occl > viewH/2) a viewH-based margin exceeds
        // visibleBottom/2, so the top guard (sy < marginY) and bottom guard (sy > visibleBottom - marginY)
        // cross and the cursor oscillates between them. A visibleBottom-based margin can never invert.
        val marginY = visibleBottom * FOLLOW_MARGIN_FRAC
        var changed = false
        when {
            sx < marginX -> { userPanX += marginX - sx; changed = true }
            sx > viewW - marginX -> { userPanX += (viewW - marginX) - sx; changed = true }
        }
        when {
            sy < marginY -> { userPanY += marginY - sy; changed = true }
            sy > visibleBottom - marginY -> { userPanY += (visibleBottom - marginY) - sy; changed = true }
        }
        if (changed) {
            clampPan()
            publishTransform()
        }
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

    /**
     * Finger down → RDPEI TouchBegin at the mapped remote pixel. [fingerId] tracks it across moves.
     * No view auto-follow: the picture only moves via the on-screen move handle, so a finger's down
     * and up map to the SAME remote pixel — that's what keeps a stationary tap registering as a
     * click (the old auto-follow shifted the view between down and up, so Windows saw a tiny drag
     * instead of a tap and the click did nothing).
     */
    fun touchDown(fingerId: Int, localX: Float, localY: Float) {
        val (rx, ry) = toRemote(localX, localY)
        sendTouch(fingerId, rx, ry, RdpTouchAction.DOWN)
    }

    /** Finger move → RDPEI TouchUpdate. */
    fun touchMove(fingerId: Int, localX: Float, localY: Float) {
        val (rx, ry) = toRemote(localX, localY)
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
            // Undo the keyboard lift too (it's an extra screen-space Y translation layered with panY),
            // so a touch still lands on the correct remote pixel while the picture is lifted.
            by = cy + (localY - cy - userPanY - imeOffsetY) / userZoom
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
        // Dead-zone for cursor-follow when zoomed: the view pans only once the cursor is within this
        // fraction of a view edge (0.25 → central 50% is a no-move zone). Keeps the cursor visible
        // without the picture jittering on every small move.
        private const val FOLLOW_MARGIN_FRAC = 0.25f
        // View pixels of two-finger drag per wheel notch.
        private const val SCROLL_NOTCH_PX = 28f
        // RDP wheel rotation magnitude for one notch.
        private const val WHEEL_NOTCH = 0x78 // 120
    }
}

/** Local view transform applied by SessionScreen's graphicsLayer (pinch zoom + pan, in view px).
 *  [offsetY] is the extra upward keyboard-lift translation (≤ 0), layered on top of [panY]. */
data class UserTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val offsetY: Float = 0f,
)
