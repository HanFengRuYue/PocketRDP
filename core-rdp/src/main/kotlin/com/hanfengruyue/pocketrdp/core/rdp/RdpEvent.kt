package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap

sealed interface RdpEvent {
    data object Connecting : RdpEvent
    data object Connected : RdpEvent
    data class Disconnected(val reason: String?) : RdpEvent
    data class Failed(val error: String) : RdpEvent
    data class GraphicsResized(val width: Int, val height: Int, val backing: Bitmap) : RdpEvent
    data class GraphicsUpdated(val x: Int, val y: Int, val width: Int, val height: Int) : RdpEvent
    data class ClipboardReceived(val text: String) : RdpEvent
    data class CertificatePrompt(val host: String, val sha256: String, val isChange: Boolean) : RdpEvent
    data object CredentialsRequired : RdpEvent
}
