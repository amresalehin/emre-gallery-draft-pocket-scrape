package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BrowserStartupBehavior(val label: String) {
    HOMEPAGE("Homepage"),
    RESTORE_PREVIOUS_SESSION("Restore previous session")
}

enum class BrowserAddressBarPosition(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom")
}

data class BrowserSettingsState(
    val startupBehavior: BrowserStartupBehavior = BrowserStartupBehavior.HOMEPAGE,
    val searchEngine: BrowserSearchEngine = BrowserSearchEngine.GOOGLE,
    val customSearchUrl: String = "https://www.google.com/search?q=%s",
    val addressBarPosition: BrowserAddressBarPosition = BrowserAddressBarPosition.TOP,
    val showLoadingProgress: Boolean = true,
    val showSecurityIndicator: Boolean = true,
    val mediaDiscoveryEnabled: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val loadImagesAutomatically: Boolean = true,
    val allowAutoplay: Boolean = true,
    val blockThirdPartyCookies: Boolean = false,
    val safeBrowsingEnabled: Boolean = true,
    val websiteTextZoom: Int = 100,
    val websiteInitialScale: Int = 100
)

/**
 * Browser preferences are isolated from gallery-wide settings. SharedPreferences
 * keeps these lightweight controls synchronous to read during WebView creation.
 */
