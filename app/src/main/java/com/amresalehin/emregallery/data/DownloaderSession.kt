package com.amresalehin.emregallery.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URL

/**
 * Single source of truth for downloader sessions across:
 * - FullScreenBrowserView
 * - DownloaderSearchMainPage
 * - ShareReceiverActivity
 *
 * Ensures discoveries made in the live browser automatically merge across sessions.
 */
object DownloaderSession {

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _sourceDomain = MutableStateFlow("")
    val sourceDomain: StateFlow<String> = _sourceDomain.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _discoveredItems = MutableStateFlow<List<ScrapedMediaItem>>(emptyList())
    val discoveredItems: StateFlow<List<ScrapedMediaItem>> = _discoveredItems.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val itemsLock = Any()
    private val canonicalMap = mutableMapOf<String, ScrapedMediaItem>()

    fun startSession(url: String) {
        val trimmed = url.trim()
        val normalized = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            "https://$trimmed"
        } else trimmed

        val domain = runCatching { URL(normalized).host }.getOrDefault("")

        synchronized(itemsLock) {
            _currentUrl.value = normalized
            _sourceDomain.value = domain
            _pageTitle.value = domain
            canonicalMap.clear()
            _discoveredItems.value = emptyList()
            _selectedItemIds.value = emptySet()
            _isExtracting.value = true
            _statusMessage.value = "Starting extraction..."
            _errorMessage.value = null
        }
    }

    fun addDiscoveredItems(newItems: List<ScrapedMediaItem>, title: String? = null) {
        synchronized(itemsLock) {
            if (newItems.isNotEmpty()) {
                // Merge before filtering so later semantic evidence can downgrade an earlier
                // content guess (for example CONTENT_IMAGE -> AVATAR) and posters can still
                // enrich a matching video's thumbnail without becoming standalone assets.
                val merged = MediaNormalizer.mergeCandidates(canonicalMap, newItems)
                val rejectedKeys = canonicalMap.entries
                    .filter { (_, item) ->
                        !MediaDiscoveryPolicy.shouldOfferForManualDownload(MediaDiscoveryCandidate.from(item))
                    }
                    .map { it.key }
                rejectedKeys.forEach(canonicalMap::remove)

                if (merged || rejectedKeys.isNotEmpty()) {
                    val sorted = canonicalMap.values.toList()
                    _discoveredItems.value = sorted
                    val validIds = sorted.map { it.id }.toSet()
                    val currentSelection = _selectedItemIds.value
                    val selectionWasEmpty = currentSelection.isEmpty()
                    val prunedSelection = currentSelection.intersect(validIds)
                    if (prunedSelection != currentSelection) {
                        _selectedItemIds.value = prunedSelection
                    }
                    // This updates selection only; enqueueing still requires a separate user action.
                    if (selectionWasEmpty && _selectedItemIds.value.isEmpty() && sorted.isNotEmpty()) {
                        _selectedItemIds.value = validIds
                    }
                }
            }
            if (!title.isNullOrBlank() && _pageTitle.value == _sourceDomain.value) {
                _pageTitle.value = title
            }
        }
    }

    fun addDiscoveredItem(item: ScrapedMediaItem) {
        addDiscoveredItems(listOf(item))
    }

    fun updateStatus(message: String, isRunning: Boolean? = null) {
        _statusMessage.value = message
        if (isRunning != null) {
            _isExtracting.value = isRunning
        }
    }

    fun setStatusMessage(msg: String) {
        updateStatus(msg)
    }

    fun finishSession(message: String? = null, error: String? = null) {
        _isExtracting.value = false
        if (message != null) {
            _statusMessage.value = message
        }
        _errorMessage.value = error
    }

    fun setPageTitle(title: String) {
        if (title.isNotBlank()) {
            _pageTitle.value = title
        }
    }

    fun toggleSelection(itemId: String) {
        synchronized(itemsLock) {
            val current = _selectedItemIds.value.toMutableSet()
            if (current.contains(itemId)) {
                current.remove(itemId)
            } else {
                current.add(itemId)
            }
            _selectedItemIds.value = current
        }
    }

    fun selectAll(ids: Set<String>) {
        synchronized(itemsLock) {
            val availableIds = canonicalMap.values.map { it.id }.toSet()
            _selectedItemIds.value = ids.intersect(availableIds)
        }
    }

    fun deselectAll() {
        synchronized(itemsLock) {
            _selectedItemIds.value = emptySet()
        }
    }
}
