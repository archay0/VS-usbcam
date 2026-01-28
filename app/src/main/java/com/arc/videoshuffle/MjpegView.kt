package com.arc.videoshuffle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MjpegView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface MjpegListener {
        fun onStreamError(e: Exception?)
    }

    private var thread: MjpegDownloader? = null
    private var mSurfaceHolder: SurfaceHolder = holder
    private var isRunning = false
    private val paint = Paint()
    private var listener: MjpegListener? = null
    private var fpsCallback: ((Int) -> Unit)? = null
    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()
    // Frame interpolation
    private var lastFrame: Bitmap? = null
    private var currentFrame: Bitmap? = null
    private var lastFrameTime = 0L
    private val targetDisplayFps = 30
    private val displayInterval = 1000L / targetDisplayFps // 33ms for 30 FPS
    
    // Error resilience
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 10  // Allow 10 errors before reporting
    private var lastSuccessfulFrame = System.currentTimeMillis()
    private var lastDisplayedSeq = 0L  // Track last displayed frame sequence

    init {
        mSurfaceHolder.addCallback(this)
        isFocusable = true
        paint.color = Color.BLACK
        paint.textSize = 40f
        paint.textAlign = Paint.Align.CENTER
    }

    fun setListener(listener: MjpegListener) {
        this.listener = listener
    }

    fun setFpsCallback(callback: (Int) -> Unit) {
        this.fpsCallback = callback
    }

    fun startStream(url: String) {
        stopStream()
        isRunning = true
        lastDisplayedSeq = 0L  // Reset sequence tracking
        thread = MjpegDownloader(url)
        thread?.start()
    }

    fun stopStream() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Ready to draw
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }

    private inner class MjpegDownloader(private val streamUrl: String) : Thread("MjpegReceiver") {
        init {
            // High priority so receiving doesn't get starved by sending
            priority = Thread.MAX_PRIORITY
        }
        
        override fun run() {
            var inputStream: InputStream? = null
            try {
                val url = URL(streamUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000 // 5s connection timeout
                connection.readTimeout = 500     // 500ms read timeout - fail fast on stalls
                connection.doInput = true
                connection.useCaches = false  // No caching for live stream
                connection.setRequestProperty("Connection", "keep-alive")  // Reuse TCP connection!
                connection.connect()
                inputStream = BufferedInputStream(connection.inputStream, 65536) // 64KB buffer for throughput

                consecutiveErrors = 0  // Reset error counter on successful connection
                
                while (isRunning) {
                    try {
                        val frame = readMjpegFrame(inputStream)
                        if (frame != null) {
                            drawBitmap(frame)
                            consecutiveErrors = 0  // Reset on successful frame
                            lastSuccessfulFrame = System.currentTimeMillis()
                        } else {
                            // Null frame - yield and retry immediately
                            consecutiveErrors++
                            Thread.yield()
                            // Only fail if we've had too many consecutive errors
                            if (consecutiveErrors >= maxConsecutiveErrors) {
                                throw Exception("Too many consecutive frame failures")
                            }
                        }
                    } catch (e: Exception) {
                        consecutiveErrors++
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            throw e  // Propagate error to outer catch
                        }
                        // Otherwise continue immediately
                    }
                }
            } catch (e: InterruptedException) {
                // Thread was interrupted by stopStream(). This is a clean shutdown.
                Log.d("MjpegView", "Stream thread intentionally interrupted.")
            } catch (e: Exception) {
                // Any other exception is a genuine error.
                Log.e("MjpegView", "Error in stream downloader", e)
                if (isRunning) {
                    listener?.onStreamError(e)
                }
            } finally {
                try {
                    inputStream?.close()
                } catch (e: IOException) { /* ignore */ }
            }
        }

        private fun readMjpegFrame(inputStream: InputStream): Bitmap? {
            try {
                // Read headers byte-by-byte (safe for boundary detection)
                var contentLength = -1
                var frameSeq = 0L
                
                val headerBytes = ByteArray(512)
                var pos = 0
                
                // Byte-by-byte for headers - bulk reads can overshoot boundary
                while (pos < 512) {
                    val b = inputStream.read()
                    if (b == -1) return null
                    headerBytes[pos++] = b.toByte()
                    
                    // Check for end of headers \r\n\r\n
                    if (pos >= 4 && 
                        headerBytes[pos-1] == 10.toByte() && 
                        headerBytes[pos-2] == 13.toByte() &&
                        headerBytes[pos-3] == 10.toByte() && 
                        headerBytes[pos-4] == 13.toByte()) {
                        break
                    }
                }
                
                val headerString = String(headerBytes, 0, pos)
                
                // Parse Content-Length
                val lengthIndex = headerString.indexOf("Content-Length: ")
                if (lengthIndex != -1) {
                    val endOfLine = headerString.indexOf("\r\n", lengthIndex)
                    if (endOfLine > lengthIndex) {
                        contentLength = headerString.substring(lengthIndex + 16, endOfLine).trim().toIntOrNull() ?: -1
                    }
                }
                
                // Parse X-Seq sequence number
                val seqIndex = headerString.indexOf("X-Seq: ")
                if (seqIndex != -1) {
                    val endOfLine = headerString.indexOf("\r\n", seqIndex)
                    if (endOfLine > seqIndex) {
                        frameSeq = headerString.substring(seqIndex + 7, endOfLine).trim().toLongOrNull() ?: 0L
                    }
                }
                
                // DROP OLD FRAMES
                if (frameSeq > 0 && frameSeq <= lastDisplayedSeq) {
                    if (contentLength > 0 && contentLength < 500000) {
                        var toSkip = contentLength
                        while (toSkip > 0) {
                            val skipped = inputStream.skip(toSkip.toLong()).toInt()
                            if (skipped <= 0) {
                                val read = inputStream.read()
                                if (read < 0) break
                                toSkip--
                            } else {
                                toSkip -= skipped
                            }
                        }
                    }
                    return null
                }

                // BULK read for image data (this is safe - we know exact length)
                if (contentLength > 0 && contentLength < 500000) {
                    val imageData = ByteArray(contentLength)
                    var totalRead = 0
                    
                    while (totalRead < contentLength) {
                        val read = inputStream.read(imageData, totalRead, contentLength - totalRead)
                        if (read <= 0) return null
                        totalRead += read
                    }
                    
                    return BitmapFactory.decodeByteArray(imageData, 0, contentLength)?.also {
                        if (frameSeq > lastDisplayedSeq) lastDisplayedSeq = frameSeq
                    }
                }
                return null
            } catch (e: Exception) {
                Log.w("MjpegView", "Frame read error: ${e.message}")
                return null
            }
        }

        private fun drawBitmap(bitmap: Bitmap) {
            lastFrameTime = System.currentTimeMillis()
            
            // Draw directly - no copying to prevent memory leak
            drawFrameToCanvas(bitmap)
            
            // Recycle after drawing - FORGET IT COMPLETELY
            bitmap.recycle()
            // NO GC CALLS - they cause 100-500ms pauses that freeze stream
        }
        
        private fun drawFrameToCanvas(bitmap: Bitmap) {
            var canvas: Canvas? = null
            try {
                canvas = mSurfaceHolder.lockCanvas()
                if (canvas != null) {
                    // Clear background
                    canvas.drawColor(Color.BLACK)
                    
                    // Scale to fit
                    val destRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bitmap, null, destRect, null)
                    
                    // FPS calculation
                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsUpdate
                    if (elapsed >= 1000) {
                        val fps = (frameCount * 1000 / elapsed).toInt()
                        fpsCallback?.invoke(fps)
                        frameCount = 0
                        lastFpsUpdate = now
                    }
                }
            } catch (e: Exception) {
                Log.e("MjpegView", "Canvas error", e)
            } finally {
                if (canvas != null) {
                    mSurfaceHolder.unlockCanvasAndPost(canvas)
                }
            }
        }
    }
}