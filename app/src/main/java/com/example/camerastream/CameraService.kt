package com.example.camerastream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import android.util.Size
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
    private var currentCameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private val lock = Any()
    private var isRunning = false
    private val TAG = "CameraService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        createNotificationChannel()
        val notification = createNotification()
        startForeground(1, notification)
        
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        
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
                .setContentText("Streaming camera on port 8080")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Camera Stream")
                .setContentText("Streaming camera on port 8080")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    private fun startCameraCapture() {
        Log.d(TAG, "Starting camera capture")
        handlerThread = HandlerThread("CameraBackground").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == currentCameraFacing
            } ?: cameraManager.cameraIdList.firstOrNull()
                ?: run { Log.e(TAG, "No camera found"); return }
            
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = streamConfigMap?.getOutputSizes(ImageFormat.YUV_420_888)?.firstOrNull() 
                ?: Size(640, 480)

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            latestFrame = yuvToJpeg(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "YUV to JPEG failed", e)
                        } finally {
                            image.close()
                        }
                    }
                }, handler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed", e)
        }
    }

    private fun createCaptureSession() {
        try {
            val outputs = listOf(imageReader!!.surface)
            val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            cameraDevice!!.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    captureSession = session
                    try {
                        session.setRepeatingRequest(request, null, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture request failed", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Session config failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Create session failed", e)
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
        android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null).compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height), 80, out
        )
        val jpegBytes = out.toByteArray()

        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        val rotatedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()

        val rotatedOut = ByteArrayOutputStream()
        rotatedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, rotatedOut)
        rotatedBitmap.recycle()

        return rotatedOut.toByteArray()
    }

    private fun startHttpServer() {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(8080)
                serverSocket?.setReuseAddress(true)
                Log.d(TAG, "HTTP Server started on port 8080")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP Server error", e)
            }
        }.start()
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buffer = ByteArray(1024)
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) return
            val request = String(buffer, 0, bytesRead)

            // Handle camera switch command
            if (request.startsWith("POST /switch-camera")) {
                switchCamera()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nCamera switched".toByteArray())
                return
            }

            // Serve HTML page for GET /
            if (request.startsWith("GET /")) {
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Camera Stream</title>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                            body { font-family: sans-serif; text-align: center; margin: 20px; }
                            img { max-width: 100%; height: auto; border: 1px solid #ccc; }
                            button { padding: 10px 20px; font-size: 16px; cursor: pointer; margin-top: 20px; }
                        </style>
                    </head>
                    <body>
                        <h1>Camera Stream</h1>
                        <img src="/stream" alt="Camera Stream">
                        <br>
                        <button onclick="switchCamera()">Switch Camera</button>
                        <script>
                            function switchCamera() {
                                fetch('/switch-camera', { method: 'POST' })
                                    .then(r => r.text())
                                    .then(msg => console.log(msg))
                                    .catch(e => console.error(e));
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                val responseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${html.length}\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(responseHeader.toByteArray())
                output.write(html.toByteArray())
                return
            }

            // Stream MJPEG for /stream
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(responseHeader.toByteArray())
            output.flush()

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
            Log.e(TAG, "Client handling error", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun switchCamera() {
        Log.d(TAG, "Switching camera")
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        currentCameraFacing = if (currentCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        startCameraCapture()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
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