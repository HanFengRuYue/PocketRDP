package com.hanfengruyue.pocketrdp.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hanfengruyue.pocketrdp.core.rdp.SessionKeepAliveFlag
import com.hanfengruyue.pocketrdp.feature.connections.edit.ConnectionEditScreen
import com.hanfengruyue.pocketrdp.feature.connections.list.ConnectionListScreen
import com.hanfengruyue.pocketrdp.feature.connections.nav.ConnectionsRoutes
import com.hanfengruyue.pocketrdp.feature.session.SessionScreen
import com.hanfengruyue.pocketrdp.keepalive.KeepAliveGuideScreen
import com.hanfengruyue.pocketrdp.keepalive.OemKeepAlive
import com.hanfengruyue.pocketrdp.logs.LogScreen

object AppRoutes {
    const val SESSION_PATTERN = "session/{id}"
    const val LOGS = "logs"
    const val KEEPALIVE_GUIDE = "keepalive_guide"
    fun session(id: Long): String = "session/$id"
}

@Composable
fun PocketRdpNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ConnectionsRoutes.LIST,
    ) {
        composable(ConnectionsRoutes.LIST) {
            // Detect a previous background process-kill once on entry: the keep-alive flag was still
            // set (we never tore the session down cleanly) AND the last process exit looks like an
            // OS/OEM kill (not a clean exit or an app crash). If so, offer the keep-alive guide.
            val context = LocalContext.current
            var showKeepAliveHint by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                showKeepAliveHint = SessionKeepAliveFlag.consumeWasActive(context) &&
                    OemKeepAlive.lastExitWasKill(context)
            }
            ConnectionListScreen(
                onAddNew = { navController.navigate(ConnectionsRoutes.edit()) },
                onEdit = { id -> navController.navigate(ConnectionsRoutes.edit(id)) },
                onConnect = { id -> navController.navigate(AppRoutes.session(id)) },
                onOpenLogs = { navController.navigate(AppRoutes.LOGS) },
                onOpenKeepAliveGuide = { navController.navigate(AppRoutes.KEEPALIVE_GUIDE) },
                showKeepAliveHint = showKeepAliveHint,
            )
        }

        composable(AppRoutes.LOGS) {
            LogScreen(onClose = { navController.popBackStack() })
        }

        composable(AppRoutes.KEEPALIVE_GUIDE) {
            KeepAliveGuideScreen(onClose = { navController.popBackStack() })
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
            ConnectionEditScreen(onClose = { navController.popBackStack() })
        }

        composable(
            route = AppRoutes.SESSION_PATTERN,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            SessionScreen(
                connectionId = id,
                onClose = { navController.popBackStack() },
            )
        }
    }
}
