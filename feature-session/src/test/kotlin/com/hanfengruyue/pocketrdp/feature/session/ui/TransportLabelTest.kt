package com.hanfengruyue.pocketrdp.feature.session.ui

import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportPhase
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportSnapshot
import org.junit.Assert.assertEquals
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
}
