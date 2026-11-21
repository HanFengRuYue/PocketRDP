package com.hanfengruyue.pocketrdp.feature.session.ui

import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportPhase
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportLabelTest {
    @Test
    fun onlyActiveSoftSyncedTunnelsAppearInCombination() {
        val mask = RdpTransportSnapshot.MASK_UDP2 or RdpTransportSnapshot.MASK_UDP_L

        assertEquals(
            "TCP + UDP2 + UDP-L",
            transportCombinationLabel(
                RdpTransportSnapshot(
                    knownVersion = true,
                    phase = RdpTransportPhase.ACTIVE,
                    activeMask = mask,
                    softSyncMask = mask,
                ),
            ),
        )
        assertEquals(
            "TCP",
            transportCombinationLabel(
                RdpTransportSnapshot(
                    knownVersion = true,
                    phase = RdpTransportPhase.WAITING_SOFT_SYNC,
                    readyMask = mask,
                ),
            ),
        )
    }

    @Test
    fun totalLatencyCombinesControlAndDisplayWithoutDoubleCountingRtt() {
        assertEquals(68, computeTotalLatencyMs(controlLatencyMs = 60, presentLagMs = 8))
        assertEquals(60, computeTotalLatencyMs(controlLatencyMs = 60, presentLagMs = -1))
        assertEquals(8, computeTotalLatencyMs(controlLatencyMs = -1, presentLagMs = 8))
        assertNull(computeTotalLatencyMs(controlLatencyMs = -1, presentLagMs = -1))
    }

    @Test
    fun statusMenuMaxHeightUsesWindowRelativeCoordinatesInSplitScreen() {
        val maxHeightPx = computeStatusMenuMaxHeightPx(
            windowHeightPx = 1000,
            anchorTopInWindowPx = 24f,
            verticalOffsetPx = 108f,
            edgePaddingPx = 24f,
        )

        assertEquals(844f, maxHeightPx, 0f)
        assertEquals(
            1f,
            computeStatusMenuMaxHeightPx(
                windowHeightPx = 100,
                anchorTopInWindowPx = 90f,
                verticalOffsetPx = 50f,
                edgePaddingPx = 16f,
            ),
            0f,
        )
    }
}
