package com.example.voiceapitest

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.voiceapitest.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val voiceApiClient = VoiceApiClient(
        ::handleToolCall,
        lifecycleScope
    )

    private val visionApiClient = VisionApiClient()

    private lateinit var navController: NavHostController

    private val _buttonsEnabled = MutableStateFlow(false)
    private val buttonsEnabled: StateFlow<Boolean> = _buttonsEnabled

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            _buttonsEnabled.value = true
        } else {
            Log.w(LOG_TAG, "no permission for RECORD_AUDIO granted")
        }
    }

    private var uiTreeCollectionJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAccessibilityService()
        checkPermissions()
        collectUiTreeDump()

        setContent {
            AppTheme {
                navController = rememberNavController()
                val buttonsEnabled by buttonsEnabled.collectAsState()
                val isConnected by voiceApiClient.isConnected.collectAsState()
                val isSpeakActive by voiceApiClient.isSpeakActive.collectAsState()
                val status by voiceApiClient.status.collectAsState()
                val lastTool by voiceApiClient.lastTool.collectAsState()
                val transcript by voiceApiClient.transcript.collectAsState()

                MainScreen(
                    data = MainScreenData(
                        buttonsEnabled = buttonsEnabled,
                        isConnected = isConnected,
                        isSpeakActive = isSpeakActive,
                        status = status,
                        lastTool = lastTool,
                        transcript = transcript,
                    ),
                    navController = navController,
                    onConnectChange = ::onConnectChange,
                    onSpeakChange = ::onSpeakChange,
                    onDumpClick = { triggerDump("nav_host") }
                )
            }
        }
    }

    private fun collectUiTreeDump() {
        uiTreeCollectionJob = lifecycleScope.launchWhenStarted {
            UiTreeRepository.uiTree.collect { uiTree ->
                Log.d(LOG_TAG, "UI-Tree: $uiTree")
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            _buttonsEnabled.value = true
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkAccessibilityService() {
        if (!AccessibilityUtils.isAccessibilityServiceEnabled(
                this,
                UiTreeDumpService::class.java
            )
        ) {
            AlertDialog.Builder(this)
                .setTitle("Activate Accessibility Service")
                .setMessage("The app requires access to the accessibility service. Please enable it in the settings.")
                .setPositiveButton("Settings") { _, _ ->
                    AccessibilityUtils.openAccessibilitySettings(
                        this
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun triggerDump(dumpId: String? = null) {
        val intent = Intent(this, UiTreeDumpService::class.java)
            .apply {
                action = "DUMP_UI_TREE"
                if (dumpId != null) {
                    putExtra("dumpId", dumpId)
                }
            }
        startService(intent)
    }

    suspend fun triggerDumpAndWait(dumpId: String? = null, timeoutMs: Long = 5000L): String? {
        triggerDump(dumpId)

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                UiTreeRepository.uiTree.first()
            }
        }
    }

    private fun onConnectChange(enabled: Boolean) {
        if (enabled) {
            voiceApiClient.connect()
        } else {
            voiceApiClient.disconnect()
        }
    }

    private fun onSpeakChange(enabled: Boolean) {
        if (enabled) {
            voiceApiClient.startSpeak()
        } else {
            voiceApiClient.stopSpeak()
        }
    }

    private fun handleToolCall(name: String, args: JSONObject, callId: String) {
        when (name) {
            "analyze_ui_with_screenshot" -> {
                val userPrompt = args.optString(
                    "prompt",
                    "Analyze the app screenshot and describe its content briefly."
                )
                val bitmap = captureScreen()
                val response = visionApiClient.analyzeImage(bitmap, userPrompt)
                voiceApiClient.sendToolCallResponse(response, callId)
            }
            "analyze_ui_with_ui_tree" -> {
                lifecycleScope.launch {
                    val response = triggerDumpAndWait("nav_host", 5000L)
                    if (response != null) {
                        voiceApiClient.sendToolCallResponse(response, callId)
                    } else {
                        voiceApiClient.sendToolCallResponse(callId, "no ui tree received")
                    }
                }
            }
            "navigate_to_screen" -> {
                val destination = args.getString("destination")
                val success = navigateToDestination(destination)
                val response = if (success) {
                    "successful navigation to screen: $destination."
                } else {
                    "screen: $destination could not be found."
                }
                voiceApiClient.sendToolCallResponse(response, callId)
            }
        }
    }

    private fun navigateToDestination(destination: String): Boolean {
        Log.i(LOG_TAG, "navigate to $destination")
        return when (destination) {
            "home" -> Screen.Home
            "favorites" -> Screen.Favorites
            "settings" -> Screen.Settings
            "music" -> Screen.Music
            else -> null
        }?.let { screen ->
            runOnUiThread { navController.navigate(screen) }
            true
        } ?: false
    }

    private fun captureScreen(): Bitmap {
        val view: View = window.decorView.rootView
        val bitmap = createBitmap(view.width, view.height)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceApiClient.disconnect()
    }

    companion object {
        private const val LOG_TAG = "MainActivity"
    }
}