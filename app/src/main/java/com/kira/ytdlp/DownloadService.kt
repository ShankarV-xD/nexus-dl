package com.kira.ytdlp

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DownloadService"

// Notification Constants
private const val NOTIFICATION_ID = 1
private const val FINAL_NOTIFICATION_BASE_ID = 2
private const val CHANNEL_ID = "YTDLP_DOWNLOAD_CHANNEL"
private const val CHANNEL_NAME = "NexusDL Downloads"
private const val PENDING_INTENT_REQUEST_CODE = 1001

// Progress Constants
private const val PROGRESS_MERGING = 95
private const val PROGRESS_SAVING = 98
private const val PROGRESS_COMPLETE = 100

// File Name Constants
private const val MAX_TITLE_LENGTH = 100
private const val MAX_DESC_LENGTH = 50

// Delay Constants
private const val PROGRESS_UPDATE_DELAY_MS = 500L

// Speed/Size Constants
private const val BYTES_PER_MB = 1024 * 1024

/**
 * Represents the result of a single format download from Python.
 */
@Serializable
data class SingleDownloadResult(
    val status: String? = null,
    val message: String? = null,
    val filepath: String? = null
)

/**
 * Represents a progress update from the Python download helper.
 */
@Serializable
data class ProgressUpdate(
    val percent: Double? = null,
    val downloaded_bytes: Long? = null,
    val total_bytes: Long? = null,
    val speed: Double? = null,
    val eta: Long? = null,
    val status: String? = null
)

