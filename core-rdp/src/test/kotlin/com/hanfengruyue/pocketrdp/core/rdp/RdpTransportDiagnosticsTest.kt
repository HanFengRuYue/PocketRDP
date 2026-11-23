package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpTransportDiagnosticsTest {
    @Test
    fun failureDiagnosticContainsStageAndNumericErrorsWithoutEndpointData() {
        val snapshot = RdpTransportSnapshot(
            knownVersion = true,
            phase = RdpTransportPhase.RECONNECTING_TCP,
            requestedMask = RdpTransportSnapshot.MASK_UDP2,
            readyMask = RdpTransportSnapshot.MASK_UDP2,
            flags = RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP or
                RdpTransportSnapshot.FLAG_RECONNECT_REQUESTED,
            securityProtocol = 2,
            failure = RdpTransportFailure.RDPEMT_REJECTED,
            tunnelHresult = 0x80004004,
            socketError = 111,
            reliable = RdpUdpTunnelSnapshot(
                kind = RdpUdpKind.UDP2,
                state = RdpUdpTunnelState.FAILED,
                protocolVersion = 0x0101,
            ),
        )

        val diagnostic = snapshot.controlDiagnostic()

        assertTrue(diagnostic.contains("phase=RECONNECTING_TCP"))
        assertTrue(diagnostic.contains("requested=UDP2"))
        assertTrue(diagnostic.contains("flags=downgraded-to-tcp|reconnect-requested"))
        assertTrue(diagnostic.contains("failure=RDPEMT_REJECTED(14)"))
        assertTrue(diagnostic.contains("hresult=0x80004004"))
        assertTrue(diagnostic.contains("socketError=111"))
        assertTrue(diagnostic.contains("reliable=UDP2/FAILED/version=0x0101"))
        assertFalse(diagnostic.contains("host", ignoreCase = true))
        assertFalse(diagnostic.contains("cookie", ignoreCase = true))
        assertFalse(diagnostic.contains("certificate", ignoreCase = true))
    }

    @Test
    fun controlDiagnosticIsStableWhenOnlyTrafficCountersChange() {
        val first = activeSnapshot(receivedBytes = 100, sentPackets = 2)
        val later = activeSnapshot(receivedBytes = 200, sentPackets = 5)

        assertEquals(first.controlDiagnostic(), later.controlDiagnostic())
        assertTrue(first.hasSameControlStateAs(later))
        assertTrue(later.trafficDiagnostic().contains("rxBytes=200"))
        assertTrue(later.trafficDiagnostic().contains("txPackets=5"))
    }

    @Test
    fun hasSameControlStateDetectsEveryControlFieldTransition() {
        val base = activeSnapshot(receivedBytes = 100, sentPackets = 2)
        assertFalse(base.hasSameControlStateAs(null))
        assertFalse(base.hasSameControlStateAs(base.copy(phase = RdpTransportPhase.TCP)))
        assertFalse(base.hasSameControlStateAs(base.copy(requestedMask = RdpTransportSnapshot.MASK_UDP_R)))
        assertFalse(base.hasSameControlStateAs(base.copy(readyMask = 0L)))
        assertFalse(base.hasSameControlStateAs(base.copy(activeMask = 0L)))
        assertFalse(base.hasSameControlStateAs(base.copy(softSyncMask = 0L)))
        assertFalse(base.hasSameControlStateAs(base.copy(flags = RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP)))
        assertFalse(base.hasSameControlStateAs(base.copy(securityProtocol = 2L)))
        assertFalse(base.hasSameControlStateAs(base.copy(routedDvcCount = 3L)))
        assertFalse(base.hasSameControlStateAs(base.copy(failure = RdpTransportFailure.TUNNEL_POLL)))
        assertFalse(base.hasSameControlStateAs(base.copy(tunnelHresult = 0x80004004)))
        assertFalse(base.hasSameControlStateAs(base.copy(socketError = 110L)))
        assertFalse(
            base.hasSameControlStateAs(
                base.copy(reliable = base.reliable.copy(state = RdpUdpTunnelState.FAILED)),
            ),
        )
        assertFalse(
            base.hasSameControlStateAs(
                base.copy(lossy = base.lossy.copy(kind = RdpUdpKind.UDP_L)),
            ),
        )
    }

    @Test
    fun failedTunnelCountsAsRequestEvidenceAfterRequestedMaskWasCleared() {
        val snapshot = RdpTransportSnapshot(
            knownVersion = true,
            phase = RdpTransportPhase.TCP,
            requestedMask = 0,
            failure = RdpTransportFailure.SYN_TIMEOUT,
            reliable = RdpUdpTunnelSnapshot(
                kind = RdpUdpKind.UDP_R,
                state = RdpUdpTunnelState.FAILED,
            ),
        )

        assertTrue(snapshot.hasTransportEvidence())
    }

    private fun activeSnapshot(receivedBytes: Long, sentPackets: Long) = RdpTransportSnapshot(
        knownVersion = true,
        phase = RdpTransportPhase.ACTIVE,
        requestedMask = RdpTransportSnapshot.MASK_UDP2,
        readyMask = RdpTransportSnapshot.MASK_UDP2,
        activeMask = RdpTransportSnapshot.MASK_UDP2,
        softSyncMask = RdpTransportSnapshot.MASK_UDP2,
        reliable = RdpUdpTunnelSnapshot(
            kind = RdpUdpKind.UDP2,
            state = RdpUdpTunnelState.ACTIVE,
            protocolVersion = 0x0101,
            receivedBytes = receivedBytes,
            sentPackets = sentPackets,
        ),
    )
}
