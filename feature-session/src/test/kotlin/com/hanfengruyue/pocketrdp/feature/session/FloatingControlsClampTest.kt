package com.hanfengruyue.pocketrdp.feature.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlsClampTest {
    @Test
    fun zoomPillCannotBeDraggedPastLeftOrVerticalEdgesOfSafeContainer() {
        val safeContainer = IntSize(width = 800, height = 400)
        val pill = IntSize(width = 100, height = 80)

        val draggedFarLeft = clampPillOffset(
            base = Offset.Zero,
            dx = -900f,
            dy = -500f,
            container = safeContainer,
            pill = pill,
        )
        val draggedFarRightAndDown = clampPillOffset(
            base = Offset.Zero,
            dx = 200f,
            dy = 500f,
            container = safeContainer,
            pill = pill,
        )

        assertEquals(Offset(-700f, -160f), draggedFarLeft)
        assertEquals(Offset(0f, 160f), draggedFarRightAndDown)
    }

    @Test
    fun panHandleReclampsAboveExpandedToolbarWhenContainerShrinks() {
        val control = IntSize(width = 120, height = 120)
        val bottomOffset = clampCenteredControlOffset(
            base = Offset.Zero,
            dx = 0f,
            dy = 500f,
            container = IntSize(width = 800, height = 800),
            control = control,
        )
        assertEquals(340f, bottomOffset.y, 0f)

        val reclampedAboveToolbar = clampCenteredControlOffset(
            base = bottomOffset,
            dx = 0f,
            dy = 0f,
            container = IntSize(width = 800, height = 300),
            control = control,
        )
        assertEquals(90f, reclampedAboveToolbar.y, 0f)
    }
}
