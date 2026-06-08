# NexusDL — Deep Code Audit & Improvements

**Scope:** Full read of every Kotlin/Python file, manifest, Gradle build files, resources, and tests in `c:/Users/ShankarV/Desktop/Resume/Projects/nexus-dl/`. Findings below cite exact file paths and line numbers.

**Project snapshot:** Kotlin/Compose app (`compileSdk=35`, `minSdk=24`, `targetSdk=35`, AGP 8.9.1, Kotlin 2.0.21) using Chaquopy 16 + yt-dlp. Media muxing uses Android's built-in `MediaMuxer`/`MediaExtractor` (no external FFmpeg dependency). Only ONE Activity (`MainActivity`) and ONE foreground service (`DownloadService`). No DI, no Room (declared but unused), no WorkManager, no NavHost. Python helper: 294 lines.

> Note: this file replaces the previous status-checklist `IMPROVEMENTS.md` with a deep, line-cited audit. Items previously marked "done" there are NOT re-verified here — this audit reflects only what is true in the source today.

---

## A) Current Issues

### A1. Bugs

1. ~~**`Toast` shown from a background `onStartCommand` path that has already returned `START_NOT_STICKY`** — `DownloadService.kt:148, 159–164, 170`. `Toast.makeText(this, …, LENGTH_LONG).show()` is called BEFORE the service has called `startForeground()`. On API 31+ if the calling activity is not in foreground when the service receives this command (process death restart), this triggers a `ForegroundServiceStartNotAllowedException`. Also: `stopSelf()` is called WITHOUT calling `startForeground()` first — on API 31+ this combination throws an exception on subsequent commands, and on API 26+ if the system delivered START_FOREGROUND_SERVICE expectation, the system can crash the app with "Context.startForegroundService() did not then call Service.startForeground()". The early return paths (lines 136, 149, 164, 171) all skip `startForeground` while the service was launched via `startForegroundService` (MainActivity:810). **This is a confirmed crash bug on API 26+.**~~ **FIXED:** `startForeground()` is now called at the top of `onStartCommand` (before any validation), and all early-return paths call `stopForeground(true)` before `stopSelf()`. The "already running" case is checked first and returns early without touching foreground state (safe because the service is already in foreground).

2. **`videoFilePath = null` after assignment makes the temp-cleanup branch incorrect** — `DownloadService.kt:313–315, 383–394`. When there is no audio (single stream), the code sets `tempFinalFilePath = videoFilePath` and then `videoFilePath = null`. Later the finally block uses `if (finalPublicUri != null || videoFilePath == null)` to decide whether to delete `tempFinalFilePath`. When the merge succeeds but MediaStore save fails, both `videoFilePath` and `audioFilePath` are also already deleted at lines 381–382 because they're not nulled out — meaning a retry is impossible since the temp source is gone.

3. **`activeDownload?.isComplete == true` and a stale download can block new searches indefinitely** — `MainActivity.kt:761–765`. After a download completes, `_activeDownload` stays populated. The download button is hidden when `isComplete == true`. `clearCompletedDownload()` is only called in `fetchVideoInfo()` (MainViewModel.kt:166), so this is technically cleared on new fetch, but the dependency is fragile.

4. **`PendingIntent` reused across all final notifications with identical request code** — `DownloadService.kt:414–419`. Uses `PENDING_INTENT_REQUEST_CODE = 1001` for the final-completion intent AND `0` for ongoing intent (line 666). When multiple downloads complete in succession, `FLAG_UPDATE_CURRENT` overwrites the previous extras silently. Tapping the OLDER completion notification opens the LATER one's destination.

5. **`uniqueFinalNotificationId = FINAL_NOTIFICATION_BASE_ID + System.currentTimeMillis().toInt()`** — `DownloadService.kt:433`. Converting `Long` milliseconds to `Int` overflows after roughly 25 days of uptime and can collide with `NOTIFICATION_ID=1` or produce negative IDs. Use `(System.currentTimeMillis() % Int.MAX_VALUE).toInt() + 2`.

6. ~~**`progressJob?.cancel()` called twice and `serviceScope` cancelled in `onDestroy` while the launched coroutine still expects to run its `finally` block** — `DownloadService.kt:189–194, 723–730`. If the system kills the service mid-download, `onDestroy()` cancels `serviceScope` which propagates `CancellationException`. The `catch (e: Exception)` block at line 349 catches `CancellationException` and the finally block writes a "Failed" `ActiveDownload`. **`CancellationException` should be re-thrown** — wrap it: `catch(e: CancellationException) { throw e }` before `catch(e: Exception)`.~~ **FIXED:** Added `catch (e: CancellationException) { throw e }` before the generic `catch (e: Exception)` block in `performDownloadAndMerge`.

