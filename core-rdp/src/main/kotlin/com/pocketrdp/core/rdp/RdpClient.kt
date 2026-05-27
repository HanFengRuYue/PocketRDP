package com.pocketrdp.core.rdp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M1 placeholder. M2 wires this to LibFreeRDP (JNI) and emits real RdpEvent values.
 *
 * Keep the surface area stable across milestones so feature-session can be written against this
 * interface without changes when M2 swaps in the native implementation.
 */
@Singleton
class RdpClient @Inject constructor() {

    private val _events = MutableSharedFlow<RdpEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RdpEvent> = _events.asSharedFlow()

    fun connect(params: RdpConnectionParams) {
        // M2: bridge to LibFreeRDP.freerdp_new(context) + parse_arguments + connect
    }

    fun disconnect() {
        // M2: LibFreeRDP.freerdp_disconnect
    }

    fun sendKeyEvent(scanCode: Int, down: Boolean) {
        // M2: LibFreeRDP.freerdp_send_key_event
    }

    fun sendUnicodeKey(codePoint: Int, down: Boolean) {
        // M2: LibFreeRDP.freerdp_send_unicodekey_event
    }

    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        // M2: LibFreeRDP.freerdp_send_cursor_event
    }

    fun sendClipboard(data: String) {
        // M2: LibFreeRDP.freerdp_send_clipboard_data
    }

    fun sendMonitorLayout(width: Int, height: Int) {
        // M2: LibFreeRDP.freerdp_send_monitor_layout
    }
}
