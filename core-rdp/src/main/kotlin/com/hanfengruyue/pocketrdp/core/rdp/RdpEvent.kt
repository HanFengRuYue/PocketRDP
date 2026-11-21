package com.hanfengruyue.pocketrdp.core.rdp

sealed interface RdpEvent {
    data object Connecting : RdpEvent
    data object Connected : RdpEvent
    data class Disconnected(
        val reason: String?,
        val retryable: Boolean = true,
        val udpDowngraded: Boolean = false,
    ) : RdpEvent
    data class Failed(
        val error: String,
        val retryable: Boolean = true,
        val udpDowngraded: Boolean = false,
    ) : RdpEvent
    data class CertificatePrompt(
        val host: String,
        val port: Int,
        val sha256: String,
        val isChange: Boolean,
    ) : RdpEvent
    data class CredentialsRequired(
        val host: String,
        val initialUsername: String,
        val initialDomain: String,
    ) : RdpEvent
}

data class RdpResize(val width: Int, val height: Int)
