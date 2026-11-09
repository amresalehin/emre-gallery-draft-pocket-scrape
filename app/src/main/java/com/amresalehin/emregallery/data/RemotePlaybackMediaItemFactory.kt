package com.amresalehin.emregallery.data

import android.net.Uri
import androidx.media3.common.MediaItem

/**
 * Converts a current resolver decision into a Media3 item without inferring MIME from a URL.
 *
 * Re-resolving immediately before construction prevents a stale UI snapshot from authorizing
 * an asset that has expired or changed to a browser-only state.
 */
object RemotePlaybackMediaItemFactory {
    fun create(
        playback: ResolvedMediaAssetPlayback,
        nowMillis: Long = System.currentTimeMillis()
    ): MediaItem {
        require(PlaybackResolver.resolve(playback.asset, nowMillis) == playback.resolution) {
            "Playback resolution is no longer valid for this asset."
        }

        return MediaItem.Builder()
            .setMediaId(playback.asset.id)
            .setUri(Uri.parse(playback.resolution.resourceUrl))
            .setMimeType(playback.resolution.mimeType)
            .build()
    }
}
