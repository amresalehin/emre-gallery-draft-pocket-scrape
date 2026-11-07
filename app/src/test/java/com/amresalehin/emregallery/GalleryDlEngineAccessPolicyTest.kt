package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.GalleryDlEngine
import com.amresalehin.emregallery.data.GalleryDlOptions
import com.amresalehin.emregallery.data.OkHttpDownloader
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
class GalleryDlEngineAccessPolicyTest {

    @Test
    fun extractorDoesNotReadAmbientWebViewCookies() {
        val json = JSONObject(GalleryDlEngine.buildExtractorOptionsJson(GalleryDlOptions()))

        assertFalse(json.has("cookies"))
        assertEquals(OkHttpDownloader.DEFAULT_USER_AGENT, json.getString("user_agent"))
    }

    @Test
    fun extractorUsesCookiesOnlyWhenCallerExplicitlySuppliesThem() {
        val json = JSONObject(
            GalleryDlEngine.buildExtractorOptionsJson(
                GalleryDlOptions(cookies = "session=test-only")
            )
        )

        assertTrue(json.has("cookies"))
        assertEquals("session=test-only", json.getString("cookies"))
    }

    @Test
    fun downloadUsesOnlyExplicitlySuppliedSessionCookies() {
        assertEquals(null, GalleryDlEngine.resolveExplicitCookies(null))
        assertEquals(null, GalleryDlEngine.resolveExplicitCookies("  "))
        assertEquals("session=test-only", GalleryDlEngine.resolveExplicitCookies("session=test-only"))
    }
}
