package com.pocketrdp.feature.connections.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketrdp.core.data.model.ConnectionEntity
import com.pocketrdp.core.data.repository.ConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionListUiState(
    val isLoading: Boolean = true,
    val connections: List<ConnectionEntity> = emptyList(),
)

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ConnectionRepository,
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
        viewModelScope.launch { repository.delete(entity) }
    }
}
