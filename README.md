# VideoShuffle: A Self-Organizing Video Shuffling App

VideoShuffle is a robust Android application designed for creating a seamless, self-organizing video shuffling network. It is built to run on a group of Android devices (such as tablets and TV boxes) on the same private mesh network (e.g., Tailscale, Headscale), and it is optimized for use with MagicDNS.

## Download the APK from the Releases page!

## Core Features

- **Automatic Peer Discovery:** The app uses a multi-pronged approach (MagicDNS, UDP broadcasts, and local network scanning) to reliably discover other devices running the same app.
- **Dynamic Network Awareness:** New devices that join the network are automatically discovered and added to the shuffling pool without requiring an app restart.
- **Intelligent Shuffling:** The app will automatically connect to the first available peer. After a 5-minute interval, it will randomly shuffle to a different device in the network.
- **Robust Error Handling:** The app gracefully handles stream failures, removing unresponsive peers from the active list and immediately attempting to switch to a different device.
- **Hardware-Aware Camera Support:** The app automatically detects if a device has a USB camera connected and will use it as a video source. On devices without a camera or on TV-based devices (which may have poor USB support), it will safely fall back to a "No Signal" placeholder.
- **Kiosk Mode:** The app is designed to run as a dedicated home screen launcher, making it ideal for single-purpose device deployments.

## Network Configuration (Tailscale/Headscale)

The app's discovery system is designed to work over a mesh VPN and relies on a specific device naming convention.

1.  **Mesh Network:** All devices running this app must be part of the same Tailscale or Headscale network. This ensures they can communicate with each other regardless of their physical location.

2.  **Enable MagicDNS:** You must enable MagicDNS in your network's admin console. This is what allows devices to find each other using their simple hostnames instead of IP addresses.

3.  **Device Naming Convention:** In your network's admin console (e.g., '//servername//'), you must name your devices according to the following pattern:
    `uninovis-tp-XX`
    Where `XX` is a two-digit number (e.g., `01`, `02`, `03`). The app is hard-coded to scan for devices named from `uninovis-tp-01` through `uninovis-tp-20`.

Failure to follow this naming convention will prevent the MagicDNS discovery from working.

## Building from Source

### Prerequisites

- Android Studio (latest version recommended)
- A Java Development Kit (JDK) version 11 or higher

### Build Instructions

1.  **Clone the Repository:**

    ```bash
    git clone https://github.com/archay0/VS-usbcam.git
    ```

2.  **Open in Android Studio:**

    -   Launch Android Studio.
    -   Select "Open" and navigate to the cloned project directory.

3.  **Build the APK:**

    -   Wait for Android Studio to sync the Gradle project.
    -   From the menu bar, go to `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
    -   Once the build is complete, a notification will appear. Click "locate" to find the generated `app-debug.apk` file.

## Distributing the App

Distribution with trusted url exchange and Headscale logins. Please ask admin.

## Enabling Debug Logging

For a clean UI, on-screen logging is disabled by default. To re-enable it for debugging:

1.  Open `MainActivity.kt`.
2.  Navigate to the `log(msg: String)` function.
3.  Uncomment the line that appends text to the `logTextView`. The `Log.d` line ensures that logs are always sent to Android Studio's Logcat.

    ```kotlin
    private fun log(msg: String) {
        // ...
        runOnUiThread {
            // Uncomment the following lines to enable on-screen logging
            // if (::logTextView.isInitialized) {
            //     logTextView.append("[$time] $msg\n")
            // }
            Log.d(TAG, "[$time] $msg")
        }
    }
    ```
4.  Rebuild the APK.
