package com.amresalehin.emregallery.ui

/**
 * Rejects playback-option refresh results that outlive their bookmark or selection.
 *
 * All methods are called from the Compose UI coroutine. A new selection supersedes the previous
 * request, and invalidating a bookmark prevents any in-flight result from opening stale content.
 */
internal class PlaybackOptionsRequestGate {
    private var activeBookmarkId: String? = null
    private var generation = 0L

    fun activate(bookmarkId: String) {
        activeBookmarkId = bookmarkId
        generation += 1
    }

    fun begin(bookmarkId: String): Long? {
        if (activeBookmarkId != bookmarkId) return null
        generation += 1
        return generation
    }

    fun isCurrent(bookmarkId: String, requestId: Long): Boolean =
        activeBookmarkId == bookmarkId && generation == requestId

    fun invalidate(bookmarkId: String) {
        if (activeBookmarkId != bookmarkId) return
        activeBookmarkId = null
        generation += 1
    }
}
