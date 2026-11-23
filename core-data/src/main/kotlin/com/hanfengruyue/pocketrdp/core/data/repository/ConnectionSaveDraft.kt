package com.hanfengruyue.pocketrdp.core.data.repository

data class ConnectionSaveDraft(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val domain: String,
    val plainPassword: String,
    val colorDepth: Int,
    val useH264: Boolean,
    val preferAvc420: Boolean,
    val useGfx: Boolean,
    val dynamicResolution: Boolean,
    val dynamicResMax: Int,
    val useMultitransport: Boolean,
    val redirectClipboard: Boolean,
    val redirectFiles: Boolean,
    val soundMode: Int,
    val desktopScaleFactor: Int,
    val customWidth: Int,
    val customHeight: Int,
    val defaultInputMode: Int,
    val targetFrameRate: Int,
    val performanceFlags: Int,
)

internal fun validateConnectionDraft(draft: ConnectionSaveDraft) {
    validateCredentialField("name", draft.name, MAX_CONNECTION_FIELD_CHARS)
    validateCredentialField("host", draft.host, MAX_CONNECTION_FIELD_CHARS)
    validateCredentialField("username", draft.username, MAX_CREDENTIAL_FIELD_CHARS)
    validateCredentialField("domain", draft.domain, MAX_CREDENTIAL_FIELD_CHARS)
    validateCredentialField("password", draft.plainPassword, MAX_CREDENTIAL_FIELD_CHARS)
    require(draft.name.isNotBlank()) { "name must not be blank" }
    require(draft.host.isNotBlank()) { "host must not be blank" }
    require(draft.username.isNotBlank()) { "username must not be blank" }
    require(draft.port in MIN_TCP_PORT..MAX_TCP_PORT) { "port is outside the TCP range" }
    require(draft.colorDepth in SUPPORTED_COLOR_DEPTHS) { "unsupported color depth" }
    require(draft.dynamicResMax == 0 || draft.dynamicResMax in MIN_REMOTE_EDGE..MAX_REMOTE_EDGE) {
        "dynamic resolution cap is outside the supported range"
    }
    require(draft.soundMode in 0..MAX_SOUND_MODE) { "unsupported audio mode" }
    require(draft.desktopScaleFactor in MIN_DESKTOP_SCALE..MAX_DESKTOP_SCALE) {
        "desktop scale is outside the supported range"
    }
    require(draft.defaultInputMode in 0..MAX_INPUT_MODE) { "unsupported input mode" }
    require(draft.targetFrameRate == 0 || draft.targetFrameRate in 1..MAX_TARGET_FRAME_RATE) {
        "target frame rate is outside the supported range"
    }
    require(hasValidCustomDimensions(draft.customWidth, draft.customHeight)) {
        "custom framebuffer dimensions are unsafe"
    }
}

private fun hasValidCustomDimensions(width: Int, height: Int): Boolean {
    if (width == 0 && height == 0) return true
    val edgesValid = width in MIN_REMOTE_EDGE..MAX_REMOTE_EDGE &&
        height in MIN_REMOTE_EDGE..MAX_REMOTE_EDGE
    return edgesValid && width.toLong() * height <= MAX_FRAMEBUFFER_PIXELS
}

private fun validateCredentialField(label: String, value: String, maxChars: Int) {
    require(value.length <= maxChars) { "$label exceeds the supported length" }
    require('\u0000' !in value) { "$label contains an unsupported NUL character" }
}

private const val MAX_CONNECTION_FIELD_CHARS = 1024
private const val MAX_CREDENTIAL_FIELD_CHARS = 4096
private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65535
private const val MIN_REMOTE_EDGE = 200
private const val MAX_REMOTE_EDGE = 8192
private const val MAX_FRAMEBUFFER_PIXELS = 16_777_216L
private const val MAX_SOUND_MODE = 2
private const val MIN_DESKTOP_SCALE = 100
private const val MAX_DESKTOP_SCALE = 300
private const val MAX_INPUT_MODE = 1
private const val MAX_TARGET_FRAME_RATE = 240
private val SUPPORTED_COLOR_DEPTHS = setOf(16, 24, 32)
