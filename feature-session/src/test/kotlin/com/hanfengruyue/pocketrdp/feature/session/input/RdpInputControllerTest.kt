package com.hanfengruyue.pocketrdp.feature.session.input

import com.hanfengruyue.pocketrdp.core.rdp.RdpPointerFlags
import com.hanfengruyue.pocketrdp.core.rdp.RdpTouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RdpInputControllerTest {
    @Test
    fun trackpadHoldDragSendsPlainMoveBetweenPressAndRelease() {
        val pointerEvents = mutableListOf<Triple<Int, Int, Int>>()
        val controller = RdpInputController(
            sendCursor = { x, y, flags -> pointerEvents += Triple(x, y, flags) },
        )
        controller.setRemoteSize(1920, 1080)
        controller.setViewSize(1920, 1080)
        controller.setViewport(scale = 1f, dx = 0f, dy = 0f)

        controller.beginDragHold()
        controller.drag(10f, 5f)
        controller.drag(10f, 5f)
        controller.endDragHold()

        assertEquals(
            listOf(
                Triple(0, 0, RdpPointerFlags.DOWN or RdpPointerFlags.BUTTON1),
                Triple(10, 5, RdpPointerFlags.MOVE),
                Triple(20, 10, RdpPointerFlags.MOVE),
                Triple(20, 10, RdpPointerFlags.BUTTON1),
            ),
            pointerEvents,
        )
    }

    @Test
    fun setViewportAndSetViewSizeSkipRedundantTransformPublish() {
        val controller = RdpInputController(sendCursor = { _, _, _ -> })
        controller.setRemoteSize(1920, 1080)
        controller.setViewSize(1920, 1080)
        controller.setViewport(scale = 1f, dx = 0f, dy = 0f)
        val initialTransform = controller.userTransform.value

        controller.setViewSize(1920, 1080)
        controller.setViewport(scale = 1f, dx = 0f, dy = 0f)

        assertSame(initialTransform, controller.userTransform.value)
    }

    @Test
    fun touchDownMapsLocalCoordinatesThroughZoomAndPanWithoutBoxing() {
        val touchEvents = mutableListOf<List<Int>>()
        val controller = RdpInputController(
            sendCursor = { _, _, _ -> },
            sendTouch = { id, x, y, action -> touchEvents += listOf(id, x, y, action) },
        )
        controller.setRemoteSize(1000, 500)
        controller.setViewSize(1000, 500)
        controller.setViewport(scale = 1f, dx = 0f, dy = 0f)
        controller.setUserZoom(2f)

        // Screen centre (500, 250) stays mapped to remote centre (500, 250) at 2x zoom.
        controller.touchDown(fingerId = 0, localX = 500f, localY = 250f)

        assertEquals(listOf(listOf(0, 500, 250, RdpTouchAction.DOWN)), touchEvents)
    }

    @Test
    fun clampPanAxisBoundsWithinOverflowAndLiftRoom() {
        assertEquals(
            0f,
            clampPanAxis(value = 50f, viewSize = 1000f, fittedStart = 0f, fittedSize = 1000f, zoom = 1f),
            0f,
        )
        assertEquals(
            200f,
            clampPanAxis(
                value = 300f,
                viewSize = 1000f,
                fittedStart = 0f,
                fittedSize = 1000f,
                zoom = 1f,
                extraMax = 200f,
            ),
            0f,
        )
        assertEquals(
            -500f,
            clampPanAxis(value = -900f, viewSize = 1000f, fittedStart = 0f, fittedSize = 1000f, zoom = 2f),
            0f,
        )
    }
}
