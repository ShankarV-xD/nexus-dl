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
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DownloadService"
private const val NOTIFICATION_ID = 1
private const val FINAL_NOTIFICATION_BASE_ID = 2
private const val CHANNEL_ID = "YTDLP_DOWNLOAD_CHANNEL"
private const val CHANNEL_NAME = "YT-DLP Downloads"
private const val PENDING_INTENT_REQUEST_CODE = 1001

@Serializable
data class SingleDownloadResult(
    val status: String? = null,
    val message: String? = null,
    val filepath: String? = null
)

@Serializable
data class ProgressUpdate(
    val percent: Double? = null,
    val downloaded_bytes: Long? = null,
    val total_bytes: Long? = null,
    val speed: Double? = null,
    val eta: Long? = null,
    val status: String? = null
)

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var isServiceRunning = AtomicBoolean(false)
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received")

        val videoUrl = intent?.getStringExtra(EXTRA_URL)
        val videoFormatId = intent?.getStringExtra(EXTRA_VIDEO_FORMAT_ID)
        val audioFormatId = intent?.getStringExtra(EXTRA_AUDIO_FORMAT_ID)
        val videoTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Video"
        val formatDescription = intent?.getStringExtra(EXTRA_FORMAT_DESC) ?: videoFormatId

        if (videoUrl == null || videoFormatId == null) {
            Log.e(TAG, "Missing core parameters in intent. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isServiceRunning.getAndSet(true)) {
            Log.w(TAG, "Service already running, not starting new download")
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting download process for URL: $videoUrl, Video Format: $videoFormatId, Audio Format: $audioFormatId, Desc: $formatDescription")

        val notification = createNotification(videoTitle, "Download starting...", progress = 0, isOngoing = true)
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            Log.d(TAG, "Coroutine launched for download task")
            performDownloadAndMerge(
                videoUrl,
                videoFormatId,
                audioFormatId,
                videoTitle,
                formatDescription ?: "Unknown Format"
            )
            Log.d(TAG, "Download task coroutine finished")
            progressJob?.cancel()
            isServiceRunning.set(false)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private suspend fun performDownloadAndMerge(
        url: String,
        videoFormatId: String,
        audioFormatId: String?,
        title: String,
        formatDescription: String
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

        try {
            // Reset progress tracking in Python module
            ytdlpHelperModule.callAttr("reset_progress_tracking")

            // Start progress monitoring
            startProgressMonitoring(title)

            // --- 1. Download Video Stream ---
            updateNotification(title, "Downloading video ($formatDescription)...", progress = 0, isOngoing = true)
            Log.d(TAG, "Attempting to download video format: $videoFormatId to $tempDir")

            // Set current phase in Python
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
            Log.i(TAG, "Video download successful (temp): $videoFilePath")

            // --- 2. Download Audio Stream (if needed) ---
            if (audioFormatId != null) {
                updateNotification(title, "Downloading audio...", progress = 0, isOngoing = true)
                Log.d(TAG, "Attempting to download audio format: $audioFormatId to $tempDir")

                // Set current phase in Python
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
                Log.i(TAG, "Audio download successful (temp): $audioFilePath")

                // --- 3. Merge using FFmpegKit (Output to Temp Dir) ---
                updateNotification(title, "Merging files...", progress = 95, isIndeterminate = true, isOngoing = true)
                Log.d(TAG, "Attempting to merge video and audio into temp dir")

                // Set current phase in Python
                ytdlpHelperModule.callAttr("set_download_phase", "merging")

                val tempMergedFileName = "merged_${System.currentTimeMillis()}.mp4"
                val tempMergedOutputFile = File(tempDir, tempMergedFileName)
                tempFinalFilePath = tempMergedOutputFile.absolutePath

                if (videoFilePath == null || audioFilePath == null) {
                    throw IOException("Missing video or audio file path for merging.")
                }

                val ffmpegCommand = "-i \"$videoFilePath\" -i \"$audioFilePath\" -c:v copy -c:a copy -map 0:v:0 -map 1:a:0 -y \"$tempFinalFilePath\""

                Log.i(TAG, "Executing FFmpeg command: $ffmpegCommand")
                val session = FFmpegKit.execute(ffmpegCommand)

                if (ReturnCode.isSuccess(session.returnCode)) {
                    Log.i(TAG, "FFmpeg merge successful (temp). Output: $tempFinalFilePath")
                } else {
                    Log.e(TAG, "FFmpeg merge failed! Return code: ${session.returnCode}")
                    session.allLogs.forEach { log -> Log.e(TAG, "[FFmpeg Log]: ${log.message}") }
                    throw Exception("Failed to merge video and audio. FFmpeg error code: ${session.returnCode}")
                }

            } else {
                Log.i(TAG, "Single stream downloaded, no merge needed.")
                if (videoFilePath == null) {
                    throw IOException("Video file path is null after single stream download.")
                }
                tempFinalFilePath = videoFilePath
                videoFilePath = null
            }

            // --- 4. Move the final temp file to Public Downloads using MediaStore ---
            if (tempFinalFilePath != null && File(tempFinalFilePath).exists()) {
                updateNotification(title, "Saving to Downloads folder...", progress = 98, isOngoing = true)
                Log.d(TAG, "Moving final temp file $tempFinalFilePath to public Downloads")

                // Set current phase in Python
                ytdlpHelperModule.callAttr("set_download_phase", "saving")

                val finalFile = File(tempFinalFilePath)
                val extension = finalFile.extension
                val mimeType = getMimeTypeFromExtension(extension) ?: "application/octet-stream"

                val safeTitle = title.replace("[^a-zA-Z0-9.-]".toRegex(), "_").take(100)
                val safeDesc = formatDescription.replace("[^a-zA-Z0-9.-]".toRegex(), "_").take(50)
                val finalFileName = "${safeTitle}_${safeDesc}.$extension"

                finalPublicUri = saveToDownloadsUsingMediaStore(
                    context = this,
                    tempFilePath = tempFinalFilePath,
                    fileName = finalFileName,
                    mimeType = mimeType
                )

                if (finalPublicUri != null) {
                    Log.i(TAG, "File successfully saved to public Downloads: $finalPublicUri")
                    finalStatus = "Download completed" // Set success status
                    finalMessage = "File saved to Downloads folder."
                } else {
                    throw Exception("Failed to save file to public Downloads folder.")
                }
            } else {
                throw Exception("Final temporary file path is missing or file does not exist after download/merge.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during download/merge/save process: ${e.message}", e)
            finalStatus = "Download failed"
            finalMessage = e.message ?: "An unexpected error occurred."
            finalPublicUri?.let { uri ->
                try {
                    contentResolver.delete(uri, null, null)
                    Log.w(TAG, "Cleaned up incomplete MediaStore entry: $uri")
                } catch (deleteEx: Exception) {
                    Log.e(TAG, "Failed to clean up MediaStore entry: $uri", deleteEx)
                }
            }
        } finally {
            // Stop progress monitoring
            progressJob?.cancel()

            Log.d(TAG, "Download process finished with status: $finalStatus, Message: $finalMessage")

            // --- Cleanup Temporary Files ---
            videoFilePath?.let { try { File(it).delete() } catch (ex: Exception) { Log.w(TAG, "Failed to delete temp video file: $it") } }
            audioFilePath?.let { try { File(it).delete() } catch (ex: Exception) { Log.w(TAG, "Failed to delete temp audio file: $it") } }
            if (finalPublicUri != null || videoFilePath == null) {
                tempFinalFilePath?.let {
                    try {
                        if (File(it).exists()) {
                            File(it).delete()
                            Log.d(TAG, "Deleted final temp file: $it")
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to delete final temp file: $it", ex)
                    }
                }
            }

            // --- Final Notification ---
            val notificationText = "$finalStatus: '$title' ($formatDescription)"
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Switching to Main thread for final notification handling.")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
                    if (channel == null) { Log.e(TAG, "!!! Notification channel $CHANNEL_ID is NULL !!!") }
                    else if (channel.importance == NotificationManager.IMPORTANCE_NONE) { Log.e(TAG, "!!! Notification channel $CHANNEL_ID is BLOCKED !!!") }
                    else { Log.d(TAG, "Notification channel $CHANNEL_ID exists and is enabled (Importance: ${channel.importance})") }
                }

                Log.d(TAG, "Cancelling ongoing notification ID: $NOTIFICATION_ID")
                notificationManager.cancel(NOTIFICATION_ID)

                // --- Create PendingIntent for successful download ---
                val pendingIntent: PendingIntent? = if (finalStatus == "Download completed") {
                    val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    Log.d(TAG, "Creating PendingIntent for ACTION_VIEW_DOWNLOADS")
                    PendingIntent.getActivity(
                        this@DownloadService,
                        PENDING_INTENT_REQUEST_CODE,
                        downloadsIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                } else {
                    null
                }

                Log.d(TAG, "Creating final status notification: Text='$notificationText'")
                val finalNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(notificationText)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(pendingIntent)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
                    .build()

                val uniqueFinalNotificationId = FINAL_NOTIFICATION_BASE_ID + System.currentTimeMillis().toInt()
                Log.d(TAG, "Posting final notification with ID: $uniqueFinalNotificationId")
                notificationManager.notify(uniqueFinalNotificationId, finalNotification)
            }
            Log.d(TAG, "Final notification posted.")
        }
    }

    // Start monitoring progress by polling the Python module
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

                        // Format speed if available
                        val speedText = if (progress.speed != null && progress.speed > 0) {
                            val speedMbps = progress.speed / (1024 * 1024)
                            String.format("%.1f MB/s", speedMbps)
                        } else ""

                        // Format ETA if available
                        val etaText = if (progress.eta != null && progress.eta > 0) {
                            val minutes = progress.eta / 60
                            val seconds = progress.eta % 60
                            if (minutes > 0) {
                                "$minutes min $seconds sec left"
                            } else {
                                "$seconds sec left"
                            }
                        } else ""

                        // Build status text based on current phase
                        val phaseText = when (phase) {
                            "video" -> "Downloading video"
                            "audio" -> "Downloading audio"
                            "merging" -> "Merging files"
                            "saving" -> "Saving to Downloads"
                            else -> "Downloading"
                        }

                        // Build status text
                        val statusText = if (speedText.isNotEmpty() && etaText.isNotEmpty()) {
                            "$phaseText... $speedText, $etaText"
                        } else if (speedText.isNotEmpty()) {
                            "$phaseText... $speedText"
                        } else {
                            "$phaseText..."
                        }

                        // Update notification with progress
                        updateNotification(title, statusText, progressPercent, isOngoing = true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in progress monitoring: ${e.message}", e)
                }

                delay(500) // Poll every 500ms
            }
        }
    }

    // --- Helper function to get MIME type ---
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

    // --- Helper function to save file using MediaStore ---
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
            Log.e(TAG, "Source file does not exist: $tempFilePath")
            return null
        }

        try {
            outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

            if (outputUri == null) {
                Log.e(TAG, "MediaStore insert failed, returned null URI for Downloads collection")
                return null
            }

            outputStream = contentResolver.openOutputStream(outputUri)
            inputStream = sourceFile.inputStream()

            if (outputStream == null) {
                Log.e(TAG, "Failed to open output stream for MediaStore URI: $outputUri")
                contentResolver.delete(outputUri, null, null) // Clean up entry
                throw IOException("Cannot open output stream for MediaStore")
            }

            Log.d(TAG, "Starting copy from $tempFilePath to $outputUri")
            val copiedBytes = inputStream.copyTo(outputStream)
            Log.d(TAG, "Finished copying $copiedBytes bytes")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(outputUri, values, null, null)
            }

            return outputUri

        } catch (e: Exception) {
            Log.e(TAG, "Error saving file to MediaStore: ${e.message}", e)
            outputUri?.let { uri ->
                try {
                    contentResolver.delete(uri, null, null)
                    Log.w(TAG, "Deleted incomplete MediaStore entry due to error: $uri")
                } catch (deleteEx: Exception) {
                    Log.e(TAG, "Failed to delete incomplete MediaStore entry: $uri", deleteEx)
                }
            }
            return null
        } finally {
            try { inputStream?.close() } catch (e: IOException) { Log.e(TAG, "Failed to close input stream", e) }
            try { outputStream?.close() } catch (e: IOException) { Log.e(TAG, "Failed to close output stream", e) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notifications for YT-DLP downloads"
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created/updated with DEFAULT importance")
        }
    }

    private fun createNotification(
        title: String,
        text: String,
        progress: Int = 0,
        isIndeterminate: Boolean = false,
        isOngoing: Boolean = true
    ): Notification {
        Log.d(TAG, "Creating notification: Title='$title', Text='$text', Progress=$progress, Ongoing=$isOngoing")
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val ongoingPendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(ongoingPendingIntent)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setPriority(if (isOngoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)

        // Add progress bar if this is a progress notification
        if (isOngoing) {
            if (isIndeterminate) {
                builder.setProgress(0, 0, true) // Indeterminate progress bar
            } else {
                builder.setProgress(100, progress, false) // Determinate progress bar
            }
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int = 0,
        isIndeterminate: Boolean = false,
        isOngoing: Boolean = true
    ) {
        Log.d(TAG, "Updating notification: Title='$title', Text='$text', Progress=$progress, Ongoing=$isOngoing")
        val notification = createNotification(title, text, progress, isIndeterminate, isOngoing)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        progressJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.kira.ytdlp.extra.URL"
        const val EXTRA_VIDEO_FORMAT_ID = "com.kira.ytdlp.extra.VIDEO_FORMAT_ID"
        const val EXTRA_AUDIO_FORMAT_ID = "com.kira.ytdlp.extra.AUDIO_FORMAT_ID"
        const val EXTRA_TITLE = "com.kira.ytdlp.extra.TITLE"
        const val EXTRA_FORMAT_DESC = "com.kira.ytdlp.extra.FORMAT_DESC"

        fun createStartIntent(
            context: Context,
            url: String,
            videoFormatId: String,
            audioFormatId: String?,
            title: String,
            formatDescription: String?
        ): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VIDEO_FORMAT_ID, videoFormatId)
                putExtra(EXTRA_AUDIO_FORMAT_ID, audioFormatId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT_DESC, formatDescription ?: videoFormatId)
            }
        }
    }
}
