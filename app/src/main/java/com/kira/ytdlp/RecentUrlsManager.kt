package com.kira.ytdlp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Manager for recently entered video URLs.
 * 
 * Uses DataStore for persistent storage of up to 10 recent URLs.
 * URLs are stored with their associated video titles when available.
 * 
 * @param context Application context
 */
class RecentUrlsManager(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "recent_urls")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Flow of recent URLs, ordered by most recent first.
     */
    val recentUrls: Flow<List<RecentUrl>> = context.dataStore.data.map { preferences ->
        val urlsJson = preferences[RECENT_URLS_KEY] ?: "[]"
        try {
            json.decodeFromString<List<RecentUrl>>(urlsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Adds a URL to recent history.
     * If URL already exists, it moves to the top.
     * Maintains maximum of 10 URLs.
     * 
     * @param url The video URL
     * @param title Optional video title
     */
    suspend fun addUrl(url: String, title: String? = null) {
        val currentList = recentUrls.first().toMutableList()
        
        // Remove if already exists (will be re-added at top)
        currentList.removeAll { it.url == url }
        
        // Add at the beginning
        currentList.add(0, RecentUrl(url, title, System.currentTimeMillis()))
        
        // Keep only last 10
        val trimmedList = currentList.take(10)
        
        context.dataStore.edit { preferences ->
            preferences[RECENT_URLS_KEY] = json.encodeToString(trimmedList)
        }
    }

    /**
     * Clears all recent URLs.
     */
    suspend fun clearUrls() {
        context.dataStore.edit { preferences ->
            preferences.remove(RECENT_URLS_KEY)
        }
    }

    companion object {
        private val RECENT_URLS_KEY = stringPreferencesKey("recent_urls")
    }
}

/**
 * Represents a recently used URL.
 * 
 * @property url The video URL
 * @property title Optional video title
 * @property timestamp When the URL was last used
 */
@kotlinx.serialization.Serializable
data class RecentUrl(
    val url: String,
    val title: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
