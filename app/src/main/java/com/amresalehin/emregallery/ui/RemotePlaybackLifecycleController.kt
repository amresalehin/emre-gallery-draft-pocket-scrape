package com.amresalehin.emregallery.ui

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
