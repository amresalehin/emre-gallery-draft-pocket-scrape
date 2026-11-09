package com.amresalehin.emregallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun releaseAndDialogDisposalAreIdempotentInEitherOrder() {
        for (disposeFirst in listOf(false, true)) {
            val owningPlayer = Any()
            var attachedPlayer: Any? = owningPlayer
            var detachCount = 0

            val detachOwnedView = {
                detachRemotePlaybackPlayerViewIfOwned(
                    currentPlayer = attachedPlayer,
                    owningPlayer = owningPlayer
                ) {
                    attachedPlayer = null
                    detachCount += 1
                }
            }
            val onAndroidViewRelease = { detachOwnedView() }
            val onDialogDispose = { detachOwnedView() }

            if (disposeFirst) {
                onDialogDispose()
                onAndroidViewRelease()
            } else {
                onAndroidViewRelease()
                onDialogDispose()
            }

            assertEquals(1, detachCount)
            assertNull(attachedPlayer)
        }
    }

    @Test
    fun cleanupDoesNotDetachAViewReboundToAnotherPlayersSession() {
        val owningPlayer = Any()
        val replacementPlayer = Any()
        var attachedPlayer: Any? = replacementPlayer
        var detached = false

        detachRemotePlaybackPlayerViewIfOwned(
            currentPlayer = attachedPlayer,
            owningPlayer = owningPlayer
        ) {
            detached = true
            attachedPlayer = null
        }

        assertFalse(detached)
        assertSame(replacementPlayer, attachedPlayer)
    }
}
