# NexusDL

A clean Android video downloader powered by [yt-dlp](https://github.com/yt-dlp/yt-dlp), built with Jetpack Compose.

Supports YouTube and 1000+ other sites yt-dlp can handle.

---

## Features

- Fetch all available video and audio formats for any URL
- Download video-only or audio-only streams; automatically merges the best audio when needed
- In-app live download progress (phase, speed, ETA)
- Channel name, duration, and thumbnail shown in the info card
- Format cards with type badges (Video Only / Audio Only), codecs, and file size
- Paste-from-clipboard button for quick URL entry
- Visible error card with a "Try Again" action
- State survives screen rotation (ViewModel + StateFlow)
- Downloads saved directly to your public Downloads folder via MediaStore

---

## Installation

> NexusDL is distributed as a sideloaded APK. It is **not** on the Google Play Store.

1. Go to the [**Releases**](../../releases) page
2. Download the APK for your device:
   - **`app-arm64-v8a-release.apk`** — most modern Android phones (recommended)
   - **`app-x86_64-release.apk`** — emulators and a few Chromebooks
   - **`app-universal-release.apk`** — if unsure, this works on everything
3. On your device: **Settings → Apps → Install unknown apps** and allow your browser/file manager
4. Open the downloaded APK and tap **Install**

---

## Requirements

- Android 7.0 (API 24) or higher
- Internet connection

---

## Building from Source

### Prerequisites
- Android Studio Hedgehog or newer
- Android SDK 35
- JDK 11

### Steps

```bash
git clone https://github.com/ShankarV-xD/nexus-dl.git
cd nexus-dl
```

Add your release credentials to `local.properties` (create the file if it doesn't exist — it is gitignored):

```properties
KEYSTORE_PATH=nexusdl-release.jks
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=nexusdl
KEY_PASSWORD=your_key_password
```

Build release APKs:

```bash
./gradlew assembleRelease
```

Output APKs will be in `app/build/outputs/apk/release/`.

---

## Tech Stack

| Layer | Library |
|-------|---------|
| UI | Jetpack Compose + Material 3 |
| State | ViewModel + StateFlow |
| Python runtime | [Chaquopy](https://chaquo.com/chaquopy/) 3.11 |
| Downloader | yt-dlp (latest) |
| Media merge | Android MediaMuxer (built-in) |
| Image loading | Coil 2.6 |
| Serialization | Kotlin Serialization 1.6.3 |

---

## License

The app code is released under the **MIT License**.

---

## Disclaimer

This app uses yt-dlp to download media. Downloading content may violate the Terms of Service of certain platforms. You are responsible for ensuring your use complies with applicable laws and platform policies. The author does not condone copyright infringement.
