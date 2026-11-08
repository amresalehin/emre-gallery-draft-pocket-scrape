package com.amresalehin.emregallery.ui

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlaybackPlayerViewTest {

    @Test
    fun detachesOnlyThePlayerOwnedByTheDisposingSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owningPlayer = ExoPlayer.Builder(context).build()
        val replacementPlayer = ExoPlayer.Builder(context).build()
        val view = PlayerView(context)

        try {
            view.player = owningPlayer

            detachRemotePlaybackPlayerView(view, replacementPlayer)

            assertSame(owningPlayer, view.player)

            detachRemotePlaybackPlayerView(view, owningPlayer)

            assertNull(view.player)
        } finally {
            detachRemotePlaybackPlayerView(view, owningPlayer)
            owningPlayer.release()
            replacementPlayer.release()
        }
    }

    @Test
    fun nullInputsAreSafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = PlayerView(context)

        detachRemotePlaybackPlayerView(null, null)
        detachRemotePlaybackPlayerView(view, null)

        assertNull(view.player)
    }
}
