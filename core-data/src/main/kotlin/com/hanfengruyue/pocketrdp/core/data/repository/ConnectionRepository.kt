package com.hanfengruyue.pocketrdp.core.data.repository

import com.hanfengruyue.pocketrdp.core.data.db.ConnectionDao
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.security.CredentialCipher
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
            preferAvc420 = preferAvc420,
            useGfx = useGfx,
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
        )
        return dao.upsert(entity)
    }

    suspend fun delete(entity: ConnectionEntity) = dao.delete(entity)

    suspend fun touchLastUsed(id: Long) = dao.touchLastUsed(id, System.currentTimeMillis())

    fun decryptPassword(entity: ConnectionEntity): String =
        if (entity.passwordCipher.isEmpty()) ""
        else cipher.decryptToString(CredentialCipher.Sealed(entity.passwordCipher, entity.passwordIv))
}
