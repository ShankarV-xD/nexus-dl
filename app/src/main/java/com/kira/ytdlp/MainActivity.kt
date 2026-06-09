package com.kira.ytdlp

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kira.ytdlp.ui.components.VideoInfoShimmerLoading

private const val TAG = "MainActivity"

val DarkBackgroundStart = Color(0xFF0A1017)
val DarkBackgroundEnd = Color(0xFF1A2433)
val DarkPrimary = Color(0xFF4ECBFF)
val DarkOnPrimary = Color(0xFF003547)
val DarkPrimaryContainer = Color(0xFF00536E)
val DarkOnPrimaryContainer = Color(0xFFD0EEFF)
val DarkSurfaceVariant = Color(0xFF30383E)
val DarkOnSurfaceVariant = Color(0xFFCBD1D8)
val DarkSurface = Color(0xFF101620)
val DarkOnSurface = Color(0xFFECECF0)
val DarkOutline = Color(0xFF8B9198)
val DarkError = Color(0xFFCF6679)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnError = Color(0xFF690005)

val darkGradientBrush = Brush.verticalGradient(
    colors = listOf(DarkBackgroundStart, DarkBackgroundEnd)
)

private val AppDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackgroundStart,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    onError = DarkOnError,
)

/**
 * Main activity for NexusDL video downloader app.
 *
 * This is the entry point of the application. It:
 * - Handles notification permission requests (Android 13+)
 * - Sets up edge-to-edge display with transparent status bar
 * - Hosts the main Jetpack Compose UI with a dark theme
 */
class MainActivity : ComponentActivity() {

