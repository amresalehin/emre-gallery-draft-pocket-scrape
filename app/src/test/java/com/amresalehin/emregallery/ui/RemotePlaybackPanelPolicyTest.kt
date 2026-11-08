package com.amresalehin.emregallery.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackPanelPolicyTest {
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
