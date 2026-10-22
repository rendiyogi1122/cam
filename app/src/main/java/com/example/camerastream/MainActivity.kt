package com.example.camerastream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var tvIpAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private var deviceId: String = ""
    private lateinit var supabase: SupabaseInterface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Load Supabase config from BuildConfig (injected via local.properties)
        val supabaseUrl = BuildConfig.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_KEY
        if (supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()) {
            supabase = SupabaseClient(supabaseUrl, supabaseKey)
        } else {
            // Fallback: dummy client that does nothing (for dev without config)
            supabase = DummySupabaseClient()
        }

        checkPermissions()

        btnStart.setOnClickListener {
            startCameraService()
        }

        btnStop.setOnClickListener {
            stopCameraService()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCameraService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant camera permission first", Toast.LENGTH_SHORT).show()
            return
        }

        // Generate unique device ID (6 chars)
        deviceId = (0..5).map { "0123456789ABCDEF"[Random.nextInt(16)] }.joinToString("")

        // Notify Supabase: start stream
        supabase.startStream(deviceId) { success, body ->
            if (success) {
                Log.d("MainActivity", "Supabase startStream OK: $body")
            } else {
                Log.e("MainActivity", "Supabase startStream failed", body?.let { Throwable(it) })
            }
        }

        val intent = Intent(this, CameraService::class.java).apply {
            putExtra("device_id", deviceId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "Stream Active"
        updateRelayUrl()
    }

    private fun stopCameraService() {
        // Notify Supabase: stop stream
        supabase.stopStream(deviceId) { success, body ->
            if (success) {
                Log.d("MainActivity", "Supabase stopStream OK: $body")
            } else {
                Log.e("MainActivity", "Supabase stopStream failed", body?.let { Throwable(it) })
            }
        }

        stopService(Intent(this, CameraService::class.java))
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "Stream Stopped"
        deviceId = ""
    }

    private fun updateRelayUrl() {
        if (deviceId.isNotEmpty()) {
            tvIpAddress.text = "Open in PC browser:\nhttps://camera-relay-k2yn.onrender.com/stream/$deviceId"
        } else {
            tvIpAddress.text = "Open in PC browser:\nhttps://camera-relay-k2yn.onrender.com/stream"
        }
    }
}