    /**
     * Launcher for requesting POST_NOTIFICATIONS permission.
     * Shows appropriate feedback to the user based on the result.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                if (BuildConfig.DEBUG) Log.i(TAG, "POST_NOTIFICATIONS permission granted.")
                Toast.makeText(this, getString(R.string.toast_notification_permission_granted), Toast.LENGTH_SHORT).show()
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                Toast.makeText(this, getString(R.string.toast_notification_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    /**
     * Checks and requests notification permission on Android 13+ (API 33+).
     *
     * Shows permission rationale if needed, otherwise directly requests permission.
     * No-op on Android versions below 13.
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Notification permission already granted.")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Showing rationale and requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            if (BuildConfig.DEBUG) Log.d(TAG, "Notification permission not required for this Android version.")
        }
    }

    /**
     * Called when the activity is first created.
     *
     * Sets up:
     * - Notification permission handling
     * - Edge-to-edge display
     * - Dark theme with custom color scheme
     * - Main Compose UI content
     *
     * @param savedInstanceState Previously saved state (not used)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) Log.d(TAG, "MainActivity onCreate")
        checkAndRequestNotificationPermission()  // called exactly once

        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = AppDarkColorScheme) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(darkGradientBrush),
                    color = Color.Transparent
                ) {
                    YtdlpInfoScreen()
                }
            }
        }
    }
}

/**
 * Parses the height (vertical resolution) from a resolution string.
 *
 * @param resolution Resolution string in format "WIDTHxHEIGHT" (e.g., "1920x1080")
 * @return The height as an integer, or null if parsing fails
 */
fun parseHeight(resolution: String?): Int? {
    return resolution?.substringAfter('x', "")?.toIntOrNull()
}

/**
 * Comparator for sorting video formats by quality.
 *
 * Sorts by:
 * 1. Video formats before audio-only formats
 * 2. For video: Height (descending), then FPS (descending), then bitrate (descending)
 * 3. For audio: Audio bitrate (descending), then total bitrate (descending)
 * 4. Format ID (ascending) as tiebreaker
 */
val formatComparator = Comparator<VideoFormat> { f1, f2 ->
    val isAudio1 = f1.vcodec == "none" || f1.resolution == null
    val isAudio2 = f2.vcodec == "none" || f2.resolution == null

    if (!isAudio1 && isAudio2) return@Comparator -1
    if (isAudio1 && !isAudio2) return@Comparator 1

    if (!isAudio1) {
        val height1 = parseHeight(f1.resolution) ?: 0
        val height2 = parseHeight(f2.resolution) ?: 0
        val heightCompare = height2.compareTo(height1)
        if (heightCompare != 0) return@Comparator heightCompare
        val fpsCompare = (f2.fps ?: 0.0).compareTo(f1.fps ?: 0.0)
        if (fpsCompare != 0) return@Comparator fpsCompare
        val tbrCompare = (f2.tbr ?: 0.0).compareTo(f1.tbr ?: 0.0)
        if (tbrCompare != 0) return@Comparator tbrCompare
    } else {
        val abrCompare = (f2.abr ?: 0.0).compareTo(f1.abr ?: 0.0)
        if (abrCompare != 0) return@Comparator abrCompare
        val tbrCompare = (f2.tbr ?: 0.0).compareTo(f1.tbr ?: 0.0)
        if (tbrCompare != 0) return@Comparator tbrCompare
    }
    (f1.formatId ?: "").compareTo(f2.formatId ?: "")
}

@Composable
fun YtdlpInfoScreen(vm: MainViewModel = viewModel()) {
    val url by vm.url.collectAsState()
    val videoInfo by vm.videoInfo.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val selectedFormatId by vm.selectedFormatId.collectAsState()
    val hasSearched by vm.hasSearched.collectAsState()
    val showVideoFormats by vm.showVideoFormats.collectAsState()
    val selectedSubtitle by vm.selectedSubtitle.collectAsState()
    val selectedAudioFormatId by vm.selectedAudioFormatId.collectAsState()
    val activeDownload by DownloadService.activeDownload.collectAsState()

    val context = LocalContext.current

    // Intercept system back press: go to welcome screen instead of closing the app
    BackHandler(enabled = hasSearched || videoInfo != null) {
        vm.clearSearch()
    }

    val systemPadding = WindowInsets.systemBars.asPaddingValues()

    val videoFormats by remember(videoInfo) {
        derivedStateOf {
            videoInfo?.formats
                ?.filter { format ->
                    val isVideo = format.vcodec != null && format.vcodec != "none"
                    val isNotStoryboard = format.extension != "mhtml" &&
                            format.formatNote?.contains("storyboard", ignoreCase = true) != true
                    !format.formatId.isNullOrBlank() && isVideo && isNotStoryboard
                }
                ?.distinctBy { it.formatId }
                ?.sortedWith(formatComparator) ?: emptyList()
        }
    }

    val audioFormats by remember(videoInfo) {
        derivedStateOf {
            videoInfo?.formats
                ?.filter { format ->
                    val isAudioOnly = format.acodec != null && format.acodec != "none" &&
                            (format.vcodec == "none" || format.vcodec == null)
                    val isNotStoryboard = format.extension != "mhtml" &&
                            format.formatNote?.contains("storyboard", ignoreCase = true) != true
                    !format.formatId.isNullOrBlank() && isAudioOnly && isNotStoryboard
                }
                ?.distinctBy { it.formatId }
                ?.sortedWith(formatComparator) ?: emptyList()
        }
    }

    val currentFormats = if (showVideoFormats) videoFormats else audioFormats

    val subtitleOptions by remember(videoInfo) {
        derivedStateOf { videoInfo?.subtitles ?: emptyList() }
    }

    var subtitleExpanded by remember { mutableStateOf(false) }
    var audioPickerExpanded by remember { mutableStateOf(false) }

    val selectedFormatObject by remember(selectedFormatId, videoFormats, audioFormats) {
        derivedStateOf {
            videoFormats.find { it.formatId == selectedFormatId }
                ?: audioFormats.find { it.formatId == selectedFormatId }
        }
    }

    val isAudioOnlySelection = selectedFormatObject?.let {
        it.vcodec == "none" || it.vcodec == null
    } ?: false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkGradientBrush)
    ) {
        // Welcome screen (before any search)
        AnimatedVisibility(
            visible = !hasSearched && videoInfo == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            WelcomeScreen(
                urlValue = url,
                onUrlChange = vm::setUrl,
                onFetch = {
                    if (url.isNotBlank()) {
                        vm.fetchVideoInfo()
                    } else {
                        Toast.makeText(context, context.getString(R.string.toast_enter_url), Toast.LENGTH_SHORT).show()
                    }
                },
                systemPadding = systemPadding
            )
        }

        // Main content after search is initiated
        AnimatedVisibility(
            visible = hasSearched || videoInfo != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = systemPadding.calculateTopPadding() + 16.dp,
                        bottom = systemPadding.calculateBottomPadding() + 16.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SearchBar(
                    url = url,
                    onUrlChange = vm::setUrl,
                    onSearch = {
                        if (url.isNotBlank() && !isLoading) {
                            vm.fetchVideoInfo()
                        } else if (url.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.toast_enter_url), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onNewSearch = vm::clearSearch,
                    onClear = { vm.setUrl("") },
                    isLoading = isLoading,
                    showRefresh = videoInfo != null
                )

                // Content area
                Box(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // Video info card (thumbnail + title + channel + duration)
                        AnimatedVisibility(
                            visible = videoInfo != null && videoInfo?.error == null &&
                                    (videoInfo?.thumbnail != null || videoInfo?.title != null),
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (videoInfo?.thumbnail != null) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                        modifier = Modifier.width(120.dp)
                                    ) {
                                        AsyncImage(
                                            model = videoInfo?.thumbnail,
                                            contentDescription = "Video Thumbnail",
                                            modifier = Modifier.aspectRatio(16f / 9f),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (videoInfo?.title != null) {
                                        Text(
                                            text = videoInfo?.title ?: "",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    val channelName = videoInfo?.channel ?: videoInfo?.uploader
                                    val duration = videoInfo?.durationString

                                    if (channelName != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Person,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = channelName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (duration != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = duration,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Loading indicator with shimmer effect
                        AnimatedVisibility(
                            visible = isLoading && videoInfo == null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            VideoInfoShimmerLoading()
                        }

                        // Error state card
                        AnimatedVisibility(
                            visible = !isLoading && videoInfo?.error != null,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ErrorOutline,
                                            contentDescription = "Error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.error_failed_to_load),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text(
                                        text = videoInfo?.error ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(
                                        onClick = {
                                            HapticFeedback.medium(context)
                                            vm.fetchVideoInfo()
                                        },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.label_try_again), fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        // Format tabs + list
                        AnimatedVisibility(
                            visible = !isLoading && (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()),
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            HapticFeedback.light(context)
                                            vm.setShowVideoFormats(true)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (showVideoFormats)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (showVideoFormats)
                                                MaterialTheme.colorScheme.onPrimary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Videocam,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.label_video) + " (${videoFormats.size})", fontWeight = FontWeight.SemiBold)
                                    }

                                    Button(
                                        onClick = {
                                            HapticFeedback.light(context)
                                            vm.setShowVideoFormats(false)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (!showVideoFormats)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (!showVideoFormats)
                                                MaterialTheme.colorScheme.onPrimary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Headphones,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.label_audio) + " (${audioFormats.size})", fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                // Subtitle selector — compact single-line row, always visible
                                if (subtitleOptions.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "CC",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f)) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticFeedback.light(context)
                                                    subtitleExpanded = true
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = (selectedSubtitle?.langName ?: "Off") + "  ▾",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = subtitleExpanded,
                                                onDismissRequest = { subtitleExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Off — no subtitles") },
                                                    onClick = {
                                                        HapticFeedback.light(context)
                                                        vm.setSelectedSubtitle(null)
                                                        subtitleExpanded = false
                                                    }
                                                )
                                                subtitleOptions.forEach { option ->
                                                    DropdownMenuItem(
                                                        text = { Text(option.langName) },
                                                        onClick = {
                                                            HapticFeedback.light(context)
                                                            vm.setSelectedSubtitle(option)
                                                            subtitleExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        if (selectedSubtitle != null &&
                                            activeDownload?.isActive != true &&
                                            activeDownload?.isComplete != true
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticFeedback.heavy(context)
                                                    val intent = DownloadService.createSubtitleOnlyIntent(
                                                        context = context,
                                                        url = url,
                                                        title = videoInfo?.title ?: "Subtitle",
                                                        subtitleLangCode = selectedSubtitle!!.langCode,
                                                        subtitleIsAuto = selectedSubtitle!!.isAuto
                                                    )
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        context.startForegroundService(intent)
                                                    } else {
                                                        context.startService(intent)
                                                    }
                                                },
                                                modifier = Modifier.height(36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.primary
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Download,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(".srt", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }

                                // Audio track picker — only when a video-only format is selected
                                if (showVideoFormats && isAudioOnlySelection == false &&
                                    selectedFormatObject != null &&
                                    (selectedFormatObject!!.acodec == null || selectedFormatObject!!.acodec == "none") &&
                                    audioFormats.isNotEmpty()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Headphones,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .padding(horizontal = 5.dp, vertical = 4.dp)
                                                    .size(12.dp)
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f)) {
                                            OutlinedButton(
                                                onClick = {
                                                    HapticFeedback.light(context)
                                                    audioPickerExpanded = true
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                val selectedAudioFmt = audioFormats.find { it.formatId == selectedAudioFormatId }
                                                val label = if (selectedAudioFmt != null) {
                                                    buildString {
                                                        append(selectedAudioFmt.formatNote ?: "Audio")
                                                        selectedAudioFmt.abr?.let { append(" · ${it.toInt()}k") }
                                                        selectedAudioFmt.extension?.let { append(" · ${it.uppercase()}") }
                                                    }
                                                } else "Auto (best quality)"
                                                Text(
                                                    text = "$label  ▾",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = audioPickerExpanded,
                                                onDismissRequest = { audioPickerExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Auto — best quality") },
                                                    onClick = {
                                                        HapticFeedback.light(context)
                                                        vm.setSelectedAudioFormatId(null)
                                                        audioPickerExpanded = false
                                                    }
                                                )
                                                audioFormats.forEach { fmt ->
                                                    val fmtLabel = buildString {
                                                        append(fmt.formatNote ?: "Audio")
                                                        fmt.abr?.let { append(" · ${it.toInt()}k") }
                                                        fmt.extension?.let { append(" · ${it.uppercase()}") }
                                                    }
                                                    DropdownMenuItem(
                                                        text = { Text(fmtLabel) },
                                                        onClick = {
                                                            HapticFeedback.light(context)
                                                            vm.setSelectedAudioFormatId(fmt.formatId)
                                                            audioPickerExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Info: explain auto-merge for video-only formats
                                if (showVideoFormats && videoFormats.any { it.acodec == null || it.acodec == "none" }) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Info,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "High quality formats are video-only. Audio will be automatically downloaded and merged.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                // Hint: video tab selected but only audio available
                                if (showVideoFormats && videoFormats.isEmpty() && audioFormats.isNotEmpty()) {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                        ),
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "No video formats available. Switch to the Audio tab to download.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                if (currentFormats.isEmpty() && !(showVideoFormats && videoFormats.isEmpty() && audioFormats.isNotEmpty())) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No ${if (showVideoFormats) "video" else "audio"} formats available",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else if (currentFormats.isNotEmpty()) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(
                                            currentFormats,
                                            key = { it.formatId ?: it.hashCode() }
                                        ) { format ->
                                            FormatItemCard(
                                                format = format,
                                                isSelected = format.formatId == selectedFormatId,
                                                onClick = {
                                                    HapticFeedback.medium(context)
                                                    vm.toggleFormatSelection(format.formatId)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // In-app download progress card
                AnimatedVisibility(
                    visible = activeDownload?.isActive == true,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    activeDownload?.let { dl ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = dl.statusText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${dl.percent}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { dl.percent / 100f },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                }

                // Download button - only show when no active download and no completed download showing
                val showDownloadButton = !isLoading &&
                        (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()) &&
                        activeDownload?.isActive != true &&
                        activeDownload?.isComplete != true

                AnimatedVisibility(
                    visible = showDownloadButton,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    val context2 = LocalContext.current
                    Button(
                        onClick = {
                            HapticFeedback.heavy(context2)
                            
                            val selectedObj = videoFormats.find { it.formatId == selectedFormatId }
                                ?: audioFormats.find { it.formatId == selectedFormatId }

                            if (selectedObj?.formatId != null) {
                                val videoId = selectedObj.formatId

                                var audioIdToDownload: String? = null
                                if (selectedObj.acodec == null || selectedObj.acodec == "none") {
                                    // Use explicitly picked audio format, or fall back to best available
                                    audioIdToDownload = selectedAudioFormatId
                                        ?: videoInfo?.formats
                                            ?.filter { it.acodec != null && it.acodec != "none" && it.vcodec == "none" }
                                            ?.sortedWith(compareByDescending { it.abr ?: 0.0 })
                                            ?.firstOrNull()?.formatId
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Video format $videoId needs audio. Chosen audio: $audioIdToDownload")
                                    if (audioIdToDownload == null) {
                                        Toast.makeText(context2, context2.getString(R.string.toast_no_audio_format), Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                }

                                val formatDescription = selectedObj.formatNote
                                    ?: selectedObj.resolution
                                    ?: videoId

                                val serviceIntent = DownloadService.createStartIntent(
                                    context = context2,
                                    url = url,
                                    videoFormatId = videoId,
                                    audioFormatId = audioIdToDownload,
                                    title = videoInfo?.title ?: "Video Download",
                                    formatDescription = formatDescription,
                                    subtitleLangCode = selectedSubtitle?.langCode,
                                    subtitleIsAuto = selectedSubtitle?.isAuto ?: false
                                )

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context2.startForegroundService(serviceIntent)
                                } else {
                                    context2.startService(serviceIntent)
                                }
                            } else {
                                Toast.makeText(context2, context2.getString(R.string.toast_no_format_selected), Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = selectedFormatId != null && !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 8.dp
                        )
                    ) {
                        Icon(
                            imageVector = if (isAudioOnlySelection) Icons.Filled.Headphones else Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(
                            if (isAudioOnlySelection) "Download Audio" else "Download Selected Format",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Download Complete Card - shows when download finishes
                AnimatedVisibility(
                    visible = activeDownload?.isComplete == true,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    activeDownload?.let { dl ->
                        val context = LocalContext.current
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (dl.isSuccess)
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f)
                                else
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = if (dl.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                        contentDescription = null,
                                        tint = if (dl.isSuccess)
                                            MaterialTheme.colorScheme.secondary
                                        else
                                            MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (dl.isSuccess) "Download Complete!" else "Download Failed",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (dl.isSuccess)
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else
                                                MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = dl.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (dl.isSuccess)
                                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                            else
                                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                // Haptic feedback when completion card appears
                                LaunchedEffect(dl.isComplete) {
                                    if (dl.isComplete) {
                                        if (dl.isSuccess) {
                                            HapticFeedback.success(context)
                                        } else {
                                            HapticFeedback.error(context)
                                        }
                                    }
                                }

                                if (dl.isSuccess && dl.fileUri != null) {
                                    Button(
                                        onClick = {
                                            HapticFeedback.medium(context)
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(dl.fileUri, "video/*")
                                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                // Fallback to opening downloads folder
                                                val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(downloadsIntent)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.label_play_video), fontWeight = FontWeight.SemiBold)
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    // Share button
                                    OutlinedButton(
                                        onClick = {
                                            HapticFeedback.medium(context)
                                            val mimeType = context.contentResolver.getType(dl.fileUri) ?: "video/*"
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = mimeType
                                                putExtra(Intent.EXTRA_STREAM, dl.fileUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share video"))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Filled.Share,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Share", fontWeight = FontWeight.SemiBold)
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    OutlinedButton(
                                        onClick = {
                                            HapticFeedback.medium(context)
                                            val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(downloadsIntent)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.label_open_downloads), fontWeight = FontWeight.SemiBold)
                                    }
                                } else if (!dl.isSuccess) {
                                    // Show error message for failed downloads
                                    Text(
                                        text = dl.errorMessage ?: "Unknown error occurred",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                // Dismiss button for all cases
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        HapticFeedback.light(context)
                                        vm.clearCompletedDownload()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (dl.isSuccess)
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        else
                                            MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text(stringResource(R.string.label_dismiss), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(
    urlValue: String,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    systemPadding: PaddingValues
) {
    val context = LocalContext.current

    val logoScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logo_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = systemPadding.calculateTopPadding() + 16.dp,
                bottom = systemPadding.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = CircleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier
                .size(120.dp)
                .scale(logoScale)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "App Logo",
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Nexus DL",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Video Downloader",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Text(
            text = "Paste a link and pick your quality - video, audio, or both.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Works with",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        listOf(
            listOf("YouTube", "Instagram", "TikTok", "Twitter/X"),
            listOf("Reddit", "Twitch", "Vimeo", "Facebook")
        ).forEach { rowPlatforms ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowPlatforms.forEach { platform ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = platform,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Text(
            text = "& 1000+ more sites via yt-dlp",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = urlValue,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.label_video_url)) },
            placeholder = { Text("https://youtu.be/...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFetch() }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (urlValue.isEmpty()) {
                        // Paste-from-clipboard button
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).coerceToText(context).toString()
                                if (text.isNotBlank()) onUrlChange(text)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = "Paste from clipboard",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onFetch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        )

        Button(
            onClick = onFetch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(58.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.label_fetch_video_info), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "by Shankar V",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SearchBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onSearch: () -> Unit,
    onNewSearch: () -> Unit,
    onClear: () -> Unit,
    isLoading: Boolean,
    showRefresh: Boolean
) {
    val context = LocalContext.current
    var initialUrl by remember { mutableStateOf(url) }
    var urlModified by remember(url) { mutableStateOf(url != initialUrl && showRefresh) }

    LaunchedEffect(showRefresh) {
        if (showRefresh) {
            initialUrl = url
            urlModified = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            )
            .padding(8.dp)
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = {
                onUrlChange(it)
                if (showRefresh) urlModified = it != initialUrl
            },
            label = { Text(stringResource(R.string.label_video_url)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (url.isNotEmpty()) {
                        // Copy URL button
                        IconButton(onClick = {
                            HapticFeedback.light(context)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Video URL", url)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = "Copy URL",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            onClear()
                            if (showRefresh) urlModified = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Paste button when field is empty
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).coerceToText(context).toString()
                                if (text.isNotBlank()) onUrlChange(text)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = "Paste from clipboard",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )

        Button(
            onClick = {
                if (!showRefresh || urlModified) onSearch() else onNewSearch()
            },
            enabled = !isLoading,
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = if (!showRefresh || urlModified) Icons.Filled.Search else Icons.Filled.Refresh,
                    contentDescription = if (!showRefresh || urlModified) "Search" else "New Search",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun FormatItemCard(
    format: VideoFormat,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isVideoOnly = format.vcodec != null && format.vcodec != "none" &&
            (format.acodec == null || format.acodec == "none")
    val isAudioOnly = (format.vcodec == "none" || format.vcodec == null) &&
            format.acodec != null && format.acodec != "none"

    val cardColors = if (isSelected) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    }

    val textColor = if (isSelected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface

    val elevation by animateDpAsState(
        targetValue = if (isSelected) 8.dp else 2.dp,
        label = "card_elevation"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.03f else 1f,
        label = "card_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = format.formatId != null) { onClick() }
            .scale(scale),
        shape = RoundedCornerShape(16.dp),
        colors = cardColors,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isSelected) Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                        )
                    ) else Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type icon
                Icon(
                    imageVector = if (isAudioOnly) Icons.Filled.Headphones else Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).padding(end = 0.dp)
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Title row: format note / resolution + type badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = format.formatNote ?: format.resolution ?: format.formatId ?: "Unknown Format",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Type badge
                        val (badgeText, badgeColor) = when {
                            isVideoOnly -> "+ Audio will be merged" to MaterialTheme.colorScheme.primary
                            isAudioOnly -> "Audio Only" to MaterialTheme.colorScheme.tertiary
                            else -> "Video + Audio" to MaterialTheme.colorScheme.secondary
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected)
                                badgeColor.copy(alpha = 0.25f)
                            else
                                badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) badgeColor
                                else badgeColor.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Details row: size | codecs | ext | fps
                    val context = LocalContext.current
                    val detailParts = mutableListOf<String>()

                    val size = format.filesize ?: format.filesizeApprox
                    if (size != null) {
                        detailParts.add(android.text.format.Formatter.formatShortFileSize(context, size))
                    } else {
                        detailParts.add("Size unknown")
                    }

                    format.vcodec?.takeIf { it != "none" }?.let {
                        detailParts.add("V: ${it.substringBefore('.')}")
                    }
                    format.acodec?.takeIf { it != "none" }?.let {
                        detailParts.add("A: ${it.substringBefore('.')}")
                    }
                    format.extension?.let { detailParts.add(it.uppercase()) }
                    format.fps?.let { detailParts.add("${"%.0f".format(it)}fps") }

                    Text(
                        text = detailParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isSelected) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}


@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DefaultPreview() {
    MaterialTheme(colorScheme = AppDarkColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize().background(darkGradientBrush),
            color = Color.Transparent
        ) {
            YtdlpInfoScreen()
        }
    }
}

@Preview(showBackground = true, widthDp = 350)
@Composable
fun FormatItemCardPreview() {
    MaterialTheme(colorScheme = AppDarkColorScheme) {
        Surface(color = DarkBackgroundStart, modifier = Modifier.padding(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatItemCard(
                    format = VideoFormat(
                        formatId = "22", extension = "mp4", resolution = "1280x720",
                        fps = 30.0, filesizeApprox = 50000000L,
                        vcodec = "avc1.64001F", acodec = "none", formatNote = "720p"
                    ),
                    isSelected = false,
                    onClick = {}
                )
                FormatItemCard(
                    format = VideoFormat(
                        formatId = "140", extension = "m4a", resolution = null,
                        fps = null, filesizeApprox = 4000000L,
                        vcodec = "none", acodec = "mp4a.40.2", formatNote = "Audio 128k"
                    ),
                    isSelected = true,
                    onClick = {}
                )
                FormatItemCard(
                    format = VideoFormat(
                        formatId = "249", extension = "webm", resolution = null,
                        fps = null, filesize = null, filesizeApprox = null,
                        vcodec = "none", acodec = "opus", formatNote = "Audio 50k"
                    ),
                    isSelected = false,
                    onClick = {}
                )
            }
        }
    }
}
