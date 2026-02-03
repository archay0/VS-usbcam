package com.arc.videoshuffle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Simple SurfaceView for displaying bitmaps from UDP frames.
 * No network code here - just displays whatever bitmap you give it.
 */
class UdpVideoView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var fpsCallback: ((Int) -> Unit)? = null
    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()
    private val destRect = Rect()
    private val paint = Paint()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    fun setFpsCallback(callback: (Int) -> Unit) {
        this.fpsCallback = callback
    }

    /**
     * Display a bitmap. Call this from any thread - it handles synchronization.
     */
    fun displayFrame(bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.BLACK)
                destRect.set(0, 0, width, height)
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
            // Ignore canvas errors
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            // Recycle bitmap after drawing
            bitmap.recycle()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
