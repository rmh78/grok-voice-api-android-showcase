package com.example.voiceapitest

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.TimeUnit

class VoiceApiClient(
    private val handleToolCall: (name: String, args: JSONObject, callId: String) -> Unit,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private var playbackJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    private var listeningJob: Job? = null
        set(value) {
            field?.cancel()
            field = value
        }

    private var webSocket: WebSocket? = null

    private var audioRecord: AudioRecord? = null
        set(value) {
            field?.stop()
            field?.release()
            field = value
        }

    private var audioTrack: AudioTrack? = null
        set(value) {
            field?.stop()
            field?.flush()
            field?.release()
            field = value
        }

    private var isPlayingAudio = false

    private val audioQueue: Queue<ByteArray> = LinkedList()

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
                        Log.i(LOG_TAG, type)
                        configureSession(webSocket)
                    }
                    "session.created" -> {
                        Log.i(LOG_TAG, type)
                    }
                    "session.updated" -> {
                        Log.i(LOG_TAG, type)
                        _isConnected.value = true
                    }
                    "input_audio_buffer.speech_started" -> {
                        Log.i(LOG_TAG, type)
                        stopAudioPlayback()
                        clearAudioQueue()
                        _status.value = "You are speaking"
                    }
                    "input_audio_buffer.speech_stopped" -> {
                        Log.i(LOG_TAG, type)
                        _status.value = "You stopped speaking"
                    }
                    "conversation.item.created" -> {
                        Log.i(LOG_TAG, "$type: $json")
                        _status.value = "Voice assistant is answering"
                    }
                    "response.audio.delta" -> {
                        val delta = json.getString("delta")
                        val bytes = Base64.decode(delta, Base64.NO_WRAP)
                        synchronized(audioQueue) {
                            audioQueue.add(bytes)
                        }
                        processAudioQueue()
                    }
                    "response.audio_transcript.delta" -> {
                        val delta = json.getString("delta")
                        _transcript.value += delta
                    }
                    "response.function_call_arguments.done" -> {
                        Log.i(LOG_TAG, type)
                        val name = json.getString("name")
                        _lastTool.value = name
                        val arguments = JSONObject(json.getString("arguments"))
                        val callId = json.getString("call_id")
                        handleToolCall(name, arguments, callId)
                    }
                    "response.audio.done" -> {
                        Log.i(LOG_TAG, type)
                    }
                    "response.done" -> {
                        Log.i(LOG_TAG, type)
                        stopSpeak()
                    }
                    else -> Unit
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
            send(JSONObject("""
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
        stopListening()
        stopAudioPlayback()
        webSocket?.close(1000, "Voice Agent deactivated")
        webSocket = null
        _isConnected.value = false
    }

    fun startSpeak() {
        startListening()
        _isSpeakActive.value = true
        _status.value = "Ready to speak"
        _lastTool.value = ""
        _transcript.value = ""
    }

    fun stopSpeak() {
        stopListening()
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
                    voice: "Ara",
                    instructions: "You are a voice assistance inside the car. Answer short and precise. Use tools for navigate to screens inside the app.",
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

    private fun stopAudioPlayback() {
        if (audioTrack != null && isPlayingAudio) {
            audioTrack = null
            isPlayingAudio = false
            Log.i(LOG_TAG, "audio playback stopped")
        }
    }

    private fun clearAudioQueue() {
        synchronized(audioQueue) {
            audioQueue.clear()
        }
        Log.d(LOG_TAG, "audio queue cleared")
    }

    private fun processAudioQueue() {
        if (!isPlayingAudio) {
            playbackJob = lifecycleScope.launch(Dispatchers.IO) {
                while (audioQueue.isNotEmpty()) {
                    isPlayingAudio = true
                    val audioData = synchronized(audioQueue) {
                        audioQueue.poll()
                    }
                    if (audioData != null) {
                        playAudio(audioData)
                    }
                }
                isPlayingAudio = false
            }
        }
    }

    private fun playAudio(audioData: ByteArray) {
        if (audioTrack == null) {
            audioTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setBufferSizeInBytes(BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }
            Log.d(LOG_TAG, "audio track initialized and started")
        }
        audioTrack?.write(audioData, 0, audioData.size)
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        try {
            Log.i(LOG_TAG, "start listening")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_SIZE
            ).apply { startRecording() }

            listeningJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                while (this.isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val base64Audio = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        webSocket?.send("""{"type":"input_audio_buffer.append","audio":"$base64Audio"}""")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "error listening", e)
        }
    }

    private fun stopListening() {
        Log.i(LOG_TAG, "stop listening")
        listeningJob = null
        audioRecord = null
    }

    companion object {
        private const val LOG_TAG = "VoiceApiClient"

        private const val SAMPLE_RATE = 24000

        private val BUFFER_SIZE =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 10
    }
}