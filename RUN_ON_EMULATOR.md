# NexusDL — Run on Emulator

## One-time setup (already done, just for reference)

**Environment variables set:**
- `JAVA_HOME` = `C:\Program Files\Java\jdk-22`
- `ANDROID_HOME` = `C:\Users\ShankarV\AppData\Local\Android\Sdk`

**PATH entries added:**
- `C:\Program Files\Java\jdk-22\bin`
- `C:\Users\ShankarV\AppData\Local\Android\Sdk\platform-tools`
- `C:\Users\ShankarV\AppData\Local\Android\Sdk\emulator`
- `C:\Users\ShankarV\AppData\Local\Android\Sdk\build-tools\36.0.0`

---

## Every session — run these in order

### Step 1 — Refresh PATH in the terminal
Run this first, every time you open a new terminal:
```powershell
$env:PATH = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
```

### Step 2 — Start the emulator
```powershell
emulator -avd Medium_Phone_API_36 -no-snapshot-load
```
Wait ~2 minutes for it to fully boot.

### Step 3 — Confirm emulator is ready
Open a second terminal tab, refresh PATH (Step 1), then:
```powershell
adb devices
```
You should see: `emulator-5554   device`
If it says `offline`, wait 30 more seconds and try again.

---

## Building and running

> **Note on ABI splits:** Since the release config was updated, all builds now produce
> per-architecture APKs instead of a single file. Use the `x86_64` variant for the emulator.

### Gradle sync
There is no separate sync command — just run any build task and Gradle syncs automatically.
If you change `build.gradle.kts` and want to verify it parses without building:
```powershell
cd "C:\Users\ShankarV\Desktop\Resume\Projects\nexus-dl"
./gradlew tasks
```

---

### Debug build (for development / emulator testing)

Build:
```powershell
cd "C:\Users\ShankarV\Desktop\Resume\Projects\nexus-dl"
./gradlew assembleDebug
```
First build: ~5–15 min. Subsequent builds: ~30–60 sec.

Install on emulator (x86_64):
```powershell
adb install app/build/outputs/apk/debug/app-x86_64-debug.apk
```
Reinstall without losing data:
```powershell
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
```

Launch (app ID has `.debug` suffix, but the Activity class stays in the original package):
```powershell
adb shell am start -n com.kira.ytdlp.debug/com.kira.ytdlp.MainActivity
```

**Quick iteration** — build + install in one command:
```powershell
./gradlew installDebug
```

---

### Release build (for GitHub uploads / final testing)

> Make sure `local.properties` has your keystore password filled in before running this.

Build all APKs:
```powershell
cd "C:\Users\ShankarV\Desktop\Resume\Projects\nexus-dl"
./gradlew assembleRelease
```

Output files in `app/build/outputs/apk/release/`:
| File | Use for |
|------|---------|
| `app-arm64-v8a-release.apk` | Physical phones (upload to GitHub Releases) |
| `app-x86_64-release.apk` | Emulator testing + Chromebooks (upload to GitHub Releases) |
| `app-universal-release.apk` | All devices fallback (upload to GitHub Releases) |

Test release APK on emulator:
```powershell
adb install app/build/outputs/apk/release/app-x86_64-release.apk
```
Reinstall:
```powershell
adb install -r app/build/outputs/apk/release/app-x86_64-release.apk
```

Launch (release package has no suffix):
```powershell
adb shell am start -n com.kira.ytdlp/.MainActivity
```

> **Tip:** Debug and release APKs can coexist on the same device/emulator because the debug
> package ID is `com.kira.ytdlp.debug` and release is `com.kira.ytdlp`.

---

## Pasting URLs into the emulator

**Method 1 — adb input (most reliable):**
Click the URL field in the app first, then:
```powershell
adb shell input text "https://youtu.be/XXXXXXXXXXX"
```

**Method 2 — Clipboard sync (run once per session):**
```powershell
adb shell settings put secure clipboard_manager_service com.android.emulator.clipboard/.ClipboardManagerService
```
After this, copy on PC with `Ctrl+C` → long-press the URL field in the emulator → Paste.

**Method 3 — Emulator side panel:**
Click `...` at the bottom of the emulator toolbar → Clipboard → paste URL → "Paste to emulator".

**Method 4 — In-app paste button:**
The app now has a clipboard paste icon in the URL field. Copy the URL on your PC, use
Method 2 once to sync the clipboard, then tap the paste icon in the app.

---

## If emulator won't start (stale lock error)

```powershell
Stop-Process -Name "emulator" -Force -ErrorAction SilentlyContinue
Stop-Process -Name "qemu-system-x86_64" -Force -ErrorAction SilentlyContinue
Remove-Item "C:\Users\ShankarV\.android\avd\Medium_Phone.avd\*.lock" -Force -ErrorAction SilentlyContinue
```
Then start again from Step 2.

---

## View logs (for debugging)

```powershell
adb logcat -s MainActivity DownloadService MainViewModel python.stdout python.stderr
```
Clear old logs before a fresh test:
```powershell
adb logcat -c
adb logcat -s MainActivity DownloadService MainViewModel python.stdout python.stderr
```

---

## AVD details
| Property | Value |
|----------|-------|
| AVD name | `Medium_Phone_API_36` |
| API level | 36 |
| Architecture | x86_64 |
| Resolution | 1080 × 2400 |
| RAM | 2 GB |
| GPU | Hardware (auto) |
