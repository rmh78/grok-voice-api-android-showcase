package com.example.voiceapitest

import android.util.Log
import com.example.voiceapitest.AudioStreamManager.Companion.SAMPLE_RATE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VoiceApiClient(
    private val handleToolCall: (name: String, args: JSONObject, callId: String) -> Unit,
) {
    private val audioManager = AudioStreamManager()

    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isSpeakActive = MutableStateFlow(false)
    val isSpeakActive: StateFlow<Boolean> = _isSpeakActive

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _lastTool = MutableStateFlow("")
    val lastTool: StateFlow<String> = _lastTool

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    val audioLevel = audioManager.audioLevel

    fun connect() {
        Log.i(LOG_TAG, "connect")

        val client = OkHttpClient.Builder()
            .webSocketCloseTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("wss://api.x.ai/v1/realtime?model=grok-4-voice")
            .addHeader("Authorization", "Bearer ${BuildConfig.XAI_API_KEY}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(LOG_TAG,"onOpen: $response")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                val type = json.getString("type")
                when (type) {
                    "conversation.created" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        configureSession(webSocket)
                    }
                    "session.updated" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        _isConnected.value = true
                        _status.value = "Connected"
                    }
                    "input_audio_buffer.speech_started" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        _status.value = "You are speaking"
                    }
                    "input_audio_buffer.speech_stopped" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        _status.value = "You stopped speaking"
                    }
                    "input_audio_buffer.committed" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        stopSpeak()
                    }
                    "conversation.item.created" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        _status.value = "Voice assistant is answering"
                    }
                    "response.audio.delta" -> {
                        val delta = json.getString("delta")
                        if (delta.isNotEmpty()) {
                            audioManager.playAudio(delta)
                        }
                    }
                    "response.audio_transcript.delta" -> {
                        val delta = json.getString("delta")
                        _transcript.value += delta
                    }
                    "response.function_call_arguments.done" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        val name = json.getString("name")
                        _lastTool.value = name
                        val arguments = JSONObject(json.getString("arguments"))
                        val callId = json.getString("call_id")
                        handleToolCall(name, arguments, callId)
                    }
                    else -> {
                        Log.i(LOG_TAG, "$type: $json")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(LOG_TAG, "onFailure: ${t.message}")
                stopSpeak()
                disconnect()
            }
        })
    }

    fun sendToolCallResponse(responseText: String, callId: String) {
        webSocket?.apply {
            Log.i(LOG_TAG, "Tool call result: $responseText")
            send(JSONObject(
                """
                    {
                        type: "conversation.item.create",
                        item: {
                            type: "function_call_output",
                            call_id: "$callId",
                            output: ${JSONObject.quote(responseText)}
                        }
                    }
                """.trimIndent()).toString())
            send(JSONObject("""{ type: "response.create" }""").toString())
        }
    }

    fun disconnect() {
        Log.i(LOG_TAG, "disconnect")
        audioManager.stopCapture()
        audioManager.stopPlayback()
        webSocket?.close(1000, "Voice Agent deactivated")
        webSocket = null
        _isConnected.value = false
    }

    fun startSpeak() {
        audioManager.startCapture { base64Audio ->
            webSocket?.send("""{"type":"input_audio_buffer.append","audio":"$base64Audio"}""")
        }
        _isSpeakActive.value = true
        _status.value = "Ready to speak"
        _lastTool.value = ""
        _transcript.value = ""
    }

    fun stopSpeak() {
        audioManager.stopCapture()
        _isSpeakActive.value = false
        _status.value = "Finished conversation"
    }

    private fun configureSession(ws: WebSocket) {
        Log.i(LOG_TAG, "configure session")
        val toolsArray = JSONArray("""
            [
                {
                    type: "web_search"
                },
                {
                    "type": "function",
                    "name": "navigate_to_screen",
                    "description": "Navigate to a specific screen inside the app.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "destination": {
                                "type": "string",
                                "enum": ["home", "favorites", "settings", "music"],
                                "description": "target screen to navigate to"
                            }
                        },
                        "required": ["destination"]
                    }
                },
                {
                    "type": "function",
                    "name": "analyze_ui_with_ui_tree",
                    "description": "Analyze the app ui-tree and describe its content elements."
                },
                {
                    "type": "function",
                    "name": "goto_item",
                    "description": "Goto/select/scroll to an item in the list.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "index": {"type": "number"}
                        },
                        "required": ["index"]
                    }
                }
            ]
        """.trimIndent())

        // tools configuration for calling the vision api with screenshot
        // too slow in response time compared to ui-tree analysis
        /*
            {
                    "type": "function",
                    "name": "analyze_ui_with_screenshot",
                    "description": "Take a screenshot and analyze the screenshot. Describe its content in detail.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "prompt": {
                                "type": "string",
                                "description": "Additional question to the screenshot (optional)"
                            }
                        }
                    }
                }
         */

        val sessionUpdate = JSONObject("""
            {
                type: "session.update",
                session: {
                    voice: "Eve",
                    instructions: "You are a voice assistance inside the car. Answer short and precise. Don't ask additional questions! Use tools for navigate to screens inside the app.",
                    turn_detection: { "type": "server_vad" },
                    audio: {
                        input: { format: { type: "audio/pcm", rate: $SAMPLE_RATE } },
                        output: { format: { type: "audio/pcm", rate: $SAMPLE_RATE } }
                    },
                    "tools": $toolsArray
                }
            }
        """.trimIndent())
        ws.send(sessionUpdate.toString())
    }

    companion object {
        private const val LOG_TAG = "VoiceApiClient"
    }
}