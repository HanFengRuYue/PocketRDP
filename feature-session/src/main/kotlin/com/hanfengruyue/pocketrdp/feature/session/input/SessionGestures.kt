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
 *   1-finger tap            → left click (two in a row = remote double-click)
 *   1-finger drag           → move virtual cursor
 *   long-press then drag    → hold-left-button drag (issue #4)
 *   double-tap then drag    → hold-left-button drag
 *   2-finger tap            → right click
 *   2-finger vertical pan   → wheel scroll (issue #7)
 *   3-finger tap            → middle click
 *
 * NOTE: there are NO picture-changing *gestures* (no pinch-zoom, no double-tap-to-reset). Per user
 * request the picture is zoomed ONLY via the zoom pill and panned ONLY via the move handle (both in
 * SessionScreen) — so every touch gesture is unambiguously a *control* gesture and can't fight the
 * view transform. This is also why a double-tap is now a clean remote double-click instead of being
 * eaten to reset the local zoom. The ONE exception is TRACKPAD cursor-follow: while zoomed, moving
 * the virtual cursor auto-pans the view to keep it visible (RdpInputController.followCursorWhenZoomed).
 * That is safe because the trackpad cursor is decoupled from the finger — it doesn't move where a tap
 * lands. NATIVE-TOUCH deliberately has NO view-follow (it broke taps; see RdpInputController.touchDown).
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
private const val MAX_TOUCH_CONTACTS = 10

fun Modifier.sessionGestures(
    controller: RdpInputController,
    modeProvider: () -> InputMode,
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
    var consumed = false            // scroll consumed this gesture (→ no tap/click on release)
    var holdDragActive = false      // we entered a held-button drag (double-tap OR long-press)
    var longPressFired = false

    // Two-finger phase tracking. Two fingers = wheel scroll ONLY (picture zoom/move live on the
    // on-screen buttons now), so we only reset the scroll accumulator when the phase starts and gate
    // scrolling on a small travel threshold so a 2-finger TAP still falls through to right-click.
    var twoActive = false
    var twoMove = 0f
    var twoPendingDy = 0f

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

            // Reset the 2-finger phase whenever we're not in it, so a 2→1→2 transition re-arms the
            // scroll accumulator cleanly.
            if (currentCount != 2) twoActive = false

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
                    // Two fingers = wheel scroll, and nothing else. There is no pinch-zoom gesture
                    // any more (picture zoom is the zoom pill), so scroll never has to fight it.
                    if (!twoActive) {
                        twoActive = true
                        twoMove = 0f
                        controller.resetScrollAccum()
                        if (holdDragActive) {
                            controller.endDragHold()
                            holdDragActive = false
                        }
                    }
                    val avgDy = (pressed[0].positionChange().y + pressed[1].positionChange().y) * 0.5f
                    twoMove += abs(avgDy)
                    twoPendingDy += avgDy
                    // Gate on a small travel so a still 2-finger TAP keeps `consumed` false and falls
                    // through to the right-click path in the finally block. Once the gate is crossed,
                    // flush the ACCUMULATED travel (incl. the pre-gate frames) so the first ~16 px of
                    // a real scroll isn't dropped, then each later frame feeds its own delta.
                    if (twoMove > TAP_SLOP_PX) {
                        controller.scroll(twoPendingDy)
                        twoPendingDy = 0f
                        consumed = true
                    }
                    pressed.forEach { it.consume() }
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
                    // Always a left click. Two taps in a row therefore land as a normal remote
                    // double-click — the old "double-tap to reset local zoom" was removed because it
                    // ate the remote double-click; zoom reset now lives only on the zoom pill.
                    controller.tap(startX, startY, size.width, size.height)
                    commitTap(now, startX, startY)
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
