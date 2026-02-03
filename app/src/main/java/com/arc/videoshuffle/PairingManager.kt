package com.arc.videoshuffle

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Robust pairing manager with proper state handling.
 * Supports multiple independent peer pairs.
 */
class PairingManager(
    private val deviceId: String,
    private val onPaired: (hostname: String, peerId: String) -> Unit,
    private val onSessionEnd: () -> Unit,
    private val log: (String) -> Unit
) {
    private var isPaired = false
    private var pairedPeerId: String? = null
    private var pairedHostname: String? = null
    private var sessionEndTime: Long = 0
    private val sessionDurationMs = 5 * 60 * 1000L // 5 minutes

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var sessionEndRunnable: Runnable? = null
    
    // Track recent pairing attempts to prevent spam
    private val recentAttempts = mutableMapOf<String, Long>()
    private val attemptCooldownMs = 10000L // 10 seconds between attempts to same peer
    
    // Track rejected requests to prevent loops
    private val recentRejections = mutableMapOf<String, Long>()
    private val rejectionCooldownMs = 30000L // 30 seconds after rejection

    fun onPeerDiscovered(hostname: String, peerId: String) {
        // If already paired, ignore (supports multiple independent pairs)
        if (isPaired) {
            return
        }
        
        // Check if we recently tried this peer (cooldown)
        val now = System.currentTimeMillis()
        val lastAttempt = recentAttempts[hostname] ?: 0
        if (now - lastAttempt < attemptCooldownMs) {
            return // Too soon, skip
        }
        
        // Check if this peer recently rejected us
        val lastRejection = recentRejections[hostname] ?: 0
        if (now - lastRejection < rejectionCooldownMs) {
            return // They rejected us recently, wait longer
        }

        // Try to pair
        attemptPairing(hostname, peerId)
    }

    /**
     * Handle incoming pair request with proper tie-breaking.
     * Returns true to accept, false to reject.
     */
    fun handlePairRequest(requesterId: String): Boolean {
        // If already paired to someone else, reject
        if (isPaired && pairedPeerId != null && pairedPeerId != requesterId) {
            log("[PAIRING] Rejecting $requesterId - already paired to $pairedPeerId")
            return false
        }
        
        // If already paired to this peer, accept (re-confirmation)
        if (isPaired && pairedPeerId == requesterId) {
            log("[PAIRING] Re-accepting $requesterId - already paired")
            return true
        }

        // Tie-breaking: smaller ID initiates, larger ID accepts
        // This prevents both devices from initiating simultaneously
        if (requesterId < deviceId) {
            // They have smaller ID, they should initiate, we should accept
            log("[PAIRING] Accepting request from $requesterId (their ID is smaller)")
            return true
        } else {
            // We have smaller ID, we should initiate, reject their request
            log("[PAIRING] Rejecting $requesterId (our ID is smaller, we initiate)")
            return false
        }
    }

    fun confirmPairing(hostname: String, peerId: String) {
        // Prevent double-pairing with DIFFERENT peer
        if (isPaired && pairedPeerId != null && pairedPeerId != peerId) {
            log("[PAIRING] Ignoring confirmation from $hostname - already paired to $pairedPeerId")
            return
        }
        
        // If re-pairing with SAME peer (e.g., after pause/resume), allow it
        val isRePairing = (isPaired && pairedPeerId == peerId)
        if (isRePairing) {
            log("[PAIRING] Re-confirmed with $hostname (resetting UDP target)")
        } else {
            log("[PAIRING] Confirmed with $hostname")
        }
        
        isPaired = true
        pairedPeerId = peerId
        pairedHostname = hostname
        sessionEndTime = System.currentTimeMillis() + sessionDurationMs
        
        // Clear rejection history for this peer
        recentRejections.remove(hostname)

        // ALWAYS call onPaired to reset UDP target, even on re-pairing
        onPaired(hostname, peerId)

        // Start 5-minute timer (cancel any existing one first)
        sessionEndRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionEndRunnable = Runnable { endSession() }
        mainHandler.postDelayed(sessionEndRunnable!!, sessionDurationMs)
    }

    private fun attemptPairing(hostname: String, peerId: String) {
        if (isPaired) return
        
        // Record this attempt
        recentAttempts[hostname] = System.currentTimeMillis()

        log("[PAIRING] Attempting to pair with $hostname")

        pairingExecutor.execute {
            var retries = 0
            val maxRetries = 2

            while (retries <= maxRetries && !isPaired) {
                try {
                    val json = JSONObject().apply {
                        put("requesterId", deviceId)
                    }

                    val request = Request.Builder()
                        .url("http://$hostname:8080/pair-request")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseJson = JSONObject(response.body?.string() ?: "{}")
                            if (responseJson.getString("status") == "accepted") {
                                val acceptedPeerId = responseJson.getString("peerId")

                                // Send confirmation
                                val confirmJson = JSONObject().apply {
                                    put("peerId", deviceId)
                                }

                                val confirmRequest = Request.Builder()
                                    .url("http://$hostname:8080/pair-confirm")
                                    .post(confirmJson.toString().toRequestBody("application/json".toMediaType()))
                                    .build()

                                httpClient.newCall(confirmRequest).execute().use { confirmResponse ->
                                    if (confirmResponse.isSuccessful) {
                                        mainHandler.post {
                                            confirmPairing(hostname, acceptedPeerId)
                                        }
                                        return@execute // Success, exit
                                    } else {
                                        log("[PAIRING] Confirmation failed with status: ${confirmResponse.code}")
                                    }
                                }
                            } else {
                                // Request was rejected - record it and back off
                                recentRejections[hostname] = System.currentTimeMillis()
                                log("[PAIRING] Request rejected by $hostname (backing off)")
                                return@execute // Don't retry if explicitly rejected
                            }
                        } else {
                            log("[PAIRING] Request failed with status: ${response.code}")
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    retries++
                    if (retries <= maxRetries) {
                        log("[PAIRING] Timeout connecting to $hostname, retry $retries/$maxRetries")
                        Thread.sleep(1000) // Brief delay before retry
                    }
                } catch (e: java.net.ConnectException) {
                    // Peer not reachable, don't retry
                    log("[PAIRING] Cannot reach $hostname: ${e.message}")
                    return@execute
                } catch (e: Exception) {
                    // Unknown error, don't retry
                    log("[PAIRING] Error pairing with $hostname: ${e.javaClass.simpleName}")
                    return@execute
                }
            }

            if (retries > maxRetries) {
                log("[PAIRING] Failed to pair with $hostname after $maxRetries retries")
            }
        }
    }

    fun endSession() {
        log("[SESSION] Ending session with $pairedHostname")

        // Cancel the session end timer
        sessionEndRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionEndRunnable = null

        // Clear state
        isPaired = false
        pairedPeerId = null
        pairedHostname = null
        sessionEndTime = 0
        
        // Clear attempt history to allow immediate re-pairing
        recentAttempts.clear()
        recentRejections.clear()

        onSessionEnd()
    }

    fun cleanup() {
        // Cancel any pending timers
        sessionEndRunnable?.let { mainHandler.removeCallbacks(it) }
        sessionEndRunnable = null
        
        // Clear all state
        recentAttempts.clear()
        recentRejections.clear()

        // Shutdown executor
        try {
            pairingExecutor.shutdown()
            if (!pairingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                pairingExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            pairingExecutor.shutdownNow()
        }
    }
}
