package com.hanfengruyue.pocketrdp.feature.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartPolicyTest {
    @Test
    fun sameConnectionDoesNotRestartWhileConnectingOrConnected() {
        assertFalse(shouldLaunchConnect(state(status = SessionConnectionStatus.Connecting), CONNECTION_ID))
        assertFalse(shouldLaunchConnect(state(status = SessionConnectionStatus.Connected), CONNECTION_ID))
    }

    @Test
    fun sameConnectionRestartsAfterDisconnectedOrFailed() {
        assertTrue(
            shouldLaunchConnect(
                state(status = SessionConnectionStatus.Disconnected(reason = "user")),
                CONNECTION_ID,
            ),
        )
        assertTrue(
            shouldLaunchConnect(
                state(status = SessionConnectionStatus.Disconnected(reason = null)),
                CONNECTION_ID,
            ),
        )
        assertTrue(
            shouldLaunchConnect(
                state(status = SessionConnectionStatus.Failed("freerdp_connect returned false")),
                CONNECTION_ID,
            ),
        )
    }

    @Test
    fun differentConnectionAlwaysStarts() {
        assertTrue(shouldLaunchConnect(state(status = SessionConnectionStatus.Connected), CONNECTION_ID + 1))
    }

    @Test
    fun invalidConnectionNeverStarts() {
        assertFalse(shouldLaunchConnect(state(status = SessionConnectionStatus.Idle), 0L))
    }

    private fun state(status: SessionConnectionStatus): SessionUiState =
        SessionUiState(
            connectionId = CONNECTION_ID,
            status = status,
        )

    private companion object {
        const val CONNECTION_ID = 42L
    }
}
