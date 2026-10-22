package com.example.camerastream

import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight Supabase REST client for Android.
 * Uses OkHttp + simple JSON, no Retrofit boilerplate.
 */
class SupabaseClient(
    private val baseUrl: String,
    private val apiKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val headers = mapOf(
        "apikey" to apiKey,
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "Prefer" to "return=representation"
    )

    companion object {
        private const val TABLE = "streams"
        private const val STATUS_ACTIVE = "aktif"
        private const val STATUS_INACTIVE = "nonaktif"
    }

    /** Upsert stream status to 'aktif' when Start Stream clicked */
    fun startStream(deviceId: String, onResult: (Boolean, String?) -> Unit) {
        upsertStatus(deviceId, STATUS_ACTIVE, onResult)
    }

    /** Update stream status to 'nonaktif' when Stop Stream clicked */
    fun stopStream(deviceId: String, onResult: (Boolean, String?) -> Unit) {
        updateStatus(deviceId, STATUS_INACTIVE, onResult)
    }

    private fun upsertStatus(deviceId: String, status: String, onResult: (Boolean, String?) -> Unit) {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("status", status)
        }

        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$TABLE?on_conflict=device_id")
            .headers(buildHeaders(headers))
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeAsync(request) { success, body ->
            onResult(success, body)
        }
    }

    private fun updateStatus(deviceId: String, status: String, onResult: (Boolean, String?) -> Unit) {
        val json = JSONObject().put("status", status)

        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$TABLE?device_id=eq.$deviceId")
            .headers(buildHeaders(headers + ("Prefer" to "return=minimal")))
            .patch(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeAsync(request) { success, body ->
            onResult(success, body)
        }
    }

    /** Query current status for a device_id */
    fun queryStatus(deviceId: String, onResult: (String?) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$TABLE?device_id=eq.$deviceId&select=status")
            .headers(buildHeaders(headers))
            .get()
            .build()

        executeAsync(request) { success, body ->
            if (success && body != null) {
                try {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        onResult(arr.getJSONObject(0).optString("status"))
                        return@executeAsync
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            onResult(null)
        }
    }

    private fun executeAsync(request: Request, callback: (Boolean, String?) -> Unit) {
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                e.printStackTrace()
                callback(false, null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                val body = response.body?.string()
                val success = response.isSuccessful
                if (!success) {
                    println("Supabase error: ${response.code} $body")
                }
                callback(success, body)
            }
        })
    }

    private fun buildHeaders(map: Map<String, String>): okhttp3.Headers {
        return okhttp3.Headers.Builder().apply {
            map.forEach { (k, v) -> add(k, v) }
        }.build()
    }
}