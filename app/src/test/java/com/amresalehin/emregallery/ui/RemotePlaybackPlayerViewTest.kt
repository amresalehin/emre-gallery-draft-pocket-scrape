package com.amresalehin.emregallery.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackPlayerViewTest {

    @Test
    fun detachesOnlyWhenTheViewStillReferencesTheDisposingPlayer() {
        val owningPlayer = Any()
        val replacementPlayer = Any()

        assertFalse(
            shouldDetachRemotePlaybackPlayerView(
                currentPlayer = owningPlayer,
                owningPlayer = replacementPlayer
            )
        )
        assertTrue(
            shouldDetachRemotePlaybackPlayerView(
                currentPlayer = owningPlayer,
                owningPlayer = owningPlayer
            )
        )
    }

    @Test
    fun missingPlayersNeverTriggerDetachment() {
        val player = Any()

        assertFalse(
            shouldDetachRemotePlaybackPlayerView(
                currentPlayer = null,
                owningPlayer = player
            )
        )
        assertFalse(
            shouldDetachRemotePlaybackPlayerView(
                currentPlayer = player,
                owningPlayer = null
            )
        )
        assertFalse(
            shouldDetachRemotePlaybackPlayerView(
                currentPlayer = null,
                owningPlayer = null
            )
        )
    }
}
