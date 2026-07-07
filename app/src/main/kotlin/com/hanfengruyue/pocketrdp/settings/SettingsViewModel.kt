package com.hanfengruyue.pocketrdp.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferences
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferencesRepository
import com.hanfengruyue.pocketrdp.core.data.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AppPreferencesRepository,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPreferences(),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setLanguageTag(tag: String) {
        viewModelScope.launch { repository.setLanguageTag(tag) }
    }

    fun setToolbarAlpha(alpha: Float) {
        viewModelScope.launch { repository.setToolbarAlpha(alpha) }
    }

    fun setControlAlpha(alpha: Float) {
        viewModelScope.launch { repository.setControlAlpha(alpha) }
    }

    fun setSimulatedCursorScale(scale: Float) {
        viewModelScope.launch { repository.setSimulatedCursorScale(scale) }
    }
}
