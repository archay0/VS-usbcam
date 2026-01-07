package com.arc.videoshuffle

import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream

class UsbCameraServer(
    private val context: Context,
    private val deviceId: String
) : NanoHTTPD(8080) {

    interface ServerListener {
        fun onServerStarted()
        fun onServerFailed(e: Exception)
        fun onCameraOpened()
        fun onCameraError(error: String)
    }

    @Volatile
    private var latestFrame: ByteArray? = null
    private var serverListener: ServerListener? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraOpenAttempted = false
    private var cameraOpenSuccess = false
    private var availableCameraIds: List<String> = emptyList()
    private var currentCameraIndex = 0
    private val backgroundHandler = Handler(Looper.getMainLooper())

    init {
        createPlaceholderFrame("USB Camera Initializing...")
    }

    fun setServerListener(listener: ServerListener) {
        this.serverListener = listener
    }

    fun startUsbCamera() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d("UsbCameraServer", "Available cameras: ${cameraIds.size}")
            
            if (cameraIds.isEmpty()) {
                Log.e("UsbCameraServer", "No cameras found on device")
                serverListener?.onCameraError("No cameras available")
                createPlaceholderFrame("No Camera Detected")
                return
            }
            
            // Store camera list for sequential attempts
            availableCameraIds = cameraIds.toList()
            
            // List all cameras with detailed info
            for (cameraId in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val facingStr = when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL/USB"
                        else -> "UNKNOWN($facing)"
                    }
                    Log.d("UsbCameraServer", "Camera $cameraId: $facingStr")
                } catch (e: Exception) {
                    Log.e("UsbCameraServer", "Failed to get characteristics for camera $cameraId", e)
                }
            }
            
            // Try to open the first camera
            currentCameraIndex = 0
            tryNextCamera(cameraManager)
            
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "Failed to enumerate cameras", e)
            serverListener?.onCameraError("Camera system error: ${e.message}")
            createPlaceholderFrame("Camera Error: ${e.message}")
        }
    }
    
    private fun tryNextCamera(cameraManager: CameraManager) {
        if (currentCameraIndex >= availableCameraIds.size) {
            // Tried all cameras, none worked
            Log.e("UsbCameraServer", "All ${availableCameraIds.size} cameras failed to open")
            serverListener?.onCameraError("Failed to open any of ${availableCameraIds.size} camera(s)")
            createPlaceholderFrame("Camera In Use or Not Available")
            return
        }
        
        val cameraId = availableCameraIds[currentCameraIndex]
        Log.d("UsbCameraServer", "=== Attempting camera $cameraId (${currentCameraIndex + 1}/${availableCameraIds.size}) ===")
        
        try {
            // Check camera characteristics before opening
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            Log.d("UsbCameraServer", "Camera $cameraId - Facing: $facing, Capabilities: ${capabilities?.contentToString()}")
            
            // Check if camera supports our required formats
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)
            Log.d("UsbCameraServer", "Camera $cameraId - JPEG sizes available: ${jpegSizes?.size ?: 0}")
            
            Log.d("UsbCameraServer", "Calling openCamera for camera $cameraId...")
            openCamera(cameraManager, cameraId)
            Log.d("UsbCameraServer", "openCamera call completed (async, waiting for callback)")
        } catch (e: SecurityException) {
            Log.e("UsbCameraServer", "SECURITY EXCEPTION for camera $cameraId", e)
            currentCameraIndex++
            tryNextCamera(cameraManager)
        } catch (e: IllegalArgumentException) {
            Log.e("UsbCameraServer", "ILLEGAL ARGUMENT for camera $cameraId: ${e.message}", e)
            currentCameraIndex++
            tryNextCamera(cameraManager)
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "EXCEPTION opening camera $cameraId: ${e.javaClass.simpleName} - ${e.message}", e)
            currentCameraIndex++
            tryNextCamera(cameraManager)
        }
    }

    private fun openCamera(cameraManager: CameraManager, cameraId: String) {
        try {
            // Setup ImageReader for capturing frames
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    latestFrame = bytes
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d("UsbCameraServer", "Camera $cameraId opened successfully")
                    cameraDevice = camera
                    cameraOpenSuccess = true
                    serverListener?.onCameraOpened()
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d("UsbCameraServer", "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    createPlaceholderFrame("Camera Disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorMsg = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE (1) - Camera is being used by another app"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE (2) - Max cameras already open"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED (3) - Camera disabled by policy"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE (4) - Fatal camera error"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE (5) - Camera service error"
                        else -> "UNKNOWN_ERROR ($error)"
                    }
                    Log.e("UsbCameraServer", "Camera $cameraId failed: $errorMsg")
                    camera.close()
                    cameraDevice = null
                    
                    // Try next camera
                    currentCameraIndex++
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    tryNextCamera(cameraManager)
                }
            }, backgroundHandler)
            
        } catch (e: SecurityException) {
            Log.e("UsbCameraServer", "No camera permission for camera $cameraId", e)
            throw e // Re-throw to be caught by attemptOpenCamera
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "Exception opening camera $cameraId", e)
            throw e // Re-throw to be caught by attemptOpenCamera
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = imageReader?.surface ?: return
            
            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        startPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("UsbCameraServer", "Capture session configuration failed")
                        serverListener?.onCameraError("Session config failed")
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "Failed to create capture session", e)
            serverListener?.onCameraError("Session creation failed")
        }
    }

    private fun startPreview() {
        try {
            val surface = imageReader?.surface ?: return
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)
            
            cameraCaptureSession?.setRepeatingRequest(
                captureRequestBuilder!!.build(),
                null,
                null
            )
            Log.d("UsbCameraServer", "Preview started")
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "Failed to start preview", e)
            serverListener?.onCameraError("Preview failed")
        }
    }

    private fun createPlaceholderFrame(message: String) {
        try {
            val bitmap = createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 30f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            
            canvas.drawText(message, 320f, 240f, paint)
            
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            latestFrame = out.toByteArray()
            
            Log.d("UsbCameraServer", "Placeholder created: $message")
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "Failed to create placeholder", e)
        }
    }

    override fun start() {
        try {
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d("UsbCameraServer", "HTTP Server started on port 8080")
            serverListener?.onServerStarted()
        } catch (e: Exception) {
            Log.e("UsbCameraServer", "Failed to start server", e)
            serverListener?.onServerFailed(e)
        }
    }

    override fun stop() {
        super.stop()
        cameraCaptureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/video" -> createMjpegResponse()
            "/status" -> {
                val json = """{"app": "VideoShuffle", "status": "ready", "device": "${android.os.Build.MODEL}", "id": "$deviceId"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            else -> {
                val html = "<html><body><h1>Video Shuffle Server</h1><p><a href='/video'>View Stream</a></p></body></html>"
                newFixedLengthResponse(html)
            }
        }
    }

    private fun createMjpegResponse(): Response {
        return object : Response(Status.OK, "multipart/x-mixed-replace; boundary=BoundaryString", null as InputStream?, -1) {
            override fun send(outputStream: java.io.OutputStream) {
                try {
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=BoundaryString\r\n\r\n"
                    outputStream.write(header.toByteArray())
                    outputStream.flush()

                    while (true) {
                        val frame = latestFrame
                        if (frame != null) {
                            outputStream.write("--BoundaryString\r\n".toByteArray())
                            outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                            outputStream.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                            outputStream.write(frame)
                            outputStream.write("\r\n".toByteArray())
                            outputStream.flush()
                        }
                        Thread.sleep(66) // ~15 FPS
                    }
                } catch (e: Exception) {
                    Log.d("UsbCameraServer", "Client disconnected")
                }
            }
        }
    }
}
