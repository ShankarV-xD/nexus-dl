# NexusDL – Project Improvement Plan

---

## 1. Bugs to Fix

### 1.1 Premium / Age-Restricted Streams Not Fetched
**File:** `app/src/main/python/ytdlp_helper.py` — `get_video_info()` (line 12–17)

The yt-dlp options have no cookie or authentication support. YouTube premium-only and age-restricted
formats simply don't appear because the request is unauthenticated.

**Fix:** Add `cookiesfrombrowser` support (e.g., Chrome cookies) or let the user supply a `cookies.txt` path:
```python
ydl_opts = {
    'quiet': True,
    'no_warnings': True,
    'cookiesfrombrowser': ('chrome',),  # or ('firefox',) etc.
    # OR: 'cookiefile': '/path/to/cookies.txt',
}
```
Expose a settings screen in the app where the user can pick a browser or upload a cookies file.

---

### 1.2 `ignoreerrors: True` Silently Swallows Failures
**File:** `ytdlp_helper.py` lines 15, 176

Both `get_video_info` and `download_single_format_with_progress` set `ignoreerrors: True`. This means
yt-dlp will silently skip formats or fail without surfacing the real error to the user.

**Fix:** Remove `ignoreerrors` from info extraction. Only keep it (optionally) for playlist-style downloads.
Return a structured error JSON so the Kotlin side can show a user-friendly message.

---

### 1.3 Thread-Safety Race on Global Progress Dict
**File:** `ytdlp_helper.py` lines 49–56, 87–106

`_current_progress` is a plain dict written by the yt-dlp download hook thread and read by the
Kotlin polling loop. Python's GIL gives partial safety, but the copy-on-write pattern in
`update_progress()` isn't atomic — a partial state is observable between the copy and the reassignment.

**Fix:** Use `threading.Lock`:
```python
import threading
_progress_lock = threading.Lock()

def update_progress(progress_dict):
    global _current_progress
    with _progress_lock:
        new_progress = _current_progress.copy()
        # ... update fields ...
        _current_progress = new_progress
```

---

### 1.4 `checkAndRequestNotificationPermission()` Called Twice
**File:** `MainActivity.kt` lines 140, 149

The method is called on both line 140 and again on line 149 inside `onCreate()`. This triggers two
permission dialogs in sequence on first launch on Android 13+.

**Fix:** Remove the duplicate call on line 149.

---

### 1.5 Download Button Shows Even When All Formats Are Audio-Only
**File:** `MainActivity.kt` lines 492–568

If a URL returns only audio formats, the download button still appears but you must switch to the
Audio tab to pick something. If the user ignores the tab and clicks Download, they get a toast "No
format selected." This is confusing.

**Fix:** Show a contextual hint when `videoFormats.isEmpty()` and `showVideoFormats == true` telling
the user to switch to the Audio tab.

---

### 1.6 Only One Concurrent Download Allowed
**File:** `DownloadService.kt` lines 81–85

`isServiceRunning` blocks any new download while one is in progress with no feedback to the user
other than a logcat warning.

**Fix:** Either implement a download queue (see Section 3) or show a Toast/notification telling the
user that a download is already running.

---

### 1.7 `final_filepath` May Be Stale from a Previous Extension
**File:** `ytdlp_helper.py` lines 156–158

When yt-dlp remuxes a format (e.g., webm → mkv), the `filename` reported in the `finished` hook
may differ from the actual path on disk because yt-dlp renames the file. The current code captures
only the hook filename and doesn't verify the extension.

**Fix:** After `ydl.download()` completes, use `ydl.prepare_filename(info_dict)` or scan the
output directory for files matching the format ID instead of trusting the hook filename alone.

---

### 1.8 FFmpeg Command Injects Unescaped Paths (Potential Crash)
**File:** `DownloadService.kt` line 193

```kotlin
val ffmpegCommand = "-i \"$videoFilePath\" -i \"$audioFilePath\" -c:v copy -c:a copy ..."
```

If any path contains special shell characters or unbalanced quotes (possible from yt-dlp's own
output template), the FFmpegKit command will fail silently or crash.

**Fix:** Use FFmpegKit's array-based `executeWithArguments()` API so no shell-quoting is needed:
```kotlin
val args = arrayOf("-i", videoFilePath, "-i", audioFilePath,
                   "-c:v", "copy", "-c:a", "copy",
                   "-map", "0:v:0", "-map", "1:a:0", "-y", tempFinalFilePath)
FFmpegKit.executeWithArguments(args)
```

