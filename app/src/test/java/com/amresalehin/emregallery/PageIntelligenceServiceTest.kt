package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.JsonLdParser
import com.amresalehin.emregallery.data.PageFetchFailureReason
import com.amresalehin.emregallery.data.PageFetcher
import com.amresalehin.emregallery.data.PageIntelligenceExtraction
import com.amresalehin.emregallery.data.PageIntelligenceExtractionFailureReason
import com.amresalehin.emregallery.data.PageIntelligenceResult
import com.amresalehin.emregallery.data.PageIntelligenceService
import com.amresalehin.emregallery.data.PageKind
import com.amresalehin.emregallery.data.PageMetadataParser
import com.amresalehin.emregallery.data.PagePreviewImageRole
import com.amresalehin.emregallery.data.PagePreviewSource
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageIntelligenceServiceTest {

    @Test
    fun fetchesOnceAndParsesMetadataAndStructuredDataAgainstTheFinalRedirectUrl() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/articles/current"))
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html><head>
                          <title>Document fallback</title>
                          <meta property="og:title" content="Current story">
                          <meta property="og:image" content="https://cdn.example.com/previews/cover.jpg">
                          <script type="application/ld+json">
                            {"@type":"Article","headline":"Structured headline","url":"related"}
                          </script>
                        </head><body>Article body</body></html>
                        """.trimIndent()
                    )
            )
            server.start()

            val requested = server.url("/short-link").toString()
            val expectedFinal = server.url("/articles/current").toString()
            var parserReceivedJsonLd = false
            val service = PageIntelligenceService(
                pageFetcher = testFetcher(),
                structuredDataParser = { html, pageUrl ->
                    parserReceivedJsonLd = html.contains("application/ld+json") && html.contains("Structured headline")
                    JsonLdParser.parse(html, pageUrl)
                }
            )
            val result = runBlocking { service.inspect(requested) }

            assertTrue(result is PageIntelligenceResult.Success)
            val snapshot = (result as PageIntelligenceResult.Success).snapshot
            assertEquals(requested, snapshot.requestedUrl)
            assertEquals(expectedFinal, snapshot.finalUrl)
            assertEquals(200, snapshot.statusCode)
            assertEquals("text/html", snapshot.contentType)
            assertTrue(snapshot.bodyBytes > 0L)
            assertEquals(2, server.requestCount)
            assertTrue("Fetched HTML did not contain the JSON-LD script payload", parserReceivedJsonLd)

            val metadata = (snapshot.metadata as PageIntelligenceExtraction.Success).value
            assertEquals(expectedFinal, metadata.sourcePageUrl)
            assertEquals("Current story", metadata.title)
            assertEquals("https://cdn.example.com/previews/cover.jpg", metadata.previewImageUrl)

            val structured = (snapshot.structuredData as PageIntelligenceExtraction.Success).value
            assertEquals(expectedFinal, structured.sourcePageUrl)
            assertEquals("Structured headline", structured.entities.single().headline)
            assertEquals(server.url("/articles/related").toString(), structured.entities.single().url)
            assertEquals(PageKind.ARTICLE, snapshot.classification.kind)
            assertFalse(snapshot.classification.isAmbiguous)
            assertEquals(expectedFinal, snapshot.preview.sourcePageUrl)
            assertEquals(metadata.previewImageUrl, snapshot.preview.imageUrl)
            assertEquals(PagePreviewImageRole.PAGE_PREVIEW, snapshot.preview.imageRole)
            assertEquals(PagePreviewSource.OPEN_GRAPH, snapshot.preview.imageEvidence?.source)
        }
    }

    @Test
    fun fetchFailuresAreReturnedWithoutRunningEnrichmentParsers() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).addHeader("Content-Type", "text/html").setBody("Forbidden"))
            server.start()
            var parserInvoked = false
            val service = PageIntelligenceService(
                pageFetcher = testFetcher(),
                metadataParser = { _, _ -> parserInvoked = true; PageMetadataParser.parse("", "https://example.com") },
                structuredDataParser = { _, _ -> parserInvoked = true; JsonLdParser.parse("", "https://example.com") }
            )

            val result = runBlocking { service.inspect(server.url("/blocked").toString()) }

            assertTrue(result is PageIntelligenceResult.FetchFailure)
            val failure = (result as PageIntelligenceResult.FetchFailure).failure
            assertEquals(PageFetchFailureReason.HTTP_ERROR, failure.reason)
            assertEquals(403, failure.httpStatus)
            assertEquals(false, parserInvoked)
        }
    }

    @Test
    fun metadataParserFailureDoesNotDiscardStructuredData() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html")
                    .setBody("""<script type="application/ld+json">{"@type":"WebPage","name":"Structured page"}</script>""")
            )
            server.start()
            var parserReceivedJsonLd = false
            val service = PageIntelligenceService(
                pageFetcher = testFetcher(),
                metadataParser = { _, _ -> throw IllegalStateException("forced metadata parser failure") },
                structuredDataParser = { html, pageUrl ->
                    parserReceivedJsonLd = html.contains("application/ld+json") && html.contains("Structured page")
                    JsonLdParser.parse(html, pageUrl)
                }
            )

            val result = runBlocking { service.inspect(server.url("/page").toString()) }

            assertTrue(result is PageIntelligenceResult.Success)
            assertTrue("Fetched HTML did not contain the JSON-LD script payload", parserReceivedJsonLd)
            val snapshot = (result as PageIntelligenceResult.Success).snapshot
            assertTrue(snapshot.metadata is PageIntelligenceExtraction.Failure)
            assertEquals(
                PageIntelligenceExtractionFailureReason.PARSER_ERROR,
                (snapshot.metadata as PageIntelligenceExtraction.Failure).failure.reason
            )
            val structured = (snapshot.structuredData as PageIntelligenceExtraction.Success).value
            assertEquals("Structured page", structured.entities.single().name)
            assertEquals(PageKind.UNKNOWN, snapshot.classification.kind)
        }
    }

    @Test
    fun structuredDataParserFailureDoesNotDiscardPageMetadata() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html><head><meta property=\"og:title\" content=\"Metadata page\"></head></html>")
            )
            server.start()
            val service = PageIntelligenceService(
                pageFetcher = testFetcher(),
                structuredDataParser = { _, _ -> throw IllegalArgumentException("forced JSON-LD parser failure") }
            )

            val result = runBlocking { service.inspect(server.url("/page").toString()) }

            assertTrue(result is PageIntelligenceResult.Success)
            val snapshot = (result as PageIntelligenceResult.Success).snapshot
            assertTrue(snapshot.structuredData is PageIntelligenceExtraction.Failure)
            val metadata = (snapshot.metadata as PageIntelligenceExtraction.Success).value
            assertEquals("Metadata page", metadata.title)
        }
    }

    @Test
    fun parserCancellationIsPropagatedInsteadOfConvertedIntoAnExtractionFailure() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html><head></head></html>")
            )
            server.start()
            val service = PageIntelligenceService(
                pageFetcher = testFetcher(),
                structuredDataParser = { _, _ -> throw CancellationException("cancel inspection") }
            )
            var cancellationPropagated = false
            try {
                runBlocking { service.inspect(server.url("/page").toString()) }
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }
            assertTrue(cancellationPropagated)
        }
    }

    private fun testFetcher(): PageFetcher {
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(Dns.SYSTEM)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        return PageFetcher(
            client = client,
            urlValidator = { url: HttpUrl ->
                url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
            }
        )
    }
}
