package com.hanfengruyue.pocketrdp.feature.session

internal fun shouldScheduleAutoReconnect(
    autoReconnectEnabled: Boolean,
    eventRetryable: Boolean,
    hasReconnectParams: Boolean,
    userInitiatedDisconnect: Boolean,
    certificateDecisionPending: Boolean,
): Boolean {
    val allowed = autoReconnectEnabled && eventRetryable && hasReconnectParams
    val blocked = userInitiatedDisconnect || certificateDecisionPending
    return allowed && !blocked
}

internal fun statusAfterAutoReconnectCancelled(
    currentStatus: SessionConnectionStatus,
    lastError: String?,
): SessionConnectionStatus {
    if (currentStatus !is SessionConnectionStatus.Reconnecting) return currentStatus
    val error = lastError?.takeIf { it.isNotBlank() }
    return if (error != null) {
        SessionConnectionStatus.Failed(error)
    } else {
        SessionConnectionStatus.Disconnected(reason = null)
    }
}
