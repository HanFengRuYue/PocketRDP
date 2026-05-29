package com.hanfengruyue.pocketrdp.feature.session.input

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.hanfengruyue.pocketrdp.core.rdp.InputMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Multi-touch gesture recognizer for the RDP session canvas. Lives in [Modifier.sessionGestures]
 * and replaces the old `detectTapGestures` + `detectTransformGestures` pair, which couldn't
 * distinguish single/double/triple finger and didn't support pinch-then-pan separation.
 *
 * Gesture vocabulary (mouse mode = TRACKPAD):
 *   1-finger tap            → left click
 *   1-finger drag           → move virtual cursor (no button)
 *   double-tap then drag    → hold-left-button drag (e.g. window move / text selection)
 *   double-tap (zoomed in)  → reset local zoom/pan
 *   2-finger tap            → right click
 *   2-finger vertical pan   → wheel scroll
 *   2-finger pinch          → local pinch-zoom (graphicsLayer)
 *   3-finger tap            → middle click
 *
 * In TOUCH mode we keep the simpler "direct-mapping" behaviour:
 *   1-finger tap/drag       → left click / left-button drag (absolute coords)
 *   2-finger tap            → right click
 *   2-finger vertical pan   → wheel scroll
 *
 * Implementation notes:
 *  - `awaitEachGesture` lets a try/finally guarantee dragHold is released even if the
 *    pointer leaves the view mid-gesture.
 *  - Tracks the PEAK pointer count across the entire gesture so a 3-finger tap is detected
 *    even if the fingers don't lift exactly simultaneously.
 *  - Tap-slop = 16 px, tap-timeout = 250 ms, double-tap-window = 280 ms — values match the
 *    Android platform defaults so the gesture feels like a native trackpad.
 */
private const val TAP_SLOP_PX = 16f
private const val TAP_TIMEOUT_MS = 250L
private const val DOUBLE_TAP_WINDOW_MS = 280L
private const val PINCH_THRESHOLD = 0.05f
private const val SCROLL_DOMINANCE = 1.4f
private const val SCROLL_MIN_DELTA_PX = 4f

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
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val gestureStartMs = System.currentTimeMillis()
        val startX = first.position.x
        val startY = first.position.y

        var pointerCountPeak = 1
        var totalMove = 0f
        var consumed = false           // scroll or pinch consumed this gesture
        var doubleTapDragHeld = false  // we entered double-tap-then-drag mode
        var touchDragInFlight = false  // TOUCH mode single-finger drag has emitted button-down

        // Two-finger tracking (initialised when 2nd finger arrives).
        var twoStartDist = 0f
        var twoStartZoom = controller.userZoom()

        val sinceLastTap = gestureStartMs - lastSingleTapEndMs
        val nearLast = abs(startX - lastSingleTapX) + abs(startY - lastSingleTapY) < TAP_SLOP_PX * 2
        val doubleTapCandidate = sinceLastTap in 1L..DOUBLE_TAP_WINDOW_MS && nearLast &&
            modeProvider() == InputMode.TRACKPAD

        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                val currentCount = pressed.size
                if (currentCount == 0) break
                pointerCountPeak = maxOf(pointerCountPeak, currentCount)

                when (currentCount) {
                    1 -> {
                        val ch = pressed.first()
                        val dx = ch.positionChange().x
                        val dy = ch.positionChange().y
                        totalMove += abs(dx) + abs(dy)
                        // Promote to drag only after we leave the tap-slop region so pure
                        // taps don't emit a stray MOVE from finger jitter.
                        if (!consumed && totalMove > TAP_SLOP_PX) {
                            if (doubleTapCandidate && !doubleTapDragHeld &&
                                modeProvider() == InputMode.TRACKPAD) {
                                controller.beginDragHold()
                                doubleTapDragHeld = true
                            }
                            if (modeProvider() == InputMode.TOUCH) touchDragInFlight = true
                            controller.drag(dx, dy, ch.position.x, ch.position.y, size.width, size.height)
                            ch.consume()
                        }
                    }
                    2 -> {
                        if (twoStartDist == 0f) {
                            // First entry into 2-finger phase — capture baseline distance.
                            val a = pressed[0].position
                            val b = pressed[1].position
                            twoStartDist = hypot((a.x - b.x), (a.y - b.y))
                            twoStartZoom = controller.userZoom()
                            // Hand off cleanly from any single-finger drag in flight.
                            if (doubleTapDragHeld) {
                                controller.endDragHold()
                                doubleTapDragHeld = false
                            }
                            if (touchDragInFlight) {
                                controller.releaseTouchDrag()
                                touchDragInFlight = false
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
                                    // While pinching, also follow the gesture midpoint so the
                                    // image feels anchored under the fingers (photo-viewer style).
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
                        if (doubleTapDragHeld) {
                            controller.endDragHold()
                            doubleTapDragHeld = false
                        }
                        if (touchDragInFlight) {
                            controller.releaseTouchDrag()
                            touchDragInFlight = false
                        }
                    }
                    else -> Unit // 4+ fingers: noise, ignore
                }
            }
        } finally {
            if (doubleTapDragHeld) controller.endDragHold()
            if (touchDragInFlight) controller.releaseTouchDrag()

            val now = System.currentTimeMillis()
            val dur = now - gestureStartMs
            val mode = modeProvider()
            if (!consumed && !doubleTapDragHeld && !touchDragInFlight &&
                dur < TAP_TIMEOUT_MS && totalMove < TAP_SLOP_PX) {
                when (pointerCountPeak) {
                    1 -> {
                        val sinceLast = now - lastSingleTapEndMs
                        val zoomedIn = controller.userZoom() > 1.01f
                        val isDoubleTap = sinceLast in 1..DOUBLE_TAP_WINDOW_MS &&
                            abs(startX - lastSingleTapX) + abs(startY - lastSingleTapY) < TAP_SLOP_PX * 2
                        if (isDoubleTap && zoomedIn && mode == InputMode.TRACKPAD) {
                            controller.resetUserTransform()
                            onPinchReset()
                            lastSingleTapEndMs = 0L
                        } else {
                            controller.tap(startX, startY, size.width, size.height)
                            lastSingleTapEndMs = now
                            lastSingleTapX = startX
                            lastSingleTapY = startY
                        }
                    }
                    2 -> {
                        controller.rightClick(startX, startY)
                        lastSingleTapEndMs = 0L
                    }
                    3 -> if (mode == InputMode.TRACKPAD) {
                        controller.middleClick(startX, startY)
                        lastSingleTapEndMs = 0L
                    }
                    else -> Unit
                }
            }
        }
    }
}
