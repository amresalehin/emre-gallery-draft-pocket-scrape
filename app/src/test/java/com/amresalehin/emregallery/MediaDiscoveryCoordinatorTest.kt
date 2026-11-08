package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryCoordinator
import com.amresalehin.emregallery.data.MediaDiscoveryEvidenceSource
import com.amresalehin.emregallery.data.MediaDiscoveryPolicy
import com.amresalehin.emregallery.data.MediaProvenance
import com.amresalehin.emregallery.data.MediaVerificationState
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class MediaDiscoveryCoordinatorTest {

    @Test
    fun successfulProbeVerifiesResourceWithoutPromotingPlayback() = runBlocking {
        val pageUrl = "https://example.com/gallery"
        val resourceUrl = "https://cdn.example.com/photo.jpg"
        val result = MediaDiscoveryCoordinator(
            probe = { url ->
                assertEquals(resourceUrl, url)
                OkHttpDownloader.DirectMediaProbeResult(
                    isConfirmedDirectMedia = true,
                    finalUrl = url,
                    contentType = "image/jpeg",
                    contentDisposition = null,
                    contentLength = 2048L,
                    statusCode = 200
                )
            }
        ).discover(listOf(imageItem(resourceUrl, pageUrl)))

        val candidate = result.candidates.single()
        assertEquals(1, result.probesAttempted)
        assertEquals(resourceUrl, candidate.resourceUrl)
        assertEquals(pageUrl, candidate.sourcePageUrl)
        assertEquals(MediaAssetRole.CONTENT_IMAGE, candidate.role)
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, candidate.verificationState)
        assertTrue(candidate.evidence.any {
            it.source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE &&
                it.observedMimeType == "image/jpeg" &&
                it.httpStatusCode == 200
        })
        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(candidate))
        assertFalse(MediaDiscoveryPolicy.canOfferDirectPlayback(candidate))
    }

    @Test
    fun mismatchedResponseMimeCannotVerifyTheCandidateRole() = runBlocking {
        val result = MediaDiscoveryCoordinator(
            probe = { url ->
                OkHttpDownloader.DirectMediaProbeResult(
                    isConfirmedDirectMedia = true,
                    finalUrl = url,
                    contentType = "video/mp4",
                    contentDisposition = null,
                    contentLength = 4096L,
                    statusCode = 200
                )
            }
        ).discover(listOf(imageItem("https://cdn.example.com/not-an-image.jpg")))

        val candidate = result.candidates.single()
        assertEquals(MediaVerificationState.REJECTED, candidate.verificationState)
        assertTrue(MediaDiscoveryPolicy.shouldOpenSourcePage(candidate))
        assertFalse(MediaDiscoveryPolicy.isGalleryEligible(candidate))
    }

    @Test
    fun genericBinaryWithMatchingMediaFilenameCanVerifyVideo() = runBlocking {
        val result = MediaDiscoveryCoordinator(
            probe = { url ->
                OkHttpDownloader.DirectMediaProbeResult(
                    isConfirmedDirectMedia = true,
                    finalUrl = url,
                    contentType = "application/octet-stream",
                    contentDisposition = "attachment; filename=clip.mp4",
                    contentLength = 4096L,
                    statusCode = 200
                )
            }
        ).discover(listOf(videoItem("https://cdn.example.com/download")))

        val candidate = result.candidates.single()
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, candidate.verificationState)
        assertTrue(MediaDiscoveryPolicy.isGalleryEligible(candidate))
    }

    @Test
    fun accessRestrictedCandidateRemainsOnTheSourcePagePath() = runBlocking {
        val result = MediaDiscoveryCoordinator(
            probe = { url ->
                OkHttpDownloader.DirectMediaProbeResult(
                    isConfirmedDirectMedia = false,
                    finalUrl = url,
                    contentType = "text/html",
                    contentDisposition = null,
                    contentLength = null,
                    statusCode = 403
                )
            }
        ).discover(listOf(imageItem("https://cdn.example.com/private.jpg")))

        val candidate = result.candidates.single()
        assertEquals(MediaVerificationState.ACCESS_RESTRICTED, candidate.verificationState)
        assertTrue(MediaDiscoveryPolicy.shouldOpenSourcePage(candidate))
        assertFalse(MediaDiscoveryPolicy.isGalleryEligible(candidate))
    }

    @Test
    fun originalSourcePageUrlOverridesExtractorProvenance() = runBlocking {
        val sourcePageUrl = "https://example.com/original?keep=1"
        val result = MediaDiscoveryCoordinator(
            probe = { throw IOException("probe intentionally unavailable") }
        ).discover(
            items = listOf(
                imageItem(
                    "https://cdn.example.com/image.jpg",
                    pageUrl = "https://cdn.example.com/embed-frame"
                )
            ),
            sourcePageUrl = sourcePageUrl
        )

        assertEquals(sourcePageUrl, result.candidates.single().sourcePageUrl)
    }

    @Test
    fun privateAddressIsRejectedBeforeTheProbeRuns() = runBlocking {
        var probeCalls = 0
        val result = MediaDiscoveryCoordinator(
            probe = {
                probeCalls += 1
                throw AssertionError("Unsafe destination must not be probed")
            }
        ).discover(listOf(imageItem("http://127.0.0.1/private.jpg")))

        assertEquals(0, probeCalls)
        assertEquals(1, result.unsafeCandidatesRejected)
        assertEquals(MediaVerificationState.REJECTED, result.candidates.single().verificationState)
        assertTrue(MediaDiscoveryPolicy.shouldOpenSourcePage(result.candidates.single()))
    }

    @Test
    fun previewAndUiRolesArePreservedButNeverProbed() = runBlocking {
        val roles = listOf(
            MediaAssetRole.VIDEO_POSTER,
            MediaAssetRole.THUMBNAIL,
            MediaAssetRole.AVATAR,
            MediaAssetRole.PAGE_COVER,
            MediaAssetRole.ARTICLE_HERO,
            MediaAssetRole.UI_DECORATION,
            MediaAssetRole.AD_OR_TRACKER
        )
        var probeCalls = 0
        val result = MediaDiscoveryCoordinator(
            probe = {
                probeCalls += 1
                throw AssertionError("Preview and UI candidates must not be probed")
            }
        ).discover(
            roles.mapIndexed { index, role ->
                imageItem(
                    "https://cdn.example.com/ui-$index.jpg",
                    metadata = mapOf("asset_role" to role.name)
                )
            }
        )

        assertEquals(0, probeCalls)
        assertEquals(roles.size, result.candidates.size)
        assertEquals(roles.toSet(), result.candidates.map { it.role }.toSet())
        assertTrue(result.candidates.none { MediaDiscoveryPolicy.isGalleryEligible(it) })
    }

    @Test
    fun probeFailureLeavesCandidateUnverifiedWithoutFailingDiscovery() = runBlocking {
        val result = MediaDiscoveryCoordinator(
            probe = { throw IOException("temporary network failure") }
        ).discover(listOf(imageItem("https://cdn.example.com/image.jpg")))

        assertEquals(1, result.probesFailed)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, result.candidates.single().verificationState)
    }

    @Test
    fun probeCountAndConcurrencyAreBounded() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val coordinator = MediaDiscoveryCoordinator(
            probe = { url ->
                calls.incrementAndGet()
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, nowActive) }
                try {
                    delay(25)
                    OkHttpDownloader.DirectMediaProbeResult(
                        isConfirmedDirectMedia = true,
                        finalUrl = url,
                        contentType = "image/jpeg",
                        contentDisposition = null,
                        contentLength = 1024L,
                        statusCode = 200
                    )
                } finally {
                    active.decrementAndGet()
                }
            },
            maxConcurrentProbes = 2,
            maxCandidatesToProbe = 3
        )

        val result = coordinator.discover(
            (1..5).map { index ->
                imageItem("https://cdn.example.com/image-$index.jpg")
            }
        )

        assertEquals(3, calls.get())
        assertEquals(3, result.probesAttempted)
        assertEquals(2, result.deferredCandidates)
        assertTrue(maximumActive.get() <= 2)
    }

    private fun videoItem(url: String) = ScrapedMediaItem(
        id = "video-" + url.hashCode(),
        url = url,
        title = "Video",
        isVideo = true,
        category = MediaCategory.VIDEOS,
        format = "MP4",
        source = "DOM",
        mimeType = "video/mp4",
        provenance = MediaProvenance(
            extractorEngine = "Static HTML (DOM Scrape)",
            pageUrl = "https://example.com/watch"
        )
    )

    private fun imageItem(
        url: String,
        pageUrl: String = "https://example.com/gallery",
        metadata: Map<String, String> = emptyMap()
    ) = ScrapedMediaItem(
        id = "item-" + url.hashCode(),
        url = url,
        title = "Image",
        category = MediaCategory.IMAGES,
        format = "JPEG",
        source = "DOM",
        mimeType = "image/jpeg",
        provenance = MediaProvenance(
            extractorEngine = "Static HTML (DOM Scrape)",
            pageUrl = pageUrl
        ),
        metadata = metadata
    )
}