7. **`get_current_phase()` reads `_current_phase` WITHOUT the lock** — `ytdlp_helper.py:122–123`. The progress lock guards `_current_progress` but `_current_phase` is read on the UI poll thread (Kotlin) at the same time it is being written by Python. On CPython this is GIL-safe for single string assignments, but still a documented race the previous IMPROVEMENTS.md claims is fixed.

8. ~~**Rate limit shared across processes and survives across the foreground service** — `MainViewModel.kt:148–159` and `DownloadService.kt:153–166`. `SecurityUtils.downloadAttempts` is a `mutableMapOf` in a singleton `object`. Every "fetch video info" AND every "start download" hits the SAME counter. With `MAX_DOWNLOADS_PER_MINUTE = 5`, a user who searches 5 videos within a minute is locked out of starting ANY downloads. **UX trap that punishes legitimate use.**~~ **FIXED:** Rate limiting removed from `fetchVideoInfo()` in `MainViewModel` entirely. Only actual downloads (`DownloadService`) are rate-limited. Removed unused `MAX_DOWNLOADS_PER_MINUTE` constant.

9. **`Uri.parse` never throws — `try/catch` at `SecurityUtils.kt:81–85` is dead code.** `Uri.parse` accepts almost anything and returns a `Uri` whose `host`/`scheme` may be null.

10. **`isValidUrl` rejects URLs without TLDs but also any URL where the host ends with `.` (rare edge) and IDN punycode URLs** — `MainViewModel.kt:218–241`. Splitting on `.` and requiring `.last().isBlank() == false` is naive. `domainParts = withoutProtocol.split("/")[0].split(".")` does not strip the query string or fragment, so `https://example?x=y` (no slash, no dot in query) passes here.

11. **Stale Python progress percent from previous download** — `ytdlp_helper.py:159–167`. `download_single_format_with_progress` sets `_current_progress.percent = 0` correctly, but only `reset_progress_tracking()` resets `_current_phase`. If the FIRST download fails before `set_download_phase` is called, `_current_phase` may carry "merging" from a prior run.

12. **`yt-dlp` pinned version mismatch** — README claims `yt-dlp 2024.12.13` but `app/build.gradle.kts:88` says `install("yt-dlp>=2025.3.31")` (no pin — open upper bound). The previous IMPROVEMENTS.md (line 133) also claims it's pinned to `2024.12.13`. **None of the three sources agree.**

13. ~~**`pip install("requests")` and `pip install("certifi")`** — `app/build.gradle.kts:89–90`. Neither is imported anywhere in `ytdlp_helper.py`. Dead dependencies bloat the APK by several MB.~~ **FIXED:** Removed both dead pip packages from `build.gradle.kts`.

14. **`SearchBar`'s `initialUrl` tracking via `remember { mutableStateOf(url) }`** — `MainActivity.kt:1203–1211`. `initialUrl` is captured ONLY on first composition. After process death + restore, the StateFlow re-emits the saved URL but `initialUrl` captures whatever state was reset to. Use `rememberSaveable`.

15. **Format filtering bug** — `MainActivity.kt:267–289`. The `hasUsefulNote` flag is computed in lines 272–275 but ALSO duplicated as inline conditions on lines 282–284 (`note?.contains("storyboard"…)`). Maintenance hazard.

16. **`fetchVideoInfo()` does not cancel a prior in-flight fetch** — `MainViewModel.kt:118–204`. The early return `if (_isLoading.value) return` prevents new fetches while one is in flight, but the user has no way to CANCEL a running fetch.

17. **`videoInfo.error = "Failed to parse response: ${e.message}"`** — `MainViewModel.kt:187`. On Kotlinx Serialization failure, the exception message often contains the offending input — could leak entire raw JSON into the error UI.

18. **`_current_progress["downloaded_bytes"] = current_total`** — `ytdlp_helper.py:201–207`. When a download finishes, `downloaded_bytes` is set to the captured `total_bytes`. But if `total_bytes` was `None` (live streams), this writes `None`.

### A2. Security

19. **`BLOCKED_PATTERNS` is a substring check, trivially bypassable** — `SecurityUtils.kt:76–78`. `if (BLOCKED_PATTERNS.any { url.contains(it) })`. A URL like `https://youtube.com/watch?ref=localhost` would be BLOCKED despite being safe. More importantly, `http://127.0.0.1.evil.com/` would be BLOCKED (good) but `http://2130706433/` (decimal IP for 127.0.0.1), `http://0x7f000001/`, or `http://0177.0.0.1/` all bypass. Use proper resolution via `InetAddress.isLoopbackAddress` AFTER DNS, or block all RFC1918 + RFC3927 ranges. **SSRF risk:** yt-dlp will then fetch from internal IPs from inside a foreground service.

20. **URL whitelist is bypassed for the actual download path** — `DownloadService.kt:139–151` re-validates, but yt-dlp itself will RECURSIVELY follow embed/iframe extractors. A `youtube.com` URL with a redirect to a non-whitelisted site still gets downloaded.

