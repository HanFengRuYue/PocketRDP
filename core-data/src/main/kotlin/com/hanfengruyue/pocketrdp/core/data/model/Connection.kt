package com.hanfengruyue.pocketrdp.core.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val host: String,
    val port: Int = 3389,
    val username: String,
    val domain: String = "",
    @ColumnInfo(name = "password_cipher") val passwordCipher: ByteArray = ByteArray(0),
    @ColumnInfo(name = "password_iv") val passwordIv: ByteArray = ByteArray(0),
    @ColumnInfo(name = "color_depth") val colorDepth: Int = 32,
    @ColumnInfo(name = "use_h264") val useH264: Boolean = true,
    @ColumnInfo(name = "use_gfx") val useGfx: Boolean = true,
    @ColumnInfo(name = "dynamic_resolution") val dynamicResolution: Boolean = true,
    @ColumnInfo(name = "redirect_clipboard") val redirectClipboard: Boolean = true,
    @ColumnInfo(name = "redirect_files") val redirectFiles: Boolean = false,
    @ColumnInfo(name = "shared_folder_uri") val sharedFolderUri: String? = null,
    @ColumnInfo(name = "sound_mode") val soundMode: Int = 0,
    @ColumnInfo(name = "desktop_scale_factor") val desktopScaleFactor: Int = 200,
    @ColumnInfo(name = "target_frame_rate") val targetFrameRate: Int = 0,
    @ColumnInfo(name = "performance_flags") val performanceFlags: Int = 0,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long = 0L,
    @ColumnInfo(name = "cert_thumb_sha256") val certThumbSha256: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionEntity) return false
        return id == other.id &&
            name == other.name &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            domain == other.domain &&
            passwordCipher.contentEquals(other.passwordCipher) &&
            passwordIv.contentEquals(other.passwordIv) &&
            colorDepth == other.colorDepth &&
            useH264 == other.useH264 &&
            useGfx == other.useGfx &&
            dynamicResolution == other.dynamicResolution &&
            redirectClipboard == other.redirectClipboard &&
            redirectFiles == other.redirectFiles &&
            sharedFolderUri == other.sharedFolderUri &&
            soundMode == other.soundMode &&
            desktopScaleFactor == other.desktopScaleFactor &&
            targetFrameRate == other.targetFrameRate &&
            performanceFlags == other.performanceFlags &&
            lastUsedAt == other.lastUsedAt &&
            certThumbSha256 == other.certThumbSha256
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + domain.hashCode()
        result = 31 * result + passwordCipher.contentHashCode()
        result = 31 * result + passwordIv.contentHashCode()
        result = 31 * result + colorDepth
        result = 31 * result + useH264.hashCode()
        result = 31 * result + useGfx.hashCode()
        result = 31 * result + dynamicResolution.hashCode()
        result = 31 * result + redirectClipboard.hashCode()
        result = 31 * result + redirectFiles.hashCode()
        result = 31 * result + (sharedFolderUri?.hashCode() ?: 0)
        result = 31 * result + soundMode
        result = 31 * result + desktopScaleFactor
        result = 31 * result + targetFrameRate
        result = 31 * result + performanceFlags
        result = 31 * result + lastUsedAt.hashCode()
        result = 31 * result + (certThumbSha256?.hashCode() ?: 0)
        return result
    }
}
