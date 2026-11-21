package com.hanfengruyue.pocketrdp

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.hanfengruyue.pocketrdp.core.data.preferences.AppPreferences
import com.hanfengruyue.pocketrdp.core.data.preferences.LANGUAGE_SYSTEM
import com.hanfengruyue.pocketrdp.core.data.preferences.ThemeMode
import com.hanfengruyue.pocketrdp.core.data.preferences.localizedContextForLanguageTag
import com.hanfengruyue.pocketrdp.core.data.preferences.sanitizeLanguageTag
import com.hanfengruyue.pocketrdp.core.ui.theme.PocketRdpTheme
import com.hanfengruyue.pocketrdp.feature.session.SessionScreen
import com.hanfengruyue.pocketrdp.nav.PocketRdpNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot(appViewModel: AppViewModel = hiltViewModel()) {
    val prefs by appViewModel.preferences.collectAsStateWithLifecycle()
    val persistedLanguageTag by appViewModel.persistedLanguageTag.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val baseResources = LocalResources.current
    val languageTag = sanitizeLanguageTag(prefs.languageTag)
    val localizedContext = remember(context, baseConfiguration, languageTag) {
        context.localizedContextForLanguageTag(languageTag)
    }
    val localizedConfiguration = remember(baseConfiguration, localizedContext, languageTag) {
        if (languageTag == LANGUAGE_SYSTEM) baseConfiguration else localizedContext.resources.configuration
    }
    val localizedResources = remember(baseResources, localizedContext, languageTag) {
        if (languageTag == LANGUAGE_SYSTEM) baseResources else localizedContext.resources
    }

    SyncAppLocale(persistedLanguageTag = persistedLanguageTag)
    SyncSystemBars(darkTheme = darkTheme)

    PocketRdpTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(
            LocalConfiguration provides localizedConfiguration,
            LocalResources provides localizedResources,
        ) {
            val sessionSnapshot by appViewModel.sessionSnapshot.collectAsStateWithLifecycle()
            val showKeepAliveHint by appViewModel.showKeepAliveHint.collectAsStateWithLifecycle()
            var foregroundSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
            val isSessionForeground = foregroundSessionId != null
            val livePreviews = if (!isSessionForeground) {
                appViewModel.livePreviews.collectAsStateWithLifecycle().value
            } else {
                emptyMap()
            }
            LaunchedEffect(Unit) {
                appViewModel.checkKeepAliveKillOnce(context, sessionSnapshot.activeConnectionIds)
            }
            LaunchedEffect(sessionSnapshot.activeConnectionIds, foregroundSessionId) {
                appViewModel.retainSessionStores(
                    sessionSnapshot.activeConnectionIds + listOfNotNull(foregroundSessionId),
                )
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().occludedBySession(isSessionForeground)) {
                    PocketRdpNavHost(
                        activeSessionIds = sessionSnapshot.activeConnectionIds,
                        liveThumbnails = livePreviews,
                        isOccluded = isSessionForeground,
                        showKeepAliveHint = showKeepAliveHint,
                        onDismissKeepAliveHint = appViewModel::dismissKeepAliveHint,
                        onConnect = { id ->
                            foregroundSessionId = id
                        },
                    )
                }
                foregroundSessionId?.let { id ->
                    SessionOverlay(
                        connectionId = id,
                        appViewModel = appViewModel,
                        prefs = prefs,
                        onClose = { foregroundSessionId = null },
                        onDisconnect = {
                            foregroundSessionId = null
                            appViewModel.clearSessionStore(id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncAppLocale(persistedLanguageTag: String?) {
    val context = LocalContext.current
    LaunchedEffect(context, persistedLanguageTag) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || persistedLanguageTag == null) {
            return@LaunchedEffect
        }
        val sanitized = sanitizeLanguageTag(persistedLanguageTag)
        val desired = if (sanitized == LANGUAGE_SYSTEM) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(sanitized)
        }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        if (localeManager != null && localeManager.applicationLocales != desired) {
            localeManager.applicationLocales = desired
        }
    }
}

@Composable
private fun SyncSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    LaunchedEffect(activity, darkTheme) {
        val componentActivity = activity as? ComponentActivity ?: return@LaunchedEffect
        val barStyle = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        componentActivity.enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle,
        )
    }
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}

@Composable
private fun SessionOverlay(
    connectionId: Long,
    appViewModel: AppViewModel,
    prefs: AppPreferences,
    onClose: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val parentOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "A ViewModelStoreOwner is required for a remote session"
    }
    val factoryOwner = parentOwner as? HasDefaultViewModelProviderFactory
        ?: error("The parent ViewModelStoreOwner must provide a default Hilt factory")
    val sessionOwner = remember(connectionId, factoryOwner) {
        SessionViewModelStoreOwner(
            viewModelStore = appViewModel.sessionStore(connectionId),
            factoryOwner = factoryOwner,
        )
    }
    SessionScreen(
        connectionId = connectionId,
        onClose = onClose,
        onDisconnect = onDisconnect,
        toolbarAlpha = prefs.toolbarAlpha,
        controlAlpha = prefs.controlAlpha,
        cursorScale = prefs.simulatedCursorScale,
        viewModel = hiltViewModel(
            viewModelStoreOwner = sessionOwner,
            key = "rdp-session-$connectionId",
        ),
    )
}

private class SessionViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val factoryOwner: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = factoryOwner.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = factoryOwner.defaultViewModelCreationExtras
}

private fun Modifier.occludedBySession(occluded: Boolean): Modifier =
    if (occluded) {
        this
            .focusProperties {
                canFocus = false
                onEnter = { cancelFocusChange() }
            }
            .focusGroup()
            .clearAndSetSemantics {
                hideFromAccessibility()
            }
    } else {
        this
    }

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