21. **No `android:exported` on `MainActivity` consistency check** — `AndroidManifest.xml:26`. If you add `<intent-filter>` for `android.intent.action.VIEW` with `data` scheme later (for share-sheet integration), the activity will accept ANY URI — risk of `intent.data` containing `javascript:` or `content://` URIs being fed straight into yt-dlp.

22. **Sanitized `formatDescription` placed unescaped into the filename then a notification text** — `DownloadService.kt:327–329, 397`. `sanitizeFileName` (line 535) does the right thing for the filename, but `formatDescription` is also concatenated raw into notification body. Notifications use `BigTextStyle` which renders `\n` literally.

23. **`logger` statements include raw URLs in production-style code** — `MainViewModel.kt:182`, `DownloadService.kt:174`. Gated by `BuildConfig.DEBUG`. However the Python side `print(f"Python: Received URL …")` (ytdlp_helper.py:11) is NOT gated and goes to `logcat` even in release builds.

24. **`Toast` shown from non-foregrounded service** — `DownloadService.kt:148, 159–164, 170`. Same as A1#1: `Toast.makeText` from background services is restricted on API 28+.

25. **Cookie support is implemented in Python but no UI exposes it, AND there's NO secure storage location specified** — `ytdlp_helper.py:38–39`. Future-risk: when the Settings screen lands, the cookie file MUST go into `context.filesDir` with `MODE_PRIVATE`.

26. **`MediaStore.MediaColumns.DATA` path is set on pre-Q without verifying file does not exist** — `DownloadService.kt:568–571`. Two downloads of the same title overwrite each other; on pre-Q there's no `IS_PENDING` flag so a crash mid-copy leaves a zero-byte file.

27. **Keystore password defaults to empty string** — `app/build.gradle.kts:38–40`. If `local.properties` is missing the property entirely, the build silently signs with an empty password — Gradle will fail at sign time but the error is downstream of build config and confusing.

### A3. Performance

28. **500 ms progress polling, NOT push-based** — `DownloadService.kt:53, 502`. Each tick, the coroutine takes a Python lock, JSON-serializes a dict, sends it to Kotlin, parses it. Chaquopy's JNI cross-call is non-trivial (~1–5 ms each call) and you make TWO calls per tick (`get_current_progress` + `get_current_phase`). Use a callback: register a Kotlin `PyObject` and call back from Python's progress hook directly.

29. **`videoFormats` / `audioFormats` `derivedStateOf` keyed on `videoInfo`** — `MainActivity.kt:264–312`. Every recomposition of `YtdlpInfoScreen` re-runs the filter+sort on the FULL formats list. The `remember(videoInfo)` keys mean ANY new `videoInfo` object (even semantically equal) busts the cache.

30. **`AnimatedVisibility` blocks nested DEEPLY** — `MainActivity.kt:333–1037`. A single huge `Box` contains 5+ `AnimatedVisibility` siblings stacked on the same layout. On slower devices the simultaneous animations cause jank. Consider an `AnimatedContent` with a sealed `ScreenState`.

31. **`shimmerBrush()` is `@Composable` and creates a new transition per call** — `ShimmerEffects.kt:22–45`. `VideoInfoShimmerLoading` calls `shimmerBrush()` once per skeleton element (8+ times), each creating its own `InfiniteTransition`. Cache a single transition at the top.

32. **MediaStore copy uses default 8 KB `copyTo` buffer** — `DownloadService.kt:602`. For a 1 GB video file, this is 130,000+ writes. Use `copyTo(outputStream, bufferSize = 64 * 1024)` or larger.

33. ~~**FFmpegKit on Main vs IO** — `DownloadService.kt:301`. `FFmpegKit.executeWithArguments` is synchronous and BLOCKS the calling thread. Consider `executeWithArgumentsAsync`.~~ **REMOVED:** FFmpegKit replaced by Android's built-in `MediaMuxer` which runs on the existing `Dispatchers.IO` coroutine — no blocking concern.

34. **No bitmap memory tuning for Coil** — `MainActivity.kt:409`. `AsyncImage` uses default ImageLoader → loads full-res thumbnails. YouTube `maxresdefault` is 1920×1080 — for a 120 dp wide card this wastes ~30× memory per thumbnail.

35. **`logoScale` animateFloatAsState targetValue = 1f from initial 1f** — `MainActivity.kt:1048–1055`. No animation actually occurs — it's a constant. The spring `dampingRatio` setup is dead code.

### A4. Code quality / maintainability

36. **`MainActivity.kt` is 1547 lines — one file, one giant `YtdlpInfoScreen` of ~800 lines.** No separation between screen-level composables and reusable atoms. Should be broken into `ui/screen/MainScreen.kt`, `ui/screen/WelcomeScreen.kt`, `ui/components/FormatItemCard.kt`, `ui/components/SearchBar.kt`, `ui/components/DownloadProgressCard.kt`, `ui/components/DownloadCompleteCard.kt`, `ui/theme/Theme.kt`.

