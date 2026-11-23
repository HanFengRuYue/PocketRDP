package com.hanfengruyue.pocketrdp.feature.session

import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportPhase
import com.hanfengruyue.pocketrdp.core.rdp.RdpTransportSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartPolicyTest {
    @Test
    fun `text input is accepted only for a live connected session`() {
        assertFalse(canQueueTextInput(SessionConnectionStatus.Idle))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Connecting))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Reconnecting))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Disconnected("network")))
        assertFalse(canQueueTextInput(SessionConnectionStatus.Failed("auth")))
        assertTrue(canQueueTextInput(SessionConnectionStatus.Connected))
    }

    @Test
    fun sameConnectionDoesNotRestartWhileConnectingReconnectingOrConnected() {
        assertFalse(shouldLaunchConnect(state(status = SessionConnectionStatus.Connecting), CONNECTION_ID))
        assertFalse(shouldLaunchConnect(state(status = SessionConnectionStatus.Reconnecting), CONNECTION_ID))
        assertFalse(shouldLaunchConnect(state(status = SessionConnectionStatus.Connected), CONNECTION_ID))
    }

    @Test
    fun pendingCredentialsPromptBlocksDuplicateRestart() {
        val prompted = state(status = SessionConnectionStatus.Idle).copy(
            pendingCredentialsPrompt = CredentialsPromptState(
                host = "192.168.1.2",
                initialUsername = "admin",
                initialDomain = "",
            ),
        )

        assertFalse(shouldLaunchConnect(prompted, CONNECTION_ID))
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
    fun singleEnterDoesNotTriggerClipboardPasteWhileMultilineAndLongTextDo() {
        assertFalse(shouldPasteTextViaClipboard("\n", clipboardSyncEnabled = true))
        assertFalse(shouldPasteTextViaClipboard("hello", clipboardSyncEnabled = true))
        assertTrue(shouldPasteTextViaClipboard("line1\nline2", clipboardSyncEnabled = true))
        assertTrue(shouldPasteTextViaClipboard("a".repeat(201), clipboardSyncEnabled = true))
        assertFalse(shouldPasteTextViaClipboard("line1\nline2", clipboardSyncEnabled = false))
    }

    @Test
    fun connectingOverlayShowsOnlyWhileConnectingReconnectingOrAwaitingFirstFrame() {
        assertTrue(shouldShowConnectingOverlay(SessionConnectionStatus.Connecting, hasPublishedFrame = false))
        assertTrue(shouldShowConnectingOverlay(SessionConnectionStatus.Connecting, hasPublishedFrame = true))
        assertTrue(shouldShowConnectingOverlay(SessionConnectionStatus.Reconnecting, hasPublishedFrame = false))
        assertTrue(shouldShowConnectingOverlay(SessionConnectionStatus.Reconnecting, hasPublishedFrame = true))
        assertTrue(shouldShowConnectingOverlay(SessionConnectionStatus.Connected, hasPublishedFrame = false))
        assertFalse(shouldShowConnectingOverlay(SessionConnectionStatus.Connected, hasPublishedFrame = true))
        assertFalse(shouldShowConnectingOverlay(SessionConnectionStatus.Idle, hasPublishedFrame = false))
        assertFalse(shouldShowConnectingOverlay(SessionConnectionStatus.Idle, hasPublishedFrame = true))
        assertFalse(
            shouldShowConnectingOverlay(
                SessionConnectionStatus.Disconnected("user"),
                hasPublishedFrame = false,
            ),
        )
        assertFalse(
            shouldShowConnectingOverlay(
                SessionConnectionStatus.Failed("error"),
                hasPublishedFrame = false,
            ),
        )
    }

    @Test
    fun terminalOverlayShowsOnlyForStoppedStatesWithoutModalPrompt() {
        assertTrue(shouldShowTerminalStatusOverlay(SessionConnectionStatus.Failed("err"), false))
        assertTrue(shouldShowTerminalStatusOverlay(SessionConnectionStatus.Disconnected("user"), false))
        assertFalse(shouldShowTerminalStatusOverlay(SessionConnectionStatus.Failed("err"), true))
        assertFalse(shouldShowTerminalStatusOverlay(SessionConnectionStatus.Idle, false))
        assertFalse(shouldShowTerminalStatusOverlay(SessionConnectionStatus.Connecting, false))
        assertFalse(shouldShowTerminalStatusOverlay(SessionConnectionStatus.Connected, false))
    }

    @Test
    fun udpDowngradeObserverDetectsAllNativeFailureAndReconnectSignals() {
        assertFalse(isUdpDowngradeObserved(RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.ACTIVE)))
        assertTrue(
            isUdpDowngradeObserved(
                RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.UDP_FAILED),
            ),
        )
        assertTrue(
            isUdpDowngradeObserved(
                RdpTransportSnapshot(knownVersion = true, phase = RdpTransportPhase.RECONNECTING_TCP),
            ),
        )
        assertTrue(
            isUdpDowngradeObserved(
                RdpTransportSnapshot(
                    knownVersion = true,
                    flags = RdpTransportSnapshot.FLAG_DOWNGRADED_TO_TCP,
                ),
            ),
        )
        assertTrue(
            isUdpDowngradeObserved(
                RdpTransportSnapshot(
                    knownVersion = true,
                    flags = RdpTransportSnapshot.FLAG_RECONNECT_REQUESTED,
                ),
            ),
        )
    }

    @Test
    fun deferredMonitorLayoutFlushesWhenImeClosesWhileConnected() {
        assertTrue(
            shouldFlushDeferredMonitorLayout(
                dynamicResolutionEnabled = true,
                imeVisible = false,
                status = SessionConnectionStatus.Connected,
                pendingSize = 1920 to 1080,
                remoteSize = 1080 to 1920,
                resizeDeferred = true,
            ),
        )
        assertFalse(
            shouldFlushDeferredMonitorLayout(
                dynamicResolutionEnabled = true,
                imeVisible = true,
                status = SessionConnectionStatus.Connected,
                pendingSize = 1920 to 1080,
                remoteSize = 1080 to 1920,
                resizeDeferred = true,
            ),
        )
        assertFalse(
            shouldFlushDeferredMonitorLayout(
                dynamicResolutionEnabled = false,
                imeVisible = false,
                status = SessionConnectionStatus.Connected,
                pendingSize = 1920 to 1080,
                remoteSize = 1080 to 1920,
                resizeDeferred = true,
            ),
        )
    }

    @Test
    fun imeInsetSyncWaitsForKeyboardToActuallyShowBeforeDetectingExternalDismiss() {
        val opening = stepImeInsetSync(imeVisible = true, imeBottomPx = 0, wasShown = false)
        assertFalse(opening.wasShown)
        assertFalse(opening.shouldDismiss)

        val shown = stepImeInsetSync(imeVisible = true, imeBottomPx = 640, wasShown = opening.wasShown)
        assertTrue(shown.wasShown)
        assertFalse(shown.shouldDismiss)

        val dismissed = stepImeInsetSync(imeVisible = true, imeBottomPx = 0, wasShown = shown.wasShown)
        assertFalse(dismissed.wasShown)
        assertTrue(dismissed.shouldDismiss)
    }

    @Test
    fun monitorLayoutMatchHonorsAckToleranceAndRejectsInvalidDims() {
        assertTrue(isMonitorLayoutMatchingRemote(1920, 1080, 1920, 1080))
        assertTrue(isMonitorLayoutMatchingRemote(1920, 1080, 1918, 1082))
        assertFalse(isMonitorLayoutMatchingRemote(1920, 1080, 1900, 1080))
        assertFalse(isMonitorLayoutMatchingRemote(1920, 1080, 0, 1080))
        assertFalse(isMonitorLayoutMatchingRemote(0, 1080, 1920, 1080))
    }

    @Test
    fun monitorLayoutRetryWaitsForAFullViewport() {
        assertFalse(shouldDispatchMonitorLayoutRetry(imeVisible = true, width = 1920, height = 540))
        assertFalse(shouldDispatchMonitorLayoutRetry(imeVisible = false, width = 0, height = 1080))
        assertTrue(shouldDispatchMonitorLayoutRetry(imeVisible = false, width = 1920, height = 1080))
    }

    @Test
    fun autoReconnectPolicyRespectsSettingAndSessionGuards() {
        assertTrue(
            shouldScheduleAutoReconnect(
                autoReconnectEnabled = true,
                eventRetryable = true,
                hasReconnectParams = true,
                userInitiatedDisconnect = false,
                certificateDecisionPending = false,
            ),
        )
        assertFalse(
            shouldScheduleAutoReconnect(
                autoReconnectEnabled = false,
                eventRetryable = true,
                hasReconnectParams = true,
                userInitiatedDisconnect = false,
                certificateDecisionPending = false,
            ),
        )
        assertFalse(
            shouldScheduleAutoReconnect(
                autoReconnectEnabled = true,
                eventRetryable = false,
                hasReconnectParams = true,
                userInitiatedDisconnect = false,
                certificateDecisionPending = false,
            ),
        )
        assertFalse(
            shouldScheduleAutoReconnect(
                autoReconnectEnabled = true,
                eventRetryable = true,
                hasReconnectParams = false,
                userInitiatedDisconnect = false,
                certificateDecisionPending = false,
            ),
        )
        assertFalse(
            shouldScheduleAutoReconnect(
                autoReconnectEnabled = true,
                eventRetryable = true,
                hasReconnectParams = true,
                userInitiatedDisconnect = true,
                certificateDecisionPending = false,
            ),
        )
        assertFalse(
            shouldScheduleAutoReconnect(
                autoReconnectEnabled = true,
                eventRetryable = true,
                hasReconnectParams = true,
                userInitiatedDisconnect = false,
                certificateDecisionPending = true,
            ),
        )
    }

    @Test
    fun cancellingPendingAutoReconnectExitsReconnectingStateCleanly() {
        assertEquals(
            SessionConnectionStatus.Disconnected(reason = null),
            statusAfterAutoReconnectCancelled(
                currentStatus = SessionConnectionStatus.Reconnecting,
                lastError = null,
            ),
        )
        assertEquals(
            SessionConnectionStatus.Disconnected(reason = null),
            statusAfterAutoReconnectCancelled(
                currentStatus = SessionConnectionStatus.Reconnecting,
                lastError = "   ",
            ),
        )
        assertEquals(
            SessionConnectionStatus.Failed("network reset"),
            statusAfterAutoReconnectCancelled(
                currentStatus = SessionConnectionStatus.Reconnecting,
                lastError = "network reset",
            ),
        )
        assertEquals(
            SessionConnectionStatus.Connected,
            statusAfterAutoReconnectCancelled(
                currentStatus = SessionConnectionStatus.Connected,
                lastError = "stale error",
            ),
        )
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