class BrowserSettingsRepository(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val mutableSettings = MutableStateFlow(readSettings())
    val settings: StateFlow<BrowserSettingsState> = mutableSettings.asStateFlow()

    private fun readSettings(): BrowserSettingsState {
        val startup = runCatching {
            BrowserStartupBehavior.valueOf(
                preferences.getString(KEY_STARTUP, BrowserStartupBehavior.HOMEPAGE.name)
                    ?: BrowserStartupBehavior.HOMEPAGE.name
            )
        }.getOrDefault(BrowserStartupBehavior.HOMEPAGE)

        val engine = runCatching {
            BrowserSearchEngine.valueOf(
                preferences.getString(KEY_SEARCH_ENGINE, BrowserSearchEngine.GOOGLE.name)
                    ?: BrowserSearchEngine.GOOGLE.name
            )
        }.getOrDefault(BrowserSearchEngine.GOOGLE)

        val position = runCatching {
            BrowserAddressBarPosition.valueOf(
                preferences.getString(KEY_ADDRESS_BAR, BrowserAddressBarPosition.TOP.name)
                    ?: BrowserAddressBarPosition.TOP.name
            )
        }.getOrDefault(BrowserAddressBarPosition.TOP)

        return BrowserSettingsState(
            startupBehavior = startup,
            searchEngine = engine,
            customSearchUrl = preferences.getString(KEY_CUSTOM_SEARCH_URL, "https://www.google.com/search?q=%s")
                ?: "https://www.google.com/search?q=%s",
            addressBarPosition = position,
            showLoadingProgress = preferences.getBoolean(KEY_PROGRESS, true),
            showSecurityIndicator = preferences.getBoolean(KEY_SECURITY, true),
            mediaDiscoveryEnabled = preferences.getBoolean(KEY_MEDIA_DISCOVERY, true),
            javaScriptEnabled = preferences.getBoolean(KEY_JAVASCRIPT, true),
            loadImagesAutomatically = preferences.getBoolean(KEY_IMAGES, true),
            allowAutoplay = preferences.getBoolean(KEY_AUTOPLAY, true),
            blockThirdPartyCookies = preferences.getBoolean(KEY_THIRD_PARTY_COOKIES, false),
            safeBrowsingEnabled = preferences.getBoolean(KEY_SAFE_BROWSING, true),
            websiteTextZoom = preferences.getInt(KEY_TEXT_ZOOM, 100).coerceIn(75, 150),
            websiteInitialScale = preferences.getInt(KEY_PAGE_ZOOM, 100).coerceIn(50, 150)
        )
    }

    private inline fun update(
        crossinline transform: (BrowserSettingsState) -> BrowserSettingsState,
        persist: SharedPreferences.Editor.() -> Unit
    ) {
        synchronized(this) {
            preferences.edit().apply(persist).apply()
            mutableSettings.value = transform(mutableSettings.value)
        }
    }

    fun setStartupBehavior(value: BrowserStartupBehavior) =
        update({ it.copy(startupBehavior = value) }) { putString(KEY_STARTUP, value.name) }

    fun setSearchEngine(value: BrowserSearchEngine) =
        update({ it.copy(searchEngine = value) }) { putString(KEY_SEARCH_ENGINE, value.name) }

    fun setCustomSearchUrl(value: String) {
        val normalized = value.trim().takeIf { it.startsWith("https://") && it.contains("%s") }
            ?: "https://www.google.com/search?q=%s"
        update({ it.copy(customSearchUrl = normalized) }) { putString(KEY_CUSTOM_SEARCH_URL, normalized) }
    }

    fun setAddressBarPosition(value: BrowserAddressBarPosition) =
        update({ it.copy(addressBarPosition = value) }) { putString(KEY_ADDRESS_BAR, value.name) }

    fun setShowLoadingProgress(value: Boolean) =
        update({ it.copy(showLoadingProgress = value) }) { putBoolean(KEY_PROGRESS, value) }

    fun setShowSecurityIndicator(value: Boolean) =
        update({ it.copy(showSecurityIndicator = value) }) { putBoolean(KEY_SECURITY, value) }

    fun setMediaDiscoveryEnabled(value: Boolean) =
        update({ it.copy(mediaDiscoveryEnabled = value) }) { putBoolean(KEY_MEDIA_DISCOVERY, value) }

    fun setJavaScriptEnabled(value: Boolean) =
        update({ it.copy(javaScriptEnabled = value) }) { putBoolean(KEY_JAVASCRIPT, value) }

    fun setLoadImagesAutomatically(value: Boolean) =
        update({ it.copy(loadImagesAutomatically = value) }) { putBoolean(KEY_IMAGES, value) }

    fun setAllowAutoplay(value: Boolean) =
        update({ it.copy(allowAutoplay = value) }) { putBoolean(KEY_AUTOPLAY, value) }

    fun setBlockThirdPartyCookies(value: Boolean) =
        update({ it.copy(blockThirdPartyCookies = value) }) { putBoolean(KEY_THIRD_PARTY_COOKIES, value) }

    fun setSafeBrowsingEnabled(value: Boolean) =
        update({ it.copy(safeBrowsingEnabled = value) }) { putBoolean(KEY_SAFE_BROWSING, value) }

    fun setWebsiteTextZoom(value: Int) {
        val bounded = value.coerceIn(75, 150)
        update({ it.copy(websiteTextZoom = bounded) }) { putInt(KEY_TEXT_ZOOM, bounded) }
    }

    fun setWebsiteInitialScale(value: Int) {
        val bounded = value.coerceIn(50, 150)
        update({ it.copy(websiteInitialScale = bounded) }) { putInt(KEY_PAGE_ZOOM, bounded) }
    }

    fun getLastVisitedUrl(): String = preferences.getString(KEY_LAST_URL, "").orEmpty()

    fun setLastVisitedUrl(url: String) {
        val safeUrl = url.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }.orEmpty()
        preferences.edit().putString(KEY_LAST_URL, safeUrl).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "emre_browser_settings"
        const val KEY_STARTUP = "startup_behavior"
        const val KEY_SEARCH_ENGINE = "search_engine"
        const val KEY_CUSTOM_SEARCH_URL = "custom_search_url"
        const val KEY_ADDRESS_BAR = "address_bar_position"
        const val KEY_PROGRESS = "show_loading_progress"
        const val KEY_SECURITY = "show_security_indicator"
        const val KEY_MEDIA_DISCOVERY = "media_discovery_enabled"
        const val KEY_JAVASCRIPT = "javascript_enabled"
        const val KEY_IMAGES = "load_images"
        const val KEY_AUTOPLAY = "allow_autoplay"
        const val KEY_THIRD_PARTY_COOKIES = "block_third_party_cookies"
        const val KEY_SAFE_BROWSING = "safe_browsing"
        const val KEY_TEXT_ZOOM = "website_text_zoom"
        const val KEY_PAGE_ZOOM = "website_initial_scale"
        const val KEY_LAST_URL = "last_visited_url"
    }
}
