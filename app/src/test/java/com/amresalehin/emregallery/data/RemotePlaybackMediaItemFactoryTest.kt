package com.amresalehin.emregallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlaybackMediaItemFactoryTest {
    @Test
    fun createPreservesSignedResourceUrlAndResolverMimeType() {
        val asset = playableAsset()
        val resolution = PlaybackResolver.resolve(asset, nowMillis = 100L)
            as PlaybackResolution.DirectRemote

        val item = RemotePlaybackMediaItemFactory.create(
            ResolvedMediaAssetPlayback(asset, resolution),
            nowMillis = 100L
        )

        assertEquals(asset.id, item.mediaId)
        assertEquals(SIGNED_URL, item.localConfiguration?.uri.toString())
        assertEquals(MIME_TYPE, item.localConfiguration?.mimeType)
    }

    @Test
    fun createRejectsAResolutionThatExpiredAfterTheUiSnapshot() {
        val asset = playableAsset().copy(expiresAtMillis = 100L)
        val resolution = PlaybackResolver.resolve(asset, nowMillis = 99L)
            as PlaybackResolution.DirectRemote

        try {
            RemotePlaybackMediaItemFactory.create(
                ResolvedMediaAssetPlayback(asset, resolution),
                nowMillis = 100L
            )
            fail("An expired playback resolution must not create a Media3 item.")
        } catch (_: IllegalArgumentException) {
            // Expected: the resolver no longer approves this asset.
        }
    }

    private fun playableAsset() = MediaAsset(
        id = "asset-1",
        bookmarkId = "bookmark-1",
        remoteUrl = SIGNED_URL,
        canonicalResourceKey = SIGNED_URL,
        mediaType = MediaCategory.VIDEOS,
        role = MediaAssetRole.CONTENT_VIDEO,
        thumbnailUrl = null,
        mimeType = MIME_TYPE,
        sizeBytes = 4096L,
        verificationState = MediaVerificationState.PLAYABLE_DIRECT,
        evidence = listOf(
            MediaDiscoveryEvidence(
                source = MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
                detail = "Successful direct response",
                observedMimeType = MIME_TYPE,
                httpStatusCode = 206,
                contentLengthBytes = 4096L
            )
        ),
        sourcePageUrl = SOURCE_PAGE,
        sourcePageUrls = listOf(SOURCE_PAGE),
        discoveredAtMillis = 1L,
        lastVerifiedAtMillis = 99L,
        expiresAtMillis = null,
        authenticationRequired = false,
        userApproved = false
    )

    companion object {
        private const val SIGNED_URL =
            "https://cdn.example.com/stream?token=keep%2Fexact&expires=123"
        private const val SOURCE_PAGE = "https://example.com/article"
        private const val MIME_TYPE = "application/vnd.apple.mpegurl"
    }
}