---

## 2. UI / UX Improvements

### 2.1 Show Video Duration and Channel Name
The `VideoInfo` data class and yt-dlp output both include `uploader`, `channel`, and
`duration_string`, but only `duration_string` is partially captured and never displayed.
Show the channel name and duration beneath the video title in the info card.

### 2.2 Format Cards Are Too Text-Heavy
Each `FormatItemCard` dumps all metadata in a single pipe-separated string. Introduce icon badges:
- A camera icon for video codec, speaker for audio codec
- A chip for resolution (e.g. `720p`) and file size pill
- A small "Video Only" or "Audio Only" label badge

### 2.3 No Empty-State Illustration
When the app first loads it shows just a plain text description card. Add a proper hero illustration
or animated Lottie file.

### 2.4 No Error Screen
If `get_video_info` fails, `statusText` is set but never rendered — there is no visible error UI,
only a spinner that disappears, leaving the user confused. Add a dedicated error state card.

### 2.5 No Pull-to-Refresh in Format List
After formats are loaded, there's no easy refresh. The current Refresh button in the SearchBar
re-queries the URL, but it's not discoverable. Add swipe-to-refresh on the `LazyColumn`.

### 2.6 Audio-Only Download Format Hint
When the user selects an audio-only format, the download button should change label to
**"Download Audio"** instead of "Download Selected Format" so intent is clear.

### 2.7 Download History Screen
After a download completes, there's no in-app record. Add a simple local history screen backed by
Room DB or even SharedPreferences, showing title, format, timestamp, and status.

### 2.8 Dark/Light Theme Toggle
The app is forced into dark mode at all times (`AppDarkColorScheme`). Add a settings option to
follow the system theme or switch manually.

### 2.9 Copy-to-Clipboard for URLs
Add a paste-from-clipboard button next to the URL field so users can tap once to paste a URL
copied in their browser.

### 2.10 Progress Screen During Download
There is no in-app progress display — only a notification. Add a bottom sheet or an in-app progress
card that mirrors the notification data using a `BroadcastReceiver` or `StateFlow` from the service.

---

## 3. Logic / Architecture Improvements

### 3.1 Download Queue
Replace the single-download lock with a `WorkManager`-based queue so multiple downloads can be
enqueued and run one-at-a-time (or in parallel if the user allows it). WorkManager also survives
process death and handles retries automatically.

### 3.2 Retry on Transient Network Failures
Neither `download_single_format_with_progress` nor the Kotlin service retries on failure. Add a
configurable retry count (default 3) with exponential back-off in the Python layer.

### 3.3 ViewModel + StateFlow Architecture
All state (`videoInfo`, `isLoading`, `selectedFormatId`) is held directly in Composables as
`mutableStateOf`. Move this to a `ViewModel` so state survives configuration changes (screen
rotation) and is easier to test.

### 3.4 Playlist Support
yt-dlp supports entire playlists and channels. Add an option to detect when a playlist URL is
entered and offer to download individual items or the whole playlist. Use `extract_flat: 'in_playlist'`
in the yt-dlp options to enumerate items quickly.

### 3.5 Format Pre-selection Logic
Instead of showing all formats and making the user pick, add a "Smart Pick" mode that automatically
selects the best quality format (e.g., 1080p + best audio) and lets the user override.

### 3.6 Hardcoded yt-dlp Version
`build.gradle.kts` uses `install("yt-dlp")` with no pinned version. yt-dlp updates very frequently
and breaking changes can silently break the app. Pin a tested version and add an in-app update check
that can download a newer `yt-dlp` wheel at runtime via Chaquopy's dynamic module feature.

### 3.7 No Unit Tests for Python Module
`ytdlp_helper.py` has zero tests. Add pytest tests that mock `yt_dlp.YoutubeDL` to verify JSON
output, error handling, and progress tracking without making real network calls.

### 3.8 Missing `filesizeApprox` Fallback Display
In `VideoData.kt`, both `filesize` and `filesizeApprox` are declared, and `FormatItemCard` uses the
correct fallback `format.filesize ?: format.filesizeApprox`. However, for formats where both are
null (common for some HLS streams), no size is shown at all. Add a "Size unknown" label so users
know it's absent data, not a bug.

