package com.arc.videoshuffle

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import org.webrtc.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * STRIPPED WebRTC manager - Camera only, no P2P/ICE.
 * Extracts frames for UDP transmission.
 */
class WebRTCManager(
    private val context: Context,
    private val onLocalFrame: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "WebRTCManager"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // SINGLE THREAD for frame processing to prevent lag/OOM
    private val processingExecutor = Executors.newSingleThreadExecutor()

    // Frame Capture Sink
    private val frameSink = object : VideoSink {
        override fun onFrame(frame: VideoFrame?) {
            frame?.let {
                // IMPORTANT: We must retain/release frame or it might be recycled while processing
                it.retain()
                processingExecutor.execute {
                    processFrame(it)
                }
            }
        }
    }

    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun initialize() {
        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // We only need the factory to create sources/tracks, no encoder/decoder needed anymore
        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized (Capture Mode Only)")
    }

    fun startLocalVideo(surfaceViewRenderer: SurfaceViewRenderer) {
        startCaptureOnly()
        attachLocalRenderer(surfaceViewRenderer)
    }

    fun startCaptureOnly() {
        if (localVideoSource != null || localVideoTrack != null) return

        localVideoSource = peerConnectionFactory!!.createVideoSource(false)
        videoCapturer = createCameraVideoCapturer()

        if (videoCapturer != null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            videoCapturer!!.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)

            // 1138x640 (16:9)
            videoCapturer!!.startCapture(1138, 640, 30)
            Log.d(TAG, "Video capture started (capture-only): 1138x640@30fps")
        } else {
            Log.e(TAG, "No video capturer found!")
        }

        localVideoTrack = peerConnectionFactory!!.createVideoTrack("local_video", localVideoSource)
        localVideoTrack!!.addSink(frameSink) // Capture bytes
        localVideoTrack!!.setEnabled(true)
    }

    fun attachLocalRenderer(surfaceViewRenderer: SurfaceViewRenderer) {
        try {
            surfaceViewRenderer.init(eglBase!!.eglBaseContext, null)
        } catch (e: Exception) {
            // Ignore if already initialized
        }
        surfaceViewRenderer.setMirror(true)
        surfaceViewRenderer.setEnableHardwareScaler(true)
        surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        localVideoTrack?.addSink(surfaceViewRenderer)
    }

    fun detachLocalRenderer(surfaceViewRenderer: SurfaceViewRenderer) {
        try {
            localVideoTrack?.removeSink(surfaceViewRenderer)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing local renderer: ${e.message}")
        }
        try {
            surfaceViewRenderer.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing renderer: ${e.message}")
        }
    }

    fun setVideoEnabled(enable: Boolean) {
        try {
            Log.d(TAG, "setVideoEnabled called: $enable")
            localVideoTrack?.setEnabled(enable)
            
            if (enable) {
                // IMPORTANT: Sometimes startCapture fails if called too quickly after stop
                // Add a small retry mechanism
                try {
                    Log.d(TAG, "Starting video capture 1138x640@30fps")
                    videoCapturer?.startCapture(1138, 640, 30)
                    Log.d(TAG, "Video capture started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed first capture attempt, retrying in 500ms: ${e.message}")
                    Thread.sleep(500)
                    videoCapturer?.startCapture(1138, 640, 30) 
                    Log.d(TAG, "Video capture started on retry")
                }
            } else {
                try {
                    Log.d(TAG, "Stopping video capture")
                    videoCapturer?.stopCapture()
                    Log.d(TAG, "Video capture stopped successfully")
                } catch (e: Exception) {
                    // Ignore "not capturing" errors
                    Log.w(TAG, "Error stopping capture (might already be stopped): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling video: ${e.message}", e)
        }
    }

    fun hardRestartCamera() {
        try {
            Log.d(TAG, "Hard restarting camera...")

            // 1. Stop and dispose existing capturer
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing old capturer: ${e.message}")
            }

            // 2. Create new capturer
            videoCapturer = createCameraVideoCapturer()

            if (videoCapturer != null) {
                // 3. Re-initialize with NEW SurfaceTextureHelper
                // Note: localVideoSource does not need to be recreated, just re-fed
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread_Restarted", eglBase!!.eglBaseContext)
                videoCapturer!!.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)

                // 4. Start
                videoCapturer!!.startCapture(1138, 640, 30)
                Log.d(TAG, "Camera hard restart successful")
            } else {
                Log.e(TAG, "Failed to recreate camera capturer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hard restart failed: ${e.message}", e)
        }
    }

    private var lastFrameTime = 0L
    private val minFrameInterval = 33L // 30 FPS (1000/30)
    private var frameProcessingErrors = 0
    private val maxConsecutiveErrors = 10

    private fun processFrame(frame: VideoFrame) {
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < minFrameInterval) {
            frame.release() // Drop frame to maintain 30fps cap
            return
        }
        lastFrameTime = now

        try {
            val buffer = frame.buffer
            val width = buffer.width
            val height = buffer.height

            // Convert I420 to NV21 (YuvImage needs NV21)
            val i420 = buffer.toI420()
            val nv21 = ByteArray(width * height * 3 / 2)
            
            val yPlane = i420.dataY
            val uPlane = i420.dataU
            val vPlane = i420.dataV
            
            val yStride = i420.strideY
            val uStride = i420.strideU
            val vStride = i420.strideV

            // Copy Y
            for (i in 0 until height) {
                yPlane.position(i * yStride)
                yPlane.get(nv21, i * width, width)
            }

            // Copy UV (Interleave)
            val chromHeight = (height + 1) / 2
            val chromWidth = (width + 1) / 2
            val uvOffset = width * height
            
            for (i in 0 until chromHeight) { 
                uPlane.position(i * uStride)
                vPlane.position(i * vStride)
                for (j in 0 until chromWidth) {
                   val v = vPlane.get()
                   val u = uPlane.get()
                   val nvIndex = uvOffset + i * width + j * 2
                   if (nvIndex + 1 < nv21.size) {
                       nv21[nvIndex] = v
                       nv21[nvIndex+1] = u
                   }
                }
            }
            
            i420.release()

            // Compress to JPEG
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            // 50% quality - balanced quality/bandwidth
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, out)
            val jpegBytes = out.toByteArray()

            // Send
            if (jpegBytes.size > 100) { // Filter out empty frames
                onLocalFrame(jpegBytes)
                frameProcessingErrors = 0 // Reset error counter on success
            } else {
                Log.w(TAG, "Captured empty/small frame: ${jpegBytes.size} bytes")
            }

        } catch (e: Exception) {
            frameProcessingErrors++
            Log.e(TAG, "Frame processing error ($frameProcessingErrors/$maxConsecutiveErrors): ${e.message}")

            if (frameProcessingErrors >= maxConsecutiveErrors) {
                Log.e(TAG, "Too many consecutive frame errors - camera may have failed")
                frameProcessingErrors = 0 // Reset to prevent spam
            }
        } finally {
            frame.release() // CRITICAL: Release frame back to WebRTC pool
        }
    }

    private fun createCameraVideoCapturer(): CameraVideoCapturer? {
        val enumerator2 = Camera2Enumerator(context)
        val deviceNames2 = enumerator2.deviceNames
        // Try Back
        for (dn in deviceNames2) { if (enumerator2.isBackFacing(dn)) return enumerator2.createCapturer(dn, null) }
        // Try Front
        for (dn in deviceNames2) { if (enumerator2.isFrontFacing(dn)) return enumerator2.createCapturer(dn, null) }
        // Try Any
        if (deviceNames2.isNotEmpty()) return enumerator2.createCapturer(deviceNames2[0], null)

        // Try Legacy
        val enumerator1 = Camera1Enumerator(true)
        val deviceNames1 = enumerator1.deviceNames
        if (deviceNames1.isNotEmpty()) return enumerator1.createCapturer(deviceNames1[0], null)
        
        return null
    }

    fun isHealthy(): Boolean {
        return videoCapturer != null &&
               localVideoTrack != null &&
               localVideoSource != null &&
               frameProcessingErrors < maxConsecutiveErrors
    }

    fun close() {
        Log.d(TAG, "Closing WebRTC manager...")

        try {
            // Stop frame processing first
            processingExecutor.shutdown()
            if (!processingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down processing executor", e)
            processingExecutor.shutdownNow()
        }

        // Clean up WebRTC resources in order
        try { videoCapturer?.stopCapture() } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer: ${e.message}")
        }
        try { videoCapturer?.dispose() } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer: ${e.message}")
        }
        try { localVideoTrack?.dispose() } catch (e: Exception) {
            Log.e(TAG, "Error disposing video track: ${e.message}")
        }
        try { localVideoSource?.dispose() } catch (e: Exception) {
            Log.e(TAG, "Error disposing video source: ${e.message}")
        }
        try { peerConnectionFactory?.dispose() } catch (e: Exception) {
            Log.e(TAG, "Error disposing factory: ${e.message}")
        }
        try { eglBase?.release() } catch (e: Exception) {
            Log.e(TAG, "Error releasing EGL: ${e.message}")
        }

        // Nullify references
        videoCapturer = null
        localVideoTrack = null
        localVideoSource = null
        peerConnectionFactory = null
        eglBase = null

        Log.d(TAG, "WebRTC manager closed")
    }
}
