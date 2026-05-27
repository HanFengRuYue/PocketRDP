package com.pocketrdp.feature.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketrdp.core.data.repository.ConnectionRepository
import com.pocketrdp.core.rdp.InputMode
import com.pocketrdp.core.rdp.RdpClient
import com.pocketrdp.core.rdp.RdpConnectionParams
import com.pocketrdp.core.rdp.RdpEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SessionConnectionStatus {
    data object Idle : SessionConnectionStatus
    data object Connecting : SessionConnectionStatus
    data object Connected : SessionConnectionStatus
    data class Disconnected(val reason: String?) : SessionConnectionStatus
    data class Failed(val error: String) : SessionConnectionStatus
}

data class SessionUiState(
    val connectionId: Long = 0L,
    val connectionName: String = "",
    val status: SessionConnectionStatus = SessionConnectionStatus.Idle,
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val mode: InputMode = InputMode.TRACKPAD,
    val toolbarVisible: Boolean = true,
    val stickyModifiers: Int = 0,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    val rdpClient: RdpClient,
    private val repository: ConnectionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    init {
        val id = savedStateHandle.get<Long>("id") ?: 0L
        _state.update { it.copy(connectionId = id) }
        observeEvents()
        if (id > 0L) launchConnect(id)
    }

    private fun observeEvents() {
        viewModelScope.launch {
            rdpClient.events.collect { event ->
                when (event) {
                    is RdpEvent.Connecting -> _state.update { it.copy(status = SessionConnectionStatus.Connecting) }
                    is RdpEvent.Connected -> _state.update { it.copy(status = SessionConnectionStatus.Connected) }
                    is RdpEvent.Disconnected -> _state.update { it.copy(status = SessionConnectionStatus.Disconnected(event.reason)) }
                    is RdpEvent.Failed -> _state.update { it.copy(status = SessionConnectionStatus.Failed(event.error)) }
                    is RdpEvent.GraphicsResized -> _state.update { it.copy(remoteWidth = event.width, remoteHeight = event.height) }
                    else -> Unit
                }
            }
        }
    }

    private fun launchConnect(id: Long) {
        viewModelScope.launch {
            val entity = repository.findById(id) ?: run {
                _state.update { it.copy(status = SessionConnectionStatus.Failed("connection not found")) }
                return@launch
            }
            _state.update { it.copy(connectionName = entity.name) }
            val plain = runCatching { repository.decryptPassword(entity) }.getOrDefault("")
            val params = RdpConnectionParams(
                connectionId = entity.id,
                host = entity.host,
                port = entity.port,
                username = entity.username,
                domain = entity.domain,
                password = plain,
                colorDepth = entity.colorDepth,
                useH264 = entity.useH264,
                useGfx = entity.useGfx,
                dynamicResolution = entity.dynamicResolution,
                redirectClipboard = entity.redirectClipboard,
                redirectFiles = entity.redirectFiles,
                sharedFolderUri = entity.sharedFolderUri,
                soundMode = entity.soundMode,
                desktopScaleFactor = entity.desktopScaleFactor,
                initialWidth = 1920,
                initialHeight = 1080,
                acceptedCertThumbprint = entity.certThumbSha256,
            )
            rdpClient.connect(params)
            repository.touchLastUsed(entity.id)
        }
    }

    fun toggleMode() {
        _state.update {
            it.copy(mode = if (it.mode == InputMode.TRACKPAD) InputMode.TOUCH else InputMode.TRACKPAD)
        }
    }

    fun toggleToolbar() {
        _state.update { it.copy(toolbarVisible = !it.toolbarVisible) }
    }

    fun toggleStickyModifier(flag: Int) {
        _state.update { it.copy(stickyModifiers = it.stickyModifiers xor flag) }
    }

    fun sendKey(scanCode: Int) {
        rdpClient.sendKeyEvent(scanCode, true)
        rdpClient.sendKeyEvent(scanCode, false)
    }

    fun sendCtrlAltDel() {
        val ctrl = 0x1D; val alt = 0x38; val del = 0x53
        rdpClient.sendKeyEvent(ctrl, true)
        rdpClient.sendKeyEvent(alt, true)
        rdpClient.sendKeyEvent(del, true)
        rdpClient.sendKeyEvent(del, false)
        rdpClient.sendKeyEvent(alt, false)
        rdpClient.sendKeyEvent(ctrl, false)
    }

    fun typeText(text: String) {
        text.forEach { ch ->
            rdpClient.sendUnicodeKey(ch.code, true)
            rdpClient.sendUnicodeKey(ch.code, false)
        }
    }

    fun onSurfaceResized(width: Int, height: Int) {
        rdpClient.sendMonitorLayout(width, height)
    }

    fun disconnect() {
        rdpClient.disconnect()
    }

    override fun onCleared() {
        // M7: keep connection alive via Foreground Service. For now (M2), disconnect on dispose.
        rdpClient.disconnect()
        super.onCleared()
    }
}
