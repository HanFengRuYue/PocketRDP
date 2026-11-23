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
    val activeConnectionIds: Set<Long> = if (sessions.isEmpty()) {
        emptySet()
    } else {
        sessions.mapNotNullTo(LinkedHashSet(sessions.size)) { info ->
            info.connectionId.takeIf { it > 0L }
        }
    }
}

@Singleton
class RdpSessionRegistry @Inject constructor() {
    private val lock = Any()
    private val sessions = IdentityHashMap<RdpClient, RdpSessionInfo>()
    private val previewCache = LivePreviewCache<RdpClient, Bitmap>(lock)
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
            previewCache.removeLocked(client)
            if (sessions.remove(client) != null) publishLocked()
        }
    }

    fun unregisterIfReconnecting(client: RdpClient): Boolean = synchronized(lock) {
        if (sessions[client]?.state != RdpSessionRuntimeState.RECONNECTING) return@synchronized false
        previewCache.removeLocked(client)
        sessions.remove(client)
        publishLocked()
        true
    }

    fun contains(client: RdpClient): Boolean = synchronized(lock) {
        sessions.containsKey(client)
    }

    /**
     * Run [action] while holding the same lock used to register sessions, but only if the registry
     * is still empty. This closes the count-then-stop race where a new session could register
     * between an `activeCount == 0` check and stopping the aggregate foreground service.
     */
    fun runIfNoActiveSessions(action: () -> Unit): Boolean = synchronized(lock) {
        if (sessions.isNotEmpty()) return@synchronized false
        action()
        true
    }

    fun isConnectionActive(connectionId: Long): Boolean = synchronized(lock) {
        connectionId > 0L && sessions.values.any { it.connectionId == connectionId }
    }

    fun disconnectAll() {
        val clients = synchronized(lock) { sessions.keys.toList() }
        clients.forEach { it.disconnect() }
    }

    fun livePreviews(maxDimension: Int): Map<Long, Bitmap> {
        val connected = synchronized(lock) {
            if (sessions.isEmpty()) return emptyMap()
            val active = ArrayList<Pair<RdpClient, Long>>(sessions.size)
            for ((client, info) in sessions) {
                if (info.state == RdpSessionRuntimeState.CONNECTED && info.connectionId > 0L) {
                    active.add(client to info.connectionId)
                }
            }
            active
        }
        if (connected.isEmpty()) return emptyMap()
        val previews = LinkedHashMap<Long, Bitmap>(connected.size)
        for ((client, connectionId) in connected) {
            val buffer = client.buffer
            val preview = previewCache.getOrCreate(
                key = client,
                hasPublishedFrame = buffer.hasPublishedFrame.value,
                frontSeq = buffer.peekFrontSeq(),
                maxDimension = maxDimension,
                isKeyValidLocked = { sessions[client]?.state == RdpSessionRuntimeState.CONNECTED },
                create = { buffer.snapshot(maxDimension) },
            )
            if (preview != null) {
                previews[connectionId] = preview
            }
        }
        return previews
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
            if (state != RdpSessionRuntimeState.CONNECTED) {
                previewCache.removeLocked(client)
            }
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

/**
 * Caches the latest scaled preview per session keyed by `(frontSeq, maxDimension)` so static
 * desktops avoid redundant bitmap scaling and allocations on periodic preview ticks.
 *
 * Cached preview bitmaps are never `recycle()`d — Compose / RenderThread may still draw the
 * previously returned reference; let GC reclaim replaced bitmaps safely.
 */
internal class LivePreviewCache<K : Any, F : Any>(
    private val lock: Any = Any(),
) {
    private data class Entry<F : Any>(
        val frontSeq: Long,
        val maxDimension: Int,
        val preview: F,
    )

    private val entries = IdentityHashMap<K, Entry<F>>()

    fun getOrCreate(
        key: K,
        hasPublishedFrame: Boolean,
        frontSeq: Long,
        maxDimension: Int,
        isKeyValidLocked: () -> Boolean = { true },
        create: () -> F?,
    ): F? {
        if (!hasPublishedFrame || maxDimension <= 0) {
            synchronized(lock) { entries.remove(key) }
            return null
        }
        val cached = synchronized(lock) { entries[key] }
        if (cached != null && cached.frontSeq == frontSeq && cached.maxDimension == maxDimension) {
            return cached.preview
        }
        val fresh = create() ?: run {
            synchronized(lock) { entries.remove(key) }
            return null
        }
        synchronized(lock) {
            if (isKeyValidLocked()) {
                entries[key] = Entry(frontSeq = frontSeq, maxDimension = maxDimension, preview = fresh)
            } else {
                entries.remove(key)
            }
        }
        return fresh
    }

    fun removeLocked(key: K) {
        entries.remove(key)
    }
}
