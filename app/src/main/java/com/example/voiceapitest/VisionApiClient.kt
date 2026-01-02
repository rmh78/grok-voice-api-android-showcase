package com.example.voiceapitest

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class VisionApiClient {

    fun analyzeImage(bitmap: Bitmap, prompt: String): String {
        Log.i(LOG_TAG, "analyze image with prompt: $prompt")
        val base64Image = bitmapToBase64(bitmap)
        return callVisionApi(base64Image, prompt)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 0, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callVisionApi(base64Image: String, prompt: String): String {
        val client = OkHttpClient.Builder()
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val json = JSONObject("""
            {
            "messages": [
                {
                    "role": "system",
                    "content": "You are an analyzer for user-interface screens inside an android app."
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": "data:image/jpeg;base64,$base64Image",
                                "detail": "high"
                            }
                        },
                        {
                            "type": "text",
                            "text": "$prompt"
                        }
                    ]
                }
            ],
            "model": "grok-4",
            "stream": false
        }
        """.trimIndent())
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        Log.i(LOG_TAG, "requestBody: $json")
        val request = Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${BuildConfig.XAI_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(LOG_TAG, "Vision API call failed: ${response.code} ${response.message}")
                throw Exception("Vision API call failed: ${response.code} ${response.message}")
            }

            val responseBody = response.body.string()
            Log.i(LOG_TAG, "response: $responseBody")

            return JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    companion object {
        private const val LOG_TAG = "VisionApiClient"
    }
}