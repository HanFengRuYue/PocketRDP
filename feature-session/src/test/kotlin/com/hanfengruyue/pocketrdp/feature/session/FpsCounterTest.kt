package com.hanfengruyue.pocketrdp.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsCounterTest {
    @Test
    fun evictsTimestampsOutsideSlidingWindow() {
        var now = 1_000L
        val counter = FpsCounter(windowMs = 1_000L, nowMs = { now })

        counter.tick()
        now = 1_500L
        counter.tick()
        assertEquals(2, counter.snapshot())

        now = 2_001L
        assertEquals(1, counter.snapshot())

        now = 2_501L
        assertEquals(0, counter.snapshot())
    }

    @Test
    fun ringBufferWrapsAndGrowsPreservingOrderAndCount() {
        var now = 1_000L
        val counter = FpsCounter(windowMs = 1_000L, nowMs = { now })

        // Advance head partway, then expire so head is non-zero before triggering capacity growth.
        repeat(80) {
            counter.tick()
            now += 1L
        }
        now = 2_500L
        assertEquals(0, counter.snapshot())

        // Insert more than initial capacity (128) within one window to force ring growth.
        repeat(200) {
            counter.tick()
            now += 2L
        }
        assertEquals(200, counter.snapshot())

        // Advance so the first 50 ticks (at 2500..2598) expire while the remaining 150 stay.
        now = 3_600L
        assertEquals(150, counter.snapshot())

        counter.reset()
        assertEquals(0, counter.snapshot())
    }
}
