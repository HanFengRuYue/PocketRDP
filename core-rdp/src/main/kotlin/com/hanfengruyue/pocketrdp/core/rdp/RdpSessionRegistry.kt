package com.hanfengruyue.pocketrdp.core.rdp

import android.graphics.Bitmap
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

data class RdpSessionInfo(
    val connectionId: Long = 0L,
    val displayName: String = "",
    val hostLabel: String = "",
    val state: RdpSessionRuntimeState = RdpSessionRuntimeState.CONNECTING,
)

data class RdpSessionRegistrySnapshot(
    val activeCount: Int = 0,
    val connectedCount: Int = 0,
    val connectingCount: Int = 0,
    val reconnectingCount: Int = 0,
    val sessions: List<RdpSessionInfo> = emptyList(),
) {
    val hasActiveSessions: Boolean get() = activeCount > 0
    val activeConnectionIds: Set<Long> get() = sessions.mapNotNullTo(mutableSetOf()) { info ->
        info.connectionId.takeIf { it > 0L }
    }
}

@Singleton
class RdpSessionRegistry @Inject constructor() {
    private val lock = Any()
    private val sessions = IdentityHashMap<RdpClient, RdpSessionInfo>()
    private val _snapshot = MutableStateFlow(RdpSessionRegistrySnapshot())
    val snapshot: StateFlow<RdpSessionRegistrySnapshot> = _snapshot.asStateFlow()

    fun markConnecting(client: RdpClient, params: RdpConnectionParams) {
        update(
            client = client,
            state = RdpSessionRuntimeState.CONNECTING,
            connectionId = params.connectionId,
            displayName = params.displayName.ifBlank { params.host },
            hostLabel = "${params.host}:${params.port}",
        )
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

    fun livePreviews(maxDimension: Int): Map<Long, Bitmap> {
        val buffers = synchronized(lock) {
            sessions.entries
                .filter { (_, info) -> info.state == RdpSessionRuntimeState.CONNECTED && info.connectionId > 0L }
                .map { (client, info) -> info.connectionId to client.buffer }
        }
        return buffers.mapNotNull { (id, buffer) ->
            buffer.snapshot(maxDimension)?.let { id to it }
        }.toMap()
    }

    private fun update(
        client: RdpClient,
        state: RdpSessionRuntimeState,
        connectionId: Long? = null,
        displayName: String? = null,
        hostLabel: String? = null,
    ) {
        synchronized(lock) {
            val previous = sessions[client]
            sessions[client] = RdpSessionInfo(
                connectionId = connectionId ?: previous?.connectionId ?: 0L,
                displayName = displayName ?: previous?.displayName.orEmpty(),
                hostLabel = hostLabel ?: previous?.hostLabel.orEmpty(),
                state = state,
            )
            publishLocked()
        }
    }

    private fun publishLocked() {
        val values = sessions.values.toList()
        _snapshot.value = RdpSessionRegistrySnapshot(
            activeCount = values.size,
            connectedCount = values.count { it.state == RdpSessionRuntimeState.CONNECTED },
            connectingCount = values.count { it.state == RdpSessionRuntimeState.CONNECTING },
            reconnectingCount = values.count { it.state == RdpSessionRuntimeState.RECONNECTING },
            sessions = values.sortedWith(compareBy<RdpSessionInfo> { it.connectionId }.thenBy { it.displayName }),
        )
    }
}
