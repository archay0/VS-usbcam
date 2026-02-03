package com.arc.videoshuffle

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Heartbeat manager - sends periodic status updates to monitoring endpoint
 * for dashboard visibility without any admin controls.
 */
class HeartbeatManager(
    private val deviceId: String,
    private val getStatusData: () -> HeartbeatData,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "HeartbeatManager"
        private const val HEARTBEAT_URL = "https://lora.thws.education/wa"
        private const val HEARTBEAT_INTERVAL_MS = 30000L // 30 seconds
        private const val MAX_RETRIES = 3
    }

    data class HeartbeatData(
        val hostname: String,
        val status: String,  // "paired", "searching", "idle", "error"
        val peerHostname: String?,
        val sessionDuration: Long,  // seconds
        val uptime: Long,  // seconds
        val memoryUsedMB: Long,
        val memoryMaxMB: Long,
        val framesSent: Long,
        val framesReceived: Long,
        val currentFps: Int,
        val errorCount: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var heartbeatRunnable: Runnable? = null
    private var isRunning = false
    private val startTime = System.currentTimeMillis()
    private var consecutiveFailures = 0

    fun start() {
        if (isRunning) {
            Log.d(TAG, "Heartbeat already running")
            return
        }

        isRunning = true
        log("[HEARTBEAT] Started (interval: ${HEARTBEAT_INTERVAL_MS / 1000}s)")
        scheduleNextHeartbeat()
    }

    fun stop() {
        isRunning = false
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        heartbeatExecutor.shutdown()
        log("[HEARTBEAT] Stopped")
    }

    private fun scheduleNextHeartbeat() {
        if (!isRunning) return

        heartbeatRunnable = Runnable {
            sendHeartbeat()
            if (isRunning) {
                scheduleNextHeartbeat()
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun sendHeartbeat() {
        heartbeatExecutor.execute {
            try {
                val data = getStatusData()
                val uptime = (System.currentTimeMillis() - startTime) / 1000

                val payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("hostname", data.hostname)
                    put("timestamp", System.currentTimeMillis())
                    put("status", data.status)
                    put("peer", data.peerHostname ?: "none")
                    put("sessionDuration", data.sessionDuration)
                    put("uptime", uptime)
                    put("memoryUsedMB", data.memoryUsedMB)
                    put("memoryMaxMB", data.memoryMaxMB)
                    put("memoryPercent", if (data.memoryMaxMB > 0) {
                        (data.memoryUsedMB * 100 / data.memoryMaxMB).toInt()
                    } else 0)
                    put("framesSent", data.framesSent)
                    put("framesReceived", data.framesReceived)
                    put("fps", data.currentFps)
                    put("errorCount", data.errorCount)
                    put("health", if (consecutiveFailures == 0) "healthy" else "degraded")
                }

                val request = Request.Builder()
                    .url(HEARTBEAT_URL)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        consecutiveFailures = 0
                        // Log the payload to UI
                        mainHandler.post {
                            log("[HEARTBEAT] Sent: ${data.status} | Peer: ${data.peerHostname ?: "none"} | FPS: ${data.currentFps} | Sent: ${data.framesSent} | Rcv: ${data.framesReceived}")
                        }
                    } else {
                        consecutiveFailures++
                        Log.w(TAG, "Heartbeat failed with status: ${response.code} (failures: $consecutiveFailures)")
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                consecutiveFailures++
                Log.w(TAG, "Heartbeat failed: No network connectivity (failures: $consecutiveFailures)")
            } catch (e: java.net.SocketTimeoutException) {
                consecutiveFailures++
                Log.w(TAG, "Heartbeat timeout (failures: $consecutiveFailures)")
            } catch (e: Exception) {
                consecutiveFailures++
                Log.e(TAG, "Heartbeat error (failures: $consecutiveFailures): ${e.javaClass.simpleName} - ${e.message}")
            }

            // If too many failures, log warning but keep trying
            if (consecutiveFailures >= 10) {
                mainHandler.post {
                    log("[HEARTBEAT] Warning: ${consecutiveFailures} consecutive failures")
                }
            }
        }
    }

    /**
     * Get the device hostname (e.g., "uninovis-tp-01")
     */
    fun getHostname(): String {
        return try {
            val hostname = InetAddress.getLocalHost().hostName
            if (hostname.isNotBlank()) hostname else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
