package com.kira.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Represents video information returned by yt-dlp.
 * 
 * @property title The title of the video
 * @property thumbnail URL to the video thumbnail image
 * @property durationString Human-readable duration (e.g., "2:34")
 * @property uploader Name of the video uploader
 * @property channel Name of the channel (may be same as uploader)
 * @property formats List of available download formats
 * @property error Error message if video info extraction failed
 */
@Serializable
data class SubtitleOption(
    @SerialName("lang_code")
    val langCode: String,
    @SerialName("lang_name")
    val langName: String,
    @SerialName("is_auto")
    val isAuto: Boolean
)

@Serializable
data class VideoInfo(
    val title: String? = null,
    val thumbnail: String? = null,
    @SerialName("duration_string")
    val durationString: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val formats: List<VideoFormat> = emptyList(),
    val subtitles: List<SubtitleOption> = emptyList(),
    val error: String? = null
)

/**
 * Represents a single video/audio format available for download.
 * 
 * @property formatId Unique identifier for this format (e.g., "137", "140")
 * @property extension File extension (e.g., "mp4", "webm")
 * @property resolution Video resolution (e.g., "1920x1080")
 * @property fps Frames per second
 * @property filesize Exact file size in bytes (if available)
 * @property filesizeApprox Approximate file size in bytes
 * @property vcodec Video codec (e.g., "avc1", "vp9", "none" for audio-only)
 * @property acodec Audio codec (e.g., "mp4a", "opus", "none" for video-only)
 * @property formatNote Human-readable format description (e.g., "1080p")
 * @property url Direct download URL for this format
 * @property tbr Total bitrate in kbps
 * @property abr Audio bitrate in kbps
 */
@Serializable
data class VideoFormat(
    @SerialName("format_id")
    val formatId: String? = null,
    @SerialName("ext")
    val extension: String? = null,
    val resolution: String? = null,
    val fps: Double? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx")
    val filesizeApprox: Long? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    @SerialName("format_note")
    val formatNote: String? = null,
    val url: String? = null,
    val tbr: Double? = null,
    val abr: Double? = null
)

/**
 * JSON parser configured to ignore unknown keys.
 * Used for parsing yt-dlp responses.
 */
val jsonParser = Json { ignoreUnknownKeys = true }
