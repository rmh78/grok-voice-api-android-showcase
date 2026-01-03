package com.example.voiceapitest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.voiceapitest.ui.theme.AppTheme

data class MainScreenData(
    val buttonsEnabled: Boolean,
    val isConnected: Boolean,
    val isSpeakActive: Boolean,
    val status: String,
    val lastTool: String,
    val transcript: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    data: MainScreenData,
    navController: NavHostController,
    onConnectChange: (Boolean) -> Unit,
    onSpeakChange: (Boolean) -> Unit,
    onDumpClick: () -> Unit,
    onNavGraphReady: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Voice API", fontWeight = FontWeight.ExtraBold)
                },
                actions = {
                    Text("Connect", Modifier.padding(horizontal = 10.dp))
                    Switch(
                        enabled = data.buttonsEnabled,
                        checked = data.isConnected,
                        onCheckedChange = { enabled ->
                            onConnectChange(enabled)
                        }
                    )
                    Text("Speak", Modifier.padding(horizontal = 10.dp))
                    Switch(
                        enabled = data.buttonsEnabled && data.isConnected,
                        checked = data.isSpeakActive,
                        onCheckedChange = { enabled ->
                            onSpeakChange(enabled)
                        }
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    Button(onClick = {
                        navController.navigate(Screen.Home)
                        onDumpClick()
                    }) { Text("Home") }
                    Spacer(modifier = Modifier.width(5.dp))
                    Button(onClick = { navController.navigate(Screen.Favorites) }) { Text("Favorites") }
                    Spacer(modifier = Modifier.width(5.dp))
                    Button(onClick = { navController.navigate(Screen.Settings) }) { Text("Settings") }
                    Spacer(modifier = Modifier.width(5.dp))
                    Button(onClick = { navController.navigate(Screen.Music) }) { Text("Music") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .padding(20.dp)
                    .border(2.dp, Color.Black)
                    .background(Color.LightGray)
                    .padding(10.dp)
            ) {
                Row {
                    Text("Status: ", fontWeight = FontWeight.Bold)
                    Text(data.status)
                }
                Row {
                    Text("Last tool: ", fontWeight = FontWeight.Bold)
                    Text(data.lastTool)
                }

                Text("Transcript: ", fontWeight = FontWeight.Bold)

                val scrollState = rememberScrollState()
                LaunchedEffect(data.transcript) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    Text(text = data.transcript)
                }
            }
            AppNavHost(
                navController = navController,
                modifier = Modifier
                    .semantics(mergeDescendants = false) { contentDescription = "nav_host" }
                    .padding(20.dp)
                    .fillMaxSize()
                    .border(2.dp, Color.Black)
                    .background(Color.White)
                    .padding(10.dp)
            )
            onNavGraphReady()
        }
    }
}

@Preview
@Composable
private fun MainWithHomeScreenPreview(
    @PreviewParameter(SubScreenStateProvider::class) subScreen: Screen
) {
    MainScreenPreview(subScreen)
}

class SubScreenStateProvider : PreviewParameterProvider<Screen> {
    override val values: Sequence<Screen> = sequenceOf(
        Screen.Home,
        Screen.Favorites,
        Screen.Settings,
        Screen.Music,
    )
}

@Composable
private fun MainScreenPreview(screen: Screen) {
    AppTheme {
        val navController: NavHostController = rememberNavController()
        MainScreen(
            data = MainScreenData(
                buttonsEnabled = true,
                isConnected = false,
                isSpeakActive = false,
                status = "Status: Connected",
                lastTool = "navigate_to_screen",
                transcript = "Transcript will appear here. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. Last word."
            ),
            navController = navController,
            onConnectChange = {},
            onSpeakChange = {},
            onDumpClick = {},
            onNavGraphReady = { navController.navigate(screen) },
        )
    }
}