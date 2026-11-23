package com.hanfengruyue.pocketrdp.core.data.repository

import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRepositoryValidationTest {
    @Test
    fun certificateTrustIsBoundToCanonicalHostAndPort() {
        assertTrue(sameCertificateEndpoint("[2001:db8::1]", 3389, "2001:DB8::1", 3389))
        assertTrue(sameCertificateEndpoint("rdp.example.", 3389, "RDP.EXAMPLE", 3389))
        assertFalse(sameCertificateEndpoint("rdp.example", 3389, "other.example", 3389))
        assertFalse(sameCertificateEndpoint("rdp.example", 3389, "rdp.example", 3390))
    }

    @Test
    fun roomV8SharedFolderColumnRemainsNullableAndDefaultsNull() {
        val entity = ConnectionEntity(name = "Workstation", host = "192.0.2.10", username = "user")
        assertNull(entity.sharedFolderUri)
    }

    @Test
    fun validDraftPassesValidation() {
        validateConnectionDraft(sampleDraft())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankHostFailsValidation() {
        validateConnectionDraft(sampleDraft(host = "   "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeDesktopScaleFailsValidation() {
        validateConnectionDraft(sampleDraft(desktopScaleFactor = OUT_OF_RANGE_SCALE))
    }

    private fun sampleDraft(
        host: String = "192.0.2.10",
        desktopScaleFactor: Int = DEFAULT_SCALE,
    ): ConnectionSaveDraft = ConnectionSaveDraft(
        name = "Workstation",
        host = host,
        port = DEFAULT_PORT,
        username = "user",
        domain = "",
        plainPassword = "secret",
        colorDepth = 32,
        useH264 = true,
        preferAvc420 = true,
        useGfx = true,
        dynamicResolution = true,
        dynamicResMax = DEFAULT_DYNAMIC_MAX,
        useMultitransport = false,
        redirectClipboard = true,
        redirectFiles = false,
        soundMode = 2,
        desktopScaleFactor = desktopScaleFactor,
        customWidth = 0,
        customHeight = 0,
        defaultInputMode = 0,
        targetFrameRate = 0,
        performanceFlags = 0,
    )

    private companion object {
        const val DEFAULT_PORT = 3389
        const val DEFAULT_SCALE = 200
        const val OUT_OF_RANGE_SCALE = 400
        const val DEFAULT_DYNAMIC_MAX = 1080
    }
}
