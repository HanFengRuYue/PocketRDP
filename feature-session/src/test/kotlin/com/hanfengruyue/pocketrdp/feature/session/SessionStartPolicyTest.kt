package com.hanfengruyue.pocketrdp.feature.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartPolicyTest {
    @Test
    fun `text input is accepted only for a live connected session`() {
        assertFalse(canQueueTextInput(SessionConnectionStatus.Idle))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Connecting))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Disconnected("network")))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Failed("auth")))
        assertTrue(canQueueTextInput(SessionConnectionStatus.Connected))
    }

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
    fun lifecycleResumeDoesNotRestartStoppedOrFailedConnection() {
        assertFalse(
            shouldLaunchConnect(
                state(status = SessionConnectionStatus.Disconnected(reason = null)),
                CONNECTION_ID,
                allowStoppedSessionRestart = false,
            ),
        )
        assertFalse(
            shouldLaunchConnect(
                state(status = SessionConnectionStatus.Failed("authentication failed")),
                CONNECTION_ID,
                allowStoppedSessionRestart = false,
            ),
        )
        assertTrue(
            shouldLaunchConnect(
                state(status = SessionConnectionStatus.Idle),
                CONNECTION_ID,
                allowStoppedSessionRestart = false,
            ),
        )
    }

    @Test
    fun allFilesAccessGateIsRestartedOnlyByItsDedicatedPermissionPath() {
        val gated = state(status = SessionConnectionStatus.Idle).copy(allFilesAccessRequired = true)

        assertFalse(shouldLaunchConnect(gated, CONNECTION_ID))
        assertFalse(
            shouldLaunchConnect(
                gated,
                CONNECTION_ID,
                allowStoppedSessionRestart = false,
            ),
        )
    }

    @Test
    fun certificateRejectionMessageSurvivesRegistryDisconnectEvent() {
        val rejected = SessionConnectionStatus.Failed("certificate rejected")

        assertEquals(rejected, statusAfterDisconnect(rejected, reason = "user"))
        assertEquals(
            SessionConnectionStatus.Disconnected(reason = null),
            statusAfterDisconnect(rejected, reason = null),
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

    @Test
    fun textCapDoesNotSplitASurrogatePair() {
        assertEquals("ab", truncateClipboardText("ab\uD83D\uDE00c", 3))
        assertEquals("ab\uD83D\uDE00", truncateClipboardText("ab\uD83D\uDE00c", 4))
    }

    @Test
    fun windowsClipboardLineEndingsAreNormalizedWithoutDoublingCarriageReturns() {
        assertEquals("a\r\nb\r\nc\r\nd", toWindowsClipboardText("a\r\nb\nc\rd"))
    }

    @Test
    fun monitorLayoutRetryWaitsForAFullViewport() {
        assertFalse(shouldDispatchMonitorLayoutRetry(imeVisible = true, width = 1920, height = 540))
        assertFalse(shouldDispatchMonitorLayoutRetry(imeVisible = false, width = 0, height = 1080))
        assertTrue(shouldDispatchMonitorLayoutRetry(imeVisible = false, width = 1920, height = 1080))
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
