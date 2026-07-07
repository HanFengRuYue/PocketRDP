package com.hanfengruyue.pocketrdp.feature.connections.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConnectionEditError {
    NAME_REQUIRED,
    HOST_REQUIRED,
    PORT_INVALID,
    USERNAME_REQUIRED,
    PASSWORD_REQUIRED,
    CUSTOM_WIDTH_INVALID,
    CUSTOM_HEIGHT_INVALID,
}

data class ConnectionEditUiState(
    val isLoading: Boolean = true,
    val id: Long? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "3389",
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val hasExistingPassword: Boolean = false,
    val colorDepth: Int = 32,
    val useH264: Boolean = true,
    // false = 画质优先 (AVC444), true = 流畅优先 (AVC420). Only meaningful while useH264.
    // Default TRUE (流畅优先) — matches ConnectionEntity.preferAvc420's intended low-latency default.
    // (This is the FORM default a NEW connection persists via repository.save; the entity default is
    // bypassed by save()'s explicit copy, so the form default is the one that actually governs. It was
    // false here, which silently put every new connection on the heavier AVC444 dual-stream decode path
    // despite the entity comment — the 操控延迟 default-inconsistency bug.)
    val preferAvc420: Boolean = true,
    val useGfx: Boolean = true,
    val dynamicResolution: Boolean = true,
    // Max remote-resolution cap while dynamic-resolution is on. 0 = 跟随设备 (no cap); otherwise the
    // short-edge px cap (long edge bounded to 16:9). See ConnectionEntity.dynamicResMax.
    // Default 1080 — matches the entity's intended low-latency default (a phone's full 1440p+ view is a
    // 4 MP+ frame to encode AND decode every frame; the 1080p cap roughly halves that). Was 0 here, which
    // — like preferAvc420 above — let new connections render uncapped at the phone's full resolution,
    // the second half of the same default-inconsistency 操控延迟 bug.
    val dynamicResMax: Int = 1080,
    val useMultitransport: Boolean = true,
    val redirectClipboard: Boolean = true,
    val redirectFiles: Boolean = false,
    val sharedFolderUri: String? = null,
    // 远程音频路由：0 = 停用 (/audio-mode:none), 1 = 控制端播放 (/audio-mode:redirect),
    // 2 = 被控端播放 (/audio-mode:server)。新建连接默认 2（被控端播放）；编辑已有连接时由 init
    // 用 entity.soundMode 覆盖（存量行的列值都是 0 = 停用，不受此默认影响）。
    val soundMode: Int = 2,
    val desktopScaleFactor: Int = 200,
    // Custom fixed remote resolution (issue 自定义分辨率). When [useCustomResolution] is on, the
    // session connects at customWidth×customHeight and dynamic-resolution is forced off.
    val useCustomResolution: Boolean = false,
    val customWidth: String = "1920",
    val customHeight: String = "1080",
    // 0 = 模拟鼠标 (TRACKPAD), 1 = 直接触屏 (TOUCH / native multi-touch).
    val defaultInputMode: Int = 0,
    val targetFrameRate: Int = 0,
    // Per-connection performance bitmask (ConnectionEntity.performanceFlags). Default 0 = rich desktop.
    // The 低延迟视觉 toggle flips bit ConnectionEntity.PERF_LOW_LATENCY_VISUALS (-wallpaper -themes).
    val performanceFlags: Int = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errors: List<ConnectionEditError> = emptyList(),
)

