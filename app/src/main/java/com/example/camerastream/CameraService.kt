package com.example.camerastream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class CameraService : Service() {
    private var serverSocket: ServerSocket? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var latestFrame: ByteArray? = null
    private val lock = Any()
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, createNotification())
        startCameraCapture()
        startHttpServer()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "camera_stream",
                "Camera Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "camera_stream")
                .setContentTitle("Camera Stream")
                .setContentText("Streaming camera...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Camera Stream")
                .setContentText("Streaming camera...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    private fun startCameraCapture() {
        handlerThread = HandlerThread("CameraBackground").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0]

        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    latestFrame = yuvToJpeg(image)
                    image.close()
                }
            }, handler)
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraService", "Camera error: $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e("CameraService", "Camera permission denied", e)
        }
    }

    private fun createCaptureSession() {
        val outputs = listOf(imageReader!!.surface)
        val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader!!.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }.build()

        try {
            cameraDevice!!.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(request, null, handler)
                    } catch (e: Exception) {
                        Log.e("CameraService", "Capture request failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraService", "Session config failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e("CameraService", "Create session failed", e)
        }
    }

    private fun yuvToJpeg(image: Image): ByteArray {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uvSize = uBuffer.remaining() + vBuffer.remaining()

        val nv21 = ByteArray(ySize + uvSize)
        yBuffer.get(nv21, 0, ySize)

        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride
        val uvPixelStride = if (uPixelStride == vPixelStride) uPixelStride else 1

        var uvIndex = ySize
        if (uvPixelStride == 1) {
            uBuffer.get(nv21, uvIndex, uBuffer.remaining())
            vBuffer.get(nv21, uvIndex + uBuffer.remaining(), vBuffer.remaining())
        } else {
            while (uBuffer.hasRemaining() && vBuffer.hasRemaining()) {
                nv21[uvIndex++] = uBuffer.get()
                nv21[uvIndex++] = vBuffer.get()
            }
        }

        val out = ByteArrayOutputStream()
        val bitmap = android.graphics.Bitmap.createBitmap(image.width, image.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null).compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height), 80, out
        )
        return out.toByteArray()
    }

    private fun startHttpServer() {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(8080)
                Log.d("CameraService", "HTTP Server started on port 8080")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                Log.e("CameraService", "HTTP Server error", e)
            }
        }.start()
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buffer = ByteArray(1024)
            input.read(buffer) // read request (ignore)

            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(responseHeader.toByteArray())

            while (isRunning && !socket.isClosed) {
                synchronized(lock) {
                    val frame = latestFrame
                    if (frame != null) {
                        val frameHeader = "--frame\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n\r\n"
                        output.write(frameHeader.toByteArray())
                        output.write(frame)
                        output.write("\r\n".toByteArray())
                        output.flush()
                    }
                }
                Thread.sleep(100) // ~10 fps
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Client handling error", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        handlerThread?.quit()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}