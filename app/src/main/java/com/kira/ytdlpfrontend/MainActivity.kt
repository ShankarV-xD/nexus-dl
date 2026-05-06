package com.kira.ytdlp

import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest


import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.scale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import coil.compose.AsyncImage
import com.chaquo.python.PyException
import com.chaquo.python.Python

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"

val DarkBackgroundStart = Color(0xFF0A1017) // Deeper, richer dark blue
val DarkBackgroundEnd = Color(0xFF1A2433) // Slightly lighter shade for gradient
val DarkPrimary = Color(0xFF4ECBFF) // Brighter, more vibrant blue
val DarkOnPrimary = Color(0xFF003547)
val DarkPrimaryContainer = Color(0xFF00536E)
val DarkOnPrimaryContainer = Color(0xFFD0EEFF)
val DarkSurfaceVariant = Color(0xFF30383E)
val DarkOnSurfaceVariant = Color(0xFFCBD1D8)
val DarkSurface = Color(0xFF101620)
val DarkOnSurface = Color(0xFFECECF0)
val DarkOutline = Color(0xFF8B9198)

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
)

class MainActivity : ComponentActivity() {
    // --- Permission Handling Code ---
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted.")
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                Toast.makeText(this, "Notification permission denied. Downloads will not show status.", Toast.LENGTH_LONG).show()
            }
        }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.i(TAG, "Showing rationale and requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.i(TAG, "Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d(TAG, "Notification permission not required for this Android version.")
        }
    }
    // --- End Permission Handling ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        checkAndRequestNotificationPermission()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT

        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        checkAndRequestNotificationPermission()

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


fun parseHeight(resolution: String?): Int? {
    return resolution?.substringAfter('x', "")?.toIntOrNull()
}

// --- Comparator for sorting VideoFormat objects ---
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

