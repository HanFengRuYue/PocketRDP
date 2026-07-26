package com.hanfengruyue.pocketrdp.core.data.repository

import androidx.room.withTransaction
import com.hanfengruyue.pocketrdp.core.data.db.ConnectionDao
import com.hanfengruyue.pocketrdp.core.data.db.PocketRdpDatabase
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
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
        name: String,
        host: String,
        port: Int,
        username: String,
        domain: String,
        plainPassword: String,
        colorDepth: Int,
        useH264: Boolean,
        preferAvc420: Boolean,
        useGfx: Boolean,
        dynamicResolution: Boolean,
        dynamicResMax: Int,
        useMultitransport: Boolean,
        redirectClipboard: Boolean,
        redirectFiles: Boolean,
        sharedFolderUri: String?,
        soundMode: Int,
        desktopScaleFactor: Int,
        customWidth: Int,
        customHeight: Int,
        defaultInputMode: Int,
        targetFrameRate: Int,
        performanceFlags: Int,
    ): Long = database.withTransaction {
        // The edit screen can remain open while a live session updates the certificate pin,
        // last-used timestamp, or legacy credential binding. Re-read the authoritative row inside
        // the same write transaction used below so a stale UI snapshot cannot roll any of those
        // security/runtime fields back. A row deleted while it was being edited stays deleted.
        val persistedExisting = existing?.let { stale ->
            dao.findById(stale.id)
                ?: error("Connection ${stale.id} was deleted while it was being edited")
        }
        validateCredentialField("name", name, MAX_CONNECTION_FIELD_CHARS)
        validateCredentialField("host", host, MAX_CONNECTION_FIELD_CHARS)
        validateCredentialField("username", username, MAX_CREDENTIAL_FIELD_CHARS)
        validateCredentialField("domain", domain, MAX_CREDENTIAL_FIELD_CHARS)
        validateCredentialField("password", plainPassword, MAX_CREDENTIAL_FIELD_CHARS)
        require(name.isNotBlank()) { "name must not be blank" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(port in 1..65535) { "port is outside the TCP range" }
        require(colorDepth in SUPPORTED_COLOR_DEPTHS) { "unsupported color depth" }
        require(dynamicResMax == 0 || dynamicResMax in MIN_REMOTE_EDGE..MAX_REMOTE_EDGE) {
            "dynamic resolution cap is outside the supported range"
        }
        require(soundMode in 0..2) { "unsupported audio mode" }
        require(desktopScaleFactor in 100..300) { "desktop scale is outside the supported range" }
        require(defaultInputMode in 0..1) { "unsupported input mode" }
        require(targetFrameRate == 0 || targetFrameRate in 1..MAX_TARGET_FRAME_RATE) {
            "target frame rate is outside the supported range"
        }
        require(
            (customWidth == 0 && customHeight == 0) ||
                (customWidth in MIN_REMOTE_EDGE..MAX_REMOTE_EDGE &&
                    customHeight in MIN_REMOTE_EDGE..MAX_REMOTE_EDGE &&
                    customWidth.toLong() * customHeight <= MAX_FRAMEBUFFER_PIXELS),
        ) { "custom framebuffer dimensions are unsafe" }
        sharedFolderUri?.let {
            validateCredentialField("shared folder URI", it, MAX_SHARED_FOLDER_URI_CHARS)
        }
        val newAad = credentialAad(host, port, username, domain)
        val identityUnchanged = persistedExisting != null &&
            persistedExisting.host.equals(host, ignoreCase = true) &&
            persistedExisting.port == port &&
            persistedExisting.username == username &&
            persistedExisting.domain == domain
        val sealed = when {
            persistedExisting == null || plainPassword.isNotEmpty() ->
                cipher.encryptString(plainPassword, newAad)
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
        val baseEntity =
            persistedExisting ?: ConnectionEntity(name = name, host = host, username = username)
        val entity = baseEntity.copy(
            name = name,
            host = host,
            port = port,
            username = username,
            domain = domain,
            passwordCipher = sealed.ciphertext,
            passwordIv = sealed.iv,
            passwordAadVersion = CREDENTIAL_AAD_VERSION,
            colorDepth = colorDepth,
            useH264 = useH264,
            preferAvc420 = preferAvc420,
            // H.264 is transported by rdpgfx; normalize stale or programmatic callers that provide
            // the impossible useH264=true/useGfx=false combination.
            useGfx = useGfx || useH264,
            dynamicResolution = dynamicResolution,
            dynamicResMax = dynamicResMax,
            useMultitransport = useMultitransport,
            redirectClipboard = redirectClipboard,
            redirectFiles = redirectFiles,
            sharedFolderUri = sharedFolderUri,
            soundMode = soundMode,
            desktopScaleFactor = desktopScaleFactor,
            customWidth = customWidth,
            customHeight = customHeight,
            defaultInputMode = defaultInputMode,
            targetFrameRate = targetFrameRate,
            performanceFlags = performanceFlags,
            // A pin belongs to one endpoint. Carrying it to an edited host/port either bricks the
            // new connection or, worse, makes the trust decision look like it applies there.
            certThumbSha256 = persistedExisting?.certThumbSha256
                ?.takeIf {
                    persistedExisting.host.equals(host, ignoreCase = true) &&
                        persistedExisting.port == port
                },
        )
        dao.upsert(entity)
    }

    suspend fun delete(entity: ConnectionEntity) = dao.delete(entity)

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

    suspend fun decryptPassword(entity: ConnectionEntity): String {
        if (entity.passwordCipher.isEmpty()) return ""
        val plain = decryptPasswordValue(entity)
        if (entity.passwordAadVersion == LEGACY_CREDENTIAL_AAD_VERSION) {
            val rebound = cipher.encryptString(
                plain,
                credentialAad(entity.host, entity.port, entity.username, entity.domain),
            )
            dao.upgradeLegacyCredentialBinding(entity.id, rebound.ciphertext, rebound.iv)
        }
        return plain
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

    private fun validateCredentialField(label: String, value: String, maxChars: Int) {
        require(value.length <= maxChars) { "$label exceeds the supported length" }
        require('\u0000' !in value) { "$label contains an unsupported NUL character" }
    }

    private companion object {
        const val LEGACY_CREDENTIAL_AAD_VERSION = 0
        const val CREDENTIAL_AAD_VERSION = 1
        const val MAX_CONNECTION_FIELD_CHARS = 1024
        const val MAX_CREDENTIAL_FIELD_CHARS = 4096
        const val MAX_SHARED_FOLDER_URI_CHARS = 8192
        const val MIN_REMOTE_EDGE = 200
        const val MAX_REMOTE_EDGE = 8192
        const val MAX_FRAMEBUFFER_PIXELS = 16_777_216L
        const val MAX_TARGET_FRAME_RATE = 240
        const val SHA256_HEX_CHARS = 64
        val SUPPORTED_COLOR_DEPTHS = setOf(16, 24, 32)
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
