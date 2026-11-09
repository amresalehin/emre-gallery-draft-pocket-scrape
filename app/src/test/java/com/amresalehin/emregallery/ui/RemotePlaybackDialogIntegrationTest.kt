package com.amresalehin.emregallery.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    fun prepareFailureRemovesObserverAndDoesNotRetryReleaseWhenReleaseThrows() {
        val playback = playablePlayback()
        val lifecycleOwner = TrackingLifecycleOwner()
        val showDialog = mutableStateOf(true)
        val players = mutableListOf<ExoPlayer>()
        var releaseAttempts = 0
        var observerRemovedBeforeRelease = false
        var playerViewFactoryCalls = 0
        lateinit var playerListener: Player.Listener

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme {
                    if (showDialog.value) {
                        RemotePlaybackDialog(
                            playback = playback,
                            onDismiss = { showDialog.value = false },
                            onOpenSourcePage = null,
                            playerFactory = { context ->
                                ExoPlayer.Builder(context).build().also(players::add)
                            },
                            preparePlayer = {
                                throw IllegalStateException("Simulated preparation failure")
                            },
                            releasePlayer = { player ->
                                releaseAttempts += 1
                                observerRemovedBeforeRelease =
                                    lifecycleOwner.trackingLifecycle.observers.isEmpty()
                                player.release()
                                throw IllegalStateException("Simulated post-release callback failure")
                            },
                            playerViewFactory = { context ->
                                playerViewFactoryCalls += 1
                                PlayerView(context)
                            },
                            onPlayerListenerCreated = { playerListener = it }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, players.size)
        assertEquals(1, releaseAttempts)
        assertTrue(observerRemovedBeforeRelease)
        assertEquals(1, lifecycleOwner.trackingLifecycle.addCount)
        assertEquals(1, lifecycleOwner.trackingLifecycle.removeCount)
        assertTrue(lifecycleOwner.trackingLifecycle.observers.isEmpty())
        assertEquals(0, playerViewFactoryCalls)

        // The failed session's listener must remain inert after cleanup and player release.
        composeRule.runOnIdle {
            playerListener.onPlayerError(
                PlaybackException(
                    "Late error from failed initialization",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Close player").performClick()
        composeRule.waitForIdle()

        // onDispose runs after the failed initialization path; it must not release the player again.
        assertEquals(1, releaseAttempts)
        assertEquals(1, lifecycleOwner.trackingLifecycle.removeCount)
        assertTrue(lifecycleOwner.trackingLifecycle.observers.isEmpty())
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

    @Test
    fun reopenedDialogUsesFreshPlayerAndIgnoresStaleListenerFromDisposedSession() {
        val playback = playablePlayback()
        val showDialog = mutableStateOf(true)
        val players = mutableListOf<ExoPlayer>()
        val views = mutableListOf<PlayerView>()
        val listeners = mutableListOf<Player.Listener>()
        val releaseCounts = mutableMapOf<Int, Int>()
        val detachedBeforeRelease = mutableMapOf<Int, Boolean>()

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
                        preparePlayer = {},
                        releasePlayer = { player ->
                            val sessionIndex = players.indexOfFirst { it === player }
                            releaseCounts[sessionIndex] = (releaseCounts[sessionIndex] ?: 0) + 1
                            detachedBeforeRelease[sessionIndex] =
                                views.getOrNull(sessionIndex)?.player == null
                            player.release()
                        },
                        playerViewFactory = { context ->
                            PlayerView(context).also(views::add)
                        },
                        onPlayerListenerCreated = listeners::add
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val firstPlayer = requireNotNull(players.getOrNull(0))
        val firstView = requireNotNull(views.getOrNull(0))
        val firstListener = requireNotNull(listeners.getOrNull(0))
        composeRule.runOnIdle { assertSame(firstPlayer, firstView.player) }

        composeRule.onNodeWithContentDescription("Close player").performClick()
        composeRule.waitForIdle()

        assertNull(firstView.player)
        assertEquals(1, releaseCounts[0])
        assertEquals(true, detachedBeforeRelease[0])

        composeRule.runOnIdle { showDialog.value = true }
        composeRule.waitForIdle()

        val secondPlayer = requireNotNull(players.getOrNull(1))
        val secondView = requireNotNull(views.getOrNull(1))
        assertFalse(firstPlayer === secondPlayer)
        composeRule.runOnIdle { assertSame(secondPlayer, secondView.player) }

        // A callback already queued by the disposed session must not touch the replacement player.
        composeRule.runOnIdle {
            firstListener.onPlayerError(
                PlaybackException(
                    "Stale session error",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(secondPlayer.playWhenReady)
            assertSame(secondPlayer, secondView.player)
        }

        composeRule.onNodeWithContentDescription("Close player").performClick()
        composeRule.waitForIdle()

        assertNull(secondView.player)
        assertEquals(1, releaseCounts[0])
        assertEquals(1, releaseCounts[1])
        assertEquals(true, detachedBeforeRelease[0])
        assertEquals(true, detachedBeforeRelease[1])
    }

    @Test
    fun dialogDisposalRemovesLifecycleObserverAndRepeatedSessionsDoNotAccumulateObservers() {
        val playback = playablePlayback()
        val lifecycleOwner = TrackingLifecycleOwner()
        val showDialog = mutableStateOf(true)
        val players = mutableListOf<ExoPlayer>()
        val views = mutableListOf<PlayerView>()
        val releaseCounts = mutableMapOf<Int, Int>()
        val detachedBeforeRelease = mutableMapOf<Int, Boolean>()

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme {
                    if (showDialog.value) {
                        RemotePlaybackDialog(
                            playback = playback,
                            onDismiss = { showDialog.value = false },
                            onOpenSourcePage = null,
                            playerFactory = { context ->
                                ExoPlayer.Builder(context).build().also(players::add)
                            },
                            preparePlayer = {},
                            releasePlayer = { player ->
                                val sessionIndex = players.indexOfFirst { it === player }
                                releaseCounts[sessionIndex] =
                                    (releaseCounts[sessionIndex] ?: 0) + 1
                                detachedBeforeRelease[sessionIndex] =
                                    views.getOrNull(sessionIndex)?.player == null
                                player.release()
                            },
                            playerViewFactory = { context ->
                                PlayerView(context).also(views::add)
                            }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val firstPlayer = requireNotNull(players.getOrNull(0))
        val firstView = requireNotNull(views.getOrNull(0))
        val firstObserver = requireNotNull(
            lifecycleOwner.trackingLifecycle.observers.singleOrNull()
        ) as LifecycleEventObserver
        composeRule.runOnIdle {
            assertSame(firstPlayer, firstView.player)
            assertEquals(1, lifecycleOwner.trackingLifecycle.addCount)
            assertEquals(0, lifecycleOwner.trackingLifecycle.removeCount)
        }

        composeRule.onNodeWithContentDescription("Close player").performClick()
        composeRule.waitForIdle()

        assertNull(firstView.player)
        assertEquals(1, releaseCounts[0])
        assertEquals(true, detachedBeforeRelease[0])
        assertTrue(lifecycleOwner.trackingLifecycle.observers.isEmpty())
        assertEquals(1, lifecycleOwner.trackingLifecycle.removeCount)

        composeRule.runOnIdle { showDialog.value = true }
        composeRule.waitForIdle()

        val secondPlayer = requireNotNull(players.getOrNull(1))
        val secondView = requireNotNull(views.getOrNull(1))
        val secondObserver = requireNotNull(
            lifecycleOwner.trackingLifecycle.observers.singleOrNull()
        ) as LifecycleEventObserver
        assertFalse(firstPlayer === secondPlayer)
        assertFalse(firstObserver === secondObserver)
        composeRule.runOnIdle {
            assertSame(secondPlayer, secondView.player)
            assertEquals(2, lifecycleOwner.trackingLifecycle.addCount)
            assertEquals(1, lifecycleOwner.trackingLifecycle.removeCount)
        }

        // A callback already queued by the disposed observer must not pause the replacement player.
        composeRule.runOnIdle {
            firstObserver.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_PAUSE)
            firstObserver.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
            assertTrue(secondPlayer.playWhenReady)
        }

        // The currently registered observer still handles real lifecycle transitions.
        composeRule.runOnIdle {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            assertFalse(secondPlayer.playWhenReady)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            assertTrue(secondPlayer.playWhenReady)
        }

        composeRule.onNodeWithContentDescription("Close player").performClick()
        composeRule.waitForIdle()

        assertNull(secondView.player)
        assertEquals(1, releaseCounts[0])
        assertEquals(1, releaseCounts[1])
        assertEquals(true, detachedBeforeRelease[0])
        assertEquals(true, detachedBeforeRelease[1])
        assertTrue(lifecycleOwner.trackingLifecycle.observers.isEmpty())
        assertEquals(2, lifecycleOwner.trackingLifecycle.addCount)
        assertEquals(2, lifecycleOwner.trackingLifecycle.removeCount)
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
    private class TrackingLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        val trackingLifecycle = TrackingLifecycle(registry)

        override val lifecycle: Lifecycle
            get() = trackingLifecycle

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private class TrackingLifecycle(
        private val registry: LifecycleRegistry
    ) : Lifecycle() {
        val observers = linkedSetOf<LifecycleObserver>()
        var addCount = 0
            private set
        var removeCount = 0
            private set

        override fun addObserver(observer: LifecycleObserver) {
            addCount += 1
            observers += observer
            registry.addObserver(observer)
        }

        override fun removeObserver(observer: LifecycleObserver) {
            removeCount += 1
            observers -= observer
            registry.removeObserver(observer)
        }

        override val currentState: Lifecycle.State
            get() = registry.currentState
    }

}
