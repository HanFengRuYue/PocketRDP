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
    // false = 画质优先 (/gfx:AVC444, full 4:4:4); true = 流畅优先 (/gfx:AVC420, single YUV420 stream).
    // Default TRUE (流畅优先) for the lowest control latency: AVC420 is a single H.264 stream with no
    // prim_YUV444 recombine, so it's both cheaper to decode AND the clean path for the MediaCodec
    // hardware decoder (a hardware codec's output strides feeding the AVC444 recombine is the historical
    // chroma-artifact risk). Existing connections keep their stored value; users who want crisp 4:4:4
    // text can still pick 画质优先. Only meaningful while useH264.
    @ColumnInfo(name = "prefer_avc420") val preferAvc420: Boolean = true,
    @ColumnInfo(name = "use_gfx") val useGfx: Boolean = true,
    @ColumnInfo(name = "dynamic_resolution") val dynamicResolution: Boolean = true,
    // Max remote resolution cap while dynamic-resolution is on (issue: 防止直接套用手机全分辨率导致被控端
    // 渲染压力过大). 0 = 跟随设备 (no cap — send the full local view size). Otherwise the SHORT-edge cap in
    // px (720 / 1080 / 1440); the long edge is bounded to 16:9 of it. Ignored when dynamicResolution is
    // off or a custom fixed resolution is set. Default 1080 (1080p cap) for lower 操控延迟: a phone's full
    // 1440p+ view is a 4 MP+ frame to encode AND decode every frame; capping to 1080p roughly halves that
    // cost. Existing connections keep their stored value (0 = uncapped); users can pick 跟随设备 for full res.
    @ColumnInfo(name = "dynamic_res_max") val dynamicResMax: Int = 1080,
    @ColumnInfo(name = "use_multitransport") val useMultitransport: Boolean = true,
    @ColumnInfo(name = "redirect_clipboard") val redirectClipboard: Boolean = true,
    @ColumnInfo(name = "redirect_files") val redirectFiles: Boolean = false,
    @ColumnInfo(name = "shared_folder_uri") val sharedFolderUri: String? = null,
    // 远程音频路由（v1 基线列）：0 = 停用 (/audio-mode:none)，1 = 控制端播放 (/audio-mode:redirect，
    // 远端声音重定向到手机，rdpsnd→OpenSL ES 出声)，2 = 被控端播放 (/audio-mode:server，
    // RemoteConsoleAudio，声音留在被控电脑扬声器)。存量行默认 0（停用）。见 RdpClient.buildCommandLine。
    @ColumnInfo(name = "sound_mode") val soundMode: Int = 0,
    @ColumnInfo(name = "desktop_scale_factor") val desktopScaleFactor: Int = 200,
    // Custom fixed remote resolution. 0/0 = "not set" → follow [dynamicResolution] / default size.
    // When both > 0 the session connects at exactly this size and dynamic-resolution is forced off
    // so the remote desktop stays pinned to it (issue: 自定义分辨率).
    @ColumnInfo(name = "custom_width") val customWidth: Int = 0,
    @ColumnInfo(name = "custom_height") val customHeight: Int = 0,
    // Input mode the session opens in: 0 = 模拟鼠标 (TRACKPAD), 1 = 直接触屏 (TOUCH / native RDPEI).
    // Default 0 keeps the historical behaviour; can still be toggled live in-session.
    @ColumnInfo(name = "default_input_mode") val defaultInputMode: Int = 0,
    @ColumnInfo(name = "target_frame_rate") val targetFrameRate: Int = 0,
    // Per-connection performance bitmask (v1 baseline column, previously unused — repurposed like
    // sound_mode was, so NO migration / version bump is needed). Bit [PERF_LOW_LATENCY_VISUALS] = 1
    // asks the server to drop wallpaper + themes (RdpClient emits -wallpaper -themes), trading remote
    // eye-candy for a slightly leaner per-frame encode/decode payload (低延迟视觉). Default 0 = unchanged
    // (rich desktop). Room more flags onto higher bits later without a schema change.
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
            preferAvc420 == other.preferAvc420 &&
            useGfx == other.useGfx &&
            dynamicResolution == other.dynamicResolution &&
            dynamicResMax == other.dynamicResMax &&
            useMultitransport == other.useMultitransport &&
            redirectClipboard == other.redirectClipboard &&
            redirectFiles == other.redirectFiles &&
            sharedFolderUri == other.sharedFolderUri &&
            soundMode == other.soundMode &&
            desktopScaleFactor == other.desktopScaleFactor &&
            customWidth == other.customWidth &&
            customHeight == other.customHeight &&
            defaultInputMode == other.defaultInputMode &&
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
        result = 31 * result + preferAvc420.hashCode()
        result = 31 * result + useGfx.hashCode()
        result = 31 * result + dynamicResolution.hashCode()
        result = 31 * result + dynamicResMax
        result = 31 * result + useMultitransport.hashCode()
        result = 31 * result + redirectClipboard.hashCode()
        result = 31 * result + redirectFiles.hashCode()
        result = 31 * result + (sharedFolderUri?.hashCode() ?: 0)
        result = 31 * result + soundMode
        result = 31 * result + desktopScaleFactor
        result = 31 * result + customWidth
        result = 31 * result + customHeight
        result = 31 * result + defaultInputMode
        result = 31 * result + targetFrameRate
        result = 31 * result + performanceFlags
        result = 31 * result + lastUsedAt.hashCode()
        result = 31 * result + (certThumbSha256?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * [performanceFlags] bit: when set, ask the remote to disable wallpaper + themes for a leaner
         * per-frame payload (低延迟视觉). Bitmask so future perf toggles can claim higher bits without a
         * DB migration. Kept here so the edit screen, the entity and RdpClient.buildCommandLine agree.
         */
        const val PERF_LOW_LATENCY_VISUALS = 1
    }
}
