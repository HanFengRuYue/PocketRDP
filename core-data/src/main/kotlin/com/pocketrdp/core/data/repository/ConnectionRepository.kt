package com.pocketrdp.core.data.repository

import com.pocketrdp.core.data.db.ConnectionDao
import com.pocketrdp.core.data.model.ConnectionEntity
import com.pocketrdp.core.data.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
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
        useGfx: Boolean,
        dynamicResolution: Boolean,
        redirectClipboard: Boolean,
        redirectFiles: Boolean,
        sharedFolderUri: String?,
        soundMode: Int,
        desktopScaleFactor: Int,
    ): Long {
        val sealed = if (plainPassword.isEmpty() && existing != null) {
            CredentialCipher.Sealed(existing.passwordCipher, existing.passwordIv)
        } else {
            cipher.encryptString(plainPassword)
        }
        val entity = (existing ?: ConnectionEntity(name = name, host = host, username = username)).copy(
            name = name,
            host = host,
            port = port,
            username = username,
            domain = domain,
            passwordCipher = sealed.ciphertext,
            passwordIv = sealed.iv,
            colorDepth = colorDepth,
            useH264 = useH264,
            useGfx = useGfx,
            dynamicResolution = dynamicResolution,
            redirectClipboard = redirectClipboard,
            redirectFiles = redirectFiles,
            sharedFolderUri = sharedFolderUri,
            soundMode = soundMode,
            desktopScaleFactor = desktopScaleFactor,
        )
        return dao.upsert(entity)
    }

    suspend fun delete(entity: ConnectionEntity) = dao.delete(entity)

    suspend fun touchLastUsed(id: Long) = dao.touchLastUsed(id, System.currentTimeMillis())

    fun decryptPassword(entity: ConnectionEntity): String =
        if (entity.passwordCipher.isEmpty()) ""
        else cipher.decryptToString(CredentialCipher.Sealed(entity.passwordCipher, entity.passwordIv))
}
