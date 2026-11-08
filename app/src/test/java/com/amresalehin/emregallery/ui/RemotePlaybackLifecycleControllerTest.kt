package com.amresalehin.emregallery.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePlaybackLifecycleControllerTest {
    @Test
    fun resumesWhenPlaybackWasRequestedBeforeLifecyclePause() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onResume(playbackFailed = false)

        assertEquals(listOf("pause", "play"), actions)
    }

    @Test
    fun doesNotResumeWhenUserHadAlreadyPausedPlayback() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = false)
        controller.onResume(playbackFailed = false)

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun duplicatePauseDoesNotEraseOriginalPlayIntent() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onPause(playWhenReady = false)
        controller.onResume(playbackFailed = false)

        assertEquals(listOf("pause", "play"), actions)
    }

    @Test
    fun doesNotResumeAfterPlaybackError() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onResume(playbackFailed = true)

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun resumeWithoutLifecyclePauseIsIgnored() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onResume(playbackFailed = false)

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun lifecycleObserverReadsFailureStateWhenResumeEventArrives() {
        val actions = mutableListOf<String>()
        var playWhenReady = true
        var playbackFailed = false
        val controller = RemotePlaybackLifecycleController(
            pausePlayback = {
                actions += "pause"
                playWhenReady = false
            },
            resumePlayback = {
                actions += "play"
                playWhenReady = true
            }
        )
        val observer = remotePlaybackLifecycleObserver(
            controller = controller,
            playWhenReady = { playWhenReady },
            playbackFailed = { playbackFailed }
        )

        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_PAUSE)
        playbackFailed = true
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun lifecycleObserverUsesFreshPlayIntentForEachPauseCycle() {
        val actions = mutableListOf<String>()
        var playWhenReady = true
        val controller = RemotePlaybackLifecycleController(
            pausePlayback = {
                actions += "pause"
                playWhenReady = false
            },
            resumePlayback = {
                actions += "play"
                playWhenReady = true
            }
        )
        val observer = remotePlaybackLifecycleObserver(
            controller = controller,
            playWhenReady = { playWhenReady },
            playbackFailed = { false }
        )

        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_RESUME)
        playWhenReady = false
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("pause", "play", "pause"), actions)
    }

    @Test
    fun lifecycleObserverIgnoresUnrelatedEvents() {
        val actions = mutableListOf<String>()
        val observer = remotePlaybackLifecycleObserver(
            controller = controller(actions),
            playWhenReady = { true },
            playbackFailed = { false }
        )

        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_CREATE)
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_START)
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_STOP)
        observer.onStateChanged(testLifecycleOwner, Lifecycle.Event.ON_DESTROY)

        assertEquals(emptyList<String>(), actions)
    }

    private fun controller(actions: MutableList<String>) =
        RemotePlaybackLifecycleController(
            pausePlayback = { actions += "pause" },
            resumePlayback = { actions += "play" }
        )

    private val testLifecycleOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = error("Lifecycle owner state must not be queried by the event observer")
    }
}
