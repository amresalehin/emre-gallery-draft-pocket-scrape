package com.amresalehin.emregallery.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.amresalehin.emregallery.data.MediaAsset
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryEvidence
import com.amresalehin.emregallery.data.MediaDiscoveryEvidenceSource
import com.amresalehin.emregallery.data.MediaVerificationState
import com.amresalehin.emregallery.data.PlaybackResolution
import com.amresalehin.emregallery.data.ResolvedMediaAssetPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlaybackDialogIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun androidViewReleaseDetachesNativePlayerViewFromItsPlayer() {
        lateinit var player: ExoPlayer
        lateinit var view: PlayerView
        val showView = mutableStateOf(true)
        val viewState = mutableStateOf<PlayerView?>(null)

        composeRule.runOnIdle {
            player = ExoPlayer.Builder(composeRule.activity.applicationContext).build()
        }

        try {
            composeRule.setContent {
                if (showView.value) {
                    OwnedRemotePlaybackAndroidView(
                        owner = player,
                        viewState = viewState,
                        factory = { context -> PlayerView(context) },
                        currentOwner = { it.player },
                        bindOwner = { playerView, owner -> playerView.player = owner as? Player },
                        modifier = Modifier.size(120.dp)
                    )
                }
            }
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                view = requireNotNull(viewState.value)
                assertSame(player, view.player)
                showView.value = false
            }
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertNull(view.player)
                assertNull(viewState.value)
            }
        } finally {
            composeRule.runOnIdle { showView.value = false }
            composeRule.waitForIdle()
            composeRule.runOnIdle { player.release() }
        }
    }

    @Test
    fun dismissingDialogDetachesPlayerViewBeforeReleasingExoPlayerExactlyOnce() {
        val playback = playablePlayback()
        val showDialog = mutableStateOf(true)
        val players = mutableListOf<ExoPlayer>()
        val views = mutableListOf<PlayerView>()
        var releaseCount = 0
        var viewWasDetachedBeforeRelease = false

        composeRule.setContent {
            MaterialTheme {
                if (showDialog.value) {
                    RemotePlaybackDialog(
                        playback = playback,
                        onDismiss = { showDialog.value = false },
                        onOpenSourcePage = null,
                        playerFactory = { context ->
                            ExoPlayer.Builder(context).build().also(players::add)
                        },
                        // The test exercises composition ownership, not network preparation.
                        preparePlayer = {},
                        releasePlayer = { player ->
                            releaseCount += 1
                            viewWasDetachedBeforeRelease = views.singleOrNull()?.player == null
                            player.release()
                        },
                        playerViewFactory = { context ->
                            PlayerView(context).also(views::add)
                        }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val player = requireNotNull(players.singleOrNull())
        val view = requireNotNull(views.singleOrNull())
        composeRule.runOnIdle { assertSame(player, view.player) }

        composeRule.onNodeWithContentDescription("Close player").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertNull(view.player) }
        assertEquals(1, releaseCount)
        assertTrue(viewWasDetachedBeforeRelease)
    }

    @Test
    fun prepareFailureKeepsBrowserFallbackAvailableAndReleasesPlayerOnce() {
        val playback = playablePlayback()
        val showDialog = mutableStateOf(true)
        var releaseCount = 0
        var playerViewFactoryCalls = 0
        var sourceFallbackCalls = 0

        composeRule.setContent {
            MaterialTheme {
                if (showDialog.value) {
                    RemotePlaybackDialog(
                        playback = playback,
                        onDismiss = { showDialog.value = false },
                        onOpenSourcePage = {
                            sourceFallbackCalls += 1
                            showDialog.value = false
                        },
                        playerFactory = { context ->
                            ExoPlayer.Builder(context).build()
                        },
                        preparePlayer = {
                            throw IllegalStateException("Simulated preparation failure")
                        },
                        releasePlayer = { player ->
                            releaseCount += 1
                            player.release()
                        },
                        playerViewFactory = { context ->
                            playerViewFactoryCalls += 1
                            PlayerView(context)
                        }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "This direct resource could not be played. The bookmark and discovered media were left unchanged."
        ).fetchSemanticsNode()
        composeRule.onNodeWithText("Open original page").performClick()
        composeRule.waitForIdle()

        assertEquals(1, releaseCount)
        assertEquals(0, playerViewFactoryCalls)
        assertEquals(1, sourceFallbackCalls)
    }

    @Test
    fun terminalPlayerErrorRemovesNativeViewAndKeepsOriginalPageFallbackAvailable() {
        val playback = playablePlayback()
        val showDialog = mutableStateOf(true)
        val players = mutableListOf<ExoPlayer>()
        val views = mutableListOf<PlayerView>()
        lateinit var playerListener: Player.Listener
        var releaseCount = 0
        var viewWasDetachedBeforeRelease = false
        var sourceFallbackCalls = 0

        composeRule.setContent {
            MaterialTheme {
                if (showDialog.value) {
                    RemotePlaybackDialog(
                        playback = playback,
                        onDismiss = { showDialog.value = false },
                        onOpenSourcePage = {
                            sourceFallbackCalls += 1
                            showDialog.value = false
                        },
                        playerFactory = { context ->
                            ExoPlayer.Builder(context).build().also(players::add)
                        },
                        preparePlayer = {},
                        releasePlayer = { player ->
                            releaseCount += 1
                            viewWasDetachedBeforeRelease = views.singleOrNull()?.player == null
                            player.release()
                        },
                        playerViewFactory = { context ->
                            PlayerView(context).also(views::add)
                        },
                        onPlayerListenerCreated = { playerListener = it }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val player = requireNotNull(players.singleOrNull())
        val view = requireNotNull(views.singleOrNull())
        composeRule.runOnIdle {
            assertSame(player, view.player)
            playerListener.onPlayerError(
                PlaybackException(
                    "Simulated terminal playback failure",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertFalse(player.playWhenReady)
            assertNull(view.player)
        }
        composeRule.onNodeWithText(
            "This direct resource could not be played. The bookmark and discovered media were left unchanged."
        ).fetchSemanticsNode()
        assertEquals(0, releaseCount)

        composeRule.onNodeWithText("Open original page").performClick()
        composeRule.waitForIdle()

        assertEquals(1, sourceFallbackCalls)
        assertEquals(1, releaseCount)
        assertTrue(viewWasDetachedBeforeRelease)
    }

    private fun playablePlayback(): ResolvedMediaAssetPlayback {
        val resourceUrl = "https://media.example.com/video.mp4?token=test"
        val sourcePageUrl = "https://example.com/watch/123"
        val evidence = MediaDiscoveryEvidence(
            source = MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
            detail = "HTTP 200 video/mp4",
            observedMimeType = "video/mp4",
            httpStatusCode = 200
        )
        val asset = MediaAsset(
            id = "asset-1",
            bookmarkId = "bookmark-1",
            remoteUrl = resourceUrl,
            canonicalResourceKey = "https://media.example.com/video.mp4",
            mediaType = MediaCategory.VIDEOS,
            role = MediaAssetRole.CONTENT_VIDEO,
            thumbnailUrl = null,
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            verificationState = MediaVerificationState.PLAYABLE_DIRECT,
            evidence = listOf(evidence),
            sourcePageUrl = sourcePageUrl,
            sourcePageUrls = listOf(sourcePageUrl),
            discoveredAtMillis = 1_000L,
            lastVerifiedAtMillis = 1_000L,
            expiresAtMillis = null,
            authenticationRequired = false,
            userApproved = false
        )
        return ResolvedMediaAssetPlayback(
            asset = asset,
            resolution = PlaybackResolution.DirectRemote(
                resourceUrl = resourceUrl,
                sourcePageUrl = sourcePageUrl,
                mimeType = "video/mp4"
            )
        )
    }
}
