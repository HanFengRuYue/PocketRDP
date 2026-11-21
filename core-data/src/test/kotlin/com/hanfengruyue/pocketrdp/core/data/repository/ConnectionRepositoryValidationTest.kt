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
}
