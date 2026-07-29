package com.hanfengruyue.pocketrdp.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickChipDragLogicTest {
    @Test
    fun crossingRightNeighbor_movesAndPreservesVisualOffset() {
        val update = calculateQuickChipDragUpdate(
            ids = listOf("a", "b", "c"),
            draggedId = "a",
            itemWidthsPx = mapOf("a" to 64, "b" to 64, "c" to 64),
            itemSpacingPx = 8f,
            currentOffsetX = 0f,
            deltaX = 73f,
        )

        assertEquals(QuickChipMove(fromIndex = 0, toIndex = 1), update.move)
        assertEquals(1f, update.offsetX, 0f)
    }

    @Test
    fun stayingBeforeNeighborCenter_keepsCurrentOrder() {
        val update = calculateQuickChipDragUpdate(
            ids = listOf("a", "b"),
            draggedId = "a",
            itemWidthsPx = mapOf("a" to 64, "b" to 64),
            itemSpacingPx = 8f,
            currentOffsetX = 0f,
            deltaX = 71f,
        )

        assertNull(update.move)
        assertEquals(71f, update.offsetX, 0f)
    }

    @Test
    fun fastDragAcrossMultipleNeighbors_movesDirectlyToCrossedSlot() {
        val update = calculateQuickChipDragUpdate(
            ids = listOf("a", "b", "c", "d"),
            draggedId = "a",
            itemWidthsPx = mapOf("a" to 64, "b" to 64, "c" to 64, "d" to 64),
            itemSpacingPx = 8f,
            currentOffsetX = 0f,
            deltaX = 160f,
        )

        assertEquals(QuickChipMove(fromIndex = 0, toIndex = 2), update.move)
        assertEquals(16f, update.offsetX, 0f)
    }

    @Test
    fun crossingVariableWidthLeftNeighbor_usesNeighborSlotWidth() {
        val update = calculateQuickChipDragUpdate(
            ids = listOf("a", "b"),
            draggedId = "b",
            itemWidthsPx = mapOf("a" to 64, "b" to 96),
            itemSpacingPx = 8f,
            currentOffsetX = 0f,
            deltaX = -90f,
        )

        assertEquals(QuickChipMove(fromIndex = 1, toIndex = 0), update.move)
        assertEquals(-18f, update.offsetX, 0f)
    }

    @Test
    fun missingLayoutWidth_defersReorderUntilItemsAreMeasured() {
        val update = calculateQuickChipDragUpdate(
            ids = listOf("a", "b"),
            draggedId = "a",
            itemWidthsPx = mapOf("a" to 64),
            itemSpacingPx = 8f,
            currentOffsetX = 0f,
            deltaX = 100f,
        )

        assertNull(update.move)
        assertEquals(100f, update.offsetX, 0f)
    }
}
