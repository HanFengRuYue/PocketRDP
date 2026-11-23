package com.hanfengruyue.pocketrdp.nav

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hanfengruyue.pocketrdp.feature.connections.edit.ConnectionEditScreen
import com.hanfengruyue.pocketrdp.feature.connections.list.ConnectionListScreen
import com.hanfengruyue.pocketrdp.feature.connections.list.ConnectionRuntimeState
import com.hanfengruyue.pocketrdp.feature.connections.list.KeepAliveHintActions
import com.hanfengruyue.pocketrdp.feature.connections.nav.ConnectionsRoutes
import com.hanfengruyue.pocketrdp.keepalive.KeepAliveGuideScreen
import com.hanfengruyue.pocketrdp.logs.LogScreen
import com.hanfengruyue.pocketrdp.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow

object AppRoutes {
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val KEEPALIVE_GUIDE = "keepalive_guide"
}

@Composable
fun PocketRdpNavHost(
    activeSessionIds: Set<Long>,
    livePreviewsFlow: StateFlow<Map<Long, Bitmap>>,
    isOccluded: Boolean,
    showKeepAliveHint: Boolean,
    onDismissKeepAliveHint: () -> Unit,
    onConnect: (Long) -> Unit,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ConnectionsRoutes.LIST,
    ) {
        composable(ConnectionsRoutes.LIST) {
            // Collect live memory previews strictly while the connection list destination is
            // composed and unoccluded, preventing background 1 Hz captures in Settings/Edit/Logs.
            val liveThumbnails = if (!isOccluded) {
                livePreviewsFlow.collectAsStateWithLifecycle().value
            } else {
                emptyMap()
            }
            ConnectionListScreen(
                onAddNew = { navController.navigateSingleTop(ConnectionsRoutes.edit()) },
                onEdit = { id -> navController.navigateSingleTop(ConnectionsRoutes.edit(id)) },
                onConnect = onConnect,
                onOpenSettings = { navController.navigateSingleTop(AppRoutes.SETTINGS) },
                keepAliveHint = KeepAliveHintActions(
                    visible = showKeepAliveHint,
                    onOpenGuide = { navController.navigateSingleTop(AppRoutes.KEEPALIVE_GUIDE) },
                    onDismiss = onDismissKeepAliveHint,
                ),
                connectionRuntime = ConnectionRuntimeState(
                    activeSessionIds = activeSessionIds,
                    liveThumbnails = liveThumbnails,
                    isOccluded = isOccluded,
                ),
            )
        }

        composable(AppRoutes.SETTINGS) {
            SettingsScreen(
                onClose = { navController.popBackStackSafely() },
                onOpenKeepAlive = { navController.navigateSingleTop(AppRoutes.KEEPALIVE_GUIDE) },
                onOpenLogs = { navController.navigateSingleTop(AppRoutes.LOGS) },
            )
        }

        composable(AppRoutes.LOGS) {
            LogScreen(onClose = { navController.popBackStackSafely() })
        }

        composable(AppRoutes.KEEPALIVE_GUIDE) {
            KeepAliveGuideScreen(onClose = { navController.popBackStackSafely() })
        }

        composable(
            route = ConnectionsRoutes.EDIT_PATTERN,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ConnectionEditScreen(onClose = { navController.popBackStackSafely() })
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

private fun NavHostController.popBackStackSafely() {
    if (previousBackStackEntry != null) {
        popBackStack()
    }
}
