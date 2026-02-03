package com.arc.videoshuffle

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.InputStream

/**
 * HTTP server for pairing and WebRTC signaling only.
 * No video streaming - WebRTC handles that directly.
 */
class PairingServer(
    private val deviceId: String,
    private val onPairRequest: (String) -> Boolean,
    private val onPairConfirm: (String, String) -> Unit, // (hostname, peerId)
    private val onSignalingMessage: (String, String, String) -> Unit // (from, type, data)
) : NanoHTTPD(8080) {

    companion object {
        private const val TAG = "PairingServer"
    }

    interface ServerListener {
        fun onServerStarted()
        fun onServerFailed(e: Exception)
    }

    private var serverListener: ServerListener? = null
    private var isRunning = false

    fun setServerListener(listener: ServerListener) {
        this.serverListener = listener
    }

    override fun start() {
        if (isRunning) {
            Log.d(TAG, "Server already running, skipping start")
            serverListener?.onServerStarted()  // Still notify listener, discovery can proceed
            return
        }

        try {
            super.start(SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.d(TAG, "Pairing server started on port 8080")
            serverListener?.onServerStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            
            // If already bound, we're actually OK - just mark as running
            if (e.message?.contains("EADDRINUSE") == true || e.message?.contains("Address already in use") == true) {
                Log.w(TAG, "Server already bound, treating as running")
                isRunning = true
                serverListener?.onServerStarted()
            } else {
                isRunning = false
                serverListener?.onServerFailed(e)
                // Try to clean up any partial state
                try { super.stop() } catch (ignored: Exception) {}
            }
        }
    }

    override fun stop() {
        if (!isRunning) {
            Log.d(TAG, "Server not running, skipping stop")
            return
        }

        try {
            super.stop()
            isRunning = false
            Log.d(TAG, "Pairing server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}", e)
            isRunning = false
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/status" -> {
                val json = """{"app": "VideoShuffle", "status": "ready", "device": "${android.os.Build.MODEL}", "id": "$deviceId"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }

            "/pair-request" -> {
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                val postData = body["postData"] ?: "{}"
                val requestJson = JSONObject(postData)
                val requesterId = requestJson.optString("requesterId", "")

                val canPair = onPairRequest(requesterId)
                val json = if (canPair) {
                    """{"status":"accepted","peerId":"$deviceId"}"""
                } else {
                    """{"status":"rejected","reason":"already_paired"}"""
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }

            "/pair-confirm" -> {
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                val postData = body["postData"] ?: "{}"
                val confirmJson = JSONObject(postData)
                val peerId = confirmJson.optString("peerId", "")
                
                // Do reverse DNS lookup to get MagicDNS hostname instead of IP
                val remoteAddr = session.remoteIpAddress
                val hostname = try {
                    val addr = java.net.InetAddress.getByName(remoteAddr)
                    val canonicalHostname = addr.canonicalHostName
                    // Use hostname if it's not just the IP repeated back
                    if (canonicalHostname != remoteAddr && canonicalHostname != "localhost") {
                        // Strip domain suffix to get short hostname (uninovis-tp-XX only)
                        val shortHostname = canonicalHostname.split(".").firstOrNull() ?: canonicalHostname
                        Log.d(TAG, "Resolved $remoteAddr to $canonicalHostname, using short name: $shortHostname")
                        shortHostname
                    } else {
                        Log.w(TAG, "Reverse DNS returned same address, using as-is")
                        remoteAddr
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reverse DNS failed: ${e.message}")
                    remoteAddr
                }

                onPairConfirm(hostname, peerId)

                val json = """{"status":"confirmed"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }

            "/webrtc-signal" -> {
                // WebRTC signaling endpoint
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                val postData = body["postData"] ?: "{}"
                val signalJson = JSONObject(postData)

                val from = signalJson.optString("from", "")
                val type = signalJson.optString("type", "") // offer, answer, candidate
                val data = signalJson.optString("data", "")

                onSignalingMessage(from, type, data)

                val json = """{"status":"received"}"""
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }

            else -> {
                val html = "<html><body><h1>Video Shuffle Server</h1><p>WebRTC P2P Video</p></body></html>"
                newFixedLengthResponse(html)
            }
        }
    }
}
