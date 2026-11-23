package com.hanfengruyue.pocketrdp.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdpInputValidationTest {
    @Test
    fun framebufferDimensionsRejectInvalidAndExcessiveAllocations() {
        assertFalse(isSafeFramebufferSize(0, 1080))
        assertFalse(isSafeFramebufferSize(8193, 1))
        assertFalse(isSafeFramebufferSize(8192, 8192))
        assertTrue(isSafeFramebufferSize(4096, 4096))
        assertTrue(isSafeFramebufferSize(3840, 2160))
    }

    @Test
    fun clipboardTruncationDoesNotSplitSurrogatePair() {
        val text = "ab\uD83D\uDE00cd"

        assertEquals("ab", truncateUtf16Safely(text, 3))
        assertEquals("ab\uD83D\uDE00", truncateUtf16Safely(text, 4))
        assertEquals(text, truncateUtf16Safely(text, 100))
    }

    @Test
    fun certificateFingerprintRequiresExactlySha256Hex() {
        val valid = "ab".repeat(32)

        assertEquals(valid, normalizeCertificateFingerprint("SHA256:${valid.chunked(2).joinToString(":")}"))
        assertEquals("", normalizeCertificateFingerprint(valid.dropLast(1)))
        assertEquals("", normalizeCertificateFingerprint("${valid.dropLast(1)}z"))
    }

    @Test
    fun certificatePinIsBoundToConfiguredEndpoint() {
        assertTrue(certificateEndpointMatches("rdp.example", 3389, "RDP.EXAMPLE", 3389))
        assertTrue(certificateEndpointMatches("[2001:db8::1]", 3389, "2001:DB8::1", 3389))
        assertFalse(certificateEndpointMatches("rdp.example", 3389, "redirect.example", 3389))
        assertFalse(certificateEndpointMatches("rdp.example", 3389, "rdp.example", 3390))
        assertFalse(certificateEndpointMatches(null, 3389, "rdp.example", 3389))
    }

    @Test
    fun permanentAuthenticationFailuresDoNotAutoReconnect() {
        assertFalse(isRetryableRdpFailure(0x0002_0009L))
        assertFalse(isRetryableRdpFailure(0x0002_0015L))
        assertFalse(isRetryableRdpFailure(0x0002_0018L))
        assertFalse(isRetryableRdpFailure(0x0002_001BL))
        assertFalse(isRetryableRdpFailure(0x0002_001EL))
        assertFalse(isRetryableRdpFailure(0x0002_001FL))
    }

    @Test
    fun intentionalDisconnectAndLogoffErrInfoCodesDoNotAutoReconnect() {
        val nonRetryableErrInfo = listOf(
            0x0001_0001L,
            0x0001_0002L,
            0x0001_0003L,
            0x0001_0004L,
            0x0001_0005L,
            0x0001_0007L,
            0x0001_0009L,
            0x0001_000AL,
            0x0001_000BL,
            0x0001_000CL,
        )
        nonRetryableErrInfo.forEach { code ->
            assertFalse("ERRINFO code 0x${code.toString(16)} should not auto-reconnect", isRetryableRdpFailure(code))
        }
        assertTrue(isRetryableRdpFailure(0x0001_0006L))
    }

    @Test
    fun transientAndUnknownFailuresRemainRetryable() {
        assertTrue(isRetryableRdpFailure(0x0002_000DL))
        assertTrue(isRetryableRdpFailure(0x0002_0011L))
        assertTrue(isRetryableRdpFailure(0x0002_001DL))
        assertTrue(isRetryableRdpFailure(0L))
        assertTrue(isRetryableRdpFailure(0x0001_0015L))
    }

    @Test
    fun disablingAutoReconnectPreventsRetryingTransientFailures() {
        assertTrue(
            shouldAutoReconnectRdpFailure(
                autoReconnectEnabled = true,
                credentialsCancelled = false,
                errorCode = 0L,
            ),
        )
        assertFalse(
            shouldAutoReconnectRdpFailure(
                autoReconnectEnabled = false,
                credentialsCancelled = false,
                errorCode = 0L,
            ),
        )
        assertFalse(
            shouldAutoReconnectRdpFailure(
                autoReconnectEnabled = true,
                credentialsCancelled = true,
                errorCode = 0L,
            ),
        )
        assertFalse(
            shouldAutoReconnectRdpFailure(
                autoReconnectEnabled = true,
                credentialsCancelled = false,
                errorCode = 0x0002_0009L,
            ),
        )
    }

    @Test
    fun disconnectReasonSuppressesFreeRdpZeroCodeSuccessString() {
        assertEquals(
            null,
            disconnectReasonForError(errorCode = 0L, errorString = "Success.", retryable = false),
        )
        assertEquals(
            null,
            disconnectReasonForError(errorCode = 0x0001_0001L, errorString = "Admin disconnect", retryable = true),
        )
        assertEquals(
            "Admin disconnect",
            disconnectReasonForError(errorCode = 0x0001_0001L, errorString = " Admin disconnect ", retryable = false),
        )
        assertEquals(
            null,
            disconnectReasonForError(errorCode = 0x0001_0001L, errorString = "   ", retryable = false),
        )
    }

    @Test
    fun fatalCertificateFailureCannotBeDowngradedByAnEarlierPrompt() {
        assertEquals(
            CertificateTerminalDisposition.NONE,
            certificateTerminalDisposition(
                promptOutstanding = false,
                fatalFailureOutstanding = false,
            ),
        )
        assertEquals(
            CertificateTerminalDisposition.PROMPT,
            certificateTerminalDisposition(
                promptOutstanding = true,
                fatalFailureOutstanding = false,
            ),
        )
        assertEquals(
            CertificateTerminalDisposition.FATAL,
            certificateTerminalDisposition(
                promptOutstanding = false,
                fatalFailureOutstanding = true,
            ),
        )
        assertEquals(
            CertificateTerminalDisposition.FATAL,
            certificateTerminalDisposition(
                promptOutstanding = true,
                fatalFailureOutstanding = true,
            ),
        )
    }
}
