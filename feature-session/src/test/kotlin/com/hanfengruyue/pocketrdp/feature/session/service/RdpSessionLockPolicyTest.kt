package com.hanfengruyue.pocketrdp.feature.session.service

import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionInfo
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistrySnapshot
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpSessionLockPolicyTest {
    @Test
    fun noActiveSessionReleasesLocks() {
        assertFalse(shouldHoldSessionLocks(RdpSessionRegistrySnapshot()))
    }

    @Test
    fun connectingSessionKeepsLocks() {
        assertTrue(shouldHoldSessionLocks(snapshot(RdpSessionRuntimeState.CONNECTING)))
    }

    @Test
    fun connectedSessionKeepsLocks() {
        assertTrue(shouldHoldSessionLocks(snapshot(RdpSessionRuntimeState.CONNECTED)))
    }

    @Test
    fun reconnectingSessionKeepsLocks() {
        assertTrue(shouldHoldSessionLocks(snapshot(RdpSessionRuntimeState.RECONNECTING)))
    }

    private fun snapshot(state: RdpSessionRuntimeState): RdpSessionRegistrySnapshot =
        RdpSessionRegistrySnapshot(
            activeCount = 1,
            connectedCount = if (state == RdpSessionRuntimeState.CONNECTED) 1 else 0,
            connectingCount = if (state == RdpSessionRuntimeState.CONNECTING) 1 else 0,
            reconnectingCount = if (state == RdpSessionRuntimeState.RECONNECTING) 1 else 0,
            sessions = listOf(
                RdpSessionInfo(
                    connectionId = 42L,
                    displayName = "Test",
                    hostLabel = "example.test:3389",
                    state = state,
                ),
            ),
        )
}
