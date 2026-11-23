package com.hanfengruyue.pocketrdp.core.data.repository

import androidx.room.withTransaction
import com.hanfengruyue.pocketrdp.core.data.db.ConnectionDao
import com.hanfengruyue.pocketrdp.core.data.db.PocketRdpDatabase
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.security.CredentialCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val database: PocketRdpDatabase,
    private val dao: ConnectionDao,
    private val cipher: CredentialCipher,
) {
    fun observeAll(): Flow<List<ConnectionEntity>> = dao.observeAll()

    suspend fun findById(id: Long): ConnectionEntity? = dao.findById(id)

    suspend fun save(
        existing: ConnectionEntity?,
        draft: ConnectionSaveDraft,
    ): Long = database.withTransaction {
        // The edit screen can remain open while a live session updates the certificate pin,
        // last-used timestamp, or legacy credential binding. Re-read the authoritative row inside
        // the same write transaction used below so a stale UI snapshot cannot roll any of those
        // security/runtime fields back. A row deleted while it was being edited stays deleted.
        val persistedExisting = existing?.let { stale ->
            dao.findById(stale.id)
                ?: error("Connection ${stale.id} was deleted while it was being edited")
        }
        validateConnectionDraft(draft)
        val newAad = credentialAad(draft.host, draft.port, draft.username, draft.domain)
        val sealed = sealPassword(persistedExisting, draft, newAad)
        val baseEntity = persistedExisting
            ?: ConnectionEntity(name = draft.name, host = draft.host, username = draft.username)
        val entity = baseEntity.copy(
            name = draft.name,
            host = draft.host,
            port = draft.port,
            username = draft.username,
            domain = draft.domain,
            passwordCipher = sealed.ciphertext,
            passwordIv = sealed.iv,
            passwordAadVersion = CREDENTIAL_AAD_VERSION,
            colorDepth = draft.colorDepth,
            useH264 = draft.useH264,
            preferAvc420 = draft.preferAvc420,
            // H.264 is transported by rdpgfx; normalize stale or programmatic callers that provide
            // the impossible useH264=true/useGfx=false combination.
            useGfx = draft.useGfx || draft.useH264,
            dynamicResolution = draft.dynamicResolution,
            dynamicResMax = draft.dynamicResMax,
            useMultitransport = draft.useMultitransport,
            redirectClipboard = draft.redirectClipboard,
            redirectFiles = draft.redirectFiles,
            soundMode = draft.soundMode,
            desktopScaleFactor = draft.desktopScaleFactor,
            customWidth = draft.customWidth,
            customHeight = draft.customHeight,
            defaultInputMode = draft.defaultInputMode,
            targetFrameRate = draft.targetFrameRate,
            performanceFlags = draft.performanceFlags,
            // A pin belongs to one endpoint. Carrying it to an edited host/port either bricks the
            // new connection or, worse, makes the trust decision look like it applies there.
            certThumbSha256 = persistedExisting?.certThumbSha256
                ?.takeIf {
                    persistedExisting.host.equals(draft.host, ignoreCase = true) &&
                        persistedExisting.port == draft.port
                },
        )
        dao.upsert(entity)
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun touchLastUsed(id: Long) = dao.touchLastUsed(id, System.currentTimeMillis())

    suspend fun setCertThumbprintIfEndpointMatches(
        id: Long,
        expectedHost: String,
        expectedPort: Int,
        thumb: String,
    ): Boolean {
        val canonical = thumb.trim().replace(":", "").lowercase(Locale.ROOT)
        require(canonical.length == SHA256_HEX_CHARS && canonical.all(Char::isHexDigit)) {
            "certificate fingerprint must be SHA-256"
        }
        return database.withTransaction {
            val current = dao.findById(id) ?: return@withTransaction false
            if (!sameCertificateEndpoint(current.host, current.port, expectedHost, expectedPort)) {
                return@withTransaction false
            }
            dao.setCertThumbprint(id, canonical)
            true
        }
    }

    suspend fun decryptPassword(entity: ConnectionEntity): String = withContext(Dispatchers.IO) {
        if (entity.passwordCipher.isEmpty()) return@withContext ""
        val plain = decryptPasswordValue(entity)
        if (entity.passwordAadVersion == LEGACY_CREDENTIAL_AAD_VERSION) {
            val rebound = cipher.encryptString(
                plain,
                credentialAad(entity.host, entity.port, entity.username, entity.domain),
            )
            dao.upgradeLegacyCredentialBinding(entity.id, rebound.ciphertext, rebound.iv)
        }
        plain
    }

    private fun sealPassword(
        persistedExisting: ConnectionEntity?,
        draft: ConnectionSaveDraft,
        newAad: ByteArray,
    ): CredentialCipher.Sealed {
        val identityUnchanged = persistedExisting != null &&
            persistedExisting.host.equals(draft.host, ignoreCase = true) &&
            persistedExisting.port == draft.port &&
            persistedExisting.username == draft.username &&
            persistedExisting.domain == draft.domain
        return when {
            persistedExisting == null || draft.plainPassword.isNotEmpty() ->
                cipher.encryptString(draft.plainPassword, newAad)
            persistedExisting.passwordCipher.isEmpty() -> cipher.encryptString("", newAad)
            identityUnchanged && persistedExisting.passwordAadVersion == CREDENTIAL_AAD_VERSION ->
                CredentialCipher.Sealed(
                    persistedExisting.passwordCipher,
                    persistedExisting.passwordIv,
                )
            else -> {
                val retainedPassword = decryptPasswordValue(persistedExisting)
                cipher.encryptString(retainedPassword, newAad)
            }
        }
    }

    private fun decryptPasswordValue(entity: ConnectionEntity): String {
        val sealed = CredentialCipher.Sealed(entity.passwordCipher, entity.passwordIv)
        return when (entity.passwordAadVersion) {
            LEGACY_CREDENTIAL_AAD_VERSION -> cipher.decryptToString(sealed)
            CREDENTIAL_AAD_VERSION -> cipher.decryptToString(
                sealed,
                credentialAad(entity.host, entity.port, entity.username, entity.domain),
            )
            else -> error("Unsupported credential AAD version ${entity.passwordAadVersion}")
        }
    }

    private fun credentialAad(host: String, port: Int, username: String, domain: String): ByteArray {
        val fields = listOf(host.lowercase(Locale.ROOT), username, domain)
            .map { it.toByteArray(Charsets.UTF_8) }
        val size = Integer.BYTES * (fields.size + 2) + fields.sumOf(ByteArray::size)
        return ByteBuffer.allocate(size).apply {
            putInt(CREDENTIAL_AAD_VERSION)
            putInt(port)
            fields.forEach { field ->
                putInt(field.size)
                put(field)
            }
        }.array()
    }

    private companion object {
        const val LEGACY_CREDENTIAL_AAD_VERSION = 0
        const val CREDENTIAL_AAD_VERSION = 1
        const val SHA256_HEX_CHARS = 64
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun sameCertificateEndpoint(
    expectedHost: String,
    expectedPort: Int,
    actualHost: String,
    actualPort: Int,
): Boolean =
    expectedPort == actualPort &&
        expectedHost.canonicalRdpHost() == actualHost.canonicalRdpHost()

private fun String.canonicalRdpHost(): String =
    trim()
        .removeSurrounding("[", "]")
        .trimEnd('.')
        .lowercase(Locale.ROOT)