/**
 * Foreground service that handles video downloads with progress tracking.
 * 
 * This service:
 * - Runs in the foreground with a persistent notification
 * - Downloads video and audio streams separately if needed
 * - Merges streams using Android MediaMuxer when necessary
 * - Saves files to the public Downloads folder via MediaStore
 * - Provides real-time progress updates via StateFlow
 * 
 * Usage:
 * ```kotlin
 * val intent = DownloadService.createStartIntent(context, url, formatId, audioId, title, desc)
 * context.startForegroundService(intent)
 * ```
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var isServiceRunning = AtomicBoolean(false)
    private var progressJob: Job? = null
    private var downloadJob: Job? = null
    private val isCancelRequested = AtomicBoolean(false)

    /**
     * Called when the service is first created.
     * Sets up the notification channel for download notifications.
     */
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(TAG, "onCreate called")
        createNotificationChannel()
    }

    /**
     * Called when the service receives a start command.
     * 
     * Validates parameters, shows initial notification, and begins the download process.
     * Only one download can run at a time - subsequent requests will show a toast message.
     * 
     * @param intent The intent containing download parameters (URL, format IDs, title)
     * @param flags Additional flags about the start request
     * @param startId A unique integer representing this specific request
     * @return START_NOT_STICKY - service should not be restarted if killed
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(TAG, "onStartCommand received")

        if (intent?.action == ACTION_CANCEL) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Cancel action received")
            if (isServiceRunning.get()) {
                isCancelRequested.set(true)
                downloadJob?.cancel()
                progressJob?.cancel()
                isServiceRunning.set(false)
                clearCompletedDownload()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // If already running a download the service is already in foreground — reject this
        // request. The 5-second startForeground rule does NOT apply to an already-foreground service.
        if (isServiceRunning.get()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Service already running, not starting new download")
            Toast.makeText(this, getString(R.string.toast_download_in_progress), Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }

        // Must call startForeground() before any stopSelf() path to satisfy the 5-second
        // foreground-start rule on API 26+ when launched via startForegroundService().
        startForeground(NOTIFICATION_ID, createNotification("NexusDL", getString(R.string.status_downloading), progress = 0, isOngoing = true))

        val videoUrl = intent?.getStringExtra(EXTRA_URL)
        val videoFormatId = intent?.getStringExtra(EXTRA_VIDEO_FORMAT_ID)
        val audioFormatId = intent?.getStringExtra(EXTRA_AUDIO_FORMAT_ID)
        val videoTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Video"
        val formatDescription = intent?.getStringExtra(EXTRA_FORMAT_DESC) ?: videoFormatId
        val subtitleLangCode = intent?.getStringExtra(EXTRA_SUBTITLE_LANG_CODE)
        val subtitleIsAuto = intent?.getBooleanExtra(EXTRA_SUBTITLE_IS_AUTO, false) ?: false
        val isSubtitleOnly = intent?.getBooleanExtra(EXTRA_SUBTITLE_ONLY, false) ?: false

        if (videoUrl == null || (!isSubtitleOnly && videoFormatId == null)) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Missing core parameters in intent. Stopping service.")
            @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Security: Validate URL against whitelist
        val validationResult = SecurityUtils.validateUrl(videoUrl)
        if (validationResult !is SecurityUtils.ValidationResult.Valid) {
            val errorMsg = when (validationResult) {
                is SecurityUtils.ValidationResult.Blocked -> validationResult.reason
                is SecurityUtils.ValidationResult.Invalid -> validationResult.reason
                else -> "URL validation failed"
            }
            if (BuildConfig.DEBUG) Log.e(TAG, "Security: URL validation failed - $errorMsg")
            Toast.makeText(this, "Security: $errorMsg", Toast.LENGTH_LONG).show()
            @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Security: Check rate limiting (downloads only — info fetches are not rate-limited)
        val deviceId = "single_device" // In a multi-user app, use actual device/user ID
        if (!SecurityUtils.checkRateLimit(deviceId)) {
            val resetTime = SecurityUtils.getRateLimitResetTime(deviceId)
            val secondsRemaining = (resetTime / 1000).toInt()
            if (BuildConfig.DEBUG) Log.w(TAG, "Rate limit exceeded. Retry in $secondsRemaining seconds")
            Toast.makeText(
                this,
                "Rate limit exceeded. Please wait $secondsRemaining seconds before trying again.",
                Toast.LENGTH_LONG
            ).show()
            @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        isCancelRequested.set(false)
        isServiceRunning.set(true)
        if (BuildConfig.DEBUG) Log.i(TAG, "Starting download process for URL: $videoUrl, Video Format: $videoFormatId, Audio Format: $audioFormatId, Desc: $formatDescription")

        val initialStatusText = if (isSubtitleOnly) "Downloading subtitle..." else getString(R.string.status_downloading)
        updateNotification(videoTitle, initialStatusText, progress = 0, isOngoing = true)

        downloadJob = serviceScope.launch {
            if (BuildConfig.DEBUG) Log.d(TAG, "Coroutine launched. subtitleOnly=$isSubtitleOnly")
            when {
                isSubtitleOnly && subtitleLangCode != null ->
                    performSubtitleOnlyDownload(videoUrl, videoTitle, subtitleLangCode, subtitleIsAuto)
                !isSubtitleOnly && videoFormatId != null ->
                    performDownloadAndMerge(
                        videoUrl, videoFormatId, audioFormatId, videoTitle,
                        formatDescription ?: "Unknown Format", subtitleLangCode, subtitleIsAuto
                    )
                else ->
                    if (BuildConfig.DEBUG) Log.e(TAG, "Invalid download dispatch state")
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Download task coroutine finished")
            progressJob?.cancel()
            isServiceRunning.set(false)
            // Note: Don't clear _activeDownload here - the completion state is set in finally block
            // and should persist until user dismisses it or starts new download
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Performs the main download and merge workflow.
     * 
     * Steps:
     * 1. Download video stream
     * 2. Download audio stream (if format is video-only)
     * 3. Merge video and audio using Android MediaMuxer (if both were downloaded)
     * 4. Save final file to public Downloads folder via MediaStore
     * 5. Clean up temporary files
     * 
     * @param url The video URL to download from
     * @param videoFormatId The format ID for the video stream
     * @param audioFormatId The format ID for the audio stream (null if video includes audio)
     * @param title The video title for the output filename
     * @param formatDescription Description of the selected format
     */
    private suspend fun performDownloadAndMerge(
        url: String,
        videoFormatId: String,
        audioFormatId: String?,
        title: String,
        formatDescription: String,
        subtitleLangCode: String? = null,
        subtitleIsAuto: Boolean = false
    ) {
        val pyInstance = Python.getInstance()
        val ytdlpHelperModule = pyInstance.getModule("ytdlp_helper")
        var finalStatus = "Download failed: Unknown error"
        var finalMessage = "An unexpected error occurred."
        var videoFilePath: String? = null
        var audioFilePath: String? = null
        val tempDir = cacheDir.absolutePath
        var tempFinalFilePath: String? = null
        var finalPublicUri: Uri? = null
        var subtitleFilePath: String? = null

        try {
            ytdlpHelperModule.callAttr("reset_progress_tracking")
            startProgressMonitoring(title)

            // --- 1. Download Video Stream ---
            updateNotification(title, getString(R.string.status_downloading), progress = 0, isOngoing = true)
            if (BuildConfig.DEBUG) Log.d(TAG, "Attempting to download video format: $videoFormatId to $tempDir")
            ytdlpHelperModule.callAttr("set_download_phase", "video")

            val videoResultJson = ytdlpHelperModule.callAttr(
                "download_single_format_with_progress",
                url,
                videoFormatId,
                tempDir
            )?.toString()

            val videoResult = videoResultJson?.let { jsonParser.decodeFromString<SingleDownloadResult>(it) }

            if (videoResult?.status != "success" || videoResult.filepath == null || !File(videoResult.filepath).exists()) {
                throw Exception("Failed to download video stream: ${videoResult?.message ?: "Unknown Python error or file missing"}")
            }
            videoFilePath = videoResult.filepath
            if (BuildConfig.DEBUG) Log.i(TAG, "Video download successful (temp): $videoFilePath")

            // --- 2. Download Audio Stream (if needed) ---
            if (audioFormatId != null) {
                updateNotification(title, getString(R.string.label_audio), progress = 0, isOngoing = true)
                if (BuildConfig.DEBUG) Log.d(TAG, "Attempting to download audio format: $audioFormatId to $tempDir")
                ytdlpHelperModule.callAttr("set_download_phase", "audio")

                val audioResultJson = ytdlpHelperModule.callAttr(
                    "download_single_format_with_progress",
                    url,
                    audioFormatId,
                    tempDir
                )?.toString()

                val audioResult = audioResultJson?.let { jsonParser.decodeFromString<SingleDownloadResult>(it) }

                if (audioResult?.status != "success" || audioResult.filepath == null || !File(audioResult.filepath).exists()) {
                    throw Exception("Failed to download audio stream: ${audioResult?.message ?: "Unknown Python error or file missing"}")
                }
                audioFilePath = audioResult.filepath
                if (BuildConfig.DEBUG) Log.i(TAG, "Audio download successful (temp): $audioFilePath")

                // --- 3. Merge using Android MediaMuxer (stream copy, no re-encoding) ---
                updateNotification(title, getString(R.string.status_merging), progress = PROGRESS_MERGING, isIndeterminate = true, isOngoing = true)
                if (BuildConfig.DEBUG) Log.d(TAG, "Attempting to merge video and audio into temp dir")
                ytdlpHelperModule.callAttr("set_download_phase", "merging")

                val vPath = videoFilePath ?: throw IOException("Missing video path for merging.")
                val aPath = audioFilePath ?: throw IOException("Missing audio path for merging.")

                tempFinalFilePath = mergeWithFFmpeg(vPath, aPath, tempDir)

            } else {
                if (BuildConfig.DEBUG) Log.i(TAG, "Single stream downloaded, no merge needed.")
                tempFinalFilePath = videoFilePath ?: throw IOException("Video file path is null after single stream download.")
                videoFilePath = null
            }

            // --- 4. Move the final temp file to Public Downloads using MediaStore ---
            if (tempFinalFilePath != null && File(tempFinalFilePath).exists()) {
                updateNotification(title, getString(R.string.status_saving), progress = PROGRESS_SAVING, isOngoing = true)
                if (BuildConfig.DEBUG) Log.d(TAG, "Moving final temp file $tempFinalFilePath to public Downloads")
                ytdlpHelperModule.callAttr("set_download_phase", "saving")

                val finalFile = File(tempFinalFilePath)
                val extension = finalFile.extension
                val mimeType = getMimeTypeFromExtension(extension) ?: "application/octet-stream"

                val safeTitle = sanitizeFileName(title).take(MAX_TITLE_LENGTH)
                val safeDesc = sanitizeFileName(formatDescription).take(MAX_DESC_LENGTH)
                val finalFileName = "${safeTitle}_${safeDesc}.$extension"

                finalPublicUri = saveToDownloadsUsingMediaStore(
                    context = this,
                    tempFilePath = tempFinalFilePath,
                    fileName = finalFileName,
                    mimeType = mimeType
                )

                if (finalPublicUri != null) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "File successfully saved to public Downloads: $finalPublicUri")
                    finalStatus = "Download completed"
                    finalMessage = "File saved to Downloads folder."
                } else {
                    throw Exception("Failed to save file to public Downloads folder.")
                }
            } else {
                throw Exception("Final temporary file path is missing or file does not exist after download/merge.")
            }

            // --- 5. Download subtitle if requested ---
            if (subtitleLangCode != null) {
                try {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Downloading subtitle: lang=$subtitleLangCode, isAuto=$subtitleIsAuto")
                    ytdlpHelperModule.callAttr("set_download_phase", "subtitle")
                    updateNotification(title, "Downloading subtitle...", progress = PROGRESS_COMPLETE, isOngoing = true)

                    val subtitleResultJson = ytdlpHelperModule.callAttr(
                        "download_subtitle", url, subtitleLangCode, subtitleIsAuto, tempDir
                    )?.toString()
                    val subtitleResult = subtitleResultJson?.let { jsonParser.decodeFromString<SingleDownloadResult>(it) }

                    if (subtitleResult?.status == "success" && subtitleResult.filepath != null) {
                        val subtitleFile = File(subtitleResult.filepath)
                        subtitleFilePath = subtitleResult.filepath
                        if (subtitleFile.exists()) {
                            val safeTitle = sanitizeFileName(title).take(MAX_TITLE_LENGTH)
                            val subtitleExt = subtitleFile.extension
                            val subtitleMimeType = when (subtitleExt.lowercase()) {
                                "srt" -> "application/x-subrip"
                                "vtt" -> "text/vtt"
                                else -> "text/plain"
                            }
                            val subtitleFileName = "${safeTitle}_${subtitleLangCode}.$subtitleExt"
                            val subtitleUri = saveToDownloadsUsingMediaStore(
                                context = this,
                                tempFilePath = subtitleResult.filepath,
                                fileName = subtitleFileName,
                                mimeType = subtitleMimeType
                            )
                            if (subtitleUri != null) {
                                if (BuildConfig.DEBUG) Log.i(TAG, "Subtitle saved: $subtitleUri")
                            } else {
                                if (BuildConfig.DEBUG) Log.w(TAG, "Failed to save subtitle to MediaStore")
                            }
                        }
                    } else {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Subtitle download skipped/failed: ${subtitleResult?.message}")
                    }
                } catch (e: Exception) {
                    // Subtitle failure does not fail the main download
                    if (BuildConfig.DEBUG) Log.e(TAG, "Subtitle download error (non-fatal): ${e.message}", e)
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Error during download/merge/save process: ${e.message}", e)
            finalStatus = "Download failed"
            finalMessage = e.message ?: "An unexpected error occurred."
            finalPublicUri?.let { uri ->
                try {
                    contentResolver.delete(uri, null, null)
                    if (BuildConfig.DEBUG) Log.w(TAG, "Cleaned up incomplete MediaStore entry: $uri")
                } catch (deleteEx: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to clean up MediaStore entry: $uri", deleteEx)
                }
            }
        } finally {
            progressJob?.cancel()

            if (BuildConfig.DEBUG) Log.d(TAG, "Download process finished with status: $finalStatus, Message: $finalMessage")

            // --- Cleanup Temporary Files ---
            videoFilePath?.let { try { File(it).delete() } catch (ex: Exception) { if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete temp video file: $it") } }
            audioFilePath?.let { try { File(it).delete() } catch (ex: Exception) { if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete temp audio file: $it") } }
            subtitleFilePath?.let { try { if (File(it).exists()) File(it).delete() } catch (ex: Exception) { if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete temp subtitle file: $it") } }
            if (finalPublicUri != null || videoFilePath == null) {
                tempFinalFilePath?.let {
                    try {
                        if (File(it).exists()) {
                            File(it).delete()
                            if (BuildConfig.DEBUG) Log.d(TAG, "Deleted final temp file: $it")
                        }
                    } catch (ex: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete final temp file: $it", ex)
                    }
                }
            }

            if (isCancelRequested.get()) return

            // Set completion state for in-app display
            val isSuccess = finalStatus == "Download completed"
            _activeDownload.value = ActiveDownload(
                title = title,
                phase = if (isSuccess) "Complete" else "Failed",
                percent = if (isSuccess) PROGRESS_COMPLETE else 0,
                statusText = if (isSuccess) getString(R.string.status_download_complete) else finalMessage,
                isActive = false,
                isComplete = true,
                isSuccess = isSuccess,
                fileUri = finalPublicUri,
                errorMessage = if (!isSuccess) finalMessage else null
            )

            // --- Final Notification ---
            val notificationText = "$finalStatus: '$title' ($formatDescription)"
            withContext(Dispatchers.Main) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Switching to Main thread for final notification handling.")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
                    if (channel == null) { if (BuildConfig.DEBUG) Log.e(TAG, "!!! Notification channel $CHANNEL_ID is NULL !!!") }
                    else if (channel.importance == NotificationManager.IMPORTANCE_NONE) { if (BuildConfig.DEBUG) Log.e(TAG, "!!! Notification channel $CHANNEL_ID is BLOCKED !!!") }
                }

                notificationManager.cancel(NOTIFICATION_ID)

                val pendingIntent: PendingIntent? = if (finalStatus == "Download completed") {
                    val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    PendingIntent.getActivity(
                        this@DownloadService,
                        PENDING_INTENT_REQUEST_CODE,
                        downloadsIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                } else null

                val finalNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(notificationText)
                    .setSmallIcon(R.drawable.ic_launcher_foreground_nexus)
                    .setContentIntent(pendingIntent)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
                    .build()

                val uniqueFinalNotificationId = FINAL_NOTIFICATION_BASE_ID + System.currentTimeMillis().toInt()
                notificationManager.notify(uniqueFinalNotificationId, finalNotification)
            }
        }
    }

    private suspend fun performSubtitleOnlyDownload(
        url: String,
        title: String,
        subtitleLangCode: String,
        subtitleIsAuto: Boolean
    ) {
        val pyInstance = Python.getInstance()
        val ytdlpHelperModule = pyInstance.getModule("ytdlp_helper")
        val tempDir = cacheDir.absolutePath
        var subtitleTempPath: String? = null
        var finalStatus = "Download failed"
        var finalMessage = "An unexpected error occurred."
        var finalPublicUri: Uri? = null

        try {
            updateNotification(title, "Downloading subtitle...", progress = 0, isIndeterminate = true, isOngoing = true)
            ytdlpHelperModule.callAttr("set_download_phase", "subtitle")

            val resultJson = ytdlpHelperModule.callAttr(
                "download_subtitle", url, subtitleLangCode, subtitleIsAuto, tempDir
            )?.toString()
            val result = resultJson?.let { jsonParser.decodeFromString<SingleDownloadResult>(it) }

            if (result?.status == "success" && result.filepath != null && File(result.filepath).exists()) {
                subtitleTempPath = result.filepath
                val subtitleFile = File(result.filepath)
                val safeTitle = sanitizeFileName(title).take(MAX_TITLE_LENGTH)
                val ext = subtitleFile.extension
                val mimeType = when (ext.lowercase()) {
                    "srt" -> "application/x-subrip"
                    "vtt" -> "text/vtt"
                    else -> "text/plain"
                }
                finalPublicUri = saveToDownloadsUsingMediaStore(
                    context = this,
                    tempFilePath = result.filepath,
                    fileName = "${safeTitle}_${subtitleLangCode}.$ext",
                    mimeType = mimeType
                )
                if (finalPublicUri != null) {
                    finalStatus = "Download completed"
                    finalMessage = "Subtitle saved to Downloads folder."
                } else {
                    throw Exception("Failed to save subtitle to Downloads.")
                }
            } else {
                throw Exception(result?.message ?: "Subtitle download failed.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Subtitle-only download failed: ${e.message}", e)
            finalStatus = "Download failed"
            finalMessage = e.message ?: "An unexpected error occurred."
        } finally {
            subtitleTempPath?.let { try { File(it).delete() } catch (ex: Exception) {} }

            if (isCancelRequested.get()) return

            val isSuccess = finalStatus == "Download completed"
            _activeDownload.value = ActiveDownload(
                title = title,
                phase = if (isSuccess) "Complete" else "Failed",
                percent = if (isSuccess) PROGRESS_COMPLETE else 0,
                statusText = if (isSuccess) "Subtitle saved to Downloads!" else finalMessage,
                isActive = false,
                isComplete = true,
                isSuccess = isSuccess,
                fileUri = null,
                errorMessage = if (!isSuccess) finalMessage else null
            )

            withContext(Dispatchers.Main) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                val notificationText = "$finalStatus: subtitle for '$title'"
                val finalNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(notificationText)
                    .setSmallIcon(R.drawable.ic_launcher_foreground_nexus)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                notificationManager.notify(FINAL_NOTIFICATION_BASE_ID + System.currentTimeMillis().toInt(), finalNotification)
            }
        }
    }

    /**
     * Starts monitoring download progress from the Python helper.
     * 
     * Polls progress every [PROGRESS_UPDATE_DELAY_MS] milliseconds and updates
     * both the notification and the [activeDownload] StateFlow.
     * 
     * @param title The video title to display in progress updates
     */
    private fun startProgressMonitoring(title: String) {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            val pyInstance = Python.getInstance()
            val ytdlpHelperModule = pyInstance.getModule("ytdlp_helper")

            while (isActive) {
                try {
                    val progressJson = ytdlpHelperModule.callAttr("get_current_progress")?.toString() ?: "{}"
                    val progress = jsonParser.decodeFromString<ProgressUpdate>(progressJson)

                    if (progress.status == "downloading") {
                        val progressPercent = progress.percent?.toInt() ?: 0
                        val phase = ytdlpHelperModule.callAttr("get_current_phase")?.toString() ?: "downloading"

                        val speedText = if (progress.speed != null && progress.speed > 0) {
                            val speedMbps = progress.speed / BYTES_PER_MB
                            String.format("%.1f MB/s", speedMbps)
                        } else ""

                        val etaText = if (progress.eta != null && progress.eta > 0) {
                            val minutes = progress.eta / 60
                            val seconds = progress.eta % 60
                            if (minutes > 0) "$minutes min $seconds sec left" else "$seconds sec left"
                        } else ""

                        val phaseText = when (phase) {
                            "video" -> "Downloading video"
                            "audio" -> "Downloading audio"
                            "merging" -> "Merging files"
                            "saving" -> "Saving to Downloads"
                            "subtitle" -> "Downloading subtitle"
                            else -> "Downloading"
                        }

                        val statusText = when {
                            speedText.isNotEmpty() && etaText.isNotEmpty() -> "$phaseText... $speedText, $etaText"
                            speedText.isNotEmpty() -> "$phaseText... $speedText"
                            else -> "$phaseText..."
                        }

                        updateNotification(title, statusText, progressPercent, isOngoing = true)

                        // Push progress to the shared StateFlow for in-app display
                        _activeDownload.value = ActiveDownload(
                            title = title,
                            phase = phaseText,
                            percent = progressPercent,
                            statusText = statusText,
                            isActive = true
                        )
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Error in progress monitoring: ${e.message}", e)
                }

                delay(PROGRESS_UPDATE_DELAY_MS)
            }
        }
    }

    /**
     * Gets the MIME type for a given file extension.
     * 
     * @param extension The file extension (e.g., "mp4", "webm")
     * @return The MIME type string, or null if unknown
     */
    private fun getMimeTypeFromExtension(extension: String): String? {
        return when (extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> null
        }
    }

    /**
     * Merges separate video and audio files into a single container using FFmpegKit
     * (stream copy — no re-encoding).
     *
     * Automatically selects MP4 output for H.264/HEVC streams and MKV for VP9/AV1/VP8,
     * as Android MediaMuxer cannot handle those codecs reliably across API levels.
     *
     * @return Absolute path of the merged output file.
     * @throws Exception if tracks cannot be found or muxing fails.
     */
    private fun mergeWithFFmpeg(videoPath: String, audioPath: String, outputDir: String): String {
        // Detect video codec from the downloaded file to pick the right output container.
        // H264/HEVC → MP4 (universally supported).
        // VP9, VP8, AV1 → MKV (MP4 doesn't reliably support Opus audio;
        // Android's MediaMuxer can't handle these codecs on API < 34).
        val extractor = MediaExtractor()
        val videoMime: String
        try {
            extractor.setDataSource(videoPath)
            videoMime = (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") } ?: ""
        } finally {
            extractor.release()
        }

        val needsMatroska = videoMime.contains("vp9", ignoreCase = true) ||
                videoMime.contains("vp8", ignoreCase = true) ||
                videoMime.contains("av01", ignoreCase = true) ||
                videoMime.contains("av1", ignoreCase = true)

        val ext = if (needsMatroska) "mkv" else "mp4"
        val outputPath = File(outputDir, "merged_${System.currentTimeMillis()}.$ext").absolutePath

        val cmd = "-y -i $videoPath -i $audioPath -c copy $outputPath"
        if (BuildConfig.DEBUG) Log.d(TAG, "FFmpeg merge cmd: $cmd")

        val session = FFmpegKit.execute(cmd)

        if (!ReturnCode.isSuccess(session.returnCode) || !File(outputPath).exists()) {
            File(outputPath).takeIf { it.exists() }?.delete()
            val logs = session.allLogsAsString?.takeLast(400) ?: "no logs"
            throw Exception("FFmpeg merge failed (rc=${session.returnCode}): $logs")
        }

        if (BuildConfig.DEBUG) Log.i(TAG, "FFmpeg merge successful: $outputPath")
        return outputPath
    }

    /**
     * Sanitizes a string to be safe for use in filenames.
     * Replaces any characters that are not alphanumeric, dot, or hyphen with underscores.
     * 
     * @param input The string to sanitize
     * @return The sanitized string safe for use in filenames
     */
    private fun sanitizeFileName(input: String): String {
        return input.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
    }

    /**
     * Saves a file to the public Downloads folder using MediaStore API.
     * 
     * This method:
     * - Creates a MediaStore entry with proper metadata
     * - Copies the file from temp location to Downloads
     * - Marks the file as complete (Android 10+)
     * - Handles cleanup on failure
     * 
     * @param context The context to use for content resolver
     * @param tempFilePath Path to the temporary file to copy
     * @param fileName The desired filename in Downloads
     * @param mimeType The MIME type of the file
     * @return The URI of the saved file, or null if saving failed
     */
    private fun saveToDownloadsUsingMediaStore(
        context: Context,
        tempFilePath: String,
        fileName: String,
        mimeType: String
    ): Uri? {
        val contentResolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val fullPath = File(publicDownloads, fileName).absolutePath
                put(MediaStore.MediaColumns.DATA, fullPath)
            }
        }

        var outputUri: Uri? = null
        var outputStream: OutputStream? = null
        var inputStream: InputStream? = null
        val sourceFile = File(tempFilePath)

        if (!sourceFile.exists()) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Source file does not exist: $tempFilePath")
            return null
        }

        try {
            outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

            if (outputUri == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "MediaStore insert failed, returned null URI for Downloads collection")
                return null
            }

            outputStream = contentResolver.openOutputStream(outputUri)
            inputStream = sourceFile.inputStream()

            if (outputStream == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to open output stream for MediaStore URI: $outputUri")
                contentResolver.delete(outputUri, null, null)
                throw IOException("Cannot open output stream for MediaStore")
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "Starting copy from $tempFilePath to $outputUri")
            val copiedBytes = inputStream.copyTo(outputStream)
            if (BuildConfig.DEBUG) Log.d(TAG, "Finished copying $copiedBytes bytes")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(outputUri, values, null, null)
            }

            return outputUri

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Error saving file to MediaStore: ${e.message}", e)
            outputUri?.let { uri ->
                try {
                    contentResolver.delete(uri, null, null)
                    if (BuildConfig.DEBUG) Log.w(TAG, "Deleted incomplete MediaStore entry due to error: $uri")
                } catch (deleteEx: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to delete incomplete MediaStore entry: $uri", deleteEx)
                }
            }
            return null
        } finally {
            try { inputStream?.close() } catch (e: IOException) { if (BuildConfig.DEBUG) Log.e(TAG, "Failed to close input stream", e) }
            try { outputStream?.close() } catch (e: IOException) { if (BuildConfig.DEBUG) Log.e(TAG, "Failed to close output stream", e) }
        }
    }

    /**
     * Creates the notification channel for download notifications.
     * Required for Android 8.0+ (API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notifications for NexusDL downloads"
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates a notification for the download service.
     * 
     * @param title The title to display
     * @param text The body text to display
     * @param progress Current progress percentage (0-100)
     * @param isIndeterminate Whether to show an indeterminate progress bar
     * @param isOngoing Whether this is an ongoing notification that can't be dismissed
     * @return The created Notification object
     */
    private fun createNotification(
        title: String,
        text: String,
        progress: Int = 0,
        isIndeterminate: Boolean = false,
        isOngoing: Boolean = true
    ): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val ongoingPendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground_nexus)
            .setContentIntent(ongoingPendingIntent)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setPriority(if (isOngoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)

        if (isOngoing) {
            if (isIndeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(PROGRESS_COMPLETE, progress, false)
            }
            val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
            val cancelPendingIntent = PendingIntent.getService(
                this, 1002, cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_delete, "Cancel", cancelPendingIntent)
        }

        return builder.build()
    }

    /**
     * Updates the ongoing download notification with new progress.
     * 
     * @param title The title to display
     * @param text The body text to display
     * @param progress Current progress percentage (0-100)
     * @param isIndeterminate Whether to show an indeterminate progress bar
     * @param isOngoing Whether this notification is ongoing
     */
    private fun updateNotification(
        title: String,
        text: String,
        progress: Int = 0,
        isIndeterminate: Boolean = false,
        isOngoing: Boolean = true
    ) {
        val notification = createNotification(title, text, progress, isIndeterminate, isOngoing)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Called when a component binds to this service.
     * 
     * @return null as this service does not support binding
     */
    override fun onBind(intent: Intent): IBinder? = null

    /**
     * Called when the service is being destroyed.
     * Cleans up coroutines and jobs.
     */
    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.d(TAG, "onDestroy called")
        progressJob?.cancel()
        serviceScope.cancel()
        // Note: We don't clear _activeDownload here anymore
        // The completion state persists until user dismisses it or starts new download
        super.onDestroy()
    }

    companion object {
        /** Action to cancel an in-progress download */
        const val ACTION_CANCEL = "com.kira.ytdlp.action.CANCEL"

        /** Intent extra key for video URL */
        const val EXTRA_URL = "com.kira.ytdlp.extra.URL"
        /** Intent extra key for video format ID */
        const val EXTRA_VIDEO_FORMAT_ID = "com.kira.ytdlp.extra.VIDEO_FORMAT_ID"
        /** Intent extra key for audio format ID */
        const val EXTRA_AUDIO_FORMAT_ID = "com.kira.ytdlp.extra.AUDIO_FORMAT_ID"
        /** Intent extra key for video title */
        const val EXTRA_TITLE = "com.kira.ytdlp.extra.TITLE"
        /** Intent extra key for format description */
        const val EXTRA_FORMAT_DESC = "com.kira.ytdlp.extra.FORMAT_DESC"
        /** Intent extra key for subtitle language code (null = no subtitle) */
        const val EXTRA_SUBTITLE_LANG_CODE = "com.kira.ytdlp.extra.SUBTITLE_LANG_CODE"
        /** Intent extra key for whether the subtitle is auto-generated */
        const val EXTRA_SUBTITLE_IS_AUTO = "com.kira.ytdlp.extra.SUBTITLE_IS_AUTO"
        /** Intent extra key — true means download subtitle only, no video */
        const val EXTRA_SUBTITLE_ONLY = "com.kira.ytdlp.extra.SUBTITLE_ONLY"

        /**
         * Represents the current state of an active or completed download.
         * 
         * Used by the UI to display download progress and completion status.
         * 
         * @property title The video title being downloaded
         * @property phase Current phase of the download (e.g., "Downloading video", "Merging files")
         * @property percent Current progress percentage (0-100)
         * @property statusText Human-readable status text for display
         * @property isActive Whether the download is currently active
         * @property isComplete Whether the download has completed (success or failure)
         * @property isSuccess Whether the download completed successfully
         * @property fileUri URI to the saved file (only if successful)
         * @property errorMessage Error message (only if failed)
         */
        data class ActiveDownload(
            val title: String,
            val phase: String,
            val percent: Int,
            val statusText: String,
            val isActive: Boolean,
            val isComplete: Boolean = false,
            val isSuccess: Boolean = false,
            val fileUri: Uri? = null,
            val errorMessage: String? = null
        )

        private val _activeDownload = MutableStateFlow<ActiveDownload?>(null)
        /** StateFlow that emits the current download state for UI observation */
        val activeDownload: StateFlow<ActiveDownload?> = _activeDownload.asStateFlow()

        /**
         * Clears the completed download state.
         * Should be called when starting a new search or dismissing the completion UI.
         */
        fun clearCompletedDownload() {
            _activeDownload.value = null
        }

        /**
         * Creates an Intent to start the download service.
         * 
         * @param context The context to create the intent from
         * @param url The video URL to download
         * @param videoFormatId The format ID for the video stream
         * @param audioFormatId The format ID for the audio stream (null if combined format)
         * @param title The video title
         * @param formatDescription Description of the selected format
         * @return An Intent ready to be passed to startForegroundService()
         */
        fun createSubtitleOnlyIntent(
            context: Context,
            url: String,
            title: String,
            subtitleLangCode: String,
            subtitleIsAuto: Boolean
        ): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE_ONLY, true)
                putExtra(EXTRA_SUBTITLE_LANG_CODE, subtitleLangCode)
                putExtra(EXTRA_SUBTITLE_IS_AUTO, subtitleIsAuto)
            }
        }

        fun createStartIntent(
            context: Context,
            url: String,
            videoFormatId: String,
            audioFormatId: String?,
            title: String,
            formatDescription: String?,
            subtitleLangCode: String? = null,
            subtitleIsAuto: Boolean = false
        ): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VIDEO_FORMAT_ID, videoFormatId)
                putExtra(EXTRA_AUDIO_FORMAT_ID, audioFormatId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT_DESC, formatDescription ?: videoFormatId)
                if (subtitleLangCode != null) {
                    putExtra(EXTRA_SUBTITLE_LANG_CODE, subtitleLangCode)
                    putExtra(EXTRA_SUBTITLE_IS_AUTO, subtitleIsAuto)
                }
            }
        }
    }
}
