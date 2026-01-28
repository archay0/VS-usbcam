package com.arc.videoshuffle

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var localVideoView: org.webrtc.SurfaceViewRenderer
    private lateinit var remoteVideoView: android.widget.ImageView
    private lateinit var fpsCounter: TextView

    private var pairingServer: PairingServer? = null
    private var heartbeatManager: HeartbeatManager? = null

    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val networkExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Use persistent device ID based on Android ID (stable across app restarts)
    private val deviceId: String by lazy {
        android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: android.os.Build.MODEL.replace(" ", "-").lowercase()
    }
    private lateinit var pairingManager: PairingManager
    private var isSelfIdentified = false
    private var currentPeerHostname: String? = null
    private var sessionStartTime: Long = 0
    private var deviceHostname: String = "unknown"

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0

    private var scanLoopRunnable: Runnable? = null
    private var isDiscoveryRunning = false
    private var isFirstResume = true
    private var lastUdpRecoverAttempt = 0L
    private val keepAliveOnPause = true
    private val debugMode = false

    // UDP health monitor - DISABLED: Too aggressive, causes restart loops
    // The UDP exchange is resilient enough without health monitoring
    /*
    private val udpHealthMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                val exchange = udpExchange
                // Only restart if truly unhealthy AND not currently receiving frames
                if (exchange != null && !exchange.isHealthy()) {
                    val recentFrames = exchange.getFramesReceived()
                    
                    // If we're receiving frames, socket is actually healthy
                    if (recentFrames > 0 && (recentFrames % 100 < 10)) {
                        // Getting frames regularly, don't restart
                        Log.d(TAG, "[UDP] Health check: receiving frames ($recentFrames), skipping restart")
                    } else {
                        log("[UDP] Socket unhealthy - restarting...")
                        networkExecutor.execute {
                            try {
                                exchange.restart()
                                // Restore target if we had one
                                currentPeerHostname?.let { hostname ->
                                    startUdpStream(hostname)
                                }
                                log("[UDP] Restart successful")
                            } catch (e: Exception) {
                                log("[ERROR] UDP restart failed: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP health check error", e)
            }

            // Check again in 30 seconds
            mainHandler.postDelayed(this, 30000)
        }
    }
    */

    // Memory monitoring for low-end devices
    private val memoryMonitorRunnable = object : Runnable {
        override fun run() {
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val percentUsed = (usedMemory * 100 / maxMemory)

            if (percentUsed > 85) {
                log("[MEMORY] High memory usage: ${usedMemory}MB / ${maxMemory}MB (${percentUsed}%)")
                // Force GC when memory is critically high
                System.gc()
            }

            // Check again in 10 seconds
            mainHandler.postDelayed(this, 10000)
        }
    }

    // WebRTC health monitor - checks camera is still working
    private val webrtcHealthMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                if (!VideoEngine.isCameraHealthy()) {
                    log("[WEBRTC] Camera unhealthy - may need manual restart")
                    // Could potentially restart camera here, but risky on low-end devices
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC health check error", e)
            }

            // Check again in 60 seconds (less frequent than UDP)
            mainHandler.postDelayed(this, 60000)
        }
    }

    // UDP receive monitor - restarts exchange if frames stall while paired
    private val udpReceiveMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                val peer = currentPeerHostname
                if (peer != null) {
                    val now = System.currentTimeMillis()
                    val lastPacket = VideoEngine.getLastPacketTime()
                    val lastFrame = VideoEngine.getLastFrameTime()
                    val lastActivity = if (lastPacket > lastFrame) lastPacket else lastFrame

                    if (lastActivity > 0 && now - lastActivity > 4000) {
                        if (now - lastUdpRecoverAttempt > 15000) {
                            lastUdpRecoverAttempt = now
                            log("[UDP] No frames for 4s while paired - restarting exchange")
                            networkExecutor.execute {
                                VideoEngine.restartUdp()
                                currentPeerHostname?.let { hostname ->
                                    startUdpStream(hostname)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP receive monitor error", e)
            }

            mainHandler.postDelayed(this, 5000)
        }
    }

    // Watchdog: Detects if main thread is responsive
    private var lastWatchdogPing = System.currentTimeMillis()
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val timeSinceLastPing = now - lastWatchdogPing

            // If more than 30 seconds since last successful ping, UI thread may be frozen
            if (timeSinceLastPing > 30000) {
                Log.e(TAG, "[WATCHDOG] UI thread may be frozen! Last ping was ${timeSinceLastPing}ms ago")
            }

            lastWatchdogPing = now
            mainHandler.postDelayed(this, 5000)  // Ping every 5 seconds
        }
    }

    companion object {
        private const val TAG = "VideoShuffle"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val MAX_DEVICES_TO_SCAN = 20
        private const val SCAN_INTERVAL = 15000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install global exception handler to prevent crashes
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL ERROR in thread ${thread.name}", throwable)
            // Log to UI if possible
            try {
                runOnUiThread {
                    log("[FATAL] Unhandled exception: ${throwable.message}")
                }
            } catch (e: Exception) {
                // UI already dead, just log to logcat
            }

            // Give logcat time to flush
            try { Thread.sleep(1000) } catch (e: Exception) {}

            // Let the app crash gracefully
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(2)
        }

        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.log_textview)
        localVideoView = findViewById(R.id.local_video_view)
        remoteVideoView = findViewById(R.id.remote_video_view)
        fpsCounter = findViewById(R.id.fps_counter)

        if (!debugMode) {
            fpsCounter.visibility = View.GONE
            (logTextView.parent as? android.widget.ScrollView)?.visibility = View.GONE
        }

        // Enable smooth bilinear filtering for remote video (better visual quality)
        remoteVideoView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // HIDE SYSTEM BARS (Immersive Mode)
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)

        log("App UI Initialized (Hybrid Mode)")
        log("Device ID: $deviceId")

        // Get device hostname for monitoring
        networkExecutor.execute {
            try {
                // Try to get hostname from reverse DNS of local addresses
                var foundHostname = false
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements() && !foundHostname) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isLoopback && networkInterface.isUp) {
                        val addresses = networkInterface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                                // Try reverse DNS lookup
                                val hostname = addr.canonicalHostName
                                if (hostname != addr.hostAddress && hostname != "localhost") {
                                    deviceHostname = hostname
                                    foundHostname = true
                                    break
                                }
                            }
                        }
                    }
                }
                
                // Fallback: use device model
                if (!foundHostname) {
                    val model = android.os.Build.MODEL.replace(" ", "-").replace("_", "-").lowercase()
                    deviceHostname = model
                }
                
                runOnUiThread {
                    log("Hostname: $deviceHostname")
                }
            } catch (e: Exception) {
                deviceHostname = android.os.Build.MODEL.replace(" ", "-").lowercase()
                Log.e(TAG, "Hostname detection error: ${e.message}")
            }
        }

        if (allPermissionsGranted()) {
            startKeepAliveService()
            startAppLogic()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startKeepAliveService()
                startAppLogic()
            } else {
                log("[ERROR] Permissions not granted by the user")
                finish()
            }
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, VideoKeepAliveService::class.java)
        ContextCompat.startForegroundService(this, intent)
        log("[SERVICE] Keep-alive foreground service started")
    }

    private fun startAppLogic() {
        log("[INFO] Permissions granted, starting Hybrid UDP/WebRTC")

        // Check if this app is set as default home launcher
        checkAndPromptDefaultLauncher()

        // 1. Initialize engine (runs in foreground service, survives Home presses)
        VideoEngine.init(applicationContext)
        VideoEngine.setFrameListener { bitmap ->
            runOnUiThread {
                remoteVideoView.setImageBitmap(bitmap)
                updateFps(bitmap.width, bitmap.height)
            }
        }
        VideoEngine.start()

        // 2. Attach local preview UI
        localVideoView.setZOrderMediaOverlay(true)
        VideoEngine.attachLocalRenderer(localVideoView)

        log("[Hybrid] Initialized. UDP Port 50000")

        // Initialize pairing manager
        pairingManager = PairingManager(
            deviceId = deviceId,
            onPaired = { hostname, peerId ->
                runOnUiThread {
                    log("[SESSION] Paired with $hostname")
                    currentPeerHostname = hostname
                    sessionStartTime = System.currentTimeMillis()

                    // Stop discovery during active session
                    stopDiscovery()

                    // Set target for UDP stream
                    startUdpStream(hostname)
                }
            },
            onSessionEnd = {
                runOnUiThread {
                    currentPeerHostname = null
                    sessionStartTime = 0
                    log("[SESSION] Ended - restarting discovery")

                    // Stop discovery first to clear any stale state
                    stopDiscovery()

                    // Small delay to ensure clean restart
                    mainHandler.postDelayed({
                        startDiscovery()
                    }, 500)
                }
            },
            log = { msg -> log(msg) }
        )

        // HTTP server for pairing and signaling
        pairingServer = PairingServer(
            deviceId = deviceId,
            onPairRequest = { requesterId ->
                pairingManager.handlePairRequest(requesterId)
            },
            onPairConfirm = { hostname: String, peerId: String ->
                log("[PAIRING] Received confirmation from $hostname")
                pairingManager.confirmPairing(hostname, peerId)
            },
            onSignalingMessage = { from: String, type: String, data: String ->
                // UDP Mode: Ignore WebRTC signaling
                log("[IGNORED] Signal from $from: $type")
            }
        )

        pairingServer?.setServerListener(object : PairingServer.ServerListener {
            override fun onServerStarted() {
                log("[SERVER] Pairing server ready on port 8080")
                // Only start discovery once, on first server start
                if (!isDiscoveryRunning) {
                    startDiscovery()
                }
            }

            override fun onServerFailed(e: Exception) {
                log("[ERROR] Server failed: ${e.message}")
            }
        })

        log("[SERVER] Starting pairing/signaling server")
        networkExecutor.execute {
            pairingServer?.start()
        }

        // Initialize heartbeat manager for monitoring
        heartbeatManager = HeartbeatManager(
            deviceId = deviceId,
            getStatusData = {
                val runtime = Runtime.getRuntime()
                val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val maxMemory = runtime.maxMemory() / 1024 / 1024
                val sessionDuration = if (sessionStartTime > 0) {
                    (System.currentTimeMillis() - sessionStartTime) / 1000
                } else 0

                val status = when {
                    currentPeerHostname != null -> "paired"
                    isDiscoveryRunning -> "searching"
                    else -> "idle"
                }

                HeartbeatManager.HeartbeatData(
                    hostname = deviceHostname,
                    status = status,
                    peerHostname = currentPeerHostname,
                    sessionDuration = sessionDuration,
                    uptime = 0, // Calculated in HeartbeatManager
                    memoryUsedMB = usedMemory,
                    memoryMaxMB = maxMemory,
                    framesSent = VideoEngine.getFramesSent(),
                    framesReceived = VideoEngine.getFramesReceived(),
                    currentFps = currentFps,
                    errorCount = VideoEngine.getErrorCount().toInt()
                )
            },
            log = { msg -> log(msg) }
        )
        heartbeatManager?.start()

        // Start health monitoring for low-end devices
        mainHandler.postDelayed(memoryMonitorRunnable, 10000)  // Memory check every 10s
        mainHandler.postDelayed(udpReceiveMonitorRunnable, 5000)  // UDP receive check every 5s
        mainHandler.postDelayed(webrtcHealthMonitorRunnable, 60000)  // WebRTC check every 60s
        mainHandler.postDelayed(watchdogRunnable, 5000)  // Watchdog every 5s
    }

    private fun startUdpStream(peerHostname: String) {
        // Pass hostname directly to UDP exchange (it handles DNS resolution internally)
        // This ensures we always use MagicDNS hostnames, not resolved IPs
        log("[UDP] Setting target to $peerHostname")
        VideoEngine.setTarget(peerHostname)
    }

    private fun startDiscovery() {
        if (isDiscoveryRunning) {
            log("[DISCOVERY] Already running, skipping")
            return
        }

        log("[DISCOVERY] Starting peer discovery")
        isDiscoveryRunning = true
        startScanningLoop()
    }

    private fun stopDiscovery() {
        scanLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        scanLoopRunnable = null
        isDiscoveryRunning = false
        log("[DISCOVERY] Stopped")
    }

    private fun startScanningLoop() {
        // Cancel any existing loop first
        scanLoopRunnable?.let { mainHandler.removeCallbacks(it) }

        log("[SCAN] Starting scan loop")
        scanLoopRunnable = object : Runnable {
            override fun run() {
                scanMagicDnsNames()
                mainHandler.postDelayed(this, SCAN_INTERVAL)
            }
        }
        mainHandler.post(scanLoopRunnable!!)
    }

    private fun verifyAndAddPeer(hostname: String) {
        val url = "http://$hostname:8080/status"
        val request = Request.Builder().url(url).build()
        try {
            scanClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty() && body.contains("VideoShuffle")) {
                        try {
                            val json = JSONObject(body)
                            val peerId = json.optString("id", "")

                            if (peerId.isEmpty()) {
                                Log.w(TAG, "Peer at $hostname has no ID")
                                return@use
                            }

                            // ALWAYS check if it's ourselves, not just once
                            if (peerId == deviceId) {
                                // This is us, skip
                                if (!isSelfIdentified) {
                                    isSelfIdentified = true
                                    runOnUiThread {
                                        log("[INFO] Self-identified at: $hostname")
                                    }
                                }
                                return@use // Don't try to pair with ourselves!
                            }
                            
                            // Also check if hostname resolves to localhost/127.0.0.1
                            try {
                                val addr = InetAddress.getByName(hostname)
                                if (addr.isLoopbackAddress) {
                                    Log.d(TAG, "Skipping loopback address: $hostname")
                                    return@use
                                }
                            } catch (e: Exception) {
                                // Couldn't resolve, proceed anyway
                            }
                            
                            // Valid peer, attempt pairing
                            if (::pairingManager.isInitialized) {
                                pairingManager.onPeerDiscovered(hostname, peerId)
                            }
                        } catch (e: org.json.JSONException) {
                            Log.w(TAG, "Invalid JSON from $hostname: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is java.net.UnknownHostException &&
                e !is java.net.ConnectException &&
                e !is java.net.SocketTimeoutException) {
                Log.w(TAG, "Scan error for $hostname: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun scanMagicDnsNames() {
        for (i in 1..MAX_DEVICES_TO_SCAN) {
            val hostname = "uninovis-tp-${String.format(Locale.US, "%02d", i)}"
            networkExecutor.execute { verifyAndAddPeer(hostname) }
        }
    }

    private fun log(msg: String) {
        if (!debugMode) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Log.d(TAG, "[$time] $msg")  // Always log to logcat
        runOnUiThread {
            if (this::logTextView.isInitialized) {
                logTextView.append("[$time] $msg\n")
                // Auto-scroll to bottom
                val scrollView = logTextView.parent as? android.widget.ScrollView
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun checkAndPromptDefaultLauncher() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            
            val currentHomePackage = resolveInfo?.activityInfo?.packageName
            val isDefaultLauncher = currentHomePackage == packageName
            
            log("[LAUNCHER] Current default: $currentHomePackage")
            
            if (!isDefaultLauncher) {
                // Show dialog to prompt user to set as default launcher
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Set as Home App")
                        .setMessage("VideoShuffle is not set as your default home launcher. Would you like to set it now?")
                        .setPositiveButton("Yes") { _, _ ->
                            openDefaultLauncherSettings()
                        }
                        .setNegativeButton("Not Now") { dialog, _ ->
                            dialog.dismiss()
                            log("[LAUNCHER] User declined to set as default")
                        }
                        .setCancelable(false)
                        .show()
                }
            } else {
                log("[LAUNCHER] Already set as default home app")
            }
        } catch (e: Exception) {
            log("[LAUNCHER] Error checking default launcher: ${e.message}")
        }
    }
    
    private fun openDefaultLauncherSettings() {
        try {
            // Try to open home settings directly
            val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
            log("[LAUNCHER] Opened home settings")
        } catch (e: Exception) {
            try {
                // Fallback: open general settings
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                startActivity(intent)
                log("[LAUNCHER] Opened general settings (home settings not available)")
            } catch (e2: Exception) {
                log("[LAUNCHER] Error opening settings: ${e2.message}")
            }
        }
    }

    private fun updateFps(width: Int, height: Int) {
        if (!debugMode) return
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = now
            fpsCounter.text = "FPS: $currentFps\nRes: ${width}x${height}"
        }
    }

    override fun onPause() {
        super.onPause()
        if (keepAliveOnPause) {
            log("[LIFECYCLE] App paused - keep-alive mode (no shutdown)")
            return
        }

        log("[LIFECYCLE] App paused - stopping camera, keeping network")

        // 1. Stop Camera immediately (System will kill it anyway in background)
        // This prevents "zombie" camera state and allows clean restart in onResume
        log("[LIFECYCLE] Stopping video capture to release camera")
        // Capture is owned by foreground service; do nothing here

        // 2. Keep Network Active (Server, UDP, Discovery)
        // This ensures we stay paired and connected
        mainHandler.removeCallbacks(memoryMonitorRunnable)
        mainHandler.removeCallbacks(udpReceiveMonitorRunnable)
        mainHandler.removeCallbacks(webrtcHealthMonitorRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
        
        // Disable screen on flag to allow screen to dim naturally? 
        // No, keep it as is.
    }

    override fun onResume() {
        super.onResume()

        if (isFirstResume) {
            log("[LIFECYCLE] First resume (startup) - skipping restarts")
            isFirstResume = false
            return
        }

        if (keepAliveOnPause) {
            log("[LIFECYCLE] App resumed - keep-alive mode (no restarts)")
            try {
                VideoEngine.attachLocalRenderer(localVideoView)
            } catch (e: Exception) {
                Log.e(TAG, "Error re-attaching local renderer", e)
            }
            return
        }

        log("[LIFECYCLE] App resumed - reviving systems")

        // 1. Restart Camera (Hardware often dies on pause)
        log("[LIFECYCLE] Restarting camera")
        // Capture is owned by foreground service; do nothing here

        // 2. Restart UDP Exchange (Socket often dies/docks to wrong network on pause)
        log("[LIFECYCLE] Restarting UDP listener")
        networkExecutor.execute {
            VideoEngine.restartUdp()
            currentPeerHostname?.let { hostname ->
                startUdpStream(hostname)
            }
        }

        // 3. Resume Monitoring
        lastWatchdogPing = System.currentTimeMillis()
        mainHandler.postDelayed(memoryMonitorRunnable, 10000)
        mainHandler.postDelayed(udpReceiveMonitorRunnable, 5000)
        mainHandler.postDelayed(webrtcHealthMonitorRunnable, 60000)
        mainHandler.postDelayed(watchdogRunnable, 5000)

        log("[LIFECYCLE] Resume complete")
    }

    override fun onDestroy() {
        super.onDestroy()
        log("[LIFECYCLE] App destroyed - cleaning up")

        try {
            VideoEngine.detachLocalRenderer(localVideoView)
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching local renderer", e)
        }

        // Stop discovery loop
        stopDiscovery()

        // Clean up in proper order
        try {
            // Cancel all pending handlers
            mainHandler.removeCallbacksAndMessages(null)

            // Stop heartbeat manager
            heartbeatManager?.stop()

            // Clean up pairing manager
            if (::pairingManager.isInitialized) {
                pairingManager.cleanup()
            }

            // Stop pairing server
            pairingServer?.stop()

            // Shutdown executor
            networkExecutor.shutdown()
            if (!networkExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                networkExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }

        // Force GC for low-end devices
        System.gc()
    }
}
