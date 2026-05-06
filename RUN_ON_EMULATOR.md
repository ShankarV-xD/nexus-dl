# NexusDL — Run on Emulator (Windsurf Guide)

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

### Step 1 — Refresh PATH in the Windsurf terminal
Open terminal (`` Ctrl+` ``) and run this first, every time you open Windsurf:
```powershell
$env:PATH = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
```

### Step 2 — Start the emulator
```powershell
emulator -avd Medium_Phone_API_36 -no-snapshot-load
# OR
& "C:\Users\ShankarV\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_36 -no-snapshot-load
```
Wait ~2 minutes for it to fully boot.

### Step 3 — Confirm emulator is ready
Open a second terminal tab and run:
```powershell
$env:PATH = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
adb devices
```
You should see: `emulator-5554   device`
If it says `offline`, wait 30 more seconds and try again.

### Step 4 — Build the APK
```powershell
cd "C:\Users\ShankarV\Desktop\Resume\Projects\YTDLP"
./gradlew assembleDebug
```
First build: ~5-15 min. Subsequent builds: ~30-60 sec.

### Step 5 — Install the APK
```powershell
adb install app/build/outputs/apk/debug/app-debug.apk
```
If app is already installed (reinstall without losing data):
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 6 — Launch the app
```powershell
adb shell am start -n com.kira.ytdlp/.MainActivity
```

---

## Quick iteration (build + install in one command)
After the first install, use this for every code change:
```powershell
./gradlew installDebug
```

---

## Pasting URLs into the emulator

**Method 1 — Type URL directly into focused field:**
Click the text field in the app first, then:
```powershell
adb shell input text "https://youtu.be/XXXXXXXXXXX"
```

**Method 2 — Enable clipboard sync (run once per session):**
```powershell
adb shell settings put secure clipboard_manager_service com.android.emulator.clipboard/.ClipboardManagerService
```
After this, copy on PC with `Ctrl+C` → long-press text field in emulator → Paste.

**Method 3 — Emulator side panel:**
Click `...` at the bottom of the emulator toolbar → Clipboard → paste URL → "Paste to emulator".

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
adb logcat -s MainActivity DownloadService python.stdout python.stderr
```
To clear old logs before a fresh test:
```powershell
adb logcat -c
adb logcat -s MainActivity DownloadService python.stdout python.stderr
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
