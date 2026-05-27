package com.pocketrdp.feature.connections.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketrdp.core.data.model.ConnectionEntity
import com.pocketrdp.core.data.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val useGfx: Boolean = true,
    val dynamicResolution: Boolean = true,
    val redirectClipboard: Boolean = true,
    val redirectFiles: Boolean = false,
    val sharedFolderUri: String? = null,
    val soundMode: Int = 0,
    val desktopScaleFactor: Int = 200,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errors: List<String> = emptyList(),
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
                            useGfx = entity.useGfx,
                            dynamicResolution = entity.dynamicResolution,
                            redirectClipboard = entity.redirectClipboard,
                            redirectFiles = entity.redirectFiles,
                            sharedFolderUri = entity.sharedFolderUri,
                            soundMode = entity.soundMode,
                            desktopScaleFactor = entity.desktopScaleFactor,
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
    fun toggleH264(value: Boolean) = _state.update { it.copy(useH264 = value) }
    fun toggleGfx(value: Boolean) = _state.update { it.copy(useGfx = value) }
    fun toggleDynamicRes(value: Boolean) = _state.update { it.copy(dynamicResolution = value) }
    fun toggleClipboard(value: Boolean) = _state.update { it.copy(redirectClipboard = value) }
    fun toggleFiles(value: Boolean) = _state.update { it.copy(redirectFiles = value) }
    fun updateSharedFolder(uri: String?) = _state.update { it.copy(sharedFolderUri = uri) }
    fun updateSoundMode(value: Int) = _state.update { it.copy(soundMode = value) }
    fun updateScaleFactor(value: Int) = _state.update { it.copy(desktopScaleFactor = value) }

    fun save() {
        val s = _state.value
        val errors = buildList {
            if (s.name.isBlank()) add("名称不能为空")
            if (s.host.isBlank()) add("主机地址不能为空")
            val p = s.port.toIntOrNull()
            if (p == null || p !in 1..65535) add("端口需为 1-65535")
            if (s.username.isBlank()) add("用户名不能为空")
            if (s.password.isEmpty() && !s.hasExistingPassword) add("密码不能为空")
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
                useGfx = s.useGfx,
                dynamicResolution = s.dynamicResolution,
                redirectClipboard = s.redirectClipboard,
                redirectFiles = s.redirectFiles,
                sharedFolderUri = s.sharedFolderUri,
                soundMode = s.soundMode,
                desktopScaleFactor = s.desktopScaleFactor,
            )
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    fun consumeErrors() = _state.update { it.copy(errors = emptyList()) }
}
