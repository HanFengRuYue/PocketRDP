package com.hanfengruyue.pocketrdp.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_preferences",
)

enum class ThemeMode(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageTag: String = LANGUAGE_SYSTEM,
    val toolbarAlpha: Float = DEFAULT_CHROME_ALPHA,
    val controlAlpha: Float = DEFAULT_CHROME_ALPHA,
)

@Singleton
class AppPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val preferences: Flow<AppPreferences> = context.appPreferencesDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            AppPreferences(
                themeMode = ThemeMode.entries.firstOrNull { it.value == prefs[KEY_THEME_MODE] }
                    ?: ThemeMode.SYSTEM,
                languageTag = sanitizeLanguageTag(prefs[KEY_LANGUAGE_TAG]),
                toolbarAlpha = (prefs[KEY_TOOLBAR_ALPHA] ?: DEFAULT_CHROME_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA),
                controlAlpha = (prefs[KEY_CONTROL_ALPHA] ?: DEFAULT_CHROME_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA),
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appPreferencesDataStore.edit { it[KEY_THEME_MODE] = mode.value }
    }

    suspend fun setLanguageTag(tag: String) {
        val sanitized = sanitizeLanguageTag(tag)
        context.appPreferencesDataStore.edit {
            if (sanitized == LANGUAGE_SYSTEM) {
                it.remove(KEY_LANGUAGE_TAG)
            } else {
                it[KEY_LANGUAGE_TAG] = sanitized
            }
        }
    }

    suspend fun setToolbarAlpha(alpha: Float) {
        context.appPreferencesDataStore.edit { it[KEY_TOOLBAR_ALPHA] = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA) }
    }

    suspend fun setControlAlpha(alpha: Float) {
        context.appPreferencesDataStore.edit { it[KEY_CONTROL_ALPHA] = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA) }
    }

    private companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_LANGUAGE_TAG = stringPreferencesKey("language_tag")
        private val KEY_TOOLBAR_ALPHA = floatPreferencesKey("toolbar_alpha")
        private val KEY_CONTROL_ALPHA = floatPreferencesKey("control_alpha")
    }
}

const val LANGUAGE_SYSTEM = "system"
val SUPPORTED_LANGUAGE_TAGS = setOf(
    LANGUAGE_SYSTEM,
    "zh-CN",
    "en",
    "es",
    "fr",
    "de",
    "ja",
    "ko",
    "pt-BR",
)

fun sanitizeLanguageTag(tag: String?): String =
    tag?.trim()?.takeIf { it in SUPPORTED_LANGUAGE_TAGS } ?: LANGUAGE_SYSTEM

const val DEFAULT_CHROME_ALPHA = 0.7f
private const val MIN_ALPHA = 0.35f
private const val MAX_ALPHA = 1f
