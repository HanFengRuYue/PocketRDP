package com.hanfengruyue.pocketrdp

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferences
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferencesRepository
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistry
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistrySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AppViewModel @Inject constructor(
    repository: AppPreferencesRepository,
    private val sessionRegistry: RdpSessionRegistry,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPreferences(),
    )

    val sessionSnapshot: StateFlow<RdpSessionRegistrySnapshot> = sessionRegistry.snapshot.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RdpSessionRegistrySnapshot(),
    )

    val livePreviews: StateFlow<Map<Long, Bitmap>> = flow {
        while (currentCoroutineContext().isActive) {
            emit(sessionRegistry.livePreviews(LIVE_PREVIEW_MAX_DIM))
            delay(LIVE_PREVIEW_INTERVAL_MS)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    private companion object {
        // Home-card previews are intentionally sampled, not frame-driven, so the list never becomes
        // another per-frame renderer while a live session is open.
        const val LIVE_PREVIEW_INTERVAL_MS = 1_000L
        const val LIVE_PREVIEW_MAX_DIM = 960
    }
}
