# Aegean Intercom

Offline Wi‑Fi intercom built with Kotlin and Android SDK. One phone hosts a hotspot/server and another connects as a client to stream duplex voice over UDP with discovery and reconnect logic.

Binary build-time assets (Gradle wrapper JAR, launcher icons) are intentionally omitted from this repository. Android Studio will recreate the Gradle wrapper automatically; if needed, regenerate with `gradle wrapper --gradle-version 8.4` (or later) before syncing.

## Running

1. Open the project in Android Studio (minimum Android 6, target Android 14).
2. Build and install on two devices.
3. On the hotspot device, tap **Start as Server**. On the peer, tap **Start as Client**.
4. Both devices auto-discover over UDP broadcast and stream audio using `AudioRecord`/`AudioTrack` in a foreground service.

The project uses Gradle 8.14.3 with the Android Gradle Plugin 8.2.2 and Kotlin 1.9.0. If you regenerate the wrapper, keep the distribution version aligned.
