package com.example.camerastream

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvStreamUrl: TextView
    private lateinit var linkArea: View
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private var deviceId: String = ""
    private lateinit var supabase: SupabaseInterface

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvStreamUrl = findViewById(R.id.tvStreamUrl)
        linkArea = findViewById(R.id.linkArea)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        // Load Supabase config from BuildConfig (injected via local.properties)
        val supabaseUrl = BuildConfig.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_KEY
        if (supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()) {
            supabase = SupabaseClient(supabaseUrl, supabaseKey)
        } else {
            supabase = DummySupabaseClient()
        }

        checkPermissions()

        btnStart.setOnClickListener { startCameraService() }
        btnStop.setOnClickListener { stopCameraService() }
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
                runOnUiThread {
                    Toast.makeText(this, "Stream started", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("MainActivity", "Supabase startStream failed", body?.let { Throwable(it) })
                runOnUiThread {
                    Toast.makeText(this, "Start failed: $body", Toast.LENGTH_LONG).show()
                }
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

        updateUIStreamStarted()
    }

    private fun stopCameraService() {
        supabase.stopStream(deviceId) { success, body ->
            if (success) {
                Log.d("MainActivity", "Supabase stopStream OK: $body")
                runOnUiThread {
                    Toast.makeText(this, "Stream stopped", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("MainActivity", "Supabase stopStream failed", body?.let { Throwable(it) })
                runOnUiThread {
                    Toast.makeText(this, "Stop failed: $body", Toast.LENGTH_LONG).show()
                }
            }
        }

        stopService(Intent(this, CameraService::class.java))
        updateUIStreamStopped()
        deviceId = ""
    }

    private fun updateUIStreamStarted() {
        tvStatus.text = "Stream Started"
        val url = "https://camera-relay-k2yn.onrender.com/stream/$deviceId"
        tvStreamUrl.text = url
        linkArea.visibility = View.VISIBLE
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun updateUIStreamStopped() {
        tvStatus.text = "Stream Stopped"
        linkArea.visibility = View.GONE
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }
}