37. **Theme colors defined as top-level `val`s in MainActivity** — `MainActivity.kt:79–113`. Belongs in `ui/theme/Color.kt` + `ui/theme/Theme.kt`.

38. **`themes.xml` is `Theme.Material.Light.NoActionBar`** — `app/src/main/res/values/themes.xml:4`. App is Compose-dark-only but the system-applied theme on cold start is the light Material theme → users see a WHITE flash for ~200 ms before Compose paints.

39. **`jsonParser` is module-level top-level in `VideoData.kt`** — `VideoData.kt:70`. Imported across files as a magic global. Should be in a companion object or DI-provided.

40. **Duplicate detail-line construction in FormatItemCard** — `MainActivity.kt:1448–1473`. Building `detailParts` inline inside the composable means it allocates a list on every recomposition.

41. **`RecentUrlsManager.kt` is fully implemented but never wired into ANY UI**. Confirmed by Grep: only `RecentUrlsManager.kt` matches the symbol. Dead code with 100 lines + DataStore overhead.

42. ~~**Room dependency declared but no Room code** — `app/build.gradle.kts:111–112`. `androidx.room:room-runtime` and `room-ktx` are pulled in (and a KSP processor would be needed but isn't even declared) — pure dead weight in the APK.~~ **FIXED:** Removed both Room dependencies from `build.gradle.kts`.

43. **`material-icons-extended-android:1.6.8`** — `app/build.gradle.kts:107`. Adds ~10 MB to the APK. With R8 enabled most are stripped, but the version mismatch with `composeBom 2025.03.01` means manual override. Just use individual icon imports.

44. **Magic strings everywhere** — `DownloadService.kt:340 ("Download completed")`, `MainActivity.kt:637`, etc. Extract to constants. `if (finalStatus == "Download completed")` on line 410 is a string comparison instead of an `enum class DownloadStatus`.

45. **`"single_device"` hard-coded as rate-limit key** — `MainViewModel.kt:149`, `DownloadService.kt:154`. Both comments say "In a multi-user app, use actual device/user ID" but there is no user concept here.

46. **`detailParts.add(android.text.format.Formatter.formatShortFileSize(context, size))`** — `MainActivity.kt:1452`. Uses fully-qualified path inside a composable instead of an import.

47. **`String.format("%.1f MB/s", speedMbps)`** — `DownloadService.kt:464`. Uses default locale → in `fr_FR` the comma decimal separator appears in the notification. Use `Locale.US` explicitly.

48. **Unused import `androidx.compose.foundation.lazy.items`** — minor.

49. **Hard-coded English strings instead of `stringResource(R.string.…)`**. `AsyncImage(model = videoInfo?.thumbnail, contentDescription = "Video Thumbnail")` (`MainActivity.kt:411`). Several other hard-coded strings: `"Share"` (962), `"Check out this video!"` (959), `"URL copied to clipboard"` (1258), `"Download Complete!"` / `"Download Failed"` (884), `"Download Audio"` / `"Download Selected Format"` (842), `"No video formats available…"` (666), `"High quality formats are video-only…"` (637), `"Nexus DL"` (1090), `"Video Downloader"` (1097), placeholder `"https://youtu.be/..."` (1128). **Defeats `strings.xml` and breaks i18n.**

### A5. UX / Accessibility / Material 3

50. **Forced dark theme — no system theme respect** — `MainActivity.kt:192, 98`. `MaterialTheme(colorScheme = AppDarkColorScheme)` is hard-coded regardless of `isSystemInDarkTheme()`. Also missing: `dynamicColor` (Material You) on Android 12+.

51. **`contentDescription = null` on every information-bearing icon** — `MainActivity.kt:443, 463, 510 (set), 581, 608`. Person and Schedule icons next to channel/duration have null content descriptions.

52. **`OutlinedTextField` has no error state for invalid URLs** — `MainActivity.kt:1124, 1229`. Validation only happens after the user taps Search; an in-line `isError` indicator with `supportingText` would surface issues earlier.

53. **No predictive back gesture opt-in** — `AndroidManifest.xml:13`. Missing `android:enableOnBackInvokedCallback="true"`.

54. **Edge-to-edge implemented manually but with deprecated mechanism** — `MainActivity.kt:184–189`. Uses `WindowCompat.setDecorFitsSystemWindows(window, false)` which is now deprecated in favor of `enableEdgeToEdge()`.

55. **No haptic feedback on `OutlinedTextField` focus changes** but heavy haptics for paste/clear. Inconsistent.

56. **`videoFormats.size` shown in Button label as `"Video (12)"`** — `MainActivity.kt:585`. No `Modifier.semantics { stateDescription = … }`.

57. **No portrait/landscape adaptation; no tablet `WindowSizeClass` handling** — single-column layout fixed via `fillMaxWidth`.

58. **Pull-to-refresh missing**.

59. **Download "Share" button shares text only, not the video file** — `MainActivity.kt:957–962`. `Intent.ACTION_SEND` with `type = "text/plain"` and a hard-coded promo line. Users will expect to share the FILE — should use `dl.fileUri` with `type = mimeType` and `FLAG_GRANT_READ_URI_PERMISSION`.

60. **Tab buttons have full color flip but no `Modifier.semantics { role = Role.Tab; selected = … }`** — `MainActivity.kt:561–613`. TalkBack announces them as Buttons.

### A6. Architecture / DI

61. **No DI framework (Hilt or Koin)**. `MainViewModel` instantiated by Compose `viewModel()`, `DownloadService` hits `Python.getInstance()` directly. Cross-cutting concerns are static `object`s — untestable.

62. **`Python.getInstance()` called from `MainViewModel` AND `DownloadService`** — `MainViewModel.kt:171`, `DownloadService.kt:222, 450`. Tight coupling. Wrap in `YtdlpRepository` interface so the ViewModel can be unit-tested without the Python runtime.

63. **No `Repository` layer**. `MainViewModel` reaches into Python directly.

64. **`MainViewModel` is `class MainViewModel : ViewModel()` with no `ViewModelProvider.Factory`** — `MainActivity.kt:251`. Cannot inject context/repositories.

65. **`DownloadService.activeDownload` is a companion-object singleton StateFlow** — `DownloadService.kt:771`. Tightly-coupled anti-pattern.

66. **No `Application` class**. `android:name="com.chaquo.python.android.PyApplication"` (AndroidManifest.xml:23) directly subclasses Chaquopy's — fine — but there's nowhere to do app-wide init.

67. **No process-restart / state-restoration**. `MainViewModel` state is `MutableStateFlow` — survives config change but NOT process death. Use `SavedStateHandle`.

### A7. Reliability

68. **`Python.start()` is never called explicitly**. A defensive `Python.start(AndroidPlatform(this))` in custom Application allows control over initialization timing.

69. **No retry logic for transient network failures**. yt-dlp internally retries some operations, but a `403 / 503` from the streaming server is propagated immediately as a hard failure.

70. **No download cancellation API**. Once a `DownloadService` job starts, the user cannot stop it from the UI. They must force-stop the app — the foreground notification has no "Cancel" action.

71. **No download queue / persistence across process death**. If the user kills the app, the in-flight download is lost AND the partial temp files in `cacheDir` remain forever.

72. **Cache dir bloat** — `cacheDir.absolutePath` (DownloadService.kt:228) accumulates failed downloads' temp files since the cleanup runs only in `finally`. Add a startup task to wipe `cacheDir`.

73. **`activeDownload` companion-object state is process-singleton, lost on process death** — `DownloadService.kt:771`. After OS kills and restarts the process while a download was running, the UI shows no download.

74. **`START_NOT_STICKY` plus no `WorkManager`** — Service is unilaterally `STOP_NOT_STICKY` (line 196). On OEMs with aggressive task killers (Xiaomi, OnePlus), downloads of multi-GB files are killed without notice.

### A8. Testing gaps

75. **`ExampleUnitTest.kt` is the literal Android Studio template** — `app/src/test/java/com/kira/ytdlp/ExampleUnitTest.kt`. Asserts `2 + 2 == 4`. Same for `ExampleInstrumentedTest.kt` (only checks `packageName`). **Zero meaningful tests** despite a 1500-line activity, security-critical URL parser, and a complex service.

76. **`SecurityUtils` URL validation is the highest-value testable surface and has zero tests**. `validateUrl`, `checkRateLimit`, `sanitizeInput`, `calculateChecksum` are all pure functions.

77. **`MainViewModel.isValidUrl` is `private`** — `MainViewModel.kt:218`. Can't be unit-tested without `@VisibleForTesting`.

78. **No Python tests** for `ytdlp_helper.py`. The progress-tracking lock semantics are testable with `threading`.

79. **No Compose UI tests** despite `ui-test-junit4` declared in `androidTestImplementation`.

### A9. Documentation

80. **Inconsistent yt-dlp version claims** (see A1#12). README says `2024.12.13`, previous IMPROVEMENTS.md says `2024.12.13` is pinned, build.gradle.kts says `>=2025.3.31`.

81. **README claims "JDK 11"** — `README.md:48` — but `build.gradle.kts:71–75` sets `VERSION_11`, while `RUN_ON_EMULATOR.md:6` says `JAVA_HOME = C:\Program Files\Java\jdk-22`.

82. **No changelog / no commit-by-commit history visible from docs**.

83. **Previous `IMPROVEMENTS.md` was a status doc, not a roadmap**. It mixed "what was done" with "what remains" — split into `CHANGELOG.md` + `ROADMAP.md`.

### A10. Compatibility

84. **`compileSdk = 35` but no Android 15 (`VANILLA_ICE_CREAM`) handling for edge-to-edge mandatory enforcement** — Activities on Android 15 are edge-to-edge BY DEFAULT and `WindowCompat.setDecorFitsSystemWindows(window, false)` is no-op. The OutlinedTextField at the bottom of `WelcomeScreen` may now hide behind the IME — needs `Modifier.imePadding()`.

85. **`foregroundServiceType="dataSync"`** — `AndroidManifest.xml:39`. On Android 14 (API 34+), `dataSync` is restricted to "transferring data between devices" — downloading from yt-dlp is arguably user-initiated download, NOT data sync. The correct type is `mediaProcessing` (API 34+) or `shortService`. **Google Play may reject `dataSync` for this use case.**

86. ~~**Missing `<queries>` element** — `AndroidManifest.xml`. On Android 11+ (API 30+), `Intent.ACTION_VIEW` with `video/*` requires a `<queries>` block in manifest or `PackageManager` will silently fail to resolve external video players:~~ **FIXED:** Added `<queries>` block with `ACTION_VIEW / video/*` intent to `AndroidManifest.xml`.
    ```xml
    <queries>
      <intent>
        <action android:name="android.intent.action.VIEW"/>
        <data android:mimeType="video/*"/>
      </intent>
    </queries>
    ```

87. **`DownloadManager.ACTION_VIEW_DOWNLOADS`** — `MainActivity.kt:930, 984`. Not available on all OEMs (e.g. Huawei without Google). Wrap in `try/catch`.

88. ~~**Vibration permission not declared but the code calls `Vibrator.vibrate`** — `HapticFeedback.kt:84, 87`. On API 26+ this requires `<uses-permission android:name="android.permission.VIBRATE" />` in the manifest. **The manifest lacks it → vibration silently fails.**~~ **FIXED:** Added `VIBRATE` permission to `AndroidManifest.xml`.

89. **`minSdk=24` (Android 7.0) but multiple code paths assume API 26+**. API 24/25 receives downloads with NO notification channel.

90. **`PendingIntent.FLAG_IMMUTABLE` requires API 23+** — correct; but combining with `FLAG_UPDATE_CURRENT` and then never updating extras (line 418) means the immutable extras stay the first version's.

91. ~~**`MediaStore.MediaColumns.RELATIVE_PATH`** — `DownloadService.kt:565`. Only available API 29+; the SDK check is correct. But for API 28, the code uses `Environment.getExternalStoragePublicDirectory` + writes via MediaStore but requires `WRITE_EXTERNAL_STORAGE` permission — **NOT declared in manifest. On API 28-29 (Pie) the save will fail with `SecurityException`.**~~ **FIXED:** Added `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"` to `AndroidManifest.xml`.

92. **`android:allowBackup="true"`** — `AndroidManifest.xml:14`. The `data_extraction_rules.xml` excludes everything (good), but the older `backup_rules.xml` has all rules commented out — so on pre-API-31 devices, AutoBackup behavior is "back up everything."

### A11. Build / ProGuard / R8

93. **No ProGuard rule for kotlinx-serialization custom serializers** — `proguard-rules.pro:13–31`. Rules cover `kotlinx.serialization.json.**` and the app's `$$serializer` classes but missing `allowobfuscation`. Risk: in some R8 modes, `VideoInfo.serializer()` is stripped.

94. **No ProGuard rules for `androidx.datastore`** — DataStore's internal `Serializer` may be stripped under R8 once `RecentUrlsManager` becomes used.

95. **`proguard-android-optimize.txt`** — `app/build.gradle.kts:50`. Aggressive — fine, but combined with the broad `-keep class androidx.lifecycle.** { *; }` (line 38) you over-keep ~80% of `androidx.lifecycle`, defeating shrinking.

96. **No `dependenciesInfo { includeInApk = false }` block** — `build.gradle.kts`. Google Play Console can flag SDK dependencies metadata.

97. **`buildConfig = true`** — `app/build.gradle.kts:79`. Enabled to access `BuildConfig.DEBUG` — fine, but no `buildConfigField` declarations for things like `BASE_URL` or `YT_DLP_VERSION`.

98. **No `kotlinOptions { freeCompilerArgs += listOf("-Xjvm-default=all") }`** — recommended for `interface` default methods.

99. **ABI splits enabled but no version code differentiation** — `app/build.gradle.kts:61–68`. `universalApk = true` plus per-ABI splits is the right setup, but without distinct `versionCodeOverride` per ABI, the Play Store cannot serve the right variant.

100. **Signing config falls back to empty password on debug builds** — `app/build.gradle.kts:35–42`.

### A12. Modularization

101. **Single `:app` module**. Everything in one Gradle module. Consider extracting `:python` (Chaquopy module), `:downloader` (DownloadService + repository), `:ui` (Compose). Even keeping a single app module, the package-level organization is flat: 7 Kotlin files all in `com.kira.ytdlp` root. Reorganize into `data/`, `domain/`, `ui/`, `service/`.

102. **Namespace mismatch** — `applicationId = "com.kira.ytdlp"` but `applicationIdSuffix = ".debug"`. However the Intent extras use the hard-coded constant `"com.kira.ytdlp.extra.URL"` (DownloadService.kt:734) — cleaner to use `BuildConfig.APPLICATION_ID + ".extra.URL"`.

---

## B) Feature Suggestions

### B1. Download engine

1. **Concurrent downloads queue (WorkManager)** — Replace `START_NOT_STICKY` direct service with a `WorkManager` `CoroutineWorker`. Implementation: new file `worker/DownloadWorker.kt` extending `CoroutineWorker` with `getForegroundInfo()`; replace `DownloadService.createStartIntent` with `WorkManager.getInstance(context).enqueueUniqueWork("download-${UUID.randomUUID()}", ExistingWorkPolicy.APPEND, OneTimeWorkRequestBuilder<DownloadWorker>()...)`. Adds queue, retry, network constraints.

2. **Resume interrupted downloads** — Pass `continuedl: True` to yt-dlp in `ytdlp_helper.py`. Track partial files in a Room table `DownloadEntity(url, formatId, partialPath, totalBytes, downloadedBytes, status)`.

3. **Subtitle / closed-caption download** — Add yt-dlp options `'writesubtitles': True, 'writeautomaticsub': True, 'subtitleslangs': ['en']`. New Compose card listing detected subtitles, multi-select chips. Save .srt next to .mp4 in Downloads.

4. **Playlist support** — Remove `'noplaylist': True` (ytdlp_helper.py:32) conditionally. Add a "Playlist mode" toggle. New screen `PlaylistScreen.kt` listing entries; each item becomes a `DownloadWorker` task.

5. **Format presets** ("Best video", "Audio only 320kbps", "720p MP4") — Selector chip row above format list. Maps preset to yt-dlp `format` string (`bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best`).

6. **Speed throttling** — yt-dlp supports `'ratelimit': 1024 * 1024 * 5`. UI: slider in Settings → DataStore-persisted preference.

7. **Concurrent fragment downloads** — yt-dlp `'concurrent_fragments': 4`. UI option for slow connections.

8. **Per-format file size estimation refresh** — Currently `filesize` can be null for many formats. Add a "Probe size" button.

### B2. Media library

9. **Built-in lightweight player** — On completion card's "Play Video" button, instead of `ACTION_VIEW`, launch a local `ExoPlayerActivity` (Media3 ExoPlayer dependency).

10. **Metadata tagging** — Embed video title/channel into the MP4 box. ⚠️ Requires re-adding an FFmpeg dependency (e.g. `ffmpeg-kit-min`) since Android `MediaMuxer` does not support metadata writing; alternatively use `mp4parser` (pure Java, ~500 KB).

11. **Thumbnail embedding** — Embed cover art into the output file. ⚠️ Requires re-adding an FFmpeg dependency or `mp4parser` for MP4, same constraint as above.

### B3. Sharing / intents

12. **Share-sheet target for URLs** — Add intent-filter in `AndroidManifest.xml`:
    ```xml
    <intent-filter>
      <action android:name="android.intent.action.SEND"/>
      <category android:name="android.intent.category.DEFAULT"/>
      <data android:mimeType="text/plain"/>
    </intent-filter>
    ```
    In `MainActivity.onCreate`, check `intent?.action == Intent.ACTION_SEND` and pre-populate URL with `intent.getStringExtra(Intent.EXTRA_TEXT)`.

13. **Browser URL intercept** — Same intent-filter with `android.intent.action.VIEW` + `data scheme="https" host="youtube.com"`. User taps a YouTube link → can choose NexusDL.

14. **Share downloaded file** — Fix existing share button (A5#59). Use `dl.fileUri` directly with `Intent.EXTRA_STREAM` and a content URI from MediaStore.

### B4. OS integration

15. **Quick Settings Tile** — `TileService` subclass in `service/QuickDownloadTileService.kt`. Tap → opens MainActivity with clipboard auto-paste.

16. **Home screen widget** — `androidx.glance:glance-appwidget:1.1.0`. A widget showing the latest download progress + a paste-from-clipboard launch button. Implementation: new module `app/src/main/java/com/kira/ytdlp/widget/DownloadWidget.kt`.

17. **App Shortcut (Static + Dynamic)** — `app/src/main/res/xml/shortcuts.xml` referenced from the launcher activity. Dynamic shortcut "Download from clipboard" pre-fills URL on tap.

18. **Notification quick action: Cancel / Pause** — Add `NotificationCompat.Action.Builder` to the ongoing notification in `DownloadService.createNotification`. Action `PendingIntent` points to a new `BroadcastReceiver` that calls `serviceScope.cancel("user-cancel")`.

### B5. Background reliability

19. **WorkManager + Constraints** — `Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build()`. Allows "Wi-Fi only" mode and pauses on low storage.

20. **Doze-mode handling** — For queued (not-yet-started) downloads, use `WorkManager` which respects Doze. Add a `Settings → "Allow downloads on cellular"` preference.

### B6. Privacy / Auth

21. **Cookie file import** — Add a Settings screen `SettingsScreen.kt`. Use `ActivityResultContracts.OpenDocument()` to let the user pick a `cookies.txt` from Storage Access Framework. Copy it to `context.filesDir/cookies.txt` (private), then pass that path to `ytdlp_helper.get_video_info(url, cookiefile=…)`.

22. **Per-domain cookie support** — Different cookies for YouTube vs Twitter. Store mapping in DataStore.

23. **Privacy-first analytics opt-OUT by default** — No analytics declared (good); document this in README as a feature.

### B7. Codec / quality

24. **HEVC / AV1 preference** — Filter `videoFormats` by `vcodec.startsWith("hev1")` or `"av01"` if user opts in. yt-dlp format string can be `bestvideo[vcodec^=hev]+bestaudio`.

25. **Audio extraction modes** — Already partial; add `mp3 192kbps`, `mp3 320kbps`, `flac (lossless if source supports)`. ⚠️ Re-encoding (e.g. `-c:a libmp3lame -b:a 320k`) requires re-adding an FFmpeg dependency; stream-copy to Opus/AAC works with current `MediaMuxer`.

26. **Custom output format** — Settings: "Always convert to MP4" toggle. ⚠️ Re-muxing from WebM → MP4 when codecs differ requires re-adding an FFmpeg dependency; `MediaMuxer` handles same-codec stream copy natively.

### B8. UX

27. **Material You (Dynamic color)** — On Android 12+, use `dynamicDarkColorScheme(context)` and toggle between custom and dynamic in Settings.

28. **Download history (Room-backed)**. The Room dependency is already declared (just unused). Create `DownloadDao`, `DownloadEntity`, `AppDatabase`. New screen + bottom-nav. Mark complete downloads; show 30-day history with file size, format, timestamp.

29. **Theming toggle (system/dark/light)** — `DataStorePreference("theme_mode")` enum. Update `MaterialTheme` based on collected flow.

30. **Empty-state illustration** — Use a vector drawable + animated `alpha`.

### B9. Multi-device / Backup

31. **Backup download queue to JSON** — Settings → "Export queue" writes a JSON via SAF; "Import queue" reads it.

32. **Wear OS companion** — Minimal effort: a tiny WearActivity showing active download progress via `DataLayer` API.

### B10. Automation / Tasker

33. **Public Intent API for Tasker** — Re-use `DownloadService.createStartIntent` and add a manifest intent-filter on a new `ApiReceiver : BroadcastReceiver` accepting `com.kira.ytdlp.action.DOWNLOAD` with extras `url`, `quality`. Restricted by signature permission for security.

34. **Command-line via ADB** — Already works via `am start-service` because extras keys are public constants — document this in README.

35. **Subscribed-channels auto-download** — Stretch: poll a list of YouTube channels every N hours via WorkManager, download newest video if not already in history. Privacy concern, opt-in only.

---

## Summary of severity hot spots

- **Crash risk:** ~~A1#1~~ ✅ FIXED, A10#85 (foregroundServiceType wrong for API 34+), ~~A10#88~~ ✅ FIXED, ~~A10#91~~ ✅ FIXED.
- **Security / SSRF:** A2#19 (substring-based blocklist trivially bypassed by decimal/hex IP encodings).
- **Reliability:** A7#71–73 (no queue, no persistence, no cancellation), ~~A1#6~~ ✅ FIXED.
- **Dead weight:** ~10 MB+ of unused code: A4#41 RecentUrlsManager, A4#42 ~~Room~~ ✅ FIXED, A4#43 material-icons-extended, ~~A2#13 requests+certifi~~ ✅ FIXED.
- **i18n / a11y:** A4#49 (~13 hard-coded user-facing strings), A5#51 (null contentDescriptions on info icons).
- **Doc drift:** A1#12 yt-dlp version triple-mismatch between README, previous IMPROVEMENTS.md, build.gradle.kts.

**Key files for future work:**
- `app/src/main/java/com/kira/ytdlp/MainActivity.kt` (1547 lines — split first)
- `app/src/main/java/com/kira/ytdlp/DownloadService.kt` (811 lines — migrate to WorkManager)
- `app/src/main/java/com/kira/ytdlp/SecurityUtils.kt` (rewrite SSRF check)
- `app/src/main/python/ytdlp_helper.py` (294 lines — push progress, not poll)
- `app/src/main/AndroidManifest.xml` (foregroundServiceType, queries, VIBRATE)
- `app/build.gradle.kts` (yt-dlp pin, dead deps)
- `app/src/main/res/values/themes.xml` (dark splash)
