package com.example.voiceapitest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun HomeScreen() {
    Column {
        Text("Home Screen", fontSize = 30.sp)
        Box(modifier = Modifier
            .size(100.dp)
            .background(Color.Red)
            .padding(10.dp)
            .semantics { contentDescription = "red box" }
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(modifier = Modifier
            .size(200.dp)
            .background(Color.Blue)
            .padding(10.dp)
            .semantics { contentDescription = "blue box" }
        )
    }
}

@Composable
fun FavoritesScreen() {
    Text("Favorites Screen", fontSize = 30.sp)
}

@Composable
fun SettingsScreen() {
    Column {
        Text("Settings Screen", fontSize = 30.sp)
        Box(modifier = Modifier
            .size(150.dp)
            .background(Color.Green)
            .padding(10.dp)
            .semantics { contentDescription = "green box" }
        )
    }
}

@Composable
fun MusicScreen() {
    Text("Music Screen", fontSize = 30.sp)
}