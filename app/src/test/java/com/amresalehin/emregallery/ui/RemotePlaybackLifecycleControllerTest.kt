package com.amresalehin.emregallery.ui

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

    private fun controller(actions: MutableList<String>) =
        RemotePlaybackLifecycleController(
            pausePlayback = { actions += "pause" },
            resumePlayback = { actions += "play" }
        )
}
