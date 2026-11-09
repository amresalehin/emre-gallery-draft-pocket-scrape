package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.BrowserTabSession
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ScrapedMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTabSessionNavigationTest {

    @After
    fun resetSharedDownloaderSession() {
        DownloaderSession.startSession("https://cleanup.invalid")
    }

    @Test
    fun navigatingToAnotherDomainClearsPreviousPageCandidates() {
        val session = BrowserTabSession(
            tabId = "cross-domain-navigation",
            initialUrl = "https://first.example.com/watch"
        )
        session.registerMedia(videoItem())

        assertEquals(1, session.detectedCount.value)

        session.updateNavigation("https://second.example.net/article")

        assertEquals("https://second.example.net/article", session.url.value)
        assertEquals(0, session.detectedCount.value)
        assertEquals(emptyList<ScrapedMediaItem>(), session.detectedItems.value)
        assertEquals(emptyList<ScrapedMediaItem>(), session.getDetectedMedia())
    }

    @Test
    fun sameDomainNavigationKeepsCandidatesForTheCurrentTab() {
        val session = BrowserTabSession(
            tabId = "same-domain-navigation",
            initialUrl = "https://example.com/first"
        )
        session.registerMedia(videoItem())

        session.updateNavigation("https://EXAMPLE.COM/second?view=full")

        assertEquals(1, session.detectedCount.value)
        assertEquals(1, session.detectedItems.value.size)
        assertEquals(1, session.getDetectedMedia().size)
    }

    @Test
    fun navigationAwayFromAWebHostClearsCandidates() {
        val session = BrowserTabSession(
            tabId = "non-web-navigation",
            initialUrl = "https://example.com/watch"
        )
        session.registerMedia(videoItem())

        session.updateNavigation("about:blank")

        assertEquals(0, session.detectedCount.value)
        assertEquals(emptyList<ScrapedMediaItem>(), session.detectedItems.value)
    }

    private fun videoItem() = ScrapedMediaItem(
        id = "video-1",
        url = "https://cdn.example.com/video.mp4",
        title = "Video"
    )
}
