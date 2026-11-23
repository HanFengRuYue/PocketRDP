package com.hanfengruyue.pocketrdp.core.data.security

import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialCipherTest {

    @Test
    fun cachesLoadedKeyAcrossEncryptAndDecryptCalls() {
        val loadCount = AtomicInteger(0)
        val key = generateAesKey()
        val cipher = CredentialCipher {
            loadCount.incrementAndGet()
            key
        }
        val aad = "host:3389".toByteArray(Charsets.UTF_8)

        val sealed = cipher.encryptString("s3cr3t!", aad)
        val plain = cipher.decryptToString(sealed, aad)

        assertEquals("s3cr3t!", plain)
        assertEquals(1, loadCount.get())
    }

    @Test
    fun reloadsCachedKeyWhenCipherInitFailsWithInvalidKey() {
        val loadCount = AtomicInteger(0)
        val brokenKey = SecretKeySpec(ByteArray(3), "AES")
        val validKey = generateAesKey()
        val cipher = CredentialCipher {
            if (loadCount.incrementAndGet() == 1) brokenKey else validKey
        }

        // First call caches brokenKey and fails on cipher.init; next call hits the cached stale
        // key, catches GeneralSecurityException, clears cache, reloads validKey, and succeeds.
        runCatching { cipher.encryptString("first") }
        val sealed = cipher.encryptString("recovered")
        assertEquals("recovered", cipher.decryptToString(sealed))
        assertEquals(2, loadCount.get())
    }

    private fun generateAesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(AES_KEY_BITS) }.generateKey()

    private companion object {
        const val AES_KEY_BITS = 256
    }
}
