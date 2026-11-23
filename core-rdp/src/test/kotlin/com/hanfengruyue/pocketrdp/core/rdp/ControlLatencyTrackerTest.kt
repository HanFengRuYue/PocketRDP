package com.hanfengruyue.pocketrdp.core.rdp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlLatencyTrackerTest {
    @Test
    fun timelyFramesComputeLowPercentileWithoutCorruptingRing() {
        val tracker = ControlLatencyTracker()
        assertEquals(-1, tracker.estimateMs())

        val samples = listOf(120L, 40L, 80L, 200L, 60L)
        var now = 1_000L
        for (delta in samples) {
            tracker.markDiscreteInput(now)
            tracker.recordServerFrame(now + delta)
            now += 500L
        }

        // Sorted: [40, 60, 80, 120, 200], index = round((5 - 1) * 0.25) = 1 -> 60 ms.
        assertEquals(5, tracker.accepted())
        assertEquals(0, tracker.discarded())
        assertEquals(60, tracker.estimateMs())
        // Reusing the scratch buffer across repeated reads must preserve the ring order.
        assertEquals(60, tracker.estimateMs())
    }

    @Test
    fun expiringStaleArmInMarkDiscreteInputIncrementsDiscardedCount() {
        val tracker = ControlLatencyTracker()

        tracker.markDiscreteInput(200L)
        // Next input arrives after the 300ms response window without an intervening frame.
        tracker.markDiscreteInput(600L)

        assertEquals(0, tracker.accepted())
        assertEquals(1, tracker.discarded())

        // The new arm at 600ms is closed by a timely frame 45ms later.
        tracker.recordServerFrame(645L)

        assertEquals(1, tracker.accepted())
        assertEquals(1, tracker.discarded())
        assertEquals(45, tracker.estimateMs())
    }

    @Test
    fun lateFrameOutsideWindowDiscardsPendingArm() {
        val tracker = ControlLatencyTracker()

        tracker.markDiscreteInput(200L)
        tracker.recordServerFrame(650L)

        assertEquals(0, tracker.accepted())
        assertEquals(1, tracker.discarded())
        assertEquals(-1, tracker.estimateMs())
    }

    @Test
    fun inputDuringActiveStreamWithinIdleGapDoesNotArm() {
        val tracker = ControlLatencyTracker()

        tracker.recordServerFrame(200L)
        // Only 50ms since last server frame (< 80ms idle gap) -> should not arm.
        tracker.markDiscreteInput(250L)
        tracker.recordServerFrame(290L)

        assertEquals(0, tracker.accepted())
        assertEquals(0, tracker.discarded())
        assertEquals(-1, tracker.estimateMs())
    }

    @Test
    fun resetClearsPendingArmSamplesAndCounters() {
        val tracker = ControlLatencyTracker()

        tracker.markDiscreteInput(200L)
        tracker.recordServerFrame(250L)
        tracker.markDiscreteInput(500L)
        tracker.markDiscreteInput(900L)
        assertEquals(1, tracker.accepted())
        assertEquals(1, tracker.discarded())
        assertEquals(50, tracker.estimateMs())

        tracker.reset()

        assertEquals(0, tracker.accepted())
        assertEquals(0, tracker.discarded())
        assertEquals(-1, tracker.estimateMs())
    }

    @Test
    fun concurrentExpireAndFrameCloseNeverDoubleCountSameArm() {
        val tracker = ControlLatencyTracker()
        val iterations = 200
        var totalArmed = 0

        for (i in 0 until iterations) {
            val base = 1_000L + i * 1_000L
            tracker.markDiscreteInput(base)
            totalArmed++

            val startGate = CountDownLatch(1)
            val done = CountDownLatch(2)
            val failure = AtomicReference<Throwable?>(null)

            val expirer = Thread {
                try {
                    startGate.await(2, TimeUnit.SECONDS)
                    // Expires the stale arm at base (> 300ms); may re-arm at base+350 if closer
                    // hasn't updated lastServerFrameAtMs yet.
                    tracker.markDiscreteInput(base + 350L)
                } finally {
                    done.countDown()
                }
            }.apply { setUncaughtExceptionHandler { _, err -> failure.compareAndSet(null, err) } }
            val closer = Thread {
                try {
                    startGate.await(2, TimeUnit.SECONDS)
                    // Either closes stale base (>300ms -> discarded=1) or closes the fresh
                    // base+350 arm (10ms -> accepted=1). In every interleaving discarded MUST be 1.
                    tracker.recordServerFrame(base + 360L)
                } finally {
                    done.countDown()
                }
            }.apply { setUncaughtExceptionHandler { _, err -> failure.compareAndSet(null, err) } }
            expirer.start()
            closer.start()
            startGate.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(1, tracker.discarded())
            assertTrue(tracker.accepted() in 0..1)
            tracker.reset()
        }
        assertEquals(iterations, totalArmed)
    }

    @Test
    fun presentLagTrackerComputesRollingMedianAndIgnoresOutliers() {
        val tracker = PresentLagTracker(sampleWindow = 5, maxLagMs = 1_000L)
        assertEquals(-1, tracker.medianMs())

        tracker.record(-5L)
        tracker.record(1_001L)
        assertEquals(-1, tracker.medianMs())

        listOf(30L, 10L, 50L, 20L, 40L).forEach(tracker::record)
        assertEquals(30, tracker.medianMs())
        // Reusing the scratch buffer across repeated reads must return the same median.
        assertEquals(30, tracker.medianMs())

        // Overwrite oldest entries with lower values: ring becomes [5, 8, 50, 20, 40] -> median 20.
        tracker.record(5L)
        tracker.record(8L)
        assertEquals(20, tracker.medianMs())

        tracker.reset()
        assertEquals(-1, tracker.medianMs())
    }
}
