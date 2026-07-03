package com.hanfengruyue.pocketrdp

import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.data.preferences.LANGUAGE_SYSTEM
import com.hanfengruyue.pocketrdp.core.data.preferences.ThemeMode
import com.hanfengruyue.pocketrdp.core.data.preferences.sanitizeLanguageTag
import com.hanfengruyue.pocketrdp.core.ui.theme.PocketRdpTheme
import com.hanfengruyue.pocketrdp.feature.session.SessionScreen
import com.hanfengruyue.pocketrdp.nav.PocketRdpNavHost
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot(appViewModel: AppViewModel = hiltViewModel()) {
    val prefs by appViewModel.preferences.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val systemConfiguration = remember { Configuration(context.resources.configuration) }
    val systemLocaleList = remember { LocaleList.getDefault() }
    val languageTag = sanitizeLanguageTag(prefs.languageTag)
    val activeLocaleList = remember(languageTag, systemLocaleList) {
        if (languageTag == LANGUAGE_SYSTEM) {
            systemLocaleList
        } else {
            LocaleList(Locale.forLanguageTag(languageTag))
        }
    }
    val localizedConfiguration = remember(systemConfiguration, activeLocaleList) {
        systemConfiguration.localizedCopy(activeLocaleList)
    }
    val localizedResources = remember(context, localizedConfiguration, languageTag) {
        if (languageTag == LANGUAGE_SYSTEM) {
            context.resources
        } else {
            context.createConfigurationContext(localizedConfiguration).resources
        }
    }
    PocketRdpTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(
            LocalConfiguration provides localizedConfiguration,
            LocalResources provides localizedResources,
        ) {
            val activeSessionIds = remember { mutableStateListOf<Long>() }
            var foregroundSessionId by remember { mutableStateOf<Long?>(null) }
            Surface(modifier = Modifier.fillMaxSize()) {
                PocketRdpNavHost(
                    activeSessionIds = activeSessionIds.toSet(),
                    onConnect = { id ->
                        if (!activeSessionIds.contains(id)) activeSessionIds.add(id)
                        foregroundSessionId = id
                    },
                )
                foregroundSessionId?.let { id ->
                    SessionScreen(
                        connectionId = id,
                        onClose = { foregroundSessionId = null },
                        onDisconnect = {
                            activeSessionIds.remove(id)
                            foregroundSessionId = null
                        },
                        toolbarAlpha = prefs.toolbarAlpha,
                        controlAlpha = prefs.controlAlpha,
                        viewModel = hiltViewModel(key = "rdp-session-$id"),
                    )
                }
            }
        }
    }
}

private fun Configuration.localizedCopy(localeList: LocaleList): Configuration =
    Configuration(this).apply {
        setLocales(localeList)
    }
