package com.hanfengruyue.pocketrdp

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferences
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferencesRepository
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistry
import com.hanfengruyue.pocketrdp.core.rdp.RdpSessionRegistrySnapshot
import com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag
import com.hanfengruyue.pocketrdp.keepalive.OemKeepAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    repository: AppPreferencesRepository,
    private val sessionRegistry: RdpSessionRegistry,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = repository.currentPreferencesOrDefault(),
    )

    val persistedLanguageTag: StateFlow<String?> = repository.preferences
        .map { it.languageTag }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = null,
        )

    val sessionSnapshot: StateFlow<RdpSessionRegistrySnapshot> = sessionRegistry.snapshot

    val livePreviews: StateFlow<Map<Long, Bitmap>> = livePreviewsFlow(
        snapshot = sessionRegistry.snapshot,
        intervalMs = LIVE_PREVIEW_INTERVAL_MS,
        capture = { sessionRegistry.livePreviews(LIVE_PREVIEW_MAX_DIM) },
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 0L,
            replayExpirationMillis = 0L,
        ),
        initialValue = emptyMap(),
    )

    private val _showKeepAliveHint = MutableStateFlow(false)
    val showKeepAliveHint: StateFlow<Boolean> = _showKeepAliveHint.asStateFlow()

    private val sessionStores: MutableMap<Long, ViewModelStore> = mutableMapOf()

    /**
     * Detect a previous background process-kill at most once per process, and only when no session
     * is currently active. Checking while a session is running would wipe the active keep-alive
     * flag and can surface a stale historical exit reason as a spurious dialog.
     */
    fun checkKeepAliveKillOnce(context: Context, activeSessionIds: Set<Long>) {
        if (!keepAliveKillChecked.compareAndSet(false, true)) return
        if (activeSessionIds.isNotEmpty() || sessionRegistry.snapshot.value.activeConnectionIds.isNotEmpty()) {
            return
        }
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            if (sessionRegistry.snapshot.value.activeConnectionIds.isNotEmpty()) return@launch
            val wasKilled = SessionKeepAliveFlag.consumeWasActive(appContext) &&
                OemKeepAlive.lastExitWasKill(appContext)
            if (wasKilled) {
                _showKeepAliveHint.value = true
            }
        }
    }

    fun dismissKeepAliveHint() {
        _showKeepAliveHint.value = false
    }

    @Synchronized
    fun sessionStore(connectionId: Long): ViewModelStore =
        sessionStores.getOrPut(connectionId) { ViewModelStore() }

    @Synchronized
    fun clearSessionStore(connectionId: Long) {
        sessionStores.remove(connectionId)?.clear()
    }

    @Synchronized
    fun retainSessionStores(connectionIds: Set<Long>) {
        val staleIds = sessionStores.keys - connectionIds
        staleIds.forEach { id -> sessionStores.remove(id)?.clear() }
    }

    override fun onCleared() {
        synchronized(this) {
            sessionStores.values.forEach(ViewModelStore::clear)
            sessionStores.clear()
        }
    }

    private companion object {
        val keepAliveKillChecked = AtomicBoolean(false)
        const val STATE_STOP_TIMEOUT_MS = 5_000L

        // Home-card previews are intentionally sampled, not frame-driven, so the list never becomes
        // another per-frame renderer while a live session is open.
        const val LIVE_PREVIEW_INTERVAL_MS = 1_000L
        const val LIVE_PREVIEW_MAX_DIM = 960
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> livePreviewsFlow(
    snapshot: StateFlow<RdpSessionRegistrySnapshot>,
    intervalMs: Long,
    capture: () -> Map<Long, T>,
): Flow<Map<Long, T>> = snapshot
    .map { it.connectedCount > 0 }
    .distinctUntilChanged()
    .flatMapLatest { hasConnected ->
        if (!hasConnected) {
            flowOf(emptyMap())
        } else {
            flow {
                while (currentCoroutineContext().isActive) {
                    emit(capture())
                    delay(intervalMs)
                }
            }.flowOn(Dispatchers.Default)
        }
    }
