package com.arc.videoshuffle

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.atomic.AtomicBoolean

object VideoEngine {
    private const val TAG = "VideoEngine"

    private var appContext: Context? = null
    private val started = AtomicBoolean(false)

    private var udpExchange: UdpFrameExchange? = null
    private var webRTCManager: WebRTCManager? = null

    @Volatile
    private var frameListener: ((Bitmap) -> Unit)? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun start() {
        if (started.getAndSet(true)) return
        val context = appContext
        if (context == null) {
            Log.e(TAG, "Cannot start: context not initialized")
            started.set(false)
            return
        }

        udpExchange = UdpFrameExchange(localPort = 50000) { bitmap ->
            frameListener?.invoke(bitmap)
        }.also { it.start() }

        webRTCManager = WebRTCManager(context) { jpegBytes ->
            udpExchange?.sendFrame(jpegBytes)
        }.also {
            it.initialize()
            it.startCaptureOnly()
        }

        Log.d(TAG, "Video engine started")
    }

    fun stop() {
        started.set(false)
        try {
            udpExchange?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UDP exchange", e)
        }
        try {
            webRTCManager?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC manager", e)
        }
        udpExchange = null
        webRTCManager = null
    }

    fun setFrameListener(listener: ((Bitmap) -> Unit)?) {
        frameListener = listener
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        webRTCManager?.attachLocalRenderer(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        webRTCManager?.detachLocalRenderer(renderer)
    }

    fun setTarget(hostname: String) {
        udpExchange?.setTarget(hostname)
    }

    fun restartUdp() {
        udpExchange?.restart()
    }

    fun isCameraHealthy(): Boolean = webRTCManager?.isHealthy() == true

    fun getFramesSent(): Long = udpExchange?.getFramesSent() ?: 0
    fun getFramesReceived(): Long = udpExchange?.getFramesReceived() ?: 0
    fun getErrorCount(): Long = udpExchange?.getErrorCount() ?: 0
    fun getLastPacketTime(): Long = udpExchange?.getLastPacketTime() ?: 0
    fun getLastFrameTime(): Long = udpExchange?.getLastFrameTime() ?: 0
}
