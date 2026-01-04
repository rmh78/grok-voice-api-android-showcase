package com.example.voiceapitest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import kotlin.math.sqrt

// Helper functions for converting between Android's float PCM (-1.0 to 1.0) and PCM16 base64 used by the API
fun float32ToPcm16Base64(floatArray: FloatArray): String {
    val shortArray = ShortArray(floatArray.size)
    for (i in floatArray.indices) {
        val sample = (floatArray[i] * 32767f).coerceIn(-32768f, 32767f)
        shortArray[i] = sample.toInt().toShort()
    }
    val byteArray = ByteArray(shortArray.size * 2)
    ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortArray)
    return Base64.encodeToString(byteArray, Base64.NO_WRAP)
}

fun base64Pcm16ToFloat32(base64: String): FloatArray {
    val byteArray = Base64.decode(base64, Base64.NO_WRAP)
    val shortBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val shortArray = ShortArray(shortBuffer.remaining())
    shortBuffer.get(shortArray)
    val floatArray = FloatArray(shortArray.size)
    for (i in shortArray.indices) {
        floatArray[i] = shortArray[i] / 32768f
    }
    return floatArray
}

/**
 * AudioStreamManager
 *
 * Handles real-time audio capture from the microphone and playback of audio received from the Grok voice API.
 * - Captures audio at 24kHz mono using PCM_FLOAT.
 * - Buffers incoming audio and sends fixed-size chunks (~100ms) as base64-encoded PCM16 to the WebSocket.
 * - Calculates a simple RMS audio level (useful for UI volume indicators or VAD).
 * - Queues and plays back incoming audio chunks from the server in real-time.
 */
class AudioStreamManager() {
    // Audio recording objects
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isCapturing: Boolean = false

    // Audio playback objects
    private var audioTrack: AudioTrack? = null
    private val playbackQueue: LinkedList<FloatArray> = LinkedList()
    private var isPlaying: Boolean = false
    private val lock: Any = Any() // Protects playback queue

    // Current audio input level (RMS, 0.0 to ~1.0) – useful for visual feedback
    var audioLevel = MutableStateFlow(0f)

    /**
     * Starts microphone capture.
     * Calls [onAudioData] for every ~100ms chunk of audio as base64-encoded PCM16 string.
     *
     * @param onAudioData Callback invoked with each chunk ready to send over WebSocket
     * @return The actual sample rate used (now fixed at 24000)
     */
    fun startCapture(onAudioData: (String) -> Unit): Int {
        // Calculate minimum buffer size required by the system
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        // Create AudioRecord instance
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()

        audioRecord!!.startRecording()
        isCapturing = true

        // Dedicated thread for reading audio data
        recordingThread = Thread {
            val floatBuffer = FloatArray(BUFFER_SIZE)

            val audioBuffer: MutableList<FloatArray> = mutableListOf()
            var totalSamples = 0

            // Number of samples in one 100ms chunk at 24kHz: 24000 * 0.1 = 2400 samples
            val chunkSizeSamples = (SAMPLE_RATE * CHUNK_DURATION_MS) / 1000

            while (isCapturing) {
                val read = audioRecord!!.read(floatBuffer, 0, BUFFER_SIZE, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val data = floatBuffer.copyOf(read)

                    // Calculate RMS for volume/level indicator
                    var sum = 0.0
                    for (s in data) sum += (s * s).toDouble()
                    val rms = sqrt(sum / read)
                    audioLevel.value = rms.toFloat()
                    Log.d(LOG_TAG, "RMS: ${audioLevel.value}")

                    // Buffer the read data
                    audioBuffer.add(data)
                    totalSamples += read

                    // Emit complete 100ms chunks
                    while (totalSamples >= chunkSizeSamples) {
                        val chunk = FloatArray(chunkSizeSamples)
                        var offset = 0

                        while (offset < chunkSizeSamples && audioBuffer.isNotEmpty()) {
                            val buf = audioBuffer[0]
                            val needed = chunkSizeSamples - offset
                            val available = buf.size

                            if (available <= needed) {
                                System.arraycopy(buf, 0, chunk, offset, available)
                                offset += available
                                totalSamples -= available
                                audioBuffer.removeAt(0)
                            } else {
                                System.arraycopy(buf, 0, chunk, offset, needed)
                                audioBuffer[0] = buf.copyOfRange(needed, buf.size)
                                offset += needed
                                totalSamples -= needed
                            }
                        }

                        val base64Audio = float32ToPcm16Base64(chunk)
                        onAudioData(base64Audio)
                    }
                }
            }
        }
        recordingThread!!.start()

        Log.d(LOG_TAG, "Audio capture started at $SAMPLE_RATE Hz")
        return SAMPLE_RATE
    }

    fun stopCapture() {
        isCapturing = false
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioLevel.value = 0f
        Log.d(LOG_TAG, "Audio capture stopped")
    }

    fun stopPlayback() {
        synchronized(lock) {
            playbackQueue.clear()
            isPlaying = false
        }
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(LOG_TAG, "Audio playback stopped")
    }

    fun playAudio(base64Audio: String) {
        val floatData = base64Pcm16ToFloat32(base64Audio)
        synchronized(lock) {
            playbackQueue.add(floatData)
            if (!isPlaying) {
                isPlaying = true
                Thread { playNextChunk() }.start()
            }
        }
    }

    private fun playNextChunk() {
        while (true) {
            val chunk: FloatArray?
            synchronized(lock) {
                if (playbackQueue.isEmpty()) {
                    isPlaying = false
                    return
                }
                chunk = playbackQueue.poll()
            }

            if (audioTrack == null) {
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                audioTrack!!.play()
            }

            audioTrack!!.write(chunk!!, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    companion object {
        private const val LOG_TAG = "AudioStreamManager"

        // Send audio chunks every 100ms – matches the expected granularity of the realtime API
        private const val CHUNK_DURATION_MS = 100

        // Internal buffer size for reading from AudioRecord (larger = lower overhead)
        private const val BUFFER_SIZE = 4096

        // Changed to 24kHz as requested
        const val SAMPLE_RATE = 24000
    }
}