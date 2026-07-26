package com.hanfengruyue.pocketrdp.core.rdp

sealed interface RdpEvent {
    data object Connecting : RdpEvent
    data object Connected : RdpEvent
    data class Disconnected(val reason: String?) : RdpEvent
    data class Failed(val error: String, val retryable: Boolean = true) : RdpEvent
    // Kept for source compatibility with older observers. High-rate updates now use the
    // dedicated conflated streams on RdpClient and these event variants are no longer emitted.
    data class GraphicsUpdated(val x: Int, val y: Int, val width: Int, val height: Int) : RdpEvent
    data class PointerChanged(val cursor: RdpCursor) : RdpEvent
    data class ClipboardReceived(val text: String) : RdpEvent
    data class CertificatePrompt(
        val host: String,
        val port: Int,
        val sha256: String,
        val isChange: Boolean,
    ) : RdpEvent
    data object CredentialsRequired : RdpEvent
}

data class RdpResize(val width: Int, val height: Int)
