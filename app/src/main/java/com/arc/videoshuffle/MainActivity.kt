package com.arc.videoshuffle

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
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
    private lateinit var mjpegView: MjpegView
    private var cameraServer: UsbCameraServer? = null

    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initExecutor = Executors.newSingleThreadExecutor()

    private val deviceId = UUID.randomUUID().toString()
    private var myHostname: String? = null
    private var activePeers = mutableListOf<String>()
    private val discoveredIPs = mutableSetOf<String>()
    private lateinit var shuffleEngine: ShuffleEngine
    private var isStreaming = false
    private var currentPeer: String? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private var isSelfIdentified = false

    companion object {
        private const val TAG = "VideoShuffle"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val MAX_DEVICES_TO_SCAN = 20
        private const val SCAN_INTERVAL = 15000L
        private const val SHUFFLE_DURATION = 300000L
        private const val BROADCAST_PORT = 8888
        private const val BROADCAST_INTERVAL = 10000L
    }

    private val shuffleRunnable = Runnable {
        log("[SHUFFLE] Time elapsed (5 minutes), switching peer")
        shuffleToNextPeer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.log_textview)
        mjpegView = findViewById(R.id.mjpeg_view)

        log("App UI Initialized.")
        log("Device ID: $deviceId")

        mjpegView.setListener(object : MjpegView.MjpegListener {
            override fun onStreamError(e: Exception?) {
                handleStreamFailure()
            }
        })

        shuffleEngine = ShuffleEngine("unknown")

        if (allPermissionsGranted()) {
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
                startAppLogic()
            } else {
                log("[ERROR] Permissions not granted by the user")
                finish()
            }
        }
    }

    private fun startAppLogic() {
        log("[INFO] Permissions granted, starting core logic")
        
        // Use USB camera server for Android TV boxes
        cameraServer = UsbCameraServer(this, deviceId)
        cameraServer?.setServerListener(object : UsbCameraServer.ServerListener {
            override fun onServerStarted() {
                log("[SERVER] HTTP server started successfully")
                
                // Double-check camera permission
                if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    log("[CAMERA] Permission verified, starting USB camera detection")
                    cameraServer?.startUsbCamera()
                } else {
                    log("[ERROR] Camera permission not granted at runtime")
                }
                
                startDiscovery()
            }
            
            override fun onServerFailed(e: Exception) {
                log("[ERROR] Server failed: ${e.message}")
            }
            
            override fun onCameraOpened() {
                log("[CAMERA] USB camera opened and streaming")
            }
            
            override fun onCameraError(error: String) {
                log("[ERROR] Camera error: $error")
            }
        })
        
        log("[SERVER] Starting HTTP server")
        Executors.newSingleThreadExecutor().execute {
            cameraServer?.start()
        }
    }
    
    private fun startDiscovery() {
        // Get hostname
        val resolvedHostname = try {
            InetAddress.getLocalHost().hostName.takeIf { it != "localhost" && !it.contains("127.0.0.1") }
        } catch (e: Exception) {
            log("[WARN] Hostname lookup failed: ${e.message}")
            null
        }

        mainHandler.post {
            myHostname = resolvedHostname
            shuffleEngine = ShuffleEngine(myHostname ?: "unknown")
            
            if (myHostname != null) {
                log("[INFO] My Hostname: $myHostname")
            } else {
                log("[WARN] No hostname, using IP")
            }
            
            log("[DISCOVERY] Starting peer discovery")
            startScanningLoop()
            startUdpBroadcast()
            startUdpListener()
            scanLocalNetwork()
            
            log("[DISCOVERY] Running initial peer check")
            updateStreamSelection()
        }
    }

    // Scanning loop for MagicDNS

    private fun startScanningLoop() {
        log("Starting Peer Scan Loop...")
        val runnable = object : Runnable {
            override fun run() {
                scanMagicDnsNames()
                scanLocalNetwork()
                mainHandler.postDelayed(this, SCAN_INTERVAL)
            }
        }
        mainHandler.post(runnable)
    }

    private fun startUdpBroadcast() {
        Thread { 
            try {
                broadcastSocket = DatagramSocket()
                broadcastSocket?.broadcast = true
                log("[UDP] Starting broadcast on port $BROADCAST_PORT")
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val message = "VIDEOSHUFFLE:$deviceId:8080"
                        val sendData = message.toByteArray()
                        val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName("255.255.255.255"), BROADCAST_PORT)
                        broadcastSocket?.send(sendPacket)
                    } catch (e: Exception) { /* Ignore */ }
                    Thread.sleep(BROADCAST_INTERVAL)
                }
            } catch (e: Exception) {
                log("[ERROR] Broadcast failed to start: ${e.message}")
            }
        }.start()
    }

    private fun startUdpListener() {
        Thread { 
            try {
                listenSocket = DatagramSocket(null)
                listenSocket?.reuseAddress = true
                listenSocket?.bind(java.net.InetSocketAddress(BROADCAST_PORT))

                log("[UDP] Listening on port $BROADCAST_PORT")
                val buffer = ByteArray(1024)
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        listenSocket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith("VIDEOSHUFFLE:")) {
                            val parts = message.split(":")
                            if (parts.size >= 3) {
                                val peerId = parts[1]
                                val peerIp = packet.address.hostAddress
                                if (peerId != deviceId && peerIp != null) {
                                    handleDiscoveredPeer(peerIp, peerId)
                                }
                            }
                        }
                    } catch (e: Exception) { /* Continue */ }
                }
            } catch (e: java.net.SocketException) {
                log("[ERROR] UDP listener failed (SocketException): ${e.message}")
            } catch (e: Exception) {
                log("[ERROR] UDP listener failed: ${e.message}")
            }
        }.start()
    }

    private fun handleDiscoveredPeer(ip: String, peerId: String) {
        if (!discoveredIPs.contains(ip)) {
            discoveredIPs.add(ip)
            log("[UDP] Discovered peer: $ip")
            Executors.newSingleThreadExecutor().execute { verifyAndAddPeer(ip) }
        }
    }

    private fun scanLocalNetwork() {
        Executors.newSingleThreadExecutor().execute { 
            try {
                val localIp = getLocalIpAddress() ?: return@execute
                log("[NETWORK] My IP: $localIp")
                
                // Skip emulator networks (10.0.2.x)
                if (localIp.startsWith("10.0.2.")) {
                    return@execute
                }
                
                val prefix = localIp.substringBeforeLast(".")
                log("[SCAN] Scanning network: $prefix.x")
                
                for (i in 1..20) {
                    val ip = "$prefix.$i"
                    if (ip != localIp && !discoveredIPs.contains(ip)) {
                        Executors.newSingleThreadExecutor().execute { verifyAndAddPeer(ip) }
                    }
                }
            } catch (e: Exception) { /* Ignore */ }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val addresses = mutableListOf<String>()
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                        addresses.add(address.hostAddress!!)
                    }
                }
            }
            
            // Prioritize Tailscale network (100.64.0.x)
            addresses.firstOrNull { it.startsWith("100.64.0.") }?.let { return it }
            
            // Then try any private network except emulator
            addresses.firstOrNull { !it.startsWith("10.0.2.") }?.let { return it }
            
            // Last resort: any address
            return addresses.firstOrNull()
        } catch (e: Exception) { /* Ignore */ }
        return null
    }

    private fun verifyAndAddPeer(ipOrHostname: String) {
        val url = "http://$ipOrHostname:8080/status"
        val request = Request.Builder().url(url).build()
        try {
            scanClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body?.contains("VideoShuffle") == true) {
                        val json = JSONObject(body)
                        val peerId = json.getString("id")

                        if (peerId == deviceId) {
                            if (!isSelfIdentified) {
                                isSelfIdentified = true
                                runOnUiThread { 
                                    log("[INFO] Self-identified at: $ipOrHostname")
                                    if (myHostname == null) myHostname = ipOrHostname
                                }
                            }
                        } else {
                            onPeerFound(ipOrHostname, peerId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently ignore expected network errors for non-existent hosts
            if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException) {
                // This is normal and expected, do not log it.
            } else {
                log("[ERROR] Scan failed for $ipOrHostname: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun scanMagicDnsNames() {
        for (i in 1..MAX_DEVICES_TO_SCAN) {
            val hostname = "uninovis-tp-${String.format(Locale.US, "%02d", i)}"
            Executors.newSingleThreadExecutor().execute { verifyAndAddPeer(hostname) }
        }
    }

    private fun onPeerFound(hostname: String, peerId: String) {
        runOnUiThread { 
            if (!activePeers.contains(hostname)) {
                activePeers.add(hostname)
                log("[PEER] Found and verified: $hostname (${peerId.take(8)}...)")
                log("   Total peers: ${activePeers.size}")

                if (!isStreaming) {
                    log("[STREAM] Not streaming yet, connecting to first peer")
                    updateStreamSelection()
                }
            }
        }
    }

    private fun handleStreamFailure() {
        runOnUiThread { 
            log("[ERROR] Stream failed for peer: $currentPeer")
            mainHandler.removeCallbacks(shuffleRunnable)

            val badPeer = currentPeer
            if (badPeer != null) {
                activePeers.remove(badPeer)
                log("   Removed $badPeer from active peers. Remaining: ${activePeers.size}")
            }
            
            isStreaming = false
            currentPeer = null
            mjpegView.stopStream()

            log("[SHUFFLE] Attempting to switch to new peer")
            updateStreamSelection()
        }
    }

    private fun shuffleToNextPeer() {
        log("[SHUFFLE] Shuffling to next peer")
        isStreaming = false
        currentPeer = null 
        mjpegView.stopStream()
        
        if (activePeers.isEmpty()) {
            log("[WARN] No peers available to shuffle to")
            return
        }
        
        updateStreamSelection()
    }

    private fun updateStreamSelection() {
        if (isStreaming) return
        if (!::shuffleEngine.isInitialized) return

        if (activePeers.isNotEmpty()) {
            val target = shuffleEngine.pickNextPeer(activePeers)
            if (target != null && target != currentPeer) {
                playVideo(target)
            } else if (target != null) {
                log("[INFO] Same peer selected, waiting for next shuffle")
                mainHandler.postDelayed(shuffleRunnable, SHUFFLE_DURATION)
            } else {
                 log("[WARN] No peer returned, waiting for discovery")
            }
        } else {
            log("[WAIT] No peers available, waiting for discovery")
            if (isStreaming) {
                mjpegView.stopStream()
                isStreaming = false
                currentPeer = null
            }
        }
    }

    private fun playVideo(hostname: String) {
        log("[STREAM] Connecting to: $hostname")
        isStreaming = true
        currentPeer = hostname
        mjpegView.startStream("http://$hostname:8080/video")
        
        mainHandler.removeCallbacks(shuffleRunnable)
        mainHandler.postDelayed(shuffleRunnable, SHUFFLE_DURATION)
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            if (this::logTextView.isInitialized) {
                logTextView.append("[$time] $msg\n")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraServer?.stop()
        mjpegView.stopStream()
        initExecutor.shutdownNow()
        
        try {
            broadcastSocket?.close()
            listenSocket?.close()
        } catch (e: Exception) { /* Ignore */ }
    }
}
