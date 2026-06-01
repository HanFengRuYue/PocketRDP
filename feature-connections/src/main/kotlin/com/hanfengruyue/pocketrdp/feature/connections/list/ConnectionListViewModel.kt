package com.hanfengruyue.pocketrdp.feature.connections.list

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import com.hanfengruyue.pocketrdp.core.data.repository.ConnectionRepository
import com.hanfengruyue.pocketrdp.core.data.thumbnail.ConnectionThumbnailStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ConnectionListUiState(
    val isLoading: Boolean = true,
    val connections: List<ConnectionEntity> = emptyList(),
)

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val thumbnailStore: ConnectionThumbnailStore,
) : ViewModel() {

    val uiState: StateFlow<ConnectionListUiState> =
        repository.observeAll()
            .map { list -> ConnectionListUiState(isLoading = false, connections = list) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ConnectionListUiState(),
            )

    fun delete(entity: ConnectionEntity) {
        viewModelScope.launch {
            repository.delete(entity)
            // Drop the desktop thumbnail too so a future connection reusing the same row id can't
            // inherit a stale picture.
            thumbnailStore.delete(entity.id)
        }
    }

    /**
     * Load the captured remote-desktop thumbnail for [id], or null if none has been saved yet.
     * Decoded off the main thread; the card shows a placeholder while this is null.
     */
    suspend fun loadThumbnail(id: Long): Bitmap? =
        withContext(Dispatchers.IO) { thumbnailStore.load(id) }
}
