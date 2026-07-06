package com.hanfengruyue.pocketrdp.core.rdp

import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RdpSessionRuntimeState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

data class RdpSessionRegistrySnapshot(
    val activeCount: Int = 0,
    val connectedCount: Int = 0,
    val connectingCount: Int = 0,
    val reconnectingCount: Int = 0,
) {
    val hasActiveSessions: Boolean get() = activeCount > 0
}

@Singleton
class RdpSessionRegistry @Inject constructor() {
    private val lock = Any()
    private val sessions = IdentityHashMap<RdpClient, RdpSessionRuntimeState>()
    private val _snapshot = MutableStateFlow(RdpSessionRegistrySnapshot())
    val snapshot: StateFlow<RdpSessionRegistrySnapshot> = _snapshot.asStateFlow()

    fun markConnecting(client: RdpClient) {
        update(client, RdpSessionRuntimeState.CONNECTING)
    }

    fun markConnected(client: RdpClient) {
        update(client, RdpSessionRuntimeState.CONNECTED)
    }

    fun markReconnecting(client: RdpClient) {
        update(client, RdpSessionRuntimeState.RECONNECTING)
    }

    fun unregister(client: RdpClient) {
        synchronized(lock) {
            if (sessions.remove(client) != null) publishLocked()
        }
    }

    fun contains(client: RdpClient): Boolean = synchronized(lock) {
        sessions.containsKey(client)
    }

    fun activeCount(): Int = snapshot.value.activeCount

    fun disconnectAll() {
        val clients = synchronized(lock) { sessions.keys.toList() }
        clients.forEach { it.disconnect() }
    }

    private fun update(client: RdpClient, state: RdpSessionRuntimeState) {
        synchronized(lock) {
            sessions[client] = state
            publishLocked()
        }
    }

    private fun publishLocked() {
        val values = sessions.values
        _snapshot.value = RdpSessionRegistrySnapshot(
            activeCount = values.size,
            connectedCount = values.count { it == RdpSessionRuntimeState.CONNECTED },
            connectingCount = values.count { it == RdpSessionRuntimeState.CONNECTING },
            reconnectingCount = values.count { it == RdpSessionRuntimeState.RECONNECTING },
        )
    }
}
