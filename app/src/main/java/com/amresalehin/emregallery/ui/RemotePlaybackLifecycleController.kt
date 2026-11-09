package com.amresalehin.emregallery.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Owns lifecycle pause/resume and terminal failure state for one remote playback session.
 *
 * The play intent is captured before lifecycle pause. A terminal player error clears any pending
 * resume request and prevents later lifecycle events from restarting the failed resource.
 */
internal class RemotePlaybackLifecycleController(
    private val pausePlayback: () -> Unit,
    private val resumePlayback: () -> Unit,
    private val onPlaybackFailed: () -> Unit = {}
) {
    private var pausedByLifecycle = false
    private var shouldResume = false
    private var playbackFailed = false

    fun onPlaybackError() {
        if (playbackFailed) return
        playbackFailed = true
        shouldResume = false
        if (!pausedByLifecycle) pausePlayback()
        onPlaybackFailed()
    }

    fun onPause(playWhenReady: Boolean) {
        if (pausedByLifecycle) return
        pausedByLifecycle = true
        shouldResume = playWhenReady && !playbackFailed
        if (!playbackFailed) pausePlayback()
    }

    fun onResume() {
        if (!pausedByLifecycle) return
        if (shouldResume && !playbackFailed) {
            resumePlayback()
        }
        shouldResume = false
        pausedByLifecycle = false
    }
}

/**
 * Prevents callbacks from a disposed player session from mutating Compose state or touching
 * an already-released player. Player callbacks and Compose disposal are serialized under one
 * small lock so close() is a hard boundary even if a callback arrives from another thread.
 */
internal class RemotePlaybackSessionGuard {
    private val lock = Any()
    private var active = true

    fun close() {
        synchronized(lock) {
            active = false
        }
    }

    fun runIfActive(action: () -> Unit) {
        synchronized(lock) {
            if (active) action()
        }
    }
}

/**
 * Bridges Android lifecycle events to the playback intent controller.
 *
 * The play intent is supplied as a lambda so ON_PAUSE observes the player's current value,
 * including the true play intent retained while Media3 is buffering.
 */
internal fun remotePlaybackLifecycleObserver(
    controller: RemotePlaybackLifecycleController,
    playWhenReady: () -> Boolean,
    sessionGuard: RemotePlaybackSessionGuard = RemotePlaybackSessionGuard()
): LifecycleEventObserver = LifecycleEventObserver { _, event ->
    sessionGuard.runIfActive {
        when (event) {
            Lifecycle.Event.ON_PAUSE -> controller.onPause(playWhenReady())
            Lifecycle.Event.ON_RESUME -> controller.onResume()
            else -> Unit
        }
    }
}