// --- Main UI Screen ---
@Composable
fun YtdlpInfoScreen() {
    var url by remember { mutableStateOf("") }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var statusText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFormatId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var showVideoFormats by remember { mutableStateOf(true) }

    var hasSearched by remember { mutableStateOf(false) }

    val systemPadding = WindowInsets.systemBars.asPaddingValues()

    val videoFormats by remember(videoInfo) {
        derivedStateOf {
            videoInfo?.formats
                ?.filter { format ->
                    val isVideo = format.vcodec != null && format.vcodec != "none"
                    val note = format.formatNote?.trim()
                    val hasResolution = !format.resolution.isNullOrBlank()
                    val hasFilesize = format.filesize != null || format.filesizeApprox != null
                    val hasUsefulNote = !note.isNullOrBlank() &&
                            note.contains("storyboard", ignoreCase = true) != true &&
                            note.contains("Default", ignoreCase = true) != true &&
                            note.contains("DRC", ignoreCase = true) != true

                    isVideo &&
                            format.extension != "mhtml" &&
                            note?.contains("storyboard", ignoreCase = true) != true &&
                            note?.contains("Default", ignoreCase = true) != true &&
                            note?.contains("DRC", ignoreCase = true) != true &&
                            !(hasResolution && !hasUsefulNote && !hasFilesize)
                }
                ?.sortedWith(formatComparator) ?: emptyList()
        }
    }

    val audioFormats by remember(videoInfo) {
        derivedStateOf {
            videoInfo?.formats
                ?.filter { format ->
                    val isAudioOnly = format.acodec != null && format.acodec != "none" &&
                            (format.vcodec == "none" || format.vcodec == null)
                    val note = format.formatNote?.trim()

                    isAudioOnly &&
                            format.extension != "mhtml" &&
                            note?.contains("storyboard", ignoreCase = true) != true &&
                            note?.contains("Default", ignoreCase = true) != true &&
                            note?.contains("DRC", ignoreCase = true) != true
                }
                ?.sortedWith(formatComparator) ?: emptyList()
        }
    }


    val currentFormats = if (showVideoFormats) videoFormats else audioFormats

    // Main column containing all UI elements
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkGradientBrush)
    ) {
        AnimatedVisibility(
            visible = !hasSearched && videoInfo == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            WelcomeScreen(
                onUrlEntered = { newUrl ->
                    url = newUrl
                    if (url.isNotBlank()) {
                        hasSearched = true
                        isLoading = true
                        videoInfo = null
                        selectedFormatId = null
                        coroutineScope.launch {
                            val py = Python.getInstance()
                            val module = py.getModule("ytdlp_helper")
                            val result = parseVideoInfoFromPython(module, url)
                            videoInfo = result
                            if (result?.error != null) {
                                statusText = result.error
                            }
                            isLoading = false
                        }
                    } else {
                        Toast.makeText(context, "Please enter a URL", Toast.LENGTH_SHORT).show()
                    }
                },
                systemPadding = systemPadding
            )
        }

        // Main content after search initiated
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
                    onUrlChange = { url = it },
                    onSearch = {
                        if (url.isNotBlank() && !isLoading) {
                            isLoading = true
                            videoInfo = null
                            selectedFormatId = null
                            coroutineScope.launch {
                                val py = Python.getInstance()
                                val module = py.getModule("ytdlp_helper")
                                val result = parseVideoInfoFromPython(module, url)
                                videoInfo = result
                                if (result?.error != null) {
                                    statusText = result.error
                                }
                                isLoading = false
                            }
                        } else if (url.isBlank()) {
                            Toast.makeText(context, "Please enter a URL", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onNewSearch = {
                        url = ""
                        videoInfo = null
                        selectedFormatId = null
                        hasSearched = false
                    },
                    onClear = { url = ""},
                    isLoading = isLoading,
                    showRefresh = videoInfo != null
                )

                // Content Area (Thumbnail, Title, Status, List)
                Box(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedVisibility(
                            visible = videoInfo != null && (videoInfo?.thumbnail != null || videoInfo?.title != null),
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
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

                                if (videoInfo?.title != null) {
                                    Text(
                                        text = videoInfo?.title ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = isLoading && videoInfo == null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        AnimatedVisibility(
                            visible = !isLoading && (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()),
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showVideoFormats = true },
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
                                        Text(
                                            "Video Formats (${videoFormats.size})",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Button(
                                        onClick = { showVideoFormats = false },
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
                                        Text(
                                            "Audio Formats (${audioFormats.size})",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                if (currentFormats.isEmpty()) {
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
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(currentFormats, key = { it.formatId ?: it.hashCode() }) { format ->
                                            FormatItemCard(
                                                format = format,
                                                isSelected = format.formatId == selectedFormatId,
                                                onClick = {
                                                    format.formatId?.let { id ->
                                                        selectedFormatId = if (selectedFormatId == id) null else id
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // End Content Area Box

                AnimatedVisibility(
                    visible = !isLoading && (videoFormats.isNotEmpty() || audioFormats.isNotEmpty()),
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Button(
                        onClick = {
                            val videoFormatToDownloadId = selectedFormatId
                            val titleToDownload = videoInfo?.title
                            val selectedVideoFormatObject = videoFormats.find { it.formatId == videoFormatToDownloadId }
                                ?: audioFormats.find { it.formatId == videoFormatToDownloadId }

                            if (selectedVideoFormatObject?.formatId != null) {
                                val videoId = selectedVideoFormatObject.formatId

                                var audioIdToDownload: String? = null
                                if (selectedVideoFormatObject.acodec == null || selectedVideoFormatObject.acodec == "none") {
                                    audioIdToDownload = videoInfo?.formats
                                        ?.filter { it.acodec != null && it.acodec != "none" && it.vcodec == "none" }
                                        ?.sortedWith(compareByDescending { it.abr ?: 0.0 })
                                        ?.firstOrNull()?.formatId
                                    Log.d(TAG, "Video format $videoId needs audio. Best audio found: $audioIdToDownload")
                                    if (audioIdToDownload == null) {
                                        Toast.makeText(context, "Could not find suitable audio format to merge!", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                } else {
                                    Log.d(TAG, "Selected format $videoId already has audio.")
                                }

                                val formatDescription = selectedVideoFormatObject.formatNote
                                    ?: selectedVideoFormatObject.resolution
                                    ?: videoId

                                Log.i(TAG, "Download button clicked. Video ID: $videoId, Audio ID: $audioIdToDownload, Desc: $formatDescription")
                                coroutineScope.launch {
                                    startDownload(
                                        context = context,
                                        videoUrl = url,
                                        videoFormatId = videoId,
                                        audioFormatId = audioIdToDownload,
                                        videoTitle = titleToDownload,
                                        formatDescription = formatDescription
                                    )
                                }
                            } else {
                                Toast.makeText(context, "No format selected or format invalid!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = selectedFormatId != null && !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp), // Slightly larger button
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
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download Icon",
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(
                            "Download Selected Format",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Welcome Screen Component
@Composable
fun WelcomeScreen(onUrlEntered: (String) -> Unit, systemPadding: PaddingValues) {
    var url by remember { mutableStateOf("") }

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
        // App logo / icon
        Card(
            shape = CircleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier
                .size(120.dp)
                .scale(logoScale)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "App Logo",
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // App name with modern typography
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

        // Description
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Enter a video URL to download in your preferred format",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // URL Input
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Video URL") },
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
            keyboardActions = KeyboardActions(onDone = { onUrlEntered(url) }),
            trailingIcon = {
                IconButton(onClick = { onUrlEntered(url) }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        // Search button
        Button(
            onClick = { onUrlEntered(url) },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 4.dp,
                pressedElevation = 8.dp
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search Icon",
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                "Fetch Video Info",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// Search Bar Component for after initial search
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
                if (showRefresh) {
                    urlModified = it != initialUrl
                }
            },
            label = { Text("Video URL") },
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
                if (url.isNotEmpty()) {
                    IconButton(onClick = {
                        onClear()
                        if (showRefresh) {
                            urlModified = false
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        Button(
            onClick = {
                if (!showRefresh || urlModified) {
                    onSearch()
                } else {
                    onNewSearch()
                }
            },
            enabled = !isLoading,
            modifier = Modifier.height(56.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 2.dp,
                pressedElevation = 4.dp
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!showRefresh || urlModified)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.tertiary
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                val icon = if (!showRefresh || urlModified) {
                    Icons.Filled.Search
                } else {
                    Icons.Filled.Refresh
                }

                val contentDescription = if (!showRefresh || urlModified) {
                    "Search"
                } else {
                    "New Search"
                }

                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

// Format Item Card
@Composable
fun FormatItemCard(
    format: VideoFormat,
    isSelected: Boolean,
    onClick: () -> Unit
) {
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
                    if (isSelected) {
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    }
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = format.formatNote ?: format.resolution ?: format.formatId ?: "Unknown Format",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor
                    )

                    val details = mutableListOf<String>()
                    val size = format.filesize ?: format.filesizeApprox
                    val context = LocalContext.current
                    size?.let { details.add("Size: ${android.text.format.Formatter.formatShortFileSize(context, it)}") }
                    format.vcodec?.takeIf { it != "none" }?.let { details.add("V: ${it.substringBefore('.')}") }
                    format.acodec?.takeIf { it != "none" }?.let { details.add("A: ${it.substringBefore('.')}") }
                    format.extension?.let { details.add("Ext: $it") }
                    format.formatId?.let { details.add("ID: $it") }
                    format.fps?.let { details.add("FPS: ${"%.0f".format(it)}") }

                    if (details.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = details.joinToString(" | "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}


// --- Function to call Python and parse JSON ---
suspend fun parseVideoInfoFromPython(ytdlpHelperModule: com.chaquo.python.PyObject, videoUrl: String): VideoInfo? {
    Log.d(TAG, "Executing parseVideoInfoFromPython on thread: ${Thread.currentThread().name}")
    return withContext(Dispatchers.IO) {
        Log.d(TAG, "Inside IO dispatcher, calling Python on thread: ${Thread.currentThread().name}")
        try {
            val jsonResult = ytdlpHelperModule.callAttr("get_video_info", videoUrl)?.toString()
            if (jsonResult != null) {
                Log.d(TAG, "Received JSON string (length ${jsonResult.length})")
                try {
                    val parsedInfo = jsonParser.decodeFromString<VideoInfo>(jsonResult)
                    if (parsedInfo.error != null) { Log.e(TAG, "Error reported in JSON: ${parsedInfo.error}") }
                    parsedInfo
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parsing failed: ${e.message}", e)
                    VideoInfo(error = "Failed to parse response: ${e.message}")
                }
            } else {
                Log.e(TAG, "Python function returned null")
                VideoInfo(error = "Python function returned null")
            }
        } catch (e: PyException) {
            Log.e(TAG, "Python Exception occurred: ${e.message}", e)
            VideoInfo(error = "Python Error:\n${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Generic Exception occurred: ${e.message}", e)
            VideoInfo(error = "Error calling Python: ${e.message}")
        }
    }
}

// --- Function to initiate the download process ---
suspend fun startDownload(
    context: Context,
    videoUrl: String,
    videoFormatId: String,
    audioFormatId: String?,
    videoTitle: String?,
    formatDescription: String?
) {
    val descToUse = formatDescription ?: videoFormatId
    Log.d(TAG, "startDownload called for Video: $videoFormatId, Audio: $audioFormatId, Desc: $descToUse")

    val serviceIntent = DownloadService.createStartIntent(
        context = context,
        url = videoUrl,
        videoFormatId = videoFormatId,
        audioFormatId = audioFormatId,
        title = videoTitle ?: "Video Download",
        formatDescription = descToUse
    )

    Log.i(TAG, "Starting DownloadService...")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
    } else {
        context.startService(serviceIntent)
    }
    withContext(Dispatchers.Main) { Toast.makeText(context, "Download starting for $descToUse...", Toast.LENGTH_SHORT).show() }
}



// --- Previews ---
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DefaultPreview() {
    MaterialTheme(colorScheme = AppDarkColorScheme) {
        Surface(modifier = Modifier.fillMaxSize().background(darkGradientBrush), color = Color.Transparent) {
            YtdlpInfoScreen()
        }
    }
}

@Preview(showBackground = true, widthDp = 350)
@Composable
fun FormatItemCardPreview() {
    MaterialTheme(colorScheme = AppDarkColorScheme) {
        Surface(color=DarkBackgroundStart, modifier=Modifier.padding(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatItemCard(
                    format = VideoFormat(formatId = "22", extension = "mp4", resolution = "1280x720", fps = 30.00, filesizeApprox = 50000000L, vcodec = "avc1.64001F", acodec = "mp4a.40.2", formatNote = "720p"),
                    isSelected = false,
                    onClick = {}
                )
                FormatItemCard(
                    format = VideoFormat(formatId = "140", extension = "m4a", resolution = null, fps = null, filesizeApprox = 4000000L, vcodec = "none", acodec = "mp4a.40.2", formatNote = "Audio Only (Medium)"),
                    isSelected = true,
                    onClick = {}
                )
                FormatItemCard(
                    format = VideoFormat(formatId = "249", extension = "webm", resolution = null, fps = null, filesizeApprox = 1000000L, vcodec = "none", acodec = "opus", formatNote = "Audio Only (Low)"),
                    isSelected = false,
                    onClick = {}
                )
            }
        }
    }
}
