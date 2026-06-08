package com.kira.ytdlp

import android.net.Uri
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Security utility class for NexusDL.
 *
 * Provides security features including:
 * - URL whitelist validation for supported platforms
 * - Rate limiting for download requests
 * - Checksum verification for downloaded files
 * - Input sanitization
 */
object SecurityUtils {

    /**
     * List of supported video platforms and their URL patterns.
     * These domains are explicitly allowed for security.
     */
    private val ALLOWED_DOMAINS = setOf(
        // YouTube domains
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        
        // Other popular platforms (can be extended)
        "vimeo.com",
        "dailymotion.com",
        "soundcloud.com",
        "tiktok.com",
        "www.tiktok.com",
        "twitter.com",
        "x.com",
        "reddit.com",
        "www.reddit.com",
        "facebook.com",
        "www.facebook.com",
        "instagram.com",
        "www.instagram.com"
    )

    /**
     * URL patterns that are explicitly blocked for security.
     */
    private val BLOCKED_PATTERNS = listOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "::1",
        "file://",
        "content://",
        "android.asset",
        "data://"
    )

    /**
     * Rate limiting tracking.
     * Maps IP/host to last download timestamp.
     */
    private val downloadAttempts = mutableMapOf<String, MutableList<Long>>()
    private const val MAX_DOWNLOADS_PER_MINUTE = 5
    private const val RATE_LIMIT_WINDOW_MS = 60_000L // 1 minute

    /**
     * Validates if a URL is from an allowed domain.
     *
     * @param url The URL to validate
     * @return ValidationResult indicating if URL is allowed and why
     */
    fun validateUrl(url: String): ValidationResult {
        // Check for blocked patterns first
        if (BLOCKED_PATTERNS.any { url.contains(it, ignoreCase = true) }) {
            return ValidationResult.Blocked("URL pattern is not allowed for security reasons")
        }

        // Parse the URL
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return ValidationResult.Invalid("Invalid URL format")
        }

        // Check scheme
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ValidationResult.Invalid("Only HTTP and HTTPS URLs are supported")
        }

        // Check host
        val host = uri.host?.lowercase() ?: return ValidationResult.Invalid("URL has no host")

        // Check for port injection (only allow standard ports)
        val port = uri.port
        if (port != -1 && port != 80 && port != 443) {
            return ValidationResult.Blocked("Non-standard ports are not allowed")
        }

        // Check if domain is allowed
        val isAllowed = ALLOWED_DOMAINS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }

        return if (isAllowed) {
            ValidationResult.Valid
        } else {
            ValidationResult.Blocked("Domain '$host' is not in the supported platforms list. " +
                "Currently supported: YouTube, Vimeo, Dailymotion, SoundCloud, TikTok, Twitter/X, Reddit, Facebook, Instagram")
        }
    }

    /**
     * Checks if a download request should be rate limited.
     *
     * @param identifier Unique identifier for the requester (e.g., user ID or device ID)
     * @return true if request is allowed, false if rate limited
     */
    fun checkRateLimit(identifier: String): Boolean {
        val now = System.currentTimeMillis()
        val attempts = downloadAttempts.getOrPut(identifier) { mutableListOf() }

        // Remove old attempts outside the window
        attempts.removeAll { now - it > RATE_LIMIT_WINDOW_MS }

        // Check if limit exceeded
        return if (attempts.size < MAX_DOWNLOADS_PER_MINUTE) {
            attempts.add(now)
            true
        } else {
            false
        }
    }

    /**
     * Gets the remaining time before rate limit resets.
     *
     * @param identifier Unique identifier for the requester
     * @return Time in milliseconds until rate limit resets, or 0 if not limited
     */
    fun getRateLimitResetTime(identifier: String): Long {
        val now = System.currentTimeMillis()
        val attempts = downloadAttempts[identifier] ?: return 0

        if (attempts.size < MAX_DOWNLOADS_PER_MINUTE) return 0

        val oldestAttempt = attempts.minOrNull() ?: return 0
        val resetTime = oldestAttempt + RATE_LIMIT_WINDOW_MS - now
        return maxOf(resetTime, 0)
    }

    /**
     * Calculates SHA-256 checksum of a file.
     *
     * @param filePath Path to the file
     * @return Hex string of the checksum, or null if file doesn't exist
     */
    fun calculateChecksum(filePath: String): String? {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verifies file integrity by comparing checksums.
     *
     * @param filePath Path to the downloaded file
     * @param expectedChecksum Expected SHA-256 checksum
     * @return true if checksums match, false otherwise
     */
    fun verifyChecksum(filePath: String, expectedChecksum: String): Boolean {
        val actualChecksum = calculateChecksum(filePath) ?: return false
        return actualChecksum.equals(expectedChecksum, ignoreCase = true)
    }

    /**
     * Sanitizes user input to prevent injection attacks.
     *
     * @param input The input string to sanitize
     * @return Sanitized string safe for logging/display
     */
    fun sanitizeInput(input: String): String {
        return input
            .replace(Regex("[<>'\"&]"), "") // Remove HTML/XML special chars
            .trim()
            .take(500) // Limit length
    }

    /**
     * Result of URL validation.
     */
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
        data class Blocked(val reason: String) : ValidationResult()

        val isValid: Boolean
            get() = this is Valid
    }
}
