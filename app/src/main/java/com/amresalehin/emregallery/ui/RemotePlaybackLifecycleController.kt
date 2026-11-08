package com.amresalehin.emregallery.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Preserves the user's play intent across lifecycle pauses without autoplaying a manually paused
 * player or restarting a resource after a playback error.
 */
internal class RemotePlaybackLifecycleController(
    private val pausePlayback: () -> Unit,
    private val resumePlayback: () -> Unit
) {
    private var pausedByLifecycle = false
    private var shouldResume = false

    fun onPause(playWhenReady: Boolean) {
        if (pausedByLifecycle) return
        pausedByLifecycle = true
        shouldResume = playWhenReady
        pausePlayback()
    }

    fun onResume(playbackFailed: Boolean) {
        if (!pausedByLifecycle) return
        if (shouldResume && !playbackFailed) {
            resumePlayback()
        }
        shouldResume = false
        pausedByLifecycle = false
    }
}

/**
 * Bridges Android lifecycle events to the playback intent controller.
 *
 * Values are supplied as lambdas so ON_PAUSE observes the player's current intent and ON_RESUME
 * observes the latest failure state, rather than values captured when the observer was created.
 */
internal fun remotePlaybackLifecycleObserver(
    controller: RemotePlaybackLifecycleController,
    playWhenReady: () -> Boolean,
    playbackFailed: () -> Boolean
): LifecycleEventObserver = LifecycleEventObserver { _, event ->
    when (event) {
        Lifecycle.Event.ON_PAUSE -> controller.onPause(playWhenReady())
        Lifecycle.Event.ON_RESUME -> controller.onResume(playbackFailed())
        else -> Unit
    }
}
