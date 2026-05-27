package com.pocketrdp.core.rdp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin facade around FreeRDP's JNI bridge (LibFreeRDP.java).
 *
 * M1: returns no-op; UI compiles against this API.
 * M2: wires the real native calls and bridges UIEventListener callbacks into [events] /
 *     [BitmapBuffer]. The public surface here is intentionally final so feature-session
 *     does not change.
 */
@Singleton
class RdpClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val buffer: BitmapBuffer = BitmapBuffer()

    private val _events = MutableSharedFlow<RdpEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RdpEvent> = _events.asSharedFlow()

    private var nativeInstance: Long = 0L

    fun connect(params: RdpConnectionParams) {
        // M2: nativeInstance = LibFreeRDP.freerdp_new(context); parse args; freerdp_connect
        emit(RdpEvent.Connecting)
    }

    fun disconnect() {
        // M2: LibFreeRDP.freerdp_disconnect(nativeInstance); freerdp_free
        nativeInstance = 0L
        emit(RdpEvent.Disconnected(reason = "user"))
    }

    fun sendKeyEvent(scanCode: Int, down: Boolean) {
        // M2: LibFreeRDP.freerdp_send_key_event(nativeInstance, scanCode, down)
    }

    fun sendUnicodeKey(codePoint: Int, down: Boolean) {
        // M2: LibFreeRDP.freerdp_send_unicodekey_event(nativeInstance, codePoint, down)
    }

    fun sendCursorEvent(x: Int, y: Int, flags: Int) {
        // M2: LibFreeRDP.freerdp_send_cursor_event(nativeInstance, x, y, flags)
    }

    fun sendClipboard(data: String) {
        // M2: LibFreeRDP.freerdp_send_clipboard_data(nativeInstance, data)
    }

    fun sendMonitorLayout(width: Int, height: Int) {
        // M2: LibFreeRDP.freerdp_send_monitor_layout(nativeInstance, width, height)
    }

    fun hasH264(): Boolean {
        // M2: LibFreeRDP.freerdp_has_h264()
        return false
    }

    fun version(): String {
        // M2: LibFreeRDP.freerdp_get_version()
        return "M1-stub"
    }

    /** Invoked by native UIEventListener.OnGraphicsResize after a session is established. */
    internal fun onResize(width: Int, height: Int) {
        buffer.resize(width, height)
        emit(RdpEvent.GraphicsResized(width, height, buffer.current.value!!))
    }

    /** Invoked by native UIEventListener.OnGraphicsUpdate after a dirty rect is written. */
    internal fun onGraphicsUpdate(x: Int, y: Int, w: Int, h: Int) {
        buffer.notifyDirty(x, y, w, h)
        emit(RdpEvent.GraphicsUpdated(x, y, w, h))
    }

    private fun emit(event: RdpEvent) {
        _events.tryEmit(event)
    }
}
