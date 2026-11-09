package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.PageFetchFailureReason
import com.amresalehin.emregallery.data.PageFetchResult
import com.amresalehin.emregallery.data.PageFetchUrlPolicy
import com.amresalehin.emregallery.data.PageFetcher
import com.amresalehin.emregallery.data.PageMetadataParser
import com.amresalehin.emregallery.data.PublicInternetAddressPolicy
import com.amresalehin.emregallery.data.PublicInternetDns
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class PageFetcherTest {

    @Test
    fun fetchesHtmlAndResolvesRedirectsWithoutForwardingCookies() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/article")
                    .addHeader("Set-Cookie", "session=should-not-be-forwarded; Path=/")
            )
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><title>Fetched page</title></html>")
            )
            server.start()

            val fetcher = testFetcher()
            val result = kotlinx.coroutines.runBlocking {
                fetcher.fetch(server.url("/start").toString())
            }

            assertTrue(result is PageFetchResult.Success)
            val page = (result as PageFetchResult.Success).page
            assertEquals(server.url("/start").toString(), page.requestedUrl)
            assertEquals(server.url("/article").toString(), page.finalUrl)
            assertEquals("text/html", page.contentType)
            assertTrue(page.html.contains("Fetched page"))
            val metadata = PageMetadataParser.parse(page.html, page.finalUrl)
            assertEquals("Fetched page", metadata.title)
            assertEquals(2, server.requestCount)

            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertNull(first.getHeader("Cookie"))
            assertNull(first.getHeader("Authorization"))
            assertNull(second.getHeader("Cookie"))
            assertNull(second.getHeader("Authorization"))
        }
    }

    @Test
    fun reportsHttpErrorsWithoutTreatingErrorPagesAsPageMetadata() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).addHeader("Content-Type", "text/html").setBody("Forbidden"))
            server.start()

            val result = kotlinx.coroutines.runBlocking {
                testFetcher().fetch(server.url("/private").toString())
            }

            assertTrue(result is PageFetchResult.Failure)
            val failure = (result as PageFetchResult.Failure).failure
            assertEquals(PageFetchFailureReason.HTTP_ERROR, failure.reason)
            assertEquals(403, failure.httpStatus)
        }
    }

    @Test
    fun rejectsNonHtmlResponses() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "image/jpeg").setBody("not html"))
            server.start()

            val result = kotlinx.coroutines.runBlocking {
                testFetcher().fetch(server.url("/image.jpg").toString())
            }

            assertTrue(result is PageFetchResult.Failure)
            assertEquals(PageFetchFailureReason.NON_HTML_CONTENT, (result as PageFetchResult.Failure).failure.reason)
        }
    }

    @Test
    fun rejectsBodiesLargerThanTheConfiguredLimit() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html")
                    .setChunkedBody("<html>12345678901234567890</html>", 4)
            )
            server.start()

            val result = kotlinx.coroutines.runBlocking {
                testFetcher(maxBytes = 12L).fetch(server.url("/large").toString())
            }

            assertTrue(result is PageFetchResult.Failure)
            assertEquals(PageFetchFailureReason.BODY_TOO_LARGE, (result as PageFetchResult.Failure).failure.reason)
        }
    }

    @Test
    fun revalidatesRedirectDestinationsAndRejectsNonHttpSchemes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "file:///etc/passwd"))
            server.start()

            val result = kotlinx.coroutines.runBlocking {
                testFetcher().fetch(server.url("/redirect").toString())
            }

            assertTrue(result is PageFetchResult.Failure)
            assertEquals(PageFetchFailureReason.REDIRECT_BLOCKED, (result as PageFetchResult.Failure).failure.reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun blocksRedirectsToPrivateNetworkAddresses() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://10.0.0.1:65535/admin"))
            server.start()

            val result = kotlinx.coroutines.runBlocking {
                testFetcher().fetch(server.url("/redirect").toString())
            }

            assertTrue(result is PageFetchResult.Failure)
            assertEquals(PageFetchFailureReason.REDIRECT_BLOCKED, (result as PageFetchResult.Failure).failure.reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun enforcesRedirectLimit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/second"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/third"))
            server.start()

            val result = kotlinx.coroutines.runBlocking {
                testFetcher(maxRedirects = 1).fetch(server.url("/first").toString())
            }

            assertTrue(result is PageFetchResult.Failure)
            assertEquals(PageFetchFailureReason.TOO_MANY_REDIRECTS, (result as PageFetchResult.Failure).failure.reason)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun rejectsMalformedCredentialedAndLocalUrls() {
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("javascript:alert(1)"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("file:///etc/passwd"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("https://user:pass@example.com/page"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("http://localhost/page"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("http://127.0.0.1/page"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("http://[::1]/page"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("http://10.0.0.2/page"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("http://100.64.0.1/page"))
        assertNull(PageFetchUrlPolicy.parseAllowedUrl("http://intranet.local/page"))
        assertNotNull(PageFetchUrlPolicy.parseAllowedUrl("https://example.com/page"))
    }

    @Test
    fun publicAddressPolicyRejectsPrivateAndSpecialUseAddresses() {
        val blocked = listOf(
            "0.0.0.0",
            "10.1.2.3",
            "100.64.0.1",
            "100.127.255.254",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.1",
            "192.0.2.1",
            "198.18.0.1",
            "203.0.113.1",
            "224.0.0.1",
            "::",
            "::1",
            "fec0::1",
            "3fff::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "100::1",
            "2001:db8::1"
        )
        blocked.forEach { host ->
            assertFalse("$host should not be public", PublicInternetAddressPolicy.isPublic(InetAddress.getByName(host)))
        }
        assertTrue(PublicInternetAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicInternetAddressPolicy.isPublic(InetAddress.getByName("100.128.0.1")))
        assertTrue(PublicInternetAddressPolicy.isPublic(InetAddress.getByName("172.32.0.1")))
        assertTrue(PublicInternetAddressPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun dnsRejectsHostnamesWithAnyPrivateOrMixedAddressAnswer() {
        val mixedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1")
            )
        }

        try {
            PublicInternetDns(mixedDns).lookup("mixed.example")
            fail("Mixed public/private DNS answers must be blocked")
        } catch (_: UnknownHostException) {
            // Expected: a mixed answer is rejected as a whole.
        }
    }

    @Test
    fun productionFetcherBlocksLocalDestinationsBeforeOpeningAConnection() {
        val result = kotlinx.coroutines.runBlocking {
            PageFetcher().fetch("http://127.0.0.1:8080/admin")
        }

        assertTrue(result is PageFetchResult.Failure)
        assertEquals(PageFetchFailureReason.BLOCKED_ADDRESS, (result as PageFetchResult.Failure).failure.reason)
    }

    private fun testFetcher(
        maxBytes: Long = 1024L,
        maxRedirects: Int = 5
    ): PageFetcher {
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(Dns.SYSTEM)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        return PageFetcher(
            client = client,
            maxResponseBytes = maxBytes,
            maxRedirects = maxRedirects,
            urlValidator = { url: HttpUrl -> url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1" }
        )
    }
}
