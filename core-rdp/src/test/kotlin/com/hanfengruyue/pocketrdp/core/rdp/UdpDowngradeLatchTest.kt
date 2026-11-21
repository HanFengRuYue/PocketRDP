package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpDowngradeLatchTest {
    @Test
    fun unknownVersionAndHealthyPhasesDoNotTriggerDowngrade() {
        val latch = UdpDowngradeLatch()

        assertFalse(
            RdpTransportSnapshot(
                knownVersion = false,
                phase = RdpTransportPhase.RECONNECTING_TCP,
                flags = RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP,
            ).indicatesUdpDowngrade(),
        )
        assertFalse(latch.observe(RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.TCP)))
        assertFalse(latch.observe(RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.NEGOTIATING)))
        assertFalse(
            latch.observe(
                RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.WAITING_SOFT_SYNC),
            ),
        )
        assertFalse(
            latch.observe(
                RdpTransportSnapshot(
                    knownVersion = true,
                    phase = RdpTransportPhase.ACTIVE,
                    activeMask = RdpTransportSnapshot.MASK_UDP2,
                ),
            ),
        )
        assertFalse(latch.hasObservedDowngrade())
        assertTrue(latch.effectiveMultitransport(requested = true))
        assertFalse(latch.effectiveMultitransport(requested = false))
    }

    @Test
    fun downgradedToTcpFlagLatchesPermanentlyForSession() {
        val latch = UdpDowngradeLatch()
        val downgraded = RdpTransportSnapshot(
            knownVersion = true,
            phase = RdpTransportPhase.TCP,
            flags = RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP,
        )

        assertTrue(latch.observe(downgraded))
        assertTrue(latch.hasObservedDowngrade())
        assertFalse(latch.effectiveMultitransport(requested = true))

        // A later clean TCP snapshot must never clear the session downgrade latch.
        assertTrue(latch.observe(RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.TCP)))
        assertTrue(latch.hasObservedDowngrade())
        assertFalse(latch.effectiveMultitransport(requested = true))
    }

    @Test
    fun udpFailedAndReconnectingTcpPhasesLatchDowngrade() {
        val failedLatch = UdpDowngradeLatch()
        assertTrue(
            failedLatch.observe(
                RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.UDP_FAILED),
            ),
        )
        assertTrue(failedLatch.hasObservedDowngrade())

        val reconnectingLatch = UdpDowngradeLatch()
        assertTrue(
            reconnectingLatch.observe(
                RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.RECONNECTING_TCP),
            ),
        )
        assertTrue(reconnectingLatch.hasObservedDowngrade())
    }

    @Test
    fun reconnectRequestedFlagLatchesDowngrade() {
        val latch = UdpDowngradeLatch()

        assertTrue(
            latch.observe(
                RdpTransportSnapshot(
                    knownVersion = true,
                    phase = RdpTransportPhase.ACTIVE,
                    flags = RdpTransportSnapshot.FLAG_RECONNECT_REQUESTED,
                ),
            ),
        )
        assertTrue(latch.hasObservedDowngrade())
        assertFalse(latch.effectiveMultitransport(requested = true))
    }

    @Test
    fun transportTrackerRetainsDowngradeLatchAcrossAttemptResets() {
        val tracker = RdpTransportTracker(logTag = "test", elapsedRealtimeMs = { 0L })
        tracker.reset(requestedByApp = true)
        assertTrue(tracker.effectiveMultitransport(requested = true))

        assertTrue(
            tracker.observeDowngrade(
                RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.UDP_FAILED),
            ),
        )
        tracker.reset(requestedByApp = false)

        assertTrue(tracker.hasObservedDowngrade())
        assertFalse(tracker.effectiveMultitransport(requested = true))
    }
}
