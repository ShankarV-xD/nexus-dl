# Keep line numbers in stack traces for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Chaquopy (Python runtime) ────────────────────────────────────────────────
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ── FFmpegKit ────────────────────────────────────────────────────────────────
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# ── Kotlin Serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable data classes in this app
-keep,includedescriptorclasses class com.kira.ytdlp.**$$serializer { *; }
-keepclassmembers class com.kira.ytdlp.** {
    *** Companion;
}
-keepclasseswithmembers class com.kira.ytdlp.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Coil (image loading) ─────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── AndroidX / Lifecycle / ViewModel ────────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ── Compose ──────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-dontwarn kotlinx.coroutines.**

# ── OkHttp / Okio (pulled in transitively by Coil) ──────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
