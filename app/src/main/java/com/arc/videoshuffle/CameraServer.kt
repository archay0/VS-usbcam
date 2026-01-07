package com.arc.videoshuffle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class CameraServer(
    private val context: Context,
    private val deviceId: String,
    private val lifecycleOwner: LifecycleOwner
) : NanoHTTPD(8080) {

    interface ServerListener {
        fun onServerStarted()
        fun onServerFailed(e: Exception)
    }

    @Volatile
    private var latestFrame: ByteArray? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var serverListener: ServerListener? = null

    init {
        createPlaceholderFrame("Initializing Camera...")
    }

    fun setServerListener(listener: ServerListener) {
        this.serverListener = listener
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e("CameraServer", "Failed to get camera provider", e)
                createPlaceholderFrame("Camera Provider Failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        if (cameraProvider == null) {
            Log.e("CameraServer", "Camera provider is not available.")
            createPlaceholderFrame("No Camera Provider")
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
            processImage(imageProxy)
        }

        try {
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.d("CameraServer", "Camera use cases bound successfully")
        } catch (e: Exception) {
            Log.e("CameraServer", "Use case binding failed", e)
            createPlaceholderFrame("Camera Binding Failed")
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (imageProxy.format == ImageFormat.YUV_420_888) {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
            latestFrame = out.toByteArray()
        }
        imageProxy.close()
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun createPlaceholderFrame(text: String) {
        try {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 40f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(text, 320f, 240f, paint)

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
            latestFrame = out.toByteArray()
        } catch (e: Exception) {
            Log.e("CameraServer", "Failed to create placeholder frame", e)
        }
    }

    override fun start() {
        try {
            super.start(SOCKET_READ_TIMEOUT, false)
            Log.d("CameraServer", "HTTP Server started successfully")
            serverListener?.onServerStarted()
        } catch (e: IOException) {
            Log.e("CameraServer", "Error starting HTTP server", e)
            serverListener?.onServerFailed(e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/video" -> newChunkedResponse(Response.Status.OK, "video/x-motion-jpeg", MJpegInputStream(this))
            "/status" -> {
                val json = "{\"app\": \"VideoShuffle\", \"status\": \"ready\", \"device\": \"${android.os.Build.MODEL}\", \"id\": \"$deviceId\"}"
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            else -> {
                val msg = "<html><body><h1>Video Shuffle Server</h1><p><a href='/video'>View Stream</a></p></body></html>"
                newFixedLengthResponse(msg)
            }
        }
    }

    private class MJpegInputStream(private val server: CameraServer) : InputStream() {
        private var frameData: ByteArray? = null
        private var position = 0
        private var headerSent = false

        override fun read(): Int {
            if (frameData == null || position >= frameData!!.size) {
                if (!headerSent) {
                    val header = "--FRAME\r\nContent-Type: image/jpeg\r\nContent-Length: ${server.latestFrame?.size}\r\n\r\n"
                    frameData = header.toByteArray()
                    position = 0
                    headerSent = true
                } else {
                    frameData = server.latestFrame
                    position = 0
                    headerSent = false
                }
            }
            return frameData?.get(position++)?.toInt() ?: -1
        }
    }
}