---

## 4. Backend / Feature Additions

### 4.1 Cookie Authentication (Premium Streams)
As described in Bug 1.1. This is the single most impactful feature gap. Without it, YouTube Premium,
age-restricted, and geo-restricted videos are inaccessible.

**Implementation:**
- Settings screen with "Select cookie source: Chrome / Firefox / Edge / Custom file"
- Pass the selected option as a parameter from Kotlin → Python
- Store the selection in `SharedPreferences`

### 4.2 Subtitle / Caption Download
yt-dlp supports subtitle extraction (`writesubtitles`, `subtitleslangs`). Add an option to download
subtitles (SRT/VTT) alongside the video and embed them using FFmpeg (`-c:s mov_text` for MP4).

### 4.3 Audio Extraction Only Mode
When the user picks an audio-only format, the app already downloads it correctly. But add a
dedicated "Extract Audio from Video" option that downloads the best video+audio combined stream
and then uses FFmpeg to strip to MP3/AAC/FLAC.

### 4.4 Multi-Platform Support
yt-dlp supports 1000+ sites beyond YouTube. Add a "Supported Sites" screen and auto-detect the
platform from the URL to show platform-specific tips (e.g., Twitter/X requires cookies for some
content).

### 4.5 Thumbnail Embedding
Use FFmpeg to embed the video thumbnail into the MP4 as cover art:
```
-attach thumbnail.jpg -metadata:s:t mimetype=image/jpeg
```

### 4.6 Background Download Scheduling
Allow users to schedule downloads for off-peak hours using WorkManager constraints
(`NetworkType.UNMETERED`, charge state) — useful for large 4K files over mobile data.

### 4.7 Cloud/Shortcut Integration
- Share intent receiver: let users share URLs directly from the browser or YouTube app into
  NexusDL instead of copy-pasting.
- Android Quick Settings tile for "Start Download" (copy URL from clipboard and queue it).

### 4.8 Per-Format Quality Presets
Let users define presets like "Best under 500MB", "720p MP4", "Audio only FLAC" that get
auto-applied when fetching formats, reducing the manual selection step.

### 4.9 Open Source Contribution: Proxy/VPN Support
yt-dlp accepts a `proxy` option. Expose this in Settings for users behind firewalls or who need
to access geo-restricted content.

---

## 5. Performance Improvements

| Area | Current | Improvement |
|------|---------|-------------|
| Format info fetch | Fetches all format metadata including manifest URLs | Use `extract_flat` for faster initial load, defer full info on demand |
| Thumbnail loading | Coil loads full thumbnail URL | Cache thumbnails with Coil's disk cache; limit size to 120dp card |
| Progress polling | Polls Python every 500ms on IO thread | Switch to a Python callback that pushes updates to Kotlin via a shared `Queue` |
| FFmpeg merge | Copies streams (lossless) — correct | Consider `ffmpeg-kit-min` (smaller footprint) if GPL isn't needed |
| App size | `ffmpeg-kit-full-gpl` is ~80MB | Use `ffmpeg-kit-audio` for audio-only builds or split APKs by ABI |

---

## 6. Security Considerations

- **Cookie file storage:** If the user provides a `cookies.txt`, store it in the app's private
  directory (`filesDir`), not on external storage. Never log cookie contents.
- **URL validation:** Add basic URL scheme validation before passing to Python to prevent passing
  `file://` or other dangerous schemes to yt-dlp.
- **No WRITE_EXTERNAL_STORAGE needed:** The current MediaStore approach is correct for Android 10+.
  Ensure the pre-Q fallback path (line 429–431 in `DownloadService.kt`) is tested.

---

## Priority Summary

| Priority | Item |
|----------|------|
| P0 (Critical) | Fix duplicate `checkAndRequestNotificationPermission()` call |
| P0 (Critical) | Fix `ignoreerrors: True` masking failures |
| P0 (Critical) | Add visible error state UI |
| P1 (High) | Cookie/Premium stream authentication |
| P1 (High) | FFmpegKit array-args fix (crash prevention) |
| P1 (High) | ViewModel + StateFlow refactor |
| P2 (Medium) | Download queue with WorkManager |
| P2 (Medium) | Playlist support |
| P2 (Medium) | Subtitle download |
| P2 (Medium) | In-app progress display |
| P3 (Low) | Theming toggle, history screen, format presets |
