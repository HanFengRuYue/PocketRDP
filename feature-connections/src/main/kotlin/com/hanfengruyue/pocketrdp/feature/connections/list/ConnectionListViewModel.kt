package com.hanfengruyue.pocketrdp.feature.connections.list

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionOperationCoordinator
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionRepository
import com.hanfengruyue.pocketrdp.core.data.thumbnail.ConnectionThumbnailStore
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Immutable UI projection of [ConnectionEntity] for list cards. Excludes mutable credential
 * [ByteArray] columns so Compose can treat each list item as strongly stable and skip unaffected
 * cards on 1 Hz live-preview updates.
 */
@Immutable
data class ConnectionListItem(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val lastUsedAt: Long,
)

@Immutable
data class ConnectionListUiState(
    val isLoading: Boolean = true,
    val connections: List<ConnectionListItem> = emptyList(),
)

internal fun ConnectionEntity.toListItem(): ConnectionListItem = ConnectionListItem(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    lastUsedAt = lastUsedAt,
)

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val thumbnailStore: ConnectionThumbnailStore,
    private val sessionRegistry: RdpSessionRegistry,
    private val operationCoordinator: ConnectionOperationCoordinator,
) : ViewModel() {

    val uiState: StateFlow<ConnectionListUiState> =
        repository.observeAll()
            .map { list ->
                ConnectionListUiState(
                    isLoading = false,
                    connections = list.map(ConnectionEntity::toListItem),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
                initialValue = ConnectionListUiState(),
            )

    val thumbnailRevisions: StateFlow<Map<Long, Long>> = thumbnailStore.revisionsById

    fun delete(connectionId: Long) {
        viewModelScope.launch {
            operationCoordinator.withExclusiveOperation delete@{
                if (sessionRegistry.isConnectionActive(connectionId)) return@delete
                repository.deleteById(connectionId)
                // Drop the desktop thumbnail too so a future connection reusing the same row id
                // cannot inherit a stale picture.
                thumbnailStore.delete(connectionId)
            }
        }
    }

    /** Synchronously read an already-cached thumbnail bitmap to avoid placeholder flashes on scroll. */
    fun peekCachedThumbnail(id: Long): Bitmap? = thumbnailStore.peekCached(id)

    /**
     * Load the captured remote-desktop thumbnail for [id], or null if none has been saved yet.
     * Decoded off the main thread; the card shows a placeholder while this is null.
     */
    suspend fun loadThumbnail(id: Long): Bitmap? =
        withContext(Dispatchers.IO) { thumbnailStore.load(id) }

    private companion object {
        const val STATE_STOP_TIMEOUT_MS = 5_000L
    }
}
