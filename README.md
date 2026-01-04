# VideoShuffle: A Self-Organizing Video Shuffling App

VideoShuffle is a robust Android application designed for creating a seamless, self-organizing video shuffling network. It is built to run on a group of Android devices (such as tablets and TV boxes) on the same private mesh network (e.g., Tailscale, Headscale), and it is optimized for use with MagicDNS.

## Core Features

- **Automatic Peer Discovery:** The app uses a multi-pronged approach (MagicDNS, UDP broadcasts, and local network scanning) to reliably discover other devices running the same app.
- **Dynamic Network Awareness:** New devices that join the network are automatically discovered and added to the shuffling pool without requiring an app restart.
- **Intelligent Shuffling:** The app will automatically connect to the first available peer. After a 5-minute interval, it will randomly shuffle to a different device in the network.
- **Robust Error Handling:** The app gracefully handles stream failures, removing unresponsive peers from the active list and immediately attempting to switch to a different device.
- **Hardware-Aware Camera Support:** The app automatically detects if a device has a USB camera connected and will use it as a video source. On devices without a camera or on TV-based devices (which may have poor USB support), it will safely fall back to a "No Signal" placeholder.
- **Kiosk Mode:** The app is designed to run as a dedicated home screen launcher, making it ideal for single-purpose device deployments.

## How It Works

Each instance of the app acts as both a client and a server.

- **As a Server:** It starts a lightweight HTTP server on port `8080`. If a USB camera is present, it serves an MJPEG stream of the camera feed. It also provides a `/status` endpoint for other peers to verify its identity.
- **As a Client:** It continuously scans the network to find and verify other peers. When a peer is found, it connects to its video stream. The app will never show its own video stream to itself.

## Building from Source

### Prerequisites

- Android Studio (latest version recommended)
- A Java Development Kit (JDK) version 11 or higher

### Build Instructions

1.  **Clone the Repository:**

    ```bash
    git clone 
    ```

2.  **Open in Android Studio:**

    -   Launch Android Studio.
    -   Select "Open" and navigate to the cloned project directory.

3.  **Build the APK:**

    -   Wait for Android Studio to sync the Gradle project.
    -   From the menu bar, go to `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
    -   Once the build is complete, a notification will appear. Click "locate" to find the generated `app-debug.apk` file. This is the file you will distribute and install on your devices.

## Configuration & Usage

### MagicDNS Naming

The app is pre-configured to discover peers with hostnames following the pattern `uninovis-tp-XX`, where `XX` is a number from `01` to `20`. For the app to work correctly, you must ensure your devices are named accordingly within your Tailscale network.

To change this prefix, modify the following line in `MainActivity.kt`:

```kotlin
// In the scanMagicDnsNames() method
val hostname = "uninovis-tp-${String.format(Locale.US, "%02d", i)}"
```

### Enabling Debug Logging

By default, the on-screen debug logging is disabled for a clean user experience. To re-enable it for debugging purposes:

1.  Open the `MainActivity.kt` file.
2.  Navigate to the `log(msg: String)` function at the end of the file.
3.  Uncomment the line that appends text to the `logTextView`:

    ```kotlin
    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            // Uncomment the following two lines to enable on-screen logging
            //if (::logTextView.isInitialized) {
            //    logTextView.append("[$time] $msg\n")
            //}

            // The line below will still send logs to the standard Android Logcat
            Log.d(TAG, "[$time] $msg")
        }
    }
    ```

4.  Rebuild the APK as described above.

This will restore the on-screen log, which is invaluable for diagnosing network or discovery issues in the field.
