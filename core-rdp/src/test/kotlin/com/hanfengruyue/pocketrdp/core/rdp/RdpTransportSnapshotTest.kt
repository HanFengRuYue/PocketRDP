package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpTransportSnapshotTest {
    @Test
    fun decodeReadsBothTunnelRecordsAtomically() {
        val raw = LongArray(RdpTransportSnapshot.FIELD_COUNT)
        raw[0] = RdpTransportSnapshot.VERSION
        raw[1] = RdpTransportPhase.ACTIVE.wireValue
        raw[2] = 7
        raw[3] = 6
        raw[4] = RdpTransportSnapshot.MASK_UDP2 or RdpTransportSnapshot.MASK_UDP_L
        raw[5] = raw[4]
        raw[6] = RdpTransportSnapshot.FLAG_RECONNECT_REQUESTED
        raw[8] = 3
        raw[12] = RdpUdpKind.UDP2.mask
        raw[13] = RdpUdpTunnelState.ACTIVE.wireValue
        raw[14] = 0x101
        raw[15] = 1234
        raw[19] = 9
        raw[22] = 18_000
        raw[24] = RdpUdpKind.UDP_L.mask
        raw[25] = RdpUdpTunnelState.ACTIVE.wireValue
        raw[26] = 2
        raw[28] = 5678
        raw[32] = 4

        val decoded = RdpTransportSnapshot.decode(raw)

        assertTrue(decoded.knownVersion)
        assertTrue(decoded.udpActive)
        assertTrue(decoded.reconnectRequested)
        assertEquals(6, decoded.activeMask)
        assertEquals(RdpUdpKind.UDP2, decoded.reliable.kind)
        assertEquals(1234, decoded.reliable.receivedBytes)
        assertEquals(9, decoded.reliable.retransmits)
        assertEquals(RdpUdpKind.UDP_L, decoded.lossy.kind)
        assertEquals(5678, decoded.lossy.sentBytes)
        assertEquals(4, decoded.lossy.fecRecovered)
    }

    @Test
    fun incompatibleSnapshotFallsBackSafelyToTcpUnknown() {
        val wrongSize = RdpTransportSnapshot.decode(LongArray(8))
        val wrongVersion = RdpTransportSnapshot.decode(LongArray(36).also { it[0] = 99 })

        assertFalse(wrongSize.knownVersion)
        assertEquals(RdpTransportPhase.TCP, wrongSize.phase)
        assertFalse(wrongVersion.knownVersion)
        assertEquals(0, wrongVersion.activeMask)
    }
}
