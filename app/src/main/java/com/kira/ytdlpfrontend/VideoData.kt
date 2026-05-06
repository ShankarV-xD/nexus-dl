package com.kira.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VideoInfo(
    val title: String? = null,
    val thumbnail: String? = null,
    @SerialName("duration_string")
    val durationString: String? = null,
    val formats: List<VideoFormat> = emptyList(),
    val error: String? = null
)

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

val jsonParser = Json { ignoreUnknownKeys = true }
