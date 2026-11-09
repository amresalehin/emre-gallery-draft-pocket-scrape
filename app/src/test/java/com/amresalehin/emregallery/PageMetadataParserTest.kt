package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.PageMetadataParser
import com.amresalehin.emregallery.data.PageMetadataSource
import com.amresalehin.emregallery.data.PreviewImageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageMetadataParserTest {

    @Test
    fun openGraphMetadataTakesPrecedenceOverTwitterAndHtmlFallbacks() {
        val result = PageMetadataParser.parse(
            html = """
                <html>
                  <head>
                    <title>Document title</title>
                    <meta property="og:title" content="Open Graph title">
                    <meta name="twitter:title" content="Twitter title">
                    <meta name="description" content="Standard description">
                    <meta property="og:description" content="Open Graph description">
                    <meta property="og:site_name" content="Example Journal">
                    <meta property="og:type" content="article">
                    <meta property="og:image" content="/images/cover.jpg">
                  </head>
                </html>
            """.trimIndent(),
            pageUrl = "https://example.com/story/42"
        )

        assertEquals("Open Graph title", result.title)
        assertEquals(PageMetadataSource.OPEN_GRAPH, result.titleSource)
        assertEquals("Open Graph description", result.description)
        assertEquals(PageMetadataSource.OPEN_GRAPH, result.descriptionSource)
        assertEquals("Example Journal", result.siteName)
        assertEquals("article", result.openGraphType)
        assertEquals("https://example.com/images/cover.jpg", result.previewImageUrl)
        assertEquals(PreviewImageSource.OPEN_GRAPH, result.previewImageSource)
    }

    @Test
    fun twitterCardAndHtmlMetadataProvideFallbacks() {
        val result = PageMetadataParser.parse(
            html = """
                <html><head>
                  <title>Fallback document title</title>
                  <meta name="twitter:title" content="Twitter title">
                  <meta name="twitter:description" content="Twitter description">
                  <meta name="twitter:card" content="summary_large_image">
                  <meta name="twitter:image" content="//cdn.example.com/card.png">
                </head></html>
            """.trimIndent(),
            pageUrl = "https://example.com/post"
        )

        assertEquals("Twitter title", result.title)
        assertEquals(PageMetadataSource.TWITTER_CARD, result.titleSource)
        assertEquals("Twitter description", result.description)
        assertEquals(PageMetadataSource.TWITTER_CARD, result.descriptionSource)
        assertEquals("summary_large_image", result.twitterCard)
        assertEquals("https://cdn.example.com/card.png", result.previewImageUrl)
        assertEquals(PreviewImageSource.TWITTER_CARD, result.previewImageSource)
    }

    @Test
    fun documentTitleAndStandardDescriptionAreUsedWhenSocialMetadataIsAbsent() {
        val result = PageMetadataParser.parse(
            html = """
                <html><head>
                  <title>  A   plain page  </title>
                  <meta name="description" content="  A   useful description  ">
                </head></html>
            """.trimIndent(),
            pageUrl = "https://example.com/plain"
        )

        assertEquals("A plain page", result.title)
        assertEquals(PageMetadataSource.HTML_TITLE, result.titleSource)
        assertEquals("A useful description", result.description)
        assertEquals(PageMetadataSource.META_DESCRIPTION, result.descriptionSource)
        assertNull(result.previewImageUrl)
    }

    @Test
    fun canonicalAndFaviconLinksResolveRelativeUrls() {
        val result = PageMetadataParser.parse(
            html = """
                <html lang="en-IN"><head>
                  <link rel="canonical" href="../article?id=7">
                  <link rel="icon" href="/favicon.ico">
                  <meta name="author" content="A. Writer">
                  <meta property="article:published_time" content="2026-10-30T08:15:00Z">
                </head></html>
            """.trimIndent(),
            pageUrl = "https://example.com/news/section/story"
        )

        assertEquals("https://example.com/news/article?id=7", result.canonicalUrl)
        assertEquals(PageMetadataSource.HTML_LINK, result.canonicalSource)
        assertEquals("https://example.com/favicon.ico", result.faviconUrl)
        assertEquals("A. Writer", result.author)
        assertEquals("2026-10-30T08:15:00Z", result.publishedAt)
        assertEquals("en-IN", result.language)
    }

    @Test
    fun unsafeImageAndCanonicalSchemesAreRejected() {
        val result = PageMetadataParser.parse(
            html = """
                <html><head>
                  <link rel="canonical" href="javascript:alert(1)">
                  <meta property="og:image" content="data:image/png;base64,AAAA">
                  <meta name="twitter:image" content="file:///sdcard/private.png">
                </head></html>
            """.trimIndent(),
            pageUrl = "https://example.com/page"
        )

        assertNull(result.canonicalUrl)
        assertNull(result.previewImageUrl)
        assertNull(result.previewImageSource)
    }

    @Test
    fun invalidOpenGraphImageFallsBackToValidTwitterPreview() {
        val result = PageMetadataParser.parse(
            html = """
                <html><head>
                  <meta property="og:image" content="javascript:alert(1)">
                  <meta name="twitter:image" content="/preview.jpg">
                </head></html>
            """.trimIndent(),
            pageUrl = "https://example.com/page"
        )

        assertEquals("https://example.com/preview.jpg", result.previewImageUrl)
        assertEquals(PreviewImageSource.TWITTER_CARD, result.previewImageSource)
    }

    @Test
    fun metadataParserDoesNotTreatPreviewAsDiscoveredMedia() {
        val result = PageMetadataParser.parse(
            html = "<meta property=\"og:image\" content=\"/hero.jpg\">",
            pageUrl = "https://example.com/article"
        )

        assertEquals("https://example.com/hero.jpg", result.previewImageUrl)
        assertEquals(PreviewImageSource.OPEN_GRAPH, result.previewImageSource)
        // The result contract intentionally has no media-candidate or download field.
    }
}
