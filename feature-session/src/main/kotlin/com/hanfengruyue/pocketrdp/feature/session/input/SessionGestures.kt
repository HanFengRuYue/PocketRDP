package com.hanfengruyue.pocketrdp.feature.session.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Multi-touch gesture recognizer for the RDP session canvas. Lives in [Modifier.sessionGestures].
 *
 * It branches on the active [InputMode] at the start of each gesture:
 *
 * **TOUCH (native Windows multi-touch):** every finger is forwarded verbatim as an RDPEI touch
 * contact (down/move/up) via the controller. No tap/click synthesis, no mouse — Windows itself
 * decides what a tap / two-finger scroll / pinch means, exactly like a physical touchscreen.
 *
 * **TRACKPAD (mouse emulation):**
 *   1-finger tap            → left click
 *   1-finger drag           → move virtual cursor (view follows when zoomed)
 *   long-press then drag    → hold-left-button drag (issue #4)
 *   double-tap then drag    → hold-left-button drag
 *   double-tap (zoomed in)  → reset local zoom/pan
 *   2-finger tap            → right click
 *   2-finger vertical pan   → wheel scroll (issue #7)
 *   2-finger pinch          → local pinch-zoom (graphicsLayer)
 *   3-finger tap            → middle click
 *
 * Implementation notes:
 *  - `awaitEachGesture` + try/finally guarantees held buttons / touch contacts are released even
 *    if a pointer leaves the view mid-gesture.
 *  - TRACKPAD tracks the PEAK pointer count so a 3-finger tap is detected even if fingers don't
 *    lift simultaneously.
 */
private const val TAP_SLOP_PX = 16f
private const val TAP_TIMEOUT_MS = 250L
private const val DOUBLE_TAP_WINDOW_MS = 280L
private const val LONG_PRESS_MS = 400L
private const val PINCH_THRESHOLD = 0.05f
private const val SCROLL_DOMINANCE = 1.4f
private const val SCROLL_MIN_DELTA_PX = 4f
private const val MAX_TOUCH_CONTACTS = 10

fun Modifier.sessionGestures(
    controller: RdpInputController,
    modeProvider: () -> InputMode,
    onPinchReset: () -> Unit = {},
): Modifier = pointerInput(controller) {
    // Persisted across gestures (for double-tap detection).
    var lastSingleTapEndMs = 0L
    var lastSingleTapX = 0f
    var lastSingleTapY = 0f

    awaitEachGesture {
        if (modeProvider() == InputMode.TOUCH) {
            awaitNativeTouchGesture(controller)
        } else {
            awaitTrackpadGesture(
                controller = controller,
                onPinchReset = onPinchReset,
                lastSingleTapEndMs = lastSingleTapEndMs,
                lastSingleTapX = lastSingleTapX,
                lastSingleTapY = lastSingleTapY,
            ) { endMs, x, y ->
                lastSingleTapEndMs = endMs
                lastSingleTapX = x
                lastSingleTapY = y
            }
        }
    }
}

/**
 * Forward raw multi-touch to Windows via RDPEI. Each Compose pointer is assigned a small finger
 * slot (RDPEI externalId); down→[RdpInputController.touchDown], move→touchMove, up→touchUp. The
 * finally block releases any contact still down if the gesture aborts (finger left the view).
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitNativeTouchGesture(
    controller: RdpInputController,
) {
    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
    val fingerOf = HashMap<Long, Int>()           // PointerId.value → finger slot
    val slotUsed = BooleanArray(MAX_TOUCH_CONTACTS)
    val lastPos = HashMap<Int, Offset>()           // finger slot → last position

    // Returns -1 when all contact slots are taken (>MAX_TOUCH_CONTACTS simultaneous fingers).
    // MUST NOT fall back to slot 0 — that would alias finger 0, double-send its TouchBegin and, on
    // the extra finger's lift, prematurely TouchEnd finger 0's still-active contact (stuck contact).
    fun allocSlot(): Int {
        for (i in slotUsed.indices) if (!slotUsed[i]) { slotUsed[i] = true; return i }
        return -1
    }

    try {
        val f0 = allocSlot()
        if (f0 >= 0) {
            fingerOf[first.id.value] = f0
            lastPos[f0] = first.position
            controller.touchDown(f0, first.position.x, first.position.y)
        }
        first.consume()

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            for (ch in event.changes) {
                val id = ch.id.value
                if (ch.pressed) {
                    val existing = fingerOf[id]
                    if (existing == null) {
                        val slot = allocSlot()
                        if (slot >= 0) {
                            fingerOf[id] = slot
                            lastPos[slot] = ch.position
                            controller.touchDown(slot, ch.position.x, ch.position.y)
                        }
                        // slot < 0 → over the contact cap; ignore this finger entirely (still
                        // consumed below so it doesn't leak to other handlers).
                    } else if (ch.positionChanged()) {
                        lastPos[existing] = ch.position
                        controller.touchMove(existing, ch.position.x, ch.position.y)
                    }
                    ch.consume()
                } else {
                    val slot = fingerOf.remove(id)
                    if (slot != null) {
                        slotUsed[slot] = false
                        lastPos.remove(slot)
                        controller.touchUp(slot, ch.position.x, ch.position.y)
                        ch.consume()
                    }
                }
            }
            if (fingerOf.isEmpty()) break
        }
    } finally {
        // Release any contact still held (pointer left the view without an up event) so Windows
        // doesn't keep a finger "stuck" on the desktop.
        for ((id, slot) in fingerOf) {
            val p = lastPos[slot] ?: Offset.Zero
            controller.touchUp(slot, p.x, p.y)
        }
    }
}

/** TRACKPAD-mode mouse emulation. [commitTap] reports the (time,x,y) of a single tap for the
 *  caller's persistent double-tap bookkeeping. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitTrackpadGesture(
    controller: RdpInputController,
    onPinchReset: () -> Unit,
    lastSingleTapEndMs: Long,
    lastSingleTapX: Float,
    lastSingleTapY: Float,
    commitTap: (endMs: Long, x: Float, y: Float) -> Unit,
) {
    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
    val gestureStartMs = System.currentTimeMillis()
    val startX = first.position.x
    val startY = first.position.y

    var pointerCountPeak = 1
    var totalMove = 0f
    var consumed = false            // scroll or pinch consumed this gesture
    var holdDragActive = false      // we entered a held-button drag (double-tap OR long-press)
    var longPressFired = false

    // Two-finger tracking (initialised when 2nd finger arrives).
    var twoStartDist = 0f
    var twoStartZoom = controller.userZoom()

    val sinceLastTap = gestureStartMs - lastSingleTapEndMs
    val nearLast = abs(startX - lastSingleTapX) + abs(startY - lastSingleTapY) < TAP_SLOP_PX * 2
    val doubleTapCandidate = sinceLastTap in 1L..DOUBLE_TAP_WINDOW_MS && nearLast

    try {
        while (true) {
            // Arm a long-press timeout only while still a stationary single-finger press.
            val armLongPress = !consumed && !holdDragActive && !longPressFired &&
                pointerCountPeak == 1 && totalMove <= TAP_SLOP_PX
            val event = if (armLongPress) {
                val remaining = (LONG_PRESS_MS - (System.currentTimeMillis() - gestureStartMs)).coerceAtLeast(1L)
                withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Main) }
            } else {
                awaitPointerEvent(PointerEventPass.Main)
            }

            if (event == null) {
                // Long-press fired: finger held still long enough → start a hold-left-button drag.
                controller.beginDragHold()
                holdDragActive = true
                longPressFired = true
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            val currentCount = pressed.size
            if (currentCount == 0) break
            pointerCountPeak = maxOf(pointerCountPeak, currentCount)

            // Re-arm the pinch baseline whenever not in a 2-finger phase, so a 2→1→2 transition
            // within one gesture re-captures a fresh distance/zoom instead of computing ratio
            // against a stale baseline (which snapped the zoom). Safe: twoStartDist is read only
            // in the 2-finger branch.
            if (currentCount != 2) twoStartDist = 0f

            when (currentCount) {
                1 -> {
                    val ch = pressed.first()
                    val dx = ch.positionChange().x
                    val dy = ch.positionChange().y
                    totalMove += abs(dx) + abs(dy)
                    if (!consumed && (totalMove > TAP_SLOP_PX || holdDragActive)) {
                        // Double-tap-then-drag promotes to hold-drag on first move.
                        if ((doubleTapCandidate || holdDragActive) && !longPressFired) {
                            if (doubleTapCandidate && !holdDragActive) {
                                controller.beginDragHold()
                                holdDragActive = true
                            }
                        }
                        controller.drag(dx, dy, ch.position.x, ch.position.y, size.width, size.height)
                        ch.consume()
                    }
                }
                2 -> {
                    if (twoStartDist == 0f) {
                        val a = pressed[0].position
                        val b = pressed[1].position
                        twoStartDist = hypot((a.x - b.x), (a.y - b.y))
                        twoStartZoom = controller.userZoom()
                        controller.resetScrollAccum()
                        if (holdDragActive) {
                            controller.endDragHold()
                            holdDragActive = false
                        }
                    } else {
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val dist = hypot((a.x - b.x), (a.y - b.y))
                        val ratio = if (twoStartDist > 0f) dist / twoStartDist else 1f

                        val avgDx = (pressed[0].positionChange().x + pressed[1].positionChange().x) * 0.5f
                        val avgDy = (pressed[0].positionChange().y + pressed[1].positionChange().y) * 0.5f

                        val pinchActive = abs(ratio - 1f) > PINCH_THRESHOLD
                        val scrollActive = abs(avgDy) > abs(avgDx) * SCROLL_DOMINANCE &&
                            abs(avgDy) > SCROLL_MIN_DELTA_PX

                        when {
                            pinchActive -> {
                                val newZoom = (twoStartZoom * ratio).coerceIn(1f, 4f)
                                controller.setUserZoom(newZoom)
                                controller.setUserPan(
                                    controller.userPanX() + avgDx,
                                    controller.userPanY() + avgDy,
                                )
                                consumed = true
                                pressed.forEach { it.consume() }
                            }
                            scrollActive -> {
                                controller.scroll(avgDy)
                                consumed = true
                                pressed.forEach { it.consume() }
                            }
                        }
                    }
                }
                3 -> {
                    if (holdDragActive) {
                        controller.endDragHold()
                        holdDragActive = false
                    }
                }
                else -> Unit // 4+ fingers: noise, ignore
            }
        }
    } finally {
        if (holdDragActive) controller.endDragHold()

        val now = System.currentTimeMillis()
        val dur = now - gestureStartMs
        if (!consumed && !holdDragActive && !longPressFired &&
            dur < TAP_TIMEOUT_MS && totalMove < TAP_SLOP_PX) {
            when (pointerCountPeak) {
                1 -> {
                    val sinceLast = now - lastSingleTapEndMs
                    val isDoubleTap = sinceLast in 1..DOUBLE_TAP_WINDOW_MS &&
                        abs(startX - lastSingleTapX) + abs(startY - lastSingleTapY) < TAP_SLOP_PX * 2
                    if (isDoubleTap && controller.isZoomed()) {
                        controller.resetUserTransform()
                        onPinchReset()
                        commitTap(0L, startX, startY)
                    } else {
                        controller.tap(startX, startY, size.width, size.height)
                        commitTap(now, startX, startY)
                    }
                }
                2 -> {
                    controller.rightClick(startX, startY)
                    commitTap(0L, startX, startY)
                }
                3 -> {
                    controller.middleClick(startX, startY)
                    commitTap(0L, startX, startY)
                }
                else -> Unit
            }
        }
    }
}
