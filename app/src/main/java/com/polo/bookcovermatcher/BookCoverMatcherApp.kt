package com.polo.bookcovermatcher

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.polo.bookcovermatcher.ui.home.HomeScreen

/**
 * Top‑level Compose application entry point.
 */
@Composable
fun BookCoverMatcherApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Home.route) {
        composable(Routes.Home.route) { HomeScreen() }
        // Future destinations will be added in later phases.
    }
}

/** Simple route holder. */
object Routes {
    @Suppress("ConstPropertyName")
    object Home {
        const val route = "home"
    }
}