package com.hanfengruyue.pocketrdp.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
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
class CredentialCipher internal constructor(
    private val keyLoader: () -> SecretKey,
) {
    @Inject
    constructor() : this(::loadOrCreateAndroidKeyStoreKey)

    @Volatile
    private var cachedKey: SecretKey? = null

    data class Sealed(val ciphertext: ByteArray, val iv: ByteArray)

    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): Sealed {
        val cipher = initCipher { key ->
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        }
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return Sealed(ciphertext = ct, iv = cipher.iv)
    }

    fun decrypt(sealed: Sealed, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = initCipher { key ->
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            }
        }
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

    private fun initCipher(initBlock: (SecretKey) -> Cipher): Cipher {
        val cached = cachedKey
        if (cached != null) {
            try {
                return initBlock(cached)
            } catch (ignored: GeneralSecurityException) {
                clearCachedKey(cached)
            }
        }
        return initBlock(getOrCreateKey())
    }

    /**
     * Key creation must be serialized. Two first-use saves racing through an empty keystore could
     * otherwise both generate the same alias; the second generation may replace the key after the
     * first caller has already encrypted a credential, making that ciphertext permanently
     * undecryptable.
     */
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: keyLoader().also { cachedKey = it }
        }
    }

    @Synchronized
    private fun clearCachedKey(staleKey: SecretKey) {
        if (cachedKey === staleKey) {
            cachedKey = null
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocketrdp_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        private fun loadOrCreateAndroidKeyStoreKey(): SecretKey {
            val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
            generator.init(spec)
            return generator.generateKey()
        }
    }
}
