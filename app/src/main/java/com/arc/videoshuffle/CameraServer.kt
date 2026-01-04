package com.arc.videoshuffle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.TextureView
import androidx.core.graphics.createBitmap
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class CameraServer(private val context: Context, private val deviceId: String, private val cameraView: TextureView) : NanoHTTPD(8080) {

    interface ServerListener {
        fun onServerStarted()
        fun onServerFailed(e: Exception)
    }

    @Volatile
    private var latestFrame: ByteArray? = null
    private var cameraClient: MultiCameraClient? = null
    private var currentCamera: MultiCameraClient.ICamera? = null
    private var serverListener: ServerListener? = null

    init {
        createPlaceholderFrame() // Create a placeholder initially
    }

    fun setServerListener(listener: ServerListener) {
        this.serverListener = listener
    }

    private val cameraStateCallback = object : ICameraStateCallBack {
        override fun onCameraState(
            self: MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?
        ) {
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    Log.d("CameraServer", "Camera opened successfully")
                    self.addPreviewDataCallBack(previewDataCallback)
                }
                ICameraStateCallBack.State.CLOSED -> {
                    Log.d("CameraServer", "Camera closed")
                    createPlaceholderFrame()
                }
                ICameraStateCallBack.State.ERROR -> {
                    Log.e("CameraServer", "Camera error: $msg")
                    createPlaceholderFrame()
                }
            }
        }
    }

    private val previewDataCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
            data?.let {
                try {
                    val yuvImage = YuvImage(it, ImageFormat.NV21, width, height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
                    latestFrame = out.toByteArray()
                } catch (e: Exception) {
                    Log.e("CameraServer", "Failed to convert frame", e)
                }
            }
        }
    }

    // This method MUST be called on the Main Thread
    fun prepare() {
        Log.d("CameraServer", "Preparing camera client...")
        cameraClient = MultiCameraClient(context, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {}
            override fun onDetachDec(device: UsbDevice?) {
                if (currentCamera?.getUsbDevice()?.deviceId == device?.deviceId) {
                    currentCamera = null
                    createPlaceholderFrame()
                }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?) {
                device?.let { dev ->
                    ctrlBlock?.let { block ->
                        val camera = CameraUVC(context, dev)
                        camera.setUsbControlBlock(block)
                        currentCamera = camera
                        startCamera(camera)
                    }
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?) {
                if (currentCamera?.getUsbDevice()?.deviceId == device?.deviceId) {
                    currentCamera?.closeCamera()
                    currentCamera = null
                    createPlaceholderFrame()
                }
            }

            override fun onCancelDev(device: UsbDevice?) {}
        })
        cameraClient?.register()
    }

    private fun startCamera(camera: MultiCameraClient.ICamera) {
        val request = CameraRequest.Builder()
            .setPreviewWidth(1920)
            .setPreviewHeight(1080)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setAspectRatioShow(true)
            .setRawPreviewData(true)
            .create()

        camera.openCamera(cameraView, request)
        camera.setCameraStateCallBack(cameraStateCallback)
    }

    fun stopCamera() {
        currentCamera?.closeCamera()
    }

    private fun createPlaceholderFrame() {
        try {
            val bitmap = createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint()
            paint.color = Color.WHITE
            paint.textSize = 40f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("No Camera / Signal", 320f, 240f, paint)
            
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
            latestFrame = out.toByteArray()
        } catch (e: Exception) {
            Log.e("CameraServer", "Failed to create placeholder", e)
        }
    }

    override fun start() {
        try {
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d("CameraServer", "HTTP Server started successfully")
            serverListener?.onServerStarted()
        } catch (e: IOException) {
            Log.e("CameraServer", "Error starting HTTP server", e)
            serverListener?.onServerFailed(e)
        }
    }

    override fun stop() {
        super.stop()
        currentCamera?.closeCamera()
        cameraClient?.unRegister()
        cameraClient?.destroy()
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/video" -> createMjpegResponse()
            "/status" -> {
                val json = "{\"app\": \"VideoShuffle\", \"status\": \"ready\", \"device\": \"${android.os.Build.MODEL}\", \"id\": \"$deviceId\"}"
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
            else -> {
                val msg = "<html><body><h1>Video Shuffle Server</h1><p><a href='/video'>View Stream</a></p></body></html>"
                newFixedLengthResponse(msg)
            }
        }
    }

    private fun createMjpegResponse(): Response {
        return object : Response(Status.OK, "multipart/x-mixed-replace; boundary=BoundaryString", null as InputStream?, -1) {
            override fun send(outputStream: java.io.OutputStream) {
                try {
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=BoundaryString\r\n" +
                            "\r\n"
                    outputStream.write(header.toByteArray())
                    outputStream.flush()

                    while (true) {
                        latestFrame?.let { frame ->
                            outputStream.write("--BoundaryString\r\n".toByteArray())
                            outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                            outputStream.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                            outputStream.write(frame)
                            outputStream.write("\r\n".toByteArray())
                            outputStream.flush()
                        }
                        Thread.sleep(66) // ~15 FPS
                    }
                } catch (e: Exception) {
                    // Client disconnected
                } finally {
                    Log.d("CameraServer", "Client stream ended")
                }
            }
        }
    }
}
