package com.amresalehin.emregallery.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlaybackLifecycleControllerTest {
    @Test
    fun resumesWhenPlaybackWasRequestedBeforeLifecyclePause() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onResume()

        assertEquals(listOf("pause", "play"), actions)
    }

    @Test
    fun doesNotResumeWhenUserHadAlreadyPausedPlayback() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = false)
        controller.onResume()

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun duplicatePauseDoesNotEraseOriginalPlayIntent() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onPause(playWhenReady = false)
        controller.onResume()

        assertEquals(listOf("pause", "play"), actions)
    }

    @Test
    fun doesNotResumeAfterTerminalPlaybackError() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onPlaybackError()
        controller.onResume()

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun terminalFailureWhileResumedPausesImmediatelyAndIsIdempotent() {
        val actions = mutableListOf<String>()
        var failureNotifications = 0
        val controller = RemotePlaybackLifecycleController(
            pausePlayback = { actions += "pause" },
            resumePlayback = { actions += "play" },
            onPlaybackFailed = { failureNotifications += 1 }
        )

        controller.onPlaybackError()
        controller.onPlaybackError()
        controller.onPause(playWhenReady = false)
        controller.onResume()

        assertEquals(listOf("pause"), actions)
        assertEquals(1, failureNotifications)
    }

    @Test
    fun resumeWithoutLifecyclePauseIsIgnored() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onResume()

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun lifecycleObserverPreservesPlayIntentWhenPausedDuringBuffering() {
        val actions = mutableListOf<String>()
        var playWhenReady = true // Media3 keeps this true while STATE_BUFFERING.
        val observer = remotePlaybackLifecycleObserver(
            controller = RemotePlaybackLifecycleController(
                pausePlayback = {
                    actions += "pause"
                    playWhenReady = false
                },
                resumePlayback = {
                    actions += "play"
                    playWhenReady = true
                }
            ),
            playWhenReady = { playWhenReady }
        )
        val owner = resumedLifecycleOwner(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("pause", "play"), actions)
        assertTrue(playWhenReady)
    }

    @Test
    fun lifecycleObserverDoesNotResumeAfterFailureArrivesWhilePaused() {
        val actions = mutableListOf<String>()
        var playWhenReady = true
        var failureNotifications = 0
        val controller = RemotePlaybackLifecycleController(
            pausePlayback = {
                actions += "pause"
                playWhenReady = false
            },
            resumePlayback = { actions += "play" },
            onPlaybackFailed = { failureNotifications += 1 }
        )
        val observer = remotePlaybackLifecycleObserver(
            controller = controller,
            playWhenReady = { playWhenReady }
        )
        val owner = resumedLifecycleOwner(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        controller.onPlaybackError()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        controller.onPlaybackError()

        assertEquals(listOf("pause"), actions)
        assertEquals(1, failureNotifications)
        assertFalse(playWhenReady)
    }

    @Test
    fun lifecycleObserverIgnoresUnrelatedEvents() {
        val actions = mutableListOf<String>()
        val observer = remotePlaybackLifecycleObserver(
            controller = controller(actions),
            playWhenReady = { true }
        )
        val owner = TestLifecycleOwner()
        owner.lifecycle.addObserver(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertEquals(emptyList<String>(), actions)
    }

    private fun controller(actions: MutableList<String>) =
        RemotePlaybackLifecycleController(
            pausePlayback = { actions += "pause" },
            resumePlayback = { actions += "play" }
        )

    private fun resumedLifecycleOwner(
        observer: LifecycleEventObserver
    ): TestLifecycleOwner {
        val owner = TestLifecycleOwner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.lifecycle.addObserver(observer)
        return owner
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }
}
