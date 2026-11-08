package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryCandidate
import com.amresalehin.emregallery.data.MediaDiscoveryPolicy
import com.amresalehin.emregallery.data.MediaProvenance
import com.amresalehin.emregallery.data.MediaVerificationState
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkHttpDirectMediaProbeTest {

    @Test
    fun mediaLookingExtensionAloneDoesNotVerifyAResource() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(206))

        val result = OkHttpDownloader.probeDirectMedia(server.url("/protected.mp4").toString())

        assertFalse(result.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, result.verificationState)
        assertEquals(206, result.statusCode)
        assertEquals("HEAD", server.takeRequest().method)
        val rangeRequest = server.takeRequest()
        assertEquals("GET", rangeRequest.method)
        assertEquals("bytes=0-1024", rangeRequest.getHeader("Range"))
    }

    @Test
    fun mediaMimeTypeOnHeadConfirmsWithoutRangeFallback() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4; charset=binary")
                .setHeader("Content-Length", "4096")
        )

        val result = OkHttpDownloader.probeDirectMedia(server.url("/asset").toString())

        assertTrue(result.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, result.verificationState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun genericBinaryRequiresARecognizedMediaFilenameDisposition() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Disposition", "attachment; filename*=UTF-8''clip%20final.mp4")
        )

        val result = OkHttpDownloader.probeDirectMedia(server.url("/download").toString())

        assertTrue(result.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, result.verificationState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun htmlChallengeAtMediaLookingUrlStaysBrowserOnly() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setHeader("Content-Disposition", "attachment; filename=\"protected.mp4\"")
                .setBody("<html><title>Sign in</title></html>")
        )

        val result = OkHttpDownloader.probeDirectMedia(server.url("/protected.mp4").toString())

        assertFalse(result.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.BROWSER_ONLY, result.verificationState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun accessAndRateLimitResponsesArePreservedWithoutRangeRetry() = withServer { server ->
        val cases = listOf(
            401 to MediaVerificationState.AUTH_REQUIRED,
            403 to MediaVerificationState.ACCESS_RESTRICTED,
            429 to MediaVerificationState.RATE_LIMITED,
            410 to MediaVerificationState.EXPIRED
        )

        cases.forEach { (status, expectedState) ->
            val requestsBefore = server.requestCount
            server.enqueue(
                MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "text/html")
            )

            val result = OkHttpDownloader.probeDirectMedia(server.url("/asset.mp4").toString())

            assertFalse("$status must not verify a media resource", result.isConfirmedDirectMedia)
            assertEquals(expectedState, result.verificationState)
            assertEquals(requestsBefore + 1, server.requestCount)
        }
    }

    @Test
    fun rangeFallbackRequiresPartialContentAndStrongMediaEvidence() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1024/4096")
                .setBody("x".repeat(1025))
        )

        val confirmed = OkHttpDownloader.probeDirectMedia(server.url("/clip").toString())

        assertTrue(confirmed.isConfirmedDirectMedia)
        assertEquals(206, confirmed.statusCode)

        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
        )

        val ignoredRange = OkHttpDownloader.probeDirectMedia(server.url("/ignored-range").toString())

        assertFalse(ignoredRange.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, ignoredRange.verificationState)

        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
        )

        val missingContentRange = OkHttpDownloader.probeDirectMedia(server.url("/missing-range-header").toString())

        assertFalse(missingContentRange.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, missingContentRange.verificationState)

        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-4096/8192")
        )

        val oversizedRange = OkHttpDownloader.probeDirectMedia(server.url("/oversized-range").toString())

        assertFalse(oversizedRange.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, oversizedRange.verificationState)

        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-1024/1024")
        )

        val invalidTotal = OkHttpDownloader.probeDirectMedia(server.url("/invalid-range-total").toString())

        assertFalse(invalidTotal.isConfirmedDirectMedia)
        assertEquals(MediaVerificationState.NEEDS_VERIFICATION, invalidTotal.verificationState)
    }

    @Test
    fun accessStatesRemainSourcePageFallbacksAndNeverBecomeGalleryAssets() {
        val base = MediaDiscoveryCandidate.from(
            ScrapedMediaItem(
                id = "candidate",
                url = "https://cdn.example.com/video.mp4",
                title = "Video",
                isVideo = true,
                category = MediaCategory.VIDEOS,
                format = "MP4",
                source = "DOM",
                mimeType = "video/mp4",
                provenance = MediaProvenance(
                    extractorEngine = "DOM Scrape",
                    pageUrl = "https://example.com/watch"
                )
            )
        ).copy(role = MediaAssetRole.CONTENT_VIDEO)

        listOf(
            MediaVerificationState.BROWSER_ONLY,
            MediaVerificationState.AUTH_REQUIRED,
            MediaVerificationState.ACCESS_RESTRICTED,
            MediaVerificationState.RATE_LIMITED,
            MediaVerificationState.EXPIRED
        ).forEach { state ->
            val candidate = base.copy(verificationState = state)
            assertTrue("$state should preserve source-page fallback", MediaDiscoveryPolicy.shouldOpenSourcePage(candidate))
            assertFalse("$state must not enter the content gallery", MediaDiscoveryPolicy.isGalleryEligible(candidate))
        }
    }

    private fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            runBlocking { block(server) }
        } finally {
            server.shutdown()
        }
    }
}
