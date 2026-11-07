package com.amresalehin.emregallery.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Legacy compatibility shim for BrowserMediaExtractor.
 * Headless browser extraction and per-tab detection are consolidated in BrowserSession.
 */
class BrowserMediaExtractor(private val context: Context) {

    private var activeJob: Job? = null

    class EmreMediaBridge(private val onMediaFound: (String) -> Unit) {
        @android.webkit.JavascriptInterface
        fun reportMedia(url: String?) {
            if (!url.isNullOrBlank()) {
                onMediaFound(url)
            }
        }
    }

    fun startLiveExtraction(
        targetUrl: String,
        onStatusUpdate: (String) -> Unit,
        onMediaDiscovered: (ScrapedMediaItem) -> Unit,
        onFinished: (failureReason: String?) -> Unit
    ) {
        activeJob?.cancel()
        activeJob = CoroutineScope(Dispatchers.Main).launch {
            val result = BrowserSession.extractHeadless(
                context = context,
                targetUrl = targetUrl,
                onStatusUpdate = onStatusUpdate,
                onMediaDiscovered = onMediaDiscovered
            )
            onFinished(result.errorMessage)
        }
    }

    fun cleanup() {
        activeJob?.cancel()
        activeJob = null
    }
}
