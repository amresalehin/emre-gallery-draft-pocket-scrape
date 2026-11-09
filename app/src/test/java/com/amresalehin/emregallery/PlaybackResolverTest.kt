package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaAsset
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryEvidence
import com.amresalehin.emregallery.data.MediaDiscoveryEvidenceSource
import com.amresalehin.emregallery.data.MediaVerificationState
import com.amresalehin.emregallery.data.PlaybackFallbackReason
import com.amresalehin.emregallery.data.PlaybackResolution
import com.amresalehin.emregallery.data.PlaybackResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolverTest {
    @Test
    fun playableVideoRequiresSuccessfulDirectEvidenceAndPreservesSignedUrl() {
        val signedUrl = "https://cdn.example.com/video.m3u8?token=keep%2Fexact&expires=123"

        val result = PlaybackResolver.resolve(
            asset(
                remoteUrl = signedUrl,
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                evidence = listOf(directEvidence("application/vnd.apple.mpegurl", 206))
            )
        )

        assertEquals(
            PlaybackResolution.DirectRemote(
                resourceUrl = signedUrl,
                sourcePageUrl = SOURCE_PAGE,
                mimeType = "application/vnd.apple.mpegurl"
            ),
            result
        )
    }

    @Test
    fun playableAudioAcceptsAudioMimeAndNormalizesMimeParameters() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_AUDIO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                evidence = listOf(directEvidence("audio/mpeg; codecs=mp3", 200))
            )
        )

        assertEquals(
            PlaybackResolution.DirectRemote(
                resourceUrl = "https://cdn.example.com/video.mp4?signature=preserve",
                sourcePageUrl = SOURCE_PAGE,
                mimeType = "audio/mpeg"
            ),
            result
        )
    }

    @Test
    fun verifiedResourceIsNotAutomaticallyPlayable() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.VERIFIED_RESOURCE,
                evidence = listOf(directEvidence("video/mp4", 200))
            )
        )

        assertFallback(result, PlaybackFallbackReason.RESOURCE_NOT_PLAYABLE)
    }

    @Test
    fun directStateWithoutResponseEvidenceFallsBackToSourcePage() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT
            )
        )

        assertFallback(result, PlaybackFallbackReason.MISSING_DIRECT_RESPONSE_EVIDENCE)
    }

    @Test
    fun unsuccessfulDirectResponseCannotAuthorizePlayback() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                evidence = listOf(directEvidence("video/mp4", 403))
            )
        )

        assertFallback(result, PlaybackFallbackReason.UNSUCCESSFUL_DIRECT_RESPONSE)
    }

    @Test
    fun missingMimeOnLatestDirectResponseDoesNotReuseStaleAssetMime() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                mimeType = "video/mp4",
                evidence = listOf(directEvidence(null, 200))
            )
        )

        assertFallback(result, PlaybackFallbackReason.MISSING_MEDIA_MIME)
    }

    @Test
    fun mismatchedMimeCannotPromoteVideoOrAudio() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                evidence = listOf(directEvidence("image/jpeg", 200))
            )
        )

        assertFallback(result, PlaybackFallbackReason.MIME_ROLE_MISMATCH)
    }

    @Test
    fun embedRemainsInBrowserEvenWhenMarkedPlayableEmbed() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.EMBED,
                state = MediaVerificationState.PLAYABLE_EMBED,
                remoteUrl = "https://player.example.com/embed/42"
            )
        )

        assertFallback(result, PlaybackFallbackReason.EMBED_REQUIRES_BROWSER)
    }

    @Test
    fun posterAndAvatarNeverBecomeDirectPlayback() {
        listOf(
            MediaAssetRole.VIDEO_POSTER,
            MediaAssetRole.THUMBNAIL,
            MediaAssetRole.AVATAR
        ).forEach { role ->
            val result = PlaybackResolver.resolve(
                asset(
                    role = role,
                    state = MediaVerificationState.PLAYABLE_DIRECT,
                    evidence = listOf(directEvidence("video/mp4", 200))
                )
            )
            assertFallback(result, PlaybackFallbackReason.PRESENTATION_ONLY)
        }
    }

    @Test
    fun authenticationAndRestrictedStatesReturnOriginalSourcePage() {
        val auth = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                authenticationRequired = true,
                evidence = listOf(directEvidence("video/mp4", 200))
            )
        )
        val restricted = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.ACCESS_RESTRICTED
            )
        )

        assertFallback(auth, PlaybackFallbackReason.AUTHENTICATION_REQUIRED)
        assertFallback(restricted, PlaybackFallbackReason.ACCESS_RESTRICTED)
    }

    @Test
    fun expiredAssetFallsBackWithoutAttemptingPlayback() {
        val result = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                expiresAtMillis = 99L,
                evidence = listOf(directEvidence("video/mp4", 200))
            ),
            nowMillis = 100L
        )

        assertFallback(result, PlaybackFallbackReason.EXPIRED)
    }

    @Test
    fun invalidResourceUrlFallsBackAndInvalidSourcePageIsUnavailable() {
        val invalidResource = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                remoteUrl = "file:///data/local/video.mp4",
                evidence = listOf(directEvidence("video/mp4", 200))
            )
        )
        val privateResource = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                remoteUrl = "http://127.0.0.1/private/video.mp4",
                evidence = listOf(directEvidence("video/mp4", 200))
            )
        )
        val invalidSource = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                sourcePageUrl = "file:///data/local/page.html",
                evidence = listOf(directEvidence("video/mp4", 200))
            )
        )
        val whitespaceSource = PlaybackResolver.resolve(
            asset(
                role = MediaAssetRole.CONTENT_VIDEO,
                state = MediaVerificationState.PLAYABLE_DIRECT,
                sourcePageUrl = " $SOURCE_PAGE ",
                evidence = listOf(directEvidence("video/mp4", 200))
            )
        )

        assertFallback(invalidResource, PlaybackFallbackReason.INVALID_RESOURCE_URL)
        assertFallback(privateResource, PlaybackFallbackReason.INVALID_RESOURCE_URL)
        assertEquals(
            PlaybackResolution.Unavailable(PlaybackFallbackReason.INVALID_SOURCE_PAGE),
            invalidSource
        )
        assertEquals(
            PlaybackResolution.Unavailable(PlaybackFallbackReason.INVALID_SOURCE_PAGE),
            whitespaceSource
        )
    }

    private fun assertFallback(
        result: PlaybackResolution,
        reason: PlaybackFallbackReason
    ) {
        assertEquals(
            PlaybackResolution.OpenSourcePage(SOURCE_PAGE, reason),
            result
        )
    }

    private fun asset(
        remoteUrl: String = "https://cdn.example.com/video.mp4?signature=preserve",
        sourcePageUrl: String = SOURCE_PAGE,
        role: MediaAssetRole,
        state: MediaVerificationState,
        mimeType: String? = null,
        evidence: List<MediaDiscoveryEvidence> = emptyList(),
        expiresAtMillis: Long? = null,
        authenticationRequired: Boolean = false
    ) = MediaAsset(
        id = "asset-1",
        bookmarkId = "bookmark-1",
        remoteUrl = remoteUrl,
        canonicalResourceKey = remoteUrl,
        mediaType = if (role == MediaAssetRole.CONTENT_AUDIO) MediaCategory.AUDIO else MediaCategory.VIDEOS,
        role = role,
        thumbnailUrl = null,
        mimeType = mimeType,
        sizeBytes = 4096L,
        verificationState = state,
        evidence = evidence,
        sourcePageUrl = sourcePageUrl,
        sourcePageUrls = listOf(sourcePageUrl),
        discoveredAtMillis = 1L,
        lastVerifiedAtMillis = 2L,
        expiresAtMillis = expiresAtMillis,
        authenticationRequired = authenticationRequired,
        userApproved = false
    )

    private fun directEvidence(mimeType: String?, status: Int) = MediaDiscoveryEvidence(
        source = MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
        detail = "Test direct response",
        observedMimeType = mimeType,
        httpStatusCode = status,
        contentLengthBytes = 4096L
    )

    companion object {
        private const val SOURCE_PAGE = "https://example.com/article"
    }
}
