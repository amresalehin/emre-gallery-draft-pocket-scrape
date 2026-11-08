package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryCandidate
import com.amresalehin.emregallery.data.MediaDiscoveryEvidenceSource
import com.amresalehin.emregallery.data.MediaDiscoveryPolicy
import com.amresalehin.emregallery.data.MediaProvenance
import com.amresalehin.emregallery.data.MediaVerificationState
import com.amresalehin.emregallery.data.ScrapedMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDiscoveryModelsTest {

    @Test
    fun domImageRemainsUnverifiedUntilAResourceProbeConfirmsIt() {
        val candidate = MediaDiscoveryCandidate.from(mediaItem())

        assertEquals(MediaAssetRole.CONTENT_IMAGE, candidate.role)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, candidate.verificationState)
        assertTrue(candidate.evidence.any { it.source == MediaDiscoveryEvidenceSource.DOM })
        assertFalse(MediaDiscoveryPolicy.isGalleryEligible(candidate))

        val verified = candidate.copy(verificationState = MediaVerificationState.VERIFIED_RESOURCE)
        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(verified))
        assertFalse(MediaDiscoveryPolicy.canOfferDirectPlayback(verified))
    }

    @Test
    fun directVideoResponseIsVerifiedButNotPlayableWithoutPlaybackResolution() {
        val candidate = MediaDiscoveryCandidate.from(
            mediaItem(
                source = "Direct HTTP",
                isVideo = true,
                mimeType = "video/mp4",
                metadata = mapOf(
                    "direct_response_confirmed" to "true",
                    "mime_hint" to "video/mp4",
                    "http_status_code" to "206"
                )
            )
        )

        assertEquals(MediaAssetRole.CONTENT_VIDEO, candidate.role)
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, candidate.verificationState)
        assertTrue(candidate.evidence.any {
            it.source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE &&
                it.observedMimeType == "video/mp4" &&
                it.httpStatusCode == 206
        })
        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(candidate))
        assertFalse(MediaDiscoveryPolicy.canOfferDirectPlayback(candidate))

        val playable = candidate.copy(verificationState = MediaVerificationState.PLAYABLE_DIRECT)
        assertTrue(MediaDiscoveryPolicy.canOfferDirectPlayback(playable))
        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(playable))
    }

    @Test
    fun directLabelWithoutProbeEvidenceDoesNotMarkAResourceVerified() {
        val candidate = MediaDiscoveryCandidate.from(
            mediaItem(source = "Direct HTTP", isVideo = true)
        )

        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, candidate.verificationState)
        assertFalse(candidate.evidence.any { it.source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE })
    }

    @Test
    fun unsuccessfulDirectResponseCannotVerifyAResource() {
        val candidate = MediaDiscoveryCandidate.from(
            mediaItem(
                source = "Direct HTTP",
                isVideo = true,
                metadata = mapOf(
                    "direct_response_confirmed" to "true",
                    "http_status_code" to "403"
                )
            )
        )

        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, candidate.verificationState)
        assertFalse(candidate.evidence.any { it.source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE })
    }

    @Test
    fun videoPosterRemainsThumbnailMetadataRatherThanASeparateCandidate() {
        val poster = "https://cdn.example.com/poster.jpg"
        val candidate = MediaDiscoveryCandidate.from(
            mediaItem(isVideo = true, thumbnailUrl = poster)
        )

        assertEquals(MediaAssetRole.CONTENT_VIDEO, candidate.role)
        assertEquals("https://cdn.example.com/media.mp4", candidate.resourceUrl)
        assertEquals(poster, candidate.thumbnailUrl)
        assertFalse(candidate.resourceUrl.contains("poster.jpg"))
    }

    @Test
    fun embedPlaybackRequiresEmbedSpecificVerification() {
        val candidate = MediaDiscoveryCandidate.from(mediaItem()).copy(
            role = MediaAssetRole.EMBED,
            verificationState = MediaVerificationState.PLAYABLE_EMBED
        )

        assertTrue(MediaDiscoveryPolicy.canOpenEmbed(candidate))
        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(candidate))
        assertFalse(MediaDiscoveryPolicy.canOfferDirectPlayback(candidate))
    }

    @Test
    fun verifiedProductImagesMayEnterTheContentGallery() {
        val productImage = MediaDiscoveryCandidate.from(mediaItem()).copy(
            role = MediaAssetRole.PRODUCT_IMAGE,
            verificationState = MediaVerificationState.VERIFIED_RESOURCE
        )

        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(productImage))
    }

    @Test
    fun previewAndAvatarRolesNeverEnterTheContentGallery() {
        val verified = MediaDiscoveryCandidate.from(mediaItem()).copy(
            verificationState = MediaVerificationState.VERIFIED_RESOURCE
        )

        listOf(
            MediaAssetRole.PAGE_COVER,
            MediaAssetRole.ARTICLE_HERO,
            MediaAssetRole.THUMBNAIL,
            MediaAssetRole.VIDEO_POSTER,
            MediaAssetRole.AVATAR,
            MediaAssetRole.UI_DECORATION,
            MediaAssetRole.AD_OR_TRACKER,
            MediaAssetRole.UNKNOWN
        ).forEach { role ->
            assertFalse("$role must remain outside the content gallery", MediaDiscoveryPolicy.isGalleryEligible(verified.copy(role = role)))
        }
    }

    @Test
    fun restrictedAndWeakCandidatesCannotEnterGalleryOrDirectPlayback() {
        val candidate = MediaDiscoveryCandidate.from(mediaItem(isVideo = true))
        val blockedStates = listOf(
            MediaVerificationState.DISCOVERED,
            MediaVerificationState.NEEDS_VERIFICATION,
            MediaVerificationState.BROWSER_ONLY,
            MediaVerificationState.AUTH_REQUIRED,
            MediaVerificationState.EXPIRED,
            MediaVerificationState.UNSUPPORTED,
            MediaVerificationState.REJECTED
        )

        blockedStates.forEach { state ->
            val blocked = candidate.copy(verificationState = state)
            assertFalse("$state must not enter the gallery", MediaDiscoveryPolicy.isGalleryEligible(blocked))
            assertFalse("$state must not offer direct playback", MediaDiscoveryPolicy.canOfferDirectPlayback(blocked))
        }
        val authRequired = candidate.copy(verificationState = MediaVerificationState.AUTH_REQUIRED)
        assertTrue(MediaDiscoveryPolicy.requiresManualAuthentication(authRequired))
        assertTrue(MediaDiscoveryPolicy.shouldOpenSourcePage(authRequired))
    }

    @Test
    fun providerEvidenceIsRetainedWithoutCopyingSensitiveHeaders() {
        val candidate = MediaDiscoveryCandidate.from(
            mediaItem(
                source = "yt-dlp",
                isVideo = true,
                provenance = MediaProvenance(
                    extractorEngine = "yt-dlp",
                    pageUrl = "https://example.com/watch"
                ),
                downloadHeaders = mapOf(
                    "Referer" to "https://example.com/watch",
                    "Cookie" to "session=must-not-enter-evidence"
                )
            )
        )

        assertTrue(candidate.evidence.any { it.source == MediaDiscoveryEvidenceSource.PROVIDER_EXTRACTOR })
        assertTrue(candidate.evidence.none { it.detail.contains("must-not-enter-evidence") })
        assertFalse(candidate.toString().contains("must-not-enter-evidence"))
    }

    @Test
    fun manualDownloadSelectionExcludesPresentationOnlyAndBlockedCandidates() {
        val content = MediaDiscoveryCandidate.from(mediaItem()).copy(
            verificationState = MediaVerificationState.NEEDS_VERIFICATION
        )
        assertTrue(MediaDiscoveryPolicy.shouldOfferForManualDownload(content))

        val excludedRoles = listOf(
            MediaAssetRole.PAGE_COVER,
            MediaAssetRole.ARTICLE_HERO,
            MediaAssetRole.THUMBNAIL,
            MediaAssetRole.VIDEO_POSTER,
            MediaAssetRole.EMBED,
            MediaAssetRole.AVATAR,
            MediaAssetRole.UI_DECORATION,
            MediaAssetRole.AD_OR_TRACKER
        )
        excludedRoles.forEach { role ->
            assertFalse(
                "$role must not enter manual download selection",
                MediaDiscoveryPolicy.shouldOfferForManualDownload(content.copy(role = role))
            )
        }

        listOf(
            MediaVerificationState.BROWSER_ONLY,
            MediaVerificationState.AUTH_REQUIRED,
            MediaVerificationState.ACCESS_RESTRICTED,
            MediaVerificationState.RATE_LIMITED,
            MediaVerificationState.EXPIRED,
            MediaVerificationState.UNSUPPORTED,
            MediaVerificationState.REJECTED
        ).forEach { state ->
            assertFalse(
                "$state must remain source-page-only",
                MediaDiscoveryPolicy.shouldOfferForManualDownload(content.copy(verificationState = state))
            )
        }
    }

    @Test
    fun downloaderSessionDoesNotOfferTaggedAvatarsOrThumbnails() {
        DownloaderSession.startSession("https://example.com/gallery")
        val avatar = mediaItem(metadata = mapOf("asset_role" to "AVATAR")).copy(id = "avatar")
        val thumbnail = mediaItem(metadata = mapOf("asset_role" to "THUMBNAIL")).copy(id = "thumbnail")
        val content = mediaItem().copy(id = "content")

        DownloaderSession.addDiscoveredItems(listOf(avatar, thumbnail, content))

        assertEquals(listOf("content"), DownloaderSession.discoveredItems.value.map { it.id })
        assertEquals(setOf("content"), DownloaderSession.selectedItemIds.value)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, MediaDiscoveryCandidate.from(content).verificationState)
    }

    private fun mediaItem(
        source: String = "DOM",
        isVideo: Boolean = false,
        isAudio: Boolean = false,
        thumbnailUrl: String? = null,
        mimeType: String = if (isVideo) "video/mp4" else if (isAudio) "audio/mpeg" else "image/jpeg",
        provenance: MediaProvenance = MediaProvenance(
            extractorEngine = if (source == "DOM") "Static HTML (DOM Scrape)" else source,
            pageUrl = "https://example.com/gallery"
        ),
        metadata: Map<String, String> = emptyMap(),
        downloadHeaders: Map<String, String> = emptyMap()
    ) = ScrapedMediaItem(
        id = "candidate-1",
        url = if (isVideo) "https://cdn.example.com/media.mp4" else "https://cdn.example.com/image.jpg",
        title = "Candidate",
        isVideo = isVideo,
        isAudio = isAudio,
        category = when {
            isVideo -> MediaCategory.VIDEOS
            isAudio -> MediaCategory.AUDIO
            else -> MediaCategory.IMAGES
        },
        format = if (isVideo) "MP4" else if (isAudio) "MP3" else "JPEG",
        thumbnailUrl = thumbnailUrl,
        source = source,
        mimeType = mimeType,
        provenance = provenance,
        metadata = metadata,
        downloadHeaders = downloadHeaders
    )
}
