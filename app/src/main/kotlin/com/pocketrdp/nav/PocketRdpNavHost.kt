package com.pocketrdp.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketrdp.feature.connections.edit.ConnectionEditScreen
import com.pocketrdp.feature.connections.list.ConnectionListScreen
import com.pocketrdp.feature.connections.nav.ConnectionsRoutes
import com.pocketrdp.feature.session.SessionScreen

object AppRoutes {
    const val SESSION_PATTERN = "session/{id}"
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
            ConnectionListScreen(
                onAddNew = { navController.navigate(ConnectionsRoutes.edit()) },
                onEdit = { id -> navController.navigate(ConnectionsRoutes.edit(id)) },
                onConnect = { id -> navController.navigate(AppRoutes.session(id)) },
            )
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
