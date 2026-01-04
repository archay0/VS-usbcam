package com.arc.videoshuffle

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity(), CameraServer.ServerListener {

    private lateinit var mjpegView: MjpegView
    private lateinit var cameraView: TextureView
    private var cameraServer: CameraServer? = null

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
        private const val MAX_DEVICES_TO_SCAN = 20
        private const val SCAN_INTERVAL = 15000L
        private const val SHUFFLE_DURATION = 300000L
        private const val BROADCAST_PORT = 8888
        private const val BROADCAST_INTERVAL = 10000L
    }

    private val shuffleRunnable = Runnable {
        log("⏱️ Shuffle time! (5 minutes elapsed)")
        shuffleToNextPeer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mjpegView = findViewById(R.id.mjpeg_view)
        cameraView = findViewById(R.id.camera_view)

        log("App UI Initialized.")
        log("Device ID: $deviceId")

        mjpegView.setListener(object : MjpegView.MjpegListener {
            override fun onStreamError(e: Exception?) {
                handleStreamFailure()
            }
        })

        cameraServer = CameraServer(this, deviceId, cameraView)
        cameraServer?.setServerListener(this)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            log("Device is not a TV. Preparing camera...")
            cameraServer?.prepare()
            log("Camera prepared.")
        } else {
            log("Device is a TV. Skipping local camera setup.")
        }

        shuffleEngine = ShuffleEngine("unknown")

        initExecutor.execute {
            log("Background services starting...")
            log("Starting web server...")
            cameraServer?.start()
        }
    }

    override fun onServerStarted() {
        log("✅ Web server has started successfully.")
        initExecutor.execute {
            val resolvedHostname = try {
                InetAddress.getLocalHost().hostName.takeIf { it != "localhost" && !it.contains("127.0.0.1") }
            } catch (e: Exception) {
                log("⚠️ Hostname lookup failed: ${e.message}")
                null
            }

            mainHandler.post {
                myHostname = resolvedHostname
                shuffleEngine = ShuffleEngine(myHostname ?: "unknown")
                log(if (myHostname != null) "✅ My Hostname: $myHostname" else "⚠️ Continuing without a known hostname.")
                
                log("Starting discovery mechanisms...")
                startScanningLoop()
                startUdpBroadcast()
                startUdpListener()
                scanLocalNetwork()
                updateStreamSelection()
            }
        }
    }

    override fun onServerFailed(e: Exception) {
        log("❌ CRITICAL: Web server failed to start: ${e.message}")
    }

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
                log("📡 Starting UDP broadcast announcements on port $BROADCAST_PORT")
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
                log("⚠️ Broadcast failed to start: ${e.message}")
            }
        }.start()
    }

    private fun startUdpListener() {
        Thread { 
            try {
                listenSocket = DatagramSocket(null) // Create socket unbound
                listenSocket?.reuseAddress = true // IMPORTANT: Allow multiple apps to listen on the same port
                listenSocket?.bind(java.net.InetSocketAddress(BROADCAST_PORT)) // Now bind to the port

                log("👂 Listening for UDP announcements on port $BROADCAST_PORT")
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
                log("⚠️ UDP Listener failed (SocketException): ${e.message}. Another app might be using port $BROADCAST_PORT.")
            } catch (e: Exception) {
                log("⚠️ UDP listener failed: ${e.message}")
            }
        }.start()
    }

    private fun handleDiscoveredPeer(ip: String, peerId: String) {
        if (!discoveredIPs.contains(ip)) {
            discoveredIPs.add(ip)
            log("📡 Discovered peer via UDP: $ip")
            Executors.newSingleThreadExecutor().execute { verifyAndAddPeer(ip) }
        }
    }

    private fun scanLocalNetwork() {
        Executors.newSingleThreadExecutor().execute { 
            try {
                val localIp = getLocalIpAddress() ?: return@execute
                val prefix = localIp.substringBeforeLast(".")
                for (i in 1..20) { // Scan the first 20 IPs as requested
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
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                        return address.hostAddress
                    }
                }
            }
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
                                    log("✅ Self-identified at: $ipOrHostname")
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
            if (e !is java.net.SocketTimeoutException && e !is java.net.ConnectException) {
                log("🔍 Scan failed for $ipOrHostname: ${e.javaClass.simpleName}")
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
                log("🎥 Found and verified peer: $hostname (${peerId.take(8)}...)")
                log("   Total peers: ${activePeers.size}")

                if (!isStreaming) {
                    log("🔗 Not streaming yet, connecting to first available peer...")
                    updateStreamSelection()
                }
            }
        }
    }

    private fun handleStreamFailure() {
        runOnUiThread { 
            log("❌ Stream failed for peer: $currentPeer")
            mainHandler.removeCallbacks(shuffleRunnable)

            val badPeer = currentPeer
            if (badPeer != null) {
                activePeers.remove(badPeer)
                log("   Removed $badPeer from active peers. Remaining: ${activePeers.size}")
            }
            
            isStreaming = false
            currentPeer = null
            mjpegView.stopStream()

            log("🔄 Attempting to switch to a new peer...")
            updateStreamSelection()
        }
    }

    private fun shuffleToNextPeer() {
        log("🔄 Shuffling to next peer...")
        isStreaming = false
        currentPeer = null 
        mjpegView.stopStream()
        
        if (activePeers.isEmpty()) {
            log("⚠️ No peers available to shuffle to.")
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
                log("⏭️ Shuffle engine selected same peer. Waiting for next shuffle.")
                mainHandler.postDelayed(shuffleRunnable, SHUFFLE_DURATION)
            } else {
                 log("⚠️ Shuffle engine returned no peer, waiting for discovery.")
            }
        } else {
            log("⏸️ No peers available, waiting for discovery...")
            if (isStreaming) {
                mjpegView.stopStream()
                isStreaming = false
                currentPeer = null
            }
        }
    }

    private fun playVideo(hostname: String) {
        log("▶️ Connecting to: $hostname")
        isStreaming = true
        currentPeer = hostname
        mjpegView.startStream("http://$hostname:8080/video")
        
        mainHandler.removeCallbacks(shuffleRunnable)
        mainHandler.postDelayed(shuffleRunnable, SHUFFLE_DURATION)
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Log.d(TAG, "[$time] $msg")
    }

    override fun onPause() {
        super.onPause()
        cameraServer?.stopCamera()
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
