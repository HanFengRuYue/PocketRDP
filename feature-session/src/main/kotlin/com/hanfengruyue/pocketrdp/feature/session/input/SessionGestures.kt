package com.hanfengruyue.pocketrdp.feature.session.input

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
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
 *   1-finger tap            → left click (two in a row = remote double-click)
 *   1-finger drag           → move virtual cursor
 *   long-press then drag    → hold-left-button drag (issue #4)
 *   double-tap then drag    → hold-left-button drag
 *   2-finger tap            → right click
 *   2-finger vertical pan   → wheel scroll (issue #7)
 *   3-finger tap            → middle click
 *
 * Reusable primitive arrays eliminate per-gesture HashMap and per-frame List/boxing allocations.
 */
private const val TAP_SLOP_PX = 16f
private const val TAP_TIMEOUT_MS = 250L
private const val DOUBLE_TAP_WINDOW_MS = 280L
private const val LONG_PRESS_MS = 400L
private const val MAX_TOUCH_CONTACTS = 10
private const val AVERAGE_TWO_FACTOR = 0.5f

private class NativeTouchScratch {
    private val pointerIds = LongArray(MAX_TOUCH_CONTACTS)
    private val lastX = FloatArray(MAX_TOUCH_CONTACTS)
    private val lastY = FloatArray(MAX_TOUCH_CONTACTS)
    private val slotUsed = BooleanArray(MAX_TOUCH_CONTACTS)
    var activeCount: Int = 0

    /**
     * Returns -1 when all contact slots are taken (>MAX_TOUCH_CONTACTS simultaneous fingers).
     * MUST NOT fall back to slot 0 — that would alias finger 0 and risk stuck contacts.
     */
    fun allocSlot(pointerId: Long, x: Float, y: Float): Int {
        for (i in slotUsed.indices) {
            if (!slotUsed[i]) {
                slotUsed[i] = true
                pointerIds[i] = pointerId
                lastX[i] = x
                lastY[i] = y
                activeCount++
                return i
            }
        }
        return -1
    }

    fun processChange(change: PointerInputChange, controller: RdpInputController) {
        val id = change.id.value
        var existing = -1
        for (i in slotUsed.indices) {
            if (slotUsed[i] && pointerIds[i] == id) {
                existing = i
                break
            }
        }
        if (change.pressed) {
            if (existing < 0) {
                val slot = allocSlot(id, change.position.x, change.position.y)
                if (slot >= 0) controller.touchDown(slot, change.position.x, change.position.y)
            } else if (change.positionChanged()) {
                lastX[existing] = change.position.x
                lastY[existing] = change.position.y
                controller.touchMove(existing, change.position.x, change.position.y)
            }
            change.consume()
        } else if (existing >= 0) {
            slotUsed[existing] = false
            activeCount--
            controller.touchUp(existing, change.position.x, change.position.y)
            change.consume()
        }
    }

    fun releaseAll(controller: RdpInputController) {
        for (i in slotUsed.indices) {
            if (slotUsed[i]) {
                slotUsed[i] = false
                controller.touchUp(i, lastX[i], lastY[i])
            }
        }
        activeCount = 0
    }
}

private class TrackpadTravelScratch {
    private val pointerIds = LongArray(MAX_TOUCH_CONTACTS)
    private val travels = FloatArray(MAX_TOUCH_CONTACTS)
    var count: Int = 0

    fun addTravel(pointerId: Long, delta: Float): Float {
        for (i in 0 until count) {
            if (pointerIds[i] == pointerId) {
                val updated = travels[i] + delta
                travels[i] = updated
                return updated
            }
        }
        if (count < pointerIds.size) {
            pointerIds[count] = pointerId
            travels[count] = delta
            count++
        }
        return delta
    }
}

private class TrackpadGestureState(
    val startMs: Long,
    val startX: Float,
    val startY: Float,
    val doubleTapCandidate: Boolean,
) {
    var pointerCountPeak: Int = 1
    var totalMove: Float = 0f
    var maxPointerTravel: Float = 0f
    var consumed: Boolean = false
    var holdDragActive: Boolean = false
    var longPressFired: Boolean = false
    var twoActive: Boolean = false
    var twoMove: Float = 0f
    var twoPendingDy: Float = 0f
    var gestureEndedNormally: Boolean = false
}

fun Modifier.sessionGestures(
    controller: RdpInputController,
): Modifier = pointerInput(controller) {
    // Persisted across gestures (for double-tap detection and zero-alloc scratch reuse).
    var lastSingleTapEndMs = 0L
    var lastSingleTapX = 0f
    var lastSingleTapY = 0f
    val nativeScratch = NativeTouchScratch()
    val travelScratch = TrackpadTravelScratch()

    awaitEachGesture {
        // Wait for the finger to LAND first, THEN read the live mode so a top-bar input-mode
        // toggle takes effect on the very next touch without a one-gesture lag.
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        if (controller.mode.value == InputMode.TOUCH) {
            awaitNativeTouchGesture(controller, first, nativeScratch)
        } else {
            awaitTrackpadGesture(
                controller = controller,
                first = first,
                travelScratch = travelScratch,
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

private suspend fun AwaitPointerEventScope.awaitNativeTouchGesture(
    controller: RdpInputController,
    first: PointerInputChange,
    scratch: NativeTouchScratch,
) {
    try {
        val f0 = scratch.allocSlot(first.id.value, first.position.x, first.position.y)
        if (f0 >= 0) {
            controller.touchDown(f0, first.position.x, first.position.y)
        }
        first.consume()

        while (scratch.activeCount > 0) {
            val changes = awaitPointerEvent(PointerEventPass.Main).changes
            for (i in changes.indices) {
                scratch.processChange(changes[i], controller)
            }
        }
    } finally {
        // Release any contact still held so Windows never keeps a finger stuck on the desktop.
        scratch.releaseAll(controller)
    }
}

@Suppress("LongParameterList")
private suspend fun AwaitPointerEventScope.awaitTrackpadGesture(
    controller: RdpInputController,
    first: PointerInputChange,
    travelScratch: TrackpadTravelScratch,
    lastSingleTapEndMs: Long,
    lastSingleTapX: Float,
    lastSingleTapY: Float,
    commitTap: (endMs: Long, x: Float, y: Float) -> Unit,
) {
    val gestureStartMs = SystemClock.uptimeMillis()
    val startX = first.position.x
    val startY = first.position.y
    val sinceLastTap = gestureStartMs - lastSingleTapEndMs
    val nearLast = abs(startX - lastSingleTapX) + abs(startY - lastSingleTapY) < TAP_SLOP_PX * 2
    val doubleTapCandidate = sinceLastTap in 1L..DOUBLE_TAP_WINDOW_MS && nearLast
    val state = TrackpadGestureState(gestureStartMs, startX, startY, doubleTapCandidate)
    travelScratch.count = 0

    try {
        var tracking = true
        while (tracking) {
            val inactive = !state.consumed && !state.holdDragActive && !state.longPressFired
            val armLongPress = inactive && state.pointerCountPeak == 1 && state.totalMove <= TAP_SLOP_PX
            val event = if (armLongPress) {
                val remaining = (LONG_PRESS_MS - (SystemClock.uptimeMillis() - state.startMs)).coerceAtLeast(1L)
                withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Main) }
            } else {
                awaitPointerEvent(PointerEventPass.Main)
            }

            if (event == null) {
                controller.beginDragHold()
                state.holdDragActive = true
                state.longPressFired = true
            } else {
                tracking = processTrackpadEvent(event.changes, controller, state, travelScratch)
            }
        }
    } finally {
        if (state.holdDragActive) controller.endDragHold()
        dispatchTrackpadTap(controller, state, commitTap)
    }
}

private fun dispatchTrackpadTap(
    controller: RdpInputController,
    state: TrackpadGestureState,
    commitTap: (endMs: Long, x: Float, y: Float) -> Unit,
) {
    val now = SystemClock.uptimeMillis()
    val clean = state.gestureEndedNormally && !state.consumed && !state.holdDragActive
    val withinTapLimits = (now - state.startMs) < TAP_TIMEOUT_MS && state.maxPointerTravel < TAP_SLOP_PX
    if (!clean || state.longPressFired || !withinTapLimits) return
    when (state.pointerCountPeak) {
        1 -> {
            controller.tap()
            commitTap(now, state.startX, state.startY)
        }
        2 -> {
            controller.rightClick()
            commitTap(0L, state.startX, state.startY)
        }
        3 -> {
            controller.middleClick()
            commitTap(0L, state.startX, state.startY)
        }
        else -> Unit
    }
}

private fun processTrackpadEvent(
    changes: List<PointerInputChange>,
    controller: RdpInputController,
    state: TrackpadGestureState,
    travelScratch: TrackpadTravelScratch,
): Boolean {
    var currentCount = 0
    var firstPressed: PointerInputChange? = null
    var secondPressed: PointerInputChange? = null
    for (i in changes.indices) {
        val change = changes[i]
        val delta = change.positionChange()
        val travel = travelScratch.addTravel(change.id.value, hypot(delta.x, delta.y))
        if (travel > state.maxPointerTravel) state.maxPointerTravel = travel
        if (change.pressed) {
            if (currentCount == 0) firstPressed = change else if (currentCount == 1) secondPressed = change
            currentCount++
        }
    }
    if (currentCount == 0) {
        state.gestureEndedNormally = true
        return false
    }
    if (currentCount > state.pointerCountPeak) state.pointerCountPeak = currentCount
    if (currentCount != 2) state.twoActive = false

    when (currentCount) {
        1 -> handleSingleFingerTrackpad(checkNotNull(firstPressed), controller, state)
        2 -> handleTwoFingerTrackpad(checkNotNull(firstPressed), checkNotNull(secondPressed), controller, state)
        3 -> if (state.holdDragActive) {
            controller.endDragHold()
            state.holdDragActive = false
        }
        else -> Unit
    }
    return true
}

private fun handleSingleFingerTrackpad(
    change: PointerInputChange,
    controller: RdpInputController,
    state: TrackpadGestureState,
) {
    val delta = change.positionChange()
    state.totalMove += abs(delta.x) + abs(delta.y)
    if (!state.consumed && (state.totalMove > TAP_SLOP_PX || state.holdDragActive)) {
        if (state.doubleTapCandidate && !state.holdDragActive && !state.longPressFired) {
            controller.beginDragHold()
            state.holdDragActive = true
        }
        controller.drag(delta.x, delta.y)
        change.consume()
    }
}

private fun handleTwoFingerTrackpad(
    first: PointerInputChange,
    second: PointerInputChange,
    controller: RdpInputController,
    state: TrackpadGestureState,
) {
    if (!state.twoActive) {
        state.twoActive = true
        state.twoMove = 0f
        state.twoPendingDy = 0f
        controller.resetScrollAccum()
        if (state.holdDragActive) {
            controller.endDragHold()
            state.holdDragActive = false
        }
    }
    val d0 = first.positionChange()
    val d1 = second.positionChange()
    state.twoMove += maxOf(hypot(d0.x, d0.y), hypot(d1.x, d1.y))
    state.twoPendingDy += (d0.y + d1.y) * AVERAGE_TWO_FACTOR
    if (state.twoMove > TAP_SLOP_PX) {
        controller.scroll(state.twoPendingDy)
        state.twoPendingDy = 0f
        state.consumed = true
    }
    first.consume()
    second.consume()
}
