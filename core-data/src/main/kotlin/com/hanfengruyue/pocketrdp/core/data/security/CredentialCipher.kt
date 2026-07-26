package com.hanfengruyue.pocketrdp.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES/GCM encryption of credentials using a key bound to the Android Keystore.
 *
 * The key is created lazily on first use and never leaves the secure hardware (when present).
 * Each encrypt() call generates a fresh IV; the IV is returned alongside the ciphertext and must
 * be persisted to allow decrypt().
 */
@Singleton
class CredentialCipher @Inject constructor() {

    data class Sealed(val ciphertext: ByteArray, val iv: ByteArray)

    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return Sealed(ciphertext = ct, iv = cipher.iv)
    }

    fun decrypt(sealed: Sealed, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, sealed.iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    fun encryptString(plain: String, aad: ByteArray = ByteArray(0)): Sealed {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        return try {
            encrypt(bytes, aad)
        } finally {
            bytes.fill(0)
        }
    }

    fun decryptToString(sealed: Sealed, aad: ByteArray = ByteArray(0)): String {
        val bytes = decrypt(sealed, aad)
        return try {
            bytes.toString(Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Key creation must be serialized. Two first-use saves racing through an empty keystore could
     * otherwise both generate the same alias; the second generation may replace the key after the
     * first caller has already encrypted a credential, making that ciphertext permanently
     * undecryptable.
     */
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocketrdp_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
