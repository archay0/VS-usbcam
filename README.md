# VideoShuffle

A lightweight, low-latency video call partner shuffling application for low-power Android devices.

## Features

- **UDP-based video streaming** - Direct JPEG frame transmission for minimal latency
- **Automatic peer discovery** - MagicDNS-based discovery
- **Smart peer pairing** - Lexicographic tie-breaking for automatic session initiation
- **5-minute auto-shuffle** - Automatic partner rotation with session timers
- **Optimized for low-end devices** - Minimal CPU overhead, aggressive memory management
- **Smooth playback** - Incomplete frame tolerance with adaptive timeout (no stuttering)
- **Bidirectional video** - Simultaneous send/receive with interleaved bursts

## Building

### Prerequisites

- Android Studio (latest)
- JDK 11+
- Android SDK 24+ (API level)

### Build Instructions

```bash
# Debug build (for development)
./gradlew assembleDebug

# Release build (for production)
./gradlew assembleRelease
```

APK locations:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

### Installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

Required:
- `CAMERA` - Video capture
- `INTERNET` - Network communication
- `ACCESS_NETWORK_STATE` - Network status

Optional (required=false):
- `RECORD_AUDIO` - Audio capture
- USB host/autofocus - Device compatibility

## Device Requirements

- Android 7.0+ (API 24+)
- 2MB minimum free RAM
- Network: 2Mbps up/down recommended
- Camera (front or back)

## Performance Tuning

### Key Settings

In `UdpFrameExchange.kt`:
```kotlin
BURST_SIZE_TX = 30            // Packets per send burst
BURST_SIZE_RX = 30            // Packets per receive burst
FRAME_TIMEOUT_MS = 250L       // Timeout for frame arrival
FRAME_TOLERANCE = 0.95        // Accept frames at 95% complete
```

In `WebRTCManager.kt`:
```kotlin
yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, out)  // JPEG quality %
```

## Known Limitations

- **Resolution**: 640x480 optimal for 2Mbps (much higher = network saturation)
- **FPS**: 20fps optimal (higher = network saturation)
- **Quality**: 50% JPEG optimal (higher = larger frames)
- **Hardware codec**: Not supported on Orbsmart TV boxes

## License

Proprietary - Archay Wakodikar
