package com.hanfengruyue.pocketrdp.core.data.repository

import org.junit.Assert.assertFalse
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
}
