package com.example.voiceapitest

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home,
        modifier = modifier
    ) {
        composable<Screen.Home> { HomeScreen() }
        composable<Screen.Favorites> { FavoritesScreen() }
        composable<Screen.Settings> { SettingsScreen() }
        composable<Screen.Music> { MusicScreen() }
    }
}

@Serializable
sealed class Screen {
    @Serializable
    data object Home : Screen()
    @Serializable
    data object Favorites : Screen()
    @Serializable
    data object Settings : Screen()
    @Serializable
    data object Music : Screen()
}