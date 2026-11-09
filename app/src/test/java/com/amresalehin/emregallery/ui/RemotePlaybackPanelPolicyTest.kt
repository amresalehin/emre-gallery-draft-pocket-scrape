package com.amresalehin.emregallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackPanelPolicyTest {
    @Test
    fun originalSourceFallbackDismissesPlaybackAndOpensTheExactSavedUrl() {
        val originalSourceUrl =
            "https://example.com/watch?list=abc%2Fdef&start=12#details"
        val events = mutableListOf<String>()
        var openedUrl: String? = null

        openOriginalSourcePage(
            originalSourceUrl = originalSourceUrl,
            dismissPlayback = { events += "dismiss" },
            onOpenBrowser = { url ->
                events += "open"
                openedUrl = url
            }
        )

        assertEquals(listOf("dismiss", "open"), events)
        assertEquals(originalSourceUrl, openedUrl)
    }

    @Test
    fun originalSourceFallbackDoesNothingWhenTheSavedUrlIsBlank() {
        var dismissed = false
        var openedUrl: String? = null

        openOriginalSourcePage(
            originalSourceUrl = " \t ",
            dismissPlayback = { dismissed = true },
            onOpenBrowser = { openedUrl = it }
        )

        assertFalse(dismissed)
        assertTrue(openedUrl == null)
    }

    @Test
    fun attachesPlayerViewWhilePlayerIsAvailableAndHealthy() {
        assertTrue(
            shouldAttachRemotePlaybackView(
                hasPlayer = true,
                playbackFailed = false
            )
        )
    }

    @Test
    fun doesNotAttachPlayerViewWhenPlayerIsUnavailable() {
        assertFalse(
            shouldAttachRemotePlaybackView(
                hasPlayer = false,
                playbackFailed = false
            )
        )
    }

    @Test
    fun detachesPlayerViewAfterPlaybackFailure() {
        assertFalse(
            shouldAttachRemotePlaybackView(
                hasPlayer = true,
                playbackFailed = true
            )
        )
    }
}
