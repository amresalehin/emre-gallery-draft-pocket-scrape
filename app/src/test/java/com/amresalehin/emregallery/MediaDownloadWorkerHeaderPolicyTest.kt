package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.sanitizeDownloadHeaders
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDownloadWorkerHeaderPolicyTest {

    @Test
    fun removesSensitiveHeadersCaseInsensitivelyButKeepsOrdinaryHints() {
        val sanitized = sanitizeDownloadHeaders(
            mapOf(
                "Referer" to "https://example.com/page",
                "User-Agent" to "EmreGallery/1.0 (Android)",
                "cOoKiE" to "session=secret",
                "AUTHORIZATION" to "Bearer secret",
                "X-API-Key" to "secret",
                "X-CSRF-Token" to "secret"
            )
        )

        assertEquals(
            mapOf(
                "Referer" to "https://example.com/page",
                "User-Agent" to "EmreGallery/1.0 (Android)"
            ),
            sanitized
        )
    }
}