@HiltViewModel
class ConnectionEditViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionEditUiState())
    val state: StateFlow<ConnectionEditUiState> = _state.asStateFlow()

    private var existing: ConnectionEntity? = null

    init {
        val id: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
        if (id == null || id <= 0L) {
            _state.update { it.copy(isLoading = false) }
        } else {
            viewModelScope.launch {
                val entity = repository.findById(id)
                existing = entity
                if (entity != null) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            id = entity.id,
                            name = entity.name,
                            host = entity.host,
                            port = entity.port.toString(),
                            username = entity.username,
                            domain = entity.domain,
                            password = "",
                            hasExistingPassword = entity.passwordCipher.isNotEmpty(),
                            colorDepth = entity.colorDepth,
                            useH264 = entity.useH264,
                            preferAvc420 = entity.preferAvc420,
                            useGfx = entity.useGfx,
                            dynamicResolution = entity.dynamicResolution,
                            dynamicResMax = entity.dynamicResMax,
                            useMultitransport = entity.useMultitransport,
                            redirectClipboard = entity.redirectClipboard,
                            redirectFiles = entity.redirectFiles,
                            sharedFolderUri = entity.sharedFolderUri,
                            soundMode = entity.soundMode,
                            desktopScaleFactor = entity.desktopScaleFactor,
                            useCustomResolution = entity.customWidth > 0 && entity.customHeight > 0,
                            customWidth = if (entity.customWidth > 0) entity.customWidth.toString() else "1920",
                            customHeight = if (entity.customHeight > 0) entity.customHeight.toString() else "1080",
                            defaultInputMode = entity.defaultInputMode,
                            targetFrameRate = entity.targetFrameRate,
                            performanceFlags = entity.performanceFlags,
                        )
                    }
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun updateName(value: String) = _state.update { it.copy(name = value) }
    fun updateHost(value: String) = _state.update { it.copy(host = value) }
    fun updatePort(value: String) = _state.update { it.copy(port = value.filter { ch -> ch.isDigit() }.take(5)) }
    fun updateUsername(value: String) = _state.update { it.copy(username = value) }
    fun updateDomain(value: String) = _state.update { it.copy(domain = value) }
    fun updatePassword(value: String) = _state.update { it.copy(password = value) }
    fun updateColorDepth(value: Int) = _state.update { it.copy(colorDepth = value) }
    // H.264 (AVC444) can only run on the GFX/rdpgfx channel, so enabling it implicitly forces the
    // GFX pipeline on. Keep the persisted entity honest (no use_h264=true/use_gfx=false records) —
    // the edit screen also locks the GFX switch ON+disabled while H.264 is enabled.
    fun toggleH264(value: Boolean) = _state.update {
        it.copy(useH264 = value, useGfx = if (value) true else it.useGfx)
    }
    /** Codec tier: false = 画质优先 (AVC444), true = 流畅优先 (AVC420). */
    fun updatePreferAvc420(value: Boolean) = _state.update { it.copy(preferAvc420 = value) }
    fun toggleGfx(value: Boolean) = _state.update { it.copy(useGfx = value) }
    fun toggleDynamicRes(value: Boolean) = _state.update { it.copy(dynamicResolution = value) }
    fun updateDynamicResMax(value: Int) = _state.update { it.copy(dynamicResMax = value) }
    fun toggleMultitransport(value: Boolean) = _state.update { it.copy(useMultitransport = value) }
    fun toggleClipboard(value: Boolean) = _state.update { it.copy(redirectClipboard = value) }
    fun toggleFiles(value: Boolean) = _state.update { it.copy(redirectFiles = value) }
    fun updateSharedFolder(uri: String?) = _state.update { it.copy(sharedFolderUri = uri) }
    fun updateSoundMode(value: Int) = _state.update { it.copy(soundMode = value) }
    fun updateScaleFactor(value: Int) = _state.update { it.copy(desktopScaleFactor = value) }
    fun updateFrameRate(value: Int) = _state.update { it.copy(targetFrameRate = value) }
    fun toggleCustomResolution(value: Boolean) = _state.update {
        // Turning custom resolution ON forces dynamic-resolution OFF (they are mutually exclusive:
        // a fixed remote size must not be resized by monitor-layout PDUs).
        it.copy(useCustomResolution = value, dynamicResolution = if (value) false else it.dynamicResolution)
    }
    fun updateCustomWidth(value: String) = _state.update { it.copy(customWidth = value.filter { ch -> ch.isDigit() }.take(5)) }
    fun updateCustomHeight(value: String) = _state.update { it.copy(customHeight = value.filter { ch -> ch.isDigit() }.take(5)) }
    fun updateDefaultInputMode(value: Int) = _state.update { it.copy(defaultInputMode = value) }

    /** 低延迟视觉: toggle the PERF_LOW_LATENCY_VISUALS bit (server drops wallpaper + themes). */
    fun toggleLowLatencyVisuals(value: Boolean) = _state.update {
        val flags = if (value) {
            it.performanceFlags or ConnectionEntity.PERF_LOW_LATENCY_VISUALS
        } else {
            it.performanceFlags and ConnectionEntity.PERF_LOW_LATENCY_VISUALS.inv()
        }
        it.copy(performanceFlags = flags)
    }

    fun save() {
        val s = _state.value
        val errors = buildList {
            if (s.name.isBlank()) add(ConnectionEditError.NAME_REQUIRED)
            if (s.host.isBlank()) add(ConnectionEditError.HOST_REQUIRED)
            val p = s.port.toIntOrNull()
            if (p == null || p !in 1..65535) add(ConnectionEditError.PORT_INVALID)
            if (s.username.isBlank()) add(ConnectionEditError.USERNAME_REQUIRED)
            if (s.password.isEmpty() && !s.hasExistingPassword) add(ConnectionEditError.PASSWORD_REQUIRED)
            if (s.useCustomResolution) {
                val w = s.customWidth.toIntOrNull()
                val h = s.customHeight.toIntOrNull()
                if (w == null || w !in 200..8192) add(ConnectionEditError.CUSTOM_WIDTH_INVALID)
                if (h == null || h !in 200..8192) add(ConnectionEditError.CUSTOM_HEIGHT_INVALID)
            }
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            repository.save(
                existing = existing,
                name = s.name.trim(),
                host = s.host.trim(),
                port = s.port.toInt(),
                username = s.username.trim(),
                domain = s.domain.trim(),
                plainPassword = s.password,
                colorDepth = s.colorDepth,
                useH264 = s.useH264,
                preferAvc420 = s.preferAvc420,
                useGfx = s.useGfx,
                dynamicResolution = s.dynamicResolution,
                // Persisted as-is; a stale cap (e.g. set, then custom-res enabled) is harmless because
                // SessionViewModel only applies it when dynamic resolution is actually in effect.
                dynamicResMax = s.dynamicResMax,
                useMultitransport = s.useMultitransport,
                redirectClipboard = s.redirectClipboard,
                redirectFiles = s.redirectFiles,
                sharedFolderUri = s.sharedFolderUri,
                soundMode = s.soundMode,
                desktopScaleFactor = s.desktopScaleFactor,
                customWidth = if (s.useCustomResolution) s.customWidth.toIntOrNull() ?: 0 else 0,
                customHeight = if (s.useCustomResolution) s.customHeight.toIntOrNull() ?: 0 else 0,
                defaultInputMode = s.defaultInputMode,
                targetFrameRate = s.targetFrameRate,
                performanceFlags = s.performanceFlags,
            )
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    fun consumeErrors() = _state.update { it.copy(errors = emptyList()) }
}
