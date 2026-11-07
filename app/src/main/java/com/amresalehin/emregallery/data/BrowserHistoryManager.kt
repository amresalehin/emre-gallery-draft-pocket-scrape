package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class BrowserSite(
    val url: String,
    val title: String,
    val domain: String,
    val visitCount: Int = 1,
    val lastVisitedMillis: Long = System.currentTimeMillis()
)

/**
 * Manages persistent browser history for tracking top visited and recent sites.
 */
class BrowserHistoryManager(private val context: Context) {

    private val historyKey = stringPreferencesKey("browser_sites_history_v1")
    private val searchQueriesKey = stringPreferencesKey("browser_downloader_recent_searches_v1")

    // No hardcoded sites; history relies strictly on user visits
    val defaultTopSites: List<BrowserSite> = emptyList()

    val recentSearchQueries: Flow<List<String>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[searchQueriesKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val q = array.getString(i).trim()
                    if (q.isNotEmpty()) list.add(q)
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.distinctUntilChanged()

    suspend fun recordSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.galleryDataStore.edit { prefs ->
            val raw = prefs[searchQueriesKey]
            val list = mutableListOf<String>()
            if (!raw.isNullOrBlank()) {
                try {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val item = array.getString(i).trim()
                        if (item.isNotEmpty() && !item.equals(trimmed, ignoreCase = true)) {
                            list.add(item)
                        }
                    }
                } catch (_: Exception) {}
            }
            list.add(0, trimmed)
            val jsonArray = JSONArray()
            for (q in list.take(15)) {
                jsonArray.put(q)
            }
            prefs[searchQueriesKey] = jsonArray.toString()
        }
    }

    val allSites: Flow<List<BrowserSite>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[historyKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                val list = mutableListOf<BrowserSite>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        BrowserSite(
                            url = obj.getString("url"),
                            title = obj.optString("title", ""),
                            domain = obj.optString("domain", ""),
                            visitCount = obj.optInt("count", 1),
                            lastVisitedMillis = obj.optLong("time", 0L)
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.distinctUntilChanged()

    suspend fun recordVisit(rawUrl: String, title: String? = null) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank() || trimmed == "about:blank" || trimmed.startsWith("javascript:")) return
        val domain = runCatching {
            val host = URL(trimmed).host
            host.removePrefix("www.")
        }.getOrDefault("").ifBlank {
            trimmed.substringAfter("://").substringBefore("/").removePrefix("www.")
        }
        if (domain.isBlank()) return

        val displayTitle = title?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("http") }
            ?: domain.substringBefore('.').replaceFirstChar { it.uppercase() }

        context.galleryDataStore.edit { prefs ->
            val raw = prefs[historyKey]
            val list = mutableListOf<BrowserSite>()
            if (!raw.isNullOrBlank()) {
                try {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            BrowserSite(
                                url = obj.getString("url"),
                                title = obj.optString("title", ""),
                                domain = obj.optString("domain", ""),
                                visitCount = obj.optInt("count", 1),
                                lastVisitedMillis = obj.optLong("time", 0L)
                            )
                        )
                    }
                } catch (_: Exception) {}
            }

            // Find existing site matching normalized domain or url
            val existingIndex = list.indexOfFirst {
                it.domain.equals(domain, ignoreCase = true) || it.url.equals(trimmed, ignoreCase = true)
            }

            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                list[existingIndex] = existing.copy(
                    url = trimmed,
                    title = if (displayTitle.isNotBlank() && displayTitle != existing.domain) displayTitle else existing.title,
                    visitCount = existing.visitCount + 1,
                    lastVisitedMillis = System.currentTimeMillis()
                )
            } else {
                list.add(
                    0,
                    BrowserSite(
                        url = trimmed,
                        title = displayTitle,
                        domain = domain,
                        visitCount = 1,
                        lastVisitedMillis = System.currentTimeMillis()
                    )
                )
            }

            // Keep top 30
            val trimmedList = list.sortedByDescending { it.lastVisitedMillis }.take(30)
            val jsonArray = JSONArray()
            for (s in trimmedList) {
                val obj = JSONObject()
                obj.put("url", s.url)
                obj.put("title", s.title)
                obj.put("domain", s.domain)
                obj.put("count", s.visitCount)
                obj.put("time", s.lastVisitedMillis)
                jsonArray.put(obj)
            }
            prefs[historyKey] = jsonArray.toString()
        }
    }

    suspend fun clearHistory() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(historyKey)
        }
    }
}
