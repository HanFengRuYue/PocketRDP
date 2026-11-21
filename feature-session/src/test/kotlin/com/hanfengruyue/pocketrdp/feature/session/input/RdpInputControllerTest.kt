package com.hanfengruyue.pocketrdp.feature.session.input

import com.hanfengruyue.pocketrdp.core.rdp.RdpPointerFlags
import org.junit.Assert.assertEquals
import org.junit.Test

class RdpInputControllerTest {
    @Test
    fun trackpadHoldDragSendsPlainMoveBetweenPressAndRelease() {
        val pointerEvents = mutableListOf<Triple<Int, Int, Int>>()
        val controller = RdpInputController(
            sendCursor = { x, y, flags -> pointerEvents += Triple(x, y, flags) },
            sendKey = { _, _ -> },
            sendUnicode = { _, _ -> },
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
}
