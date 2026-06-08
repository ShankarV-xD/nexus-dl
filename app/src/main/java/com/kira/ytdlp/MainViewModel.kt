package com.kira.ytdlp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VM_TAG = "MainViewModel"

/**
 * ViewModel for the main video download screen.
 * 
 * Manages UI state including:
 * - URL input
 * - Video information loading
 * - Format selection
 * - Loading states
 * 
 * Uses StateFlow for reactive UI updates that survive configuration changes.
 */
class MainViewModel : ViewModel() {

    private val _url = MutableStateFlow("")
    /** The current URL entered by the user */
    val url: StateFlow<String> = _url.asStateFlow()

    private val _videoInfo = MutableStateFlow<VideoInfo?>(null)
    /** Video information including title, thumbnail, and available formats */
    val videoInfo: StateFlow<VideoInfo?> = _videoInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /** Whether video information is currently being loaded */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedFormatId = MutableStateFlow<String?>(null)
    /** ID of the currently selected format for download */
    val selectedFormatId: StateFlow<String?> = _selectedFormatId.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    /** Whether a search has been performed (used to show appropriate UI state) */
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _showVideoFormats = MutableStateFlow(true)
    /** Whether to show video formats (true) or audio formats (false) */
    val showVideoFormats: StateFlow<Boolean> = _showVideoFormats.asStateFlow()

    private val _selectedSubtitle = MutableStateFlow<SubtitleOption?>(null)
    /** Currently selected subtitle option, or null for no subtitles */
    val selectedSubtitle: StateFlow<SubtitleOption?> = _selectedSubtitle.asStateFlow()

    /**
     * Updates the current URL.
     * 
     * @param newUrl The new URL to set
     */
    fun setUrl(newUrl: String) {
        _url.value = newUrl
    }

    /**
     * Toggles between showing video formats and audio formats.
     * 
     * @param show true to show video formats, false to show audio formats
     */
    fun setShowVideoFormats(show: Boolean) {
        _showVideoFormats.value = show
    }

    /**
     * Toggles selection of a format. If the format is already selected,
     * it will be deselected.
     *
     * @param formatId The format ID to toggle, or null to clear selection
     */
    fun toggleFormatSelection(formatId: String?) {
        _selectedFormatId.value = if (_selectedFormatId.value == formatId) null else formatId
    }

    fun setSelectedSubtitle(option: SubtitleOption?) {
        _selectedSubtitle.value = option
    }

    /**
     * Resets all search state to initial values.
     * Clears URL, video info, format selection, and search status.
     */
    fun clearSearch() {
        _url.value = ""
        _videoInfo.value = null
        _selectedFormatId.value = null
        _selectedSubtitle.value = null
        _hasSearched.value = false
        _showVideoFormats.value = true
    }

    /**
     * Clears any completed download state from the download service.
     * Called when starting a new search to ensure clean UI state.
     */
    fun clearCompletedDownload() {
        DownloadService.clearCompletedDownload()
    }

    /**
     * Fetches video information for the current URL.
     * 
     * Performs security validation including:
     * - URL format validation
     * - Domain whitelist validation (only allowed platforms)
     * - Rate limiting to prevent abuse
     * 
     * Then calls the Python yt-dlp helper to extract video information
     * including available formats. Updates [videoInfo] with the result
     * or an error message.
     * 
     * This function is safe to call from the UI thread as it uses
     * coroutines to perform the network operation on a background thread.
     */
    fun fetchVideoInfo() {
        val currentUrl = _url.value.trim()
        if (currentUrl.isBlank() || _isLoading.value) return

        // Basic URL format validation
        if (!isValidUrl(currentUrl)) {
            _videoInfo.value = VideoInfo(error = "Invalid URL. Please enter a valid URL starting with http:// or https://")
            _hasSearched.value = true
            return
        }

        // Security: Validate against domain whitelist
        val validationResult = SecurityUtils.validateUrl(currentUrl)
        when (validationResult) {
            is SecurityUtils.ValidationResult.Blocked -> {
                if (BuildConfig.DEBUG) Log.w(VM_TAG, "URL blocked by security policy: ${validationResult.reason}")
                _videoInfo.value = VideoInfo(error = validationResult.reason)
                _hasSearched.value = true
                return
            }
            is SecurityUtils.ValidationResult.Invalid -> {
                _videoInfo.value = VideoInfo(error = validationResult.reason)
                _hasSearched.value = true
                return
            }
            SecurityUtils.ValidationResult.Valid -> {
                // Continue with fetch
            }
        }

        _isLoading.value = true
        _videoInfo.value = null
        _selectedFormatId.value = null
        _selectedSubtitle.value = null
        _hasSearched.value = true
        // Clear any completed download state when starting a new search
        clearCompletedDownload()

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("ytdlp_helper")
                    
                    // Sanitize URL before passing to Python
                    val sanitizedUrl = SecurityUtils.sanitizeInput(currentUrl)
                    
                    val jsonResult = module.callAttr("get_video_info", sanitizedUrl)?.toString()
                    if (jsonResult != null) {
                        try {
                            val parsed = jsonParser.decodeFromString<VideoInfo>(jsonResult)
                            if (parsed.error != null) {
                                if (BuildConfig.DEBUG) Log.e(VM_TAG, "Error in JSON response: ${parsed.error}")
                            }
                            parsed
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.e(VM_TAG, "JSON parse failed: ${e.message}", e)
                            VideoInfo(error = "Failed to parse response: ${e.message}")
                        }
                    } else {
                        if (BuildConfig.DEBUG) Log.e(VM_TAG, "Python function returned null")
                        VideoInfo(error = "Python function returned null")
                    }
                } catch (e: PyException) {
                    if (BuildConfig.DEBUG) Log.e(VM_TAG, "Python Exception: ${e.message}", e)
                    VideoInfo(error = "Python Error:\n${e.message}")
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(VM_TAG, "Exception calling Python: ${e.message}", e)
                    VideoInfo(error = "Error calling Python: ${e.message}")
                }
            }
            _videoInfo.value = result
            if (result.subtitles.isNotEmpty()) {
                _selectedSubtitle.value = result.subtitles.first()
            }
            _isLoading.value = false
        }
    }

    /**
     * Validates that a URL has the correct format.
     * 
     * Checks:
     * - URL starts with http:// or https://
     * - URL contains a domain (e.g., youtube.com)
     * - Domain has at least one dot (e.g., .com, .org)
     * - No spaces in the URL
     * 
     * @param url The URL to validate
     * @return true if the URL appears valid, false otherwise
     */
    private fun isValidUrl(url: String): Boolean {
        // Check protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false
        }

        // Remove protocol for domain validation
        val withoutProtocol = url.removePrefix("http://").removePrefix("https://")

        // Check for empty after protocol
        if (withoutProtocol.isBlank()) return false

        // Check for spaces
        if (url.contains(" ")) return false

        // Basic domain structure check (must contain at least one dot for TLD)
        val domainParts = withoutProtocol.split("/")[0].split(".")
        if (domainParts.size < 2) return false

        // Check TLD is not empty (e.g., "youtube." would be invalid)
        if (domainParts.last().isBlank()) return false

        return true
    }
}
