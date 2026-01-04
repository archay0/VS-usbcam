package com.arc.videoshuffle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    fun startStream(url: String) {
        stopStream()
        isRunning = true
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

    private inner class MjpegDownloader(private val streamUrl: String) : Thread() {
        override fun run() {
            var inputStream: InputStream? = null
            try {
                val url = URL(streamUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000 // 10s connection timeout
                connection.readTimeout = 10000    // 10s read timeout
                connection.doInput = true
                connection.connect()
                inputStream = BufferedInputStream(connection.inputStream)

                while (isRunning) {
                    val frame = readMjpegFrame(inputStream)
                    if (frame != null) {
                        drawBitmap(frame)
                    } else {
                        // If we fail to decode a frame, treat it as an error to trigger reconnect
                        throw Exception("Frame decode failed or stream ended")
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
                // Read Headers until \r\n\r\n
                var contentLength = -1
                
                val headerBytes = ByteArray(4096)
                var pos = 0
                var byte: Int
                while (inputStream.read().also { byte = it } != -1) {
                    if (pos < 4096) {
                        headerBytes[pos++] = byte.toByte()
                    }
                    if (pos >= 4 && 
                        headerBytes[pos-1] == 10.toByte() && 
                        headerBytes[pos-2] == 13.toByte() &&
                        headerBytes[pos-3] == 10.toByte() && 
                        headerBytes[pos-4] == 13.toByte()) {
                        break // End of headers
                    }
                    if (pos >= 4095) break 
                }
                
                val headerString = String(headerBytes, 0, pos)
                val lengthIndex = headerString.indexOf("Content-Length: ")
                if (lengthIndex != -1) {
                    val endOfLine = headerString.indexOf("\r\n", lengthIndex)
                    val lengthStr = headerString.substring(lengthIndex + 16, endOfLine).trim()
                    
                    try {
                        contentLength = lengthStr.toInt()
                    } catch (e: NumberFormatException) {
                        contentLength = -1
                    }
                }

                // Sanity check on size (max 10MB)
                if (contentLength > 0 && contentLength < 10 * 1024 * 1024) {
                    val imageData = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = inputStream.read(imageData, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    return BitmapFactory.decodeByteArray(imageData, 0, contentLength)
                }
                
            } catch (e: Exception) {
                throw e 
            }
            return null
        }

        private fun drawBitmap(bitmap: Bitmap) {
            var canvas: Canvas? = null
            try {
                canvas = mSurfaceHolder.lockCanvas()
                if (canvas != null) {
                    // Clear background
                    canvas.drawColor(Color.BLACK)
                    
                    // Scale to fit
                    val destRect = Rect(0, 0, width, height)
                    canvas.drawBitmap(bitmap, null, destRect, null)
                }
            } finally {
                if (canvas != null) {
                    mSurfaceHolder.unlockCanvasAndPost(canvas)
                }
            }
        }
    }
}