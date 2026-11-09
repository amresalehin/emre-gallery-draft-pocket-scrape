package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaDiscoveryCandidate
import com.amresalehin.emregallery.data.MediaDiscoveryPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaCaptureEnginePreviewIsolationTest {

    @Test
    fun domScanDropsStandalonePosterAndStructuredPreviewImages() {
        val scan = JSONArray()
            .put(candidate("https://cdn.example.com/poster.jpg", role = "poster", source = "POSTER"))
            .put(candidate("https://cdn.example.com/thumb.jpg", role = "json-ld-thumb"))
            .put(candidate("https://cdn.example.com/hero.jpg", role = "json-ld-image"))
            .put(
                candidate(
                    "https://cdn.example.com/video.mp4",
                    role = "video",
                    poster = "https://cdn.example.com/poster.jpg"
                )
            )

        val items = MediaCaptureEngine.parseJsDomScanResult(scan.toString(), "https://example.com/article")

        assertEquals(1, items.size)
        assertTrue(items.single().isVideo)
        assertEquals("https://cdn.example.com/poster.jpg", items.single().thumbnailUrl)
    }

    @Test
    fun domScanKeepsStructuredContentUrlButNotPreviewFields() {
        val scan = JSONArray()
            .put(candidate("https://cdn.example.com/content.mp4", role = "json-ld"))
            .put(candidate("https://cdn.example.com/thumbnail.jpg", role = "json-ld-thumb"))
            .put(candidate("https://cdn.example.com/image.jpg", role = "json-ld-image"))

        val items = MediaCaptureEngine.parseJsDomScanResult(scan.toString(), "https://example.com/article")

        assertEquals(1, items.size)
        assertTrue(items.single().isVideo)
        assertEquals("https://cdn.example.com/content.mp4", items.single().url)
    }

    @Test
    fun domScanStillAcceptsActualImageCandidates() {
        val scan = JSONArray().put(
            candidate(
                "https://cdn.example.com/gallery/photo.jpg",
                role = "2x",
                source = "SRCSET"
            )
        )

        val items = MediaCaptureEngine.parseJsDomScanResult(scan.toString(), "https://example.com/gallery")

        assertEquals(1, items.size)
        assertFalse(items.single().isVideo)
        assertEquals("2x", items.single().resolutionHint)
    }

    @Test
    fun networkImageSubresourcesAreNotTreatedAsContentAssets() {
        assertFalse(
            MediaCaptureEngine.shouldCaptureNetworkCandidate(
                isVideo = false,
                isAudio = false,
                isImage = true,
                isMainFrame = false
            )
        )
        assertTrue(
            MediaCaptureEngine.shouldCaptureNetworkCandidate(
                isVideo = false,
                isAudio = false,
                isImage = true,
                isMainFrame = true
            )
        )
        assertTrue(
            MediaCaptureEngine.shouldCaptureNetworkCandidate(
                isVideo = true,
                isAudio = false,
                isImage = false,
                isMainFrame = false
            )
        )
        assertTrue(
            MediaCaptureEngine.shouldCaptureNetworkCandidate(
                isVideo = false,
                isAudio = true,
                isImage = false,
                isMainFrame = false
            )
        )
    }

    @Test
    fun mediaCandidatesNeverCopySessionCookiesIntoDownloadHeaders() {
        val item = MediaCaptureEngine.buildMediaItem(
            rawUrl = "https://cdn.example.com/photo.jpg",
            source = ExtractionSource.DOM_SCRAPE,
            baseUrl = "https://example.com/gallery"
        )

        assertTrue(item != null)
        assertFalse(item!!.downloadHeaders.keys.any {
            it.equals("Cookie", ignoreCase = true) ||
                    it.equals("Authorization", ignoreCase = true)
        })
    }

    @Test
    fun injectedScansDoNotEmitPosterOnlyOrJsonLdPreviewCandidates() {
        val domScript = MediaCaptureEngine.getJsDomScanScript()
        assertFalse(domScript.contains("s: 'POSTER'"))
        assertFalse(domScript.contains("r: 'json-ld-thumb'"))
        assertFalse(domScript.contains("r: 'json-ld-image'"))

        val observerScript = MediaCaptureEngine.getMutationObserverScript()
        assertFalse(observerScript.contains("report(node.getAttribute('poster'))"))
        assertFalse(observerScript.contains("'poster']"))
    }

    @Test
    fun domScanPreservesAvatarProductAndPreviewRolesForDiscoveryPolicy() {
        val scan = JSONArray()
            .put(candidate("https://cdn.example.com/users/avatar.jpg", role = "avatar").put("a", "AVATAR"))
            .put(candidate("https://cdn.example.com/products/front.jpg", role = "2x").put("a", "PRODUCT_IMAGE"))
            .put(candidate("https://cdn.example.com/video/thumb.jpg", role = "2x").put("a", "THUMBNAIL"))

        val items = MediaCaptureEngine.parseJsDomScanResult(scan.toString(), "https://example.com/gallery")
        val byRole = items.associateBy { it.metadata["asset_role"] }

        val avatar = requireNotNull(byRole["AVATAR"])
        val avatarCandidate = MediaDiscoveryCandidate.from(avatar)
        assertEquals(MediaAssetRole.AVATAR, avatarCandidate.role)
        assertFalse(MediaDiscoveryPolicy.shouldOfferForManualDownload(avatarCandidate))

        val product = requireNotNull(byRole["PRODUCT_IMAGE"])
        val productCandidate = MediaDiscoveryCandidate.from(product)
        assertEquals(MediaAssetRole.PRODUCT_IMAGE, productCandidate.role)
        assertTrue(MediaDiscoveryPolicy.shouldOfferForManualDownload(productCandidate))

        val thumbnail = requireNotNull(byRole["THUMBNAIL"])
        assertFalse(MediaDiscoveryPolicy.shouldOfferForManualDownload(MediaDiscoveryCandidate.from(thumbnail)))
    }

    @Test
    fun invalidRoleHintsAreIgnoredRatherThanTrusted() {
        val item = MediaCaptureEngine.parseJsDomScanResult(
            JSONArray().put(candidate("https://cdn.example.com/photo.jpg", role = "2x").put("a", "DOWNLOAD_EVERYTHING")).toString(),
            "https://example.com/gallery"
        ).single()

        assertFalse(item.metadata.containsKey("asset_role"))
        assertEquals(MediaAssetRole.CONTENT_IMAGE, MediaDiscoveryCandidate.from(item).role)
    }

    @Test
    fun mutationObserverPropagatesSemanticRolesToTheNativeBridge() {
        val domScript = MediaCaptureEngine.getJsDomScanScript()
        assertTrue(domScript.contains("a: assetRole"))
        assertTrue(domScript.contains("function imageAssetRole(el)"))
        assertTrue(domScript.contains("return 'AVATAR'"))

        val observerScript = MediaCaptureEngine.getMutationObserverScript()
        assertTrue(observerScript.contains("reportMedia(abs, assetRole)"))
        assertTrue(observerScript.contains("var imageRole = imageAssetRole(node)"))
        assertTrue(observerScript.contains("report(node.src, imageRole)"))
        assertTrue(observerScript.contains("report(vsSrc, 'CONTENT_VIDEO')"))
    }

    private fun candidate(
        url: String,
        role: String,
        source: String = "DOM",
        poster: String? = null
    ): JSONObject = JSONObject().apply {
        put("u", url)
        put("s", source)
        put("r", role)
        poster?.let { put("p", it) }
    }
}
