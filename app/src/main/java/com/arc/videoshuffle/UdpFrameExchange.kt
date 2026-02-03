package com.arc.videoshuffle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Optimized UDP frame exchange for true simultaneous bidirectional video.
 *
 * Design goals:
 * - Interleaved Tx/Rx: Send a few packets, Receive a few packets. Never block.
 * - Prioritize Latest: If a new frame is captured while sending an old one, abort old one.
 */
class UdpFrameExchange(
    private val localPort: Int = 50000,
    private val onFrameReceived: (Bitmap) -> Unit
) {
    companion object {
        private const val TAG = "UdpFrameExchange"
        private const val MAX_PACKET_SIZE = 1200
        private const val HEADER_SIZE = 12
        private const val MAX_DATA_PER_PACKET = MAX_PACKET_SIZE - HEADER_SIZE

        // Tuning parameters for SMOOTH playback with BETTER QUALITY
        private const val BURST_SIZE_TX = 30  // Send faster bursts to saturate 2Mbps link
        private const val BURST_SIZE_RX = 30 // Receive faster bursts
        private const val FRAME_TIMEOUT_MS = 250L // Allow 250ms for packet arrival (better for 2Mbps)
        private const val FRAME_TOLERANCE = 1.0  // Require complete frames to avoid visual artifacts
    }

    private var socket: DatagramSocket? = null
    private var peerAddress: InetAddress? = null
    private var peerPort: Int = localPort // Symmetric

    private val isRunning = AtomicBoolean(false)
    private var exchangeThread: Thread? = null

    // === SENDER STATE ===
    private val frameSequence = AtomicLong(0)
    @Volatile private var latestFrame: ByteArray? = null // From Camera

    // Active transmission state
    private var currentTxFrameId: Int = 0
    private var currentTxData: ByteArray? = null
    private var currentTxPart: Int = 0
    private var currentTxTotalParts: Int = 0

    // === RECEIVER STATE ===
    private class FrameAssembler(val frameId: Int, val totalParts: Int) {
        val parts = arrayOfNulls<ByteArray>(totalParts)
        var receivedCount = 0
        var birthTime = System.currentTimeMillis()

        fun addPart(partNum: Int, data: ByteArray): Boolean {
            if (partNum < totalParts && parts[partNum] == null) {
                parts[partNum] = data
                receivedCount++
            }
            return receivedCount == totalParts
        }

        fun assemble(): ByteArray {
            val totalSize = parts.filterNotNull().sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            for (part in parts) {
                if (part != null) {
                    System.arraycopy(part, 0, result, offset, part.size)
                    offset += part.size
                }
            }
            return result
        }
    }

    // Cache for out-of-order packets (keeps last 5 frames)
    private val assemblers = LruCache<Int, FrameAssembler>(5)
    private val assemblerLock = Any()
    // Tracking for frame monotonicity
    private var lastCompletedFrameId = -1

    // Statistics for monitoring
    private val framesSent = AtomicLong(0)
    private val framesReceived = AtomicLong(0)
    private val errorsEncountered = AtomicLong(0)
    private val lastPacketTime = AtomicLong(0)
    private val lastFrameTime = AtomicLong(0)

    private var exchangeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var decodeExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    private fun ensureExecutors() {
        if (exchangeExecutor.isShutdown || exchangeExecutor.isTerminated) {
            exchangeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        }
        if (decodeExecutor.isShutdown || decodeExecutor.isTerminated) {
            decodeExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
        }
        if (targetExecutor.isShutdown || targetExecutor.isTerminated) {
            targetExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        ensureExecutors()

        try {
            socket = DatagramSocket(localPort).apply {
                soTimeout = 1 // 1ms timeout = tight loop
                sendBufferSize = 2 * 1024 * 1024
                receiveBufferSize = 2 * 1024 * 1024
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind port $localPort", e)
            isRunning.set(false)
            return
        }

        exchangeExecutor.execute {
            Log.d(TAG, "Exchange thread started - Interleaved Mode")

            // Reusable buffers
            val rxBuffer = ByteArray(MAX_PACKET_SIZE)
            val rxPacket = DatagramPacket(rxBuffer, rxBuffer.size)
            val txBuffer = ByteArray(MAX_PACKET_SIZE)
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 100

            while (isRunning.get() && consecutiveErrors < maxConsecutiveErrors) {
                val sock = socket
                if (sock == null || sock.isClosed) break
                val peer = peerAddress

                var rxCount = 0  // Declare outside try block

                try {
                    // ============================================================
                    // 1. CHECK FOR NEW FRAME (Prioritize Freshness)
                    // ============================================================
                    val newFrame = latestFrame
                    if (newFrame != null) {
                        latestFrame = null // Consume immediately
                        // Start new transmission (aborting old one if exists)
                        currentTxData = newFrame
                        currentTxFrameId = frameSequence.incrementAndGet().toInt()
                        currentTxTotalParts = (newFrame.size + MAX_DATA_PER_PACKET - 1) / MAX_DATA_PER_PACKET
                        currentTxPart = 0

                        // Safety cap
                        if (currentTxTotalParts > 200) {
                            currentTxData = null // Drop too big
                        }
                    }

                    // ============================================================
                    // 2. SEND BURST
                    // ============================================================
                    if (peer != null && currentTxData != null && currentTxPart < currentTxTotalParts) {
                        var sentCount = 0
                        while (sentCount < BURST_SIZE_TX && currentTxPart < currentTxTotalParts) {
                            sendPacket(sock, peer, txBuffer)
                            currentTxPart++
                            sentCount++
                        }
                        
                        // If finished this frame, clear
                        if (currentTxPart >= currentTxTotalParts) {
                            currentTxData = null
                        }
                    }

                    // ============================================================
                    // 3. RECEIVE BURST
                    // ============================================================
                    rxCount = 0  // Reset at start of loop
                    while (rxCount < BURST_SIZE_RX) {
                        try {
                            sock.receive(rxPacket)
                            processPacket(rxPacket)
                            rxCount++
                        } catch (e: SocketTimeoutException) {
                            break // No more data right now
                        }
                    }

                    // ============================================================
                    // 4. SMART YIELD
                    // ============================================================
                    // If we did NO work (no tx, no rx), sleep a bit to save CPU
                    if (currentTxData == null && rxCount == 0) {
                         Thread.sleep(5)
                    } else {
                        // We are busy, but yield slightly to let Radio switch modes?
                        // Or just loop fast.
                        // Let's sleep tiny bit effectively limiting rate but allowing sharing
                        // Thread.sleep(0, 500000) // 0.5ms? Na, Java sleep is coarse.
                        // Just loop. The OS socket timeout of 2ms acts as a throttle if RX is empty.
                        
                        // If we are transmitting HEAVILY, force a tiny yield every loop to let RX happen
                        if (currentTxData != null) {
                             // Force the radio to breathe. 
                             // Reduced sleep to 0 (effectively removed) as timeout does the job
                             // Thread.sleep(0) 
                        }
                    }

                } catch (e: InterruptedException) {
                    Log.d(TAG, "Exchange thread interrupted")
                    break
                } catch (e: Exception) {
                    consecutiveErrors++
                    errorsEncountered.incrementAndGet()
                    if (isRunning.get()) {
                        Log.e(TAG, "Loop error ($consecutiveErrors/$maxConsecutiveErrors): ${e.javaClass.simpleName} - ${e.message}")
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            Log.e(TAG, "Too many consecutive errors - stopping exchange thread")
                            isRunning.set(false)
                            break
                        }
                        try { Thread.sleep(100) } catch (ignore: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }

                // Reset error counter on successful loop
                if (consecutiveErrors > 0 && rxCount > 0) {
                    consecutiveErrors = 0
                }
            }

            if (consecutiveErrors >= maxConsecutiveErrors) {
                Log.e(TAG, "Exchange thread stopped due to too many errors")
            } else {
                Log.d(TAG, "Exchange thread stopped normally")
            }
        }
    }

    private fun sendPacket(socket: DatagramSocket, peer: InetAddress, buffer: ByteArray) {
        val data = currentTxData ?: return
        
        // Calculate offsets
        val offset = currentTxPart * MAX_DATA_PER_PACKET
        val length = Math.min(MAX_DATA_PER_PACKET, data.size - offset)
        
        // Fill Header
        // [0-3] FrameID
        buffer[0] = (currentTxFrameId shr 24).toByte()
        buffer[1] = (currentTxFrameId shr 16).toByte()
        buffer[2] = (currentTxFrameId shr 8).toByte()
        buffer[3] = currentTxFrameId.toByte()
        
        // [4-5] PartNum
        buffer[4] = (currentTxPart shr 8).toByte()
        buffer[5] = currentTxPart.toByte()
        
        // [6-7] TotalParts
        buffer[6] = (currentTxTotalParts shr 8).toByte()
        buffer[7] = currentTxTotalParts.toByte()
        
        // [8-11] DataLen
        buffer[8] = (length shr 24).toByte()
        buffer[9] = (length shr 16).toByte()
        buffer[10] = (length shr 8).toByte()
        buffer[11] = length.toByte()
        
        // Payload
        System.arraycopy(data, offset, buffer, HEADER_SIZE, length)
        
        // Send
        val pkt = DatagramPacket(buffer, HEADER_SIZE + length, peer, peerPort)
        try {
            socket.send(pkt)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            Log.d(TAG, "UDP exchange already stopped")
            return
        }

        Log.d(TAG, "Stopping UDP exchange...")

        // Close socket first to unblock any receive() calls
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
        socket = null

        // Shutdown executors with timeout
        try {
            exchangeExecutor.shutdown()
            if (!exchangeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "Exchange executor didn't terminate, forcing shutdown")
                exchangeExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down exchange executor", e)
            exchangeExecutor.shutdownNow()
        }

        try {
            decodeExecutor.shutdown()
            if (!decodeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "Decode executor didn't terminate, forcing shutdown")
                decodeExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down decode executor", e)
            decodeExecutor.shutdownNow()
        }

        try {
            targetExecutor.shutdown()
            if (!targetExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                targetExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down target executor", e)
            targetExecutor.shutdownNow()
        }

        // Clear assembler cache
        synchronized(assemblerLock) {
            assemblers.evictAll()
        }

        // Clear transmission state
        currentTxData = null
        latestFrame = null
        peerAddress = null

        Log.d(TAG, "UDP exchange stopped successfully")
    }

    fun isHealthy(): Boolean {
        return isRunning.get() && socket != null && socket?.isClosed == false
    }

    fun restart() {
        Log.d(TAG, "Restarting UDP exchange...")
        stop()

        // Give it a moment to clean up
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Restart interrupted during sleep")
            return
        }

        start()
        Log.d(TAG, "UDP exchange restart complete")
    }

    private var targetExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun setTarget(hostname: String) {
        ensureExecutors()
        targetExecutor.execute {
            try {
                Log.d(TAG, "Setting target: $hostname")
                val address = InetAddress.getByName(hostname)
                peerAddress = address
                Log.d(TAG, "Target set to $hostname:$peerPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve target $hostname: ${e.message}")
                peerAddress = null
            }
        }
    }

    fun sendFrame(jpegData: ByteArray) {
        latestFrame = jpegData
        framesSent.incrementAndGet()
    }

    // Statistics getters for monitoring
    fun getFramesSent(): Long = framesSent.get()
    fun getFramesReceived(): Long = framesReceived.get()
    fun getErrorCount(): Long = errorsEncountered.get()
    fun getLastPacketTime(): Long = lastPacketTime.get()
    fun getLastFrameTime(): Long = lastFrameTime.get()

    private fun tryProcessIncompleteFrame(frameId: Int, assembler: FrameAssembler) {
        try {
            // Skip missing packets and use what we have
            val totalSize = assembler.parts.filterNotNull().sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            for (part in assembler.parts) {
                if (part != null) {
                    System.arraycopy(part, 0, result, offset, part.size)
                    offset += part.size
                }
            }

            // Try to decode the incomplete frame
            decodeExecutor.execute {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(result, 0, result.size)
                    if (bitmap != null) {
                        onFrameReceived(bitmap)
                        lastFrameTime.set(System.currentTimeMillis())
                        Log.d(TAG, "Used incomplete frame $frameId (${assembler.receivedCount}/${assembler.totalParts})")
                    }
                } catch (e: Exception) {
                    // Incomplete frame wasn't decodable, skip it
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not salvage frame $frameId")
        }
    }

    private fun processPacket(packet: DatagramPacket) {
        val buffer = packet.data
        val length = packet.length
        if (length < HEADER_SIZE) return

        lastPacketTime.set(System.currentTimeMillis())

        // Parse header
        val frameId = ((buffer[0].toInt() and 0xFF) shl 24) or
                ((buffer[1].toInt() and 0xFF) shl 16) or
                ((buffer[2].toInt() and 0xFF) shl 8) or
                (buffer[3].toInt() and 0xFF)
        val partNum = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val totalParts = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        val dataLen = ((buffer[8].toInt() and 0xFF) shl 24) or
                ((buffer[9].toInt() and 0xFF) shl 16) or
                ((buffer[10].toInt() and 0xFF) shl 8) or
                (buffer[11].toInt() and 0xFF)

        // Sanity
        if (dataLen <= 0 || dataLen > MAX_DATA_PER_PACKET) return
        if (partNum >= totalParts) return
        if (totalParts > 200) return

        synchronized(assemblerLock) {
            // Detect sender restart or frame ID wrap-around
            if (lastCompletedFrameId != -1 && frameId + 50 < lastCompletedFrameId) {
                Log.d(TAG, "Frame ID jumped backwards ($frameId < $lastCompletedFrameId) - resetting assembler")
                assemblers.evictAll()
                lastCompletedFrameId = -1
            }

            // Drop old frames
            if (lastCompletedFrameId != -1) {
                if (frameId <= lastCompletedFrameId && (lastCompletedFrameId - frameId) < 1000) {
                     return
                }
            }

            var assembler = assemblers.get(frameId)
            if (assembler == null) {
                assembler = FrameAssembler(frameId, totalParts)
                assemblers.put(frameId, assembler)
            }

            // Clean up old incomplete frames - AGGRESSIVE timeout
            val now = System.currentTimeMillis()
            val iter = assemblers.snapshot().iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val oldAssembler = entry.value
                // Drop incomplete old frames to avoid flicker/artifacts
                if (now - oldAssembler.birthTime > FRAME_TIMEOUT_MS && entry.key != frameId) {
                    assemblers.remove(entry.key)
                }
            }

            val data = ByteArray(dataLen)
            System.arraycopy(buffer, HEADER_SIZE, data, 0, dataLen)

            if (assembler.addPart(partNum, data)) {
                 // Check if frame is "complete enough" (95%)
                 val completeness = assembler.receivedCount.toDouble() / assembler.totalParts
                 if (completeness >= FRAME_TOLERANCE) {
                     val fullFrame = assembler.assemble()
                     assemblers.remove(frameId)
                     lastCompletedFrameId = frameId
                     lastFrameTime.set(System.currentTimeMillis())

                     decodeExecutor.execute {
                         try {
                             val bitmap = BitmapFactory.decodeByteArray(fullFrame, 0, fullFrame.size)
                             if (bitmap != null) {
                                 onFrameReceived(bitmap)
                                 framesReceived.incrementAndGet()
                             } else {
                                 errorsEncountered.incrementAndGet()
                             }
                         } catch (e: Exception) {
                             // Silently skip corrupt frames
                             errorsEncountered.incrementAndGet()
                         }
                     }
                 }
            }
        }
    }
}
