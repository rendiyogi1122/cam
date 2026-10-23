package com.example.camerastream

import okhttp3.*
import java.io.IOException

interface SupabaseInterface {
    fun startStream(deviceId: String, onResult: (Boolean, String?) -> Unit)
    fun stopStream(deviceId: String, onResult: (Boolean, String?) -> Unit)
}

class SupabaseClient(
    private val url: String,
    private val key: String
) : SupabaseInterface {
    private val client = OkHttpClient()
    private val json = MediaType.get("application/json")
    private val supabaseUrl = url.removeSuffix("/")

    override fun startStream(deviceId: String, onResult: (Boolean, String?) -> Unit) {
        val body = """{"device_id": "$deviceId", "status": "aktif"}"""
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/streams?count=none")
            .method("POST", body.toRequestBody())
            .addHeader("apikey", key)
            .addHeader("Content-Type", "application/json")
            .build()
        enqueue(request, onResult)
    }

    override fun stopStream(deviceId: String, onResult: (Boolean, String?) -> Unit) {
        val body = """{"status": "nonaktif"}"""
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/streams?device_id=eq.$deviceId")
            .method("PATCH", body.toRequestBody())
            .addHeader("apikey", key)
            .addHeader("Content-Type", "application/json")
            .build()
        enqueue(request, onResult)
    }

    private fun String.toRequestBody() = RequestBody.create(json, this)

    private fun enqueue(request: Request, onResult: (Boolean, String?) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful) {
                    onResult(true, body)
                } else {
                    onResult(false, body ?: "HTTP ${response.code}")
                }
                response.close()
            }
        })
    }
}

class DummySupabaseClient : SupabaseInterface {
    override fun startStream(deviceId: String, onResult: (Boolean, String?) -> Unit) { onResult(true, null) }
    override fun stopStream(deviceId: String, onResult: (Boolean, String?) -> Unit) { onResult(true, null) }
}
