package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpSessionRegistrySnapshotTest {
    @Test
    fun activeConnectionIdsIncludesOnlyRealConnectionIds() {
        val snapshot = RdpSessionRegistrySnapshot(
            activeCount = 3,
            connectedCount = 1,
            connectingCount = 1,
            reconnectingCount = 1,
            sessions = listOf(
                RdpSessionInfo(
                    connectionId = 7L,
                    displayName = "NAS",
                    hostLabel = "10.0.0.2:3389",
                    state = RdpSessionRuntimeState.CONNECTED,
                ),
                RdpSessionInfo(
                    connectionId = 0L,
                    displayName = "",
                    hostLabel = "",
                    state = RdpSessionRuntimeState.CONNECTING,
                ),
                RdpSessionInfo(
                    connectionId = 9L,
                    displayName = "Desktop",
                    hostLabel = "10.0.0.3:3389",
                    state = RdpSessionRuntimeState.RECONNECTING,
                ),
            ),
        )

        assertEquals(setOf(7L, 9L), snapshot.activeConnectionIds)
        assertTrue(snapshot.hasActiveSessions)
    }
}
