package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.JsonLdParseResult
import com.amresalehin.emregallery.data.PageClassification
import com.amresalehin.emregallery.data.PageClassificationConfidence
import com.amresalehin.emregallery.data.PageClassifier
import com.amresalehin.emregallery.data.PageKind
import com.amresalehin.emregallery.data.PageMetadata
import com.amresalehin.emregallery.data.PagePreviewImageRole
import com.amresalehin.emregallery.data.PagePreviewResolver
import com.amresalehin.emregallery.data.PagePreviewSource
import com.amresalehin.emregallery.data.PreviewImageSource
import com.amresalehin.emregallery.data.StructuredPageEntity
import com.amresalehin.emregallery.data.StructuredResourceReference
import com.amresalehin.emregallery.data.StructuredResourceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePreviewResolverTest {

    @Test
    fun pageKindSpecificStructuredImageWinsOverOpenGraphPreview() {
        val pageUrl = "https://example.com/story"
        val metadata = PageMetadata(
            sourcePageUrl = pageUrl,
            title = "Metadata title",
            previewImageUrl = "https://cdn.example.com/og-cover.jpg",
            previewImageSource = PreviewImageSource.OPEN_GRAPH
        )
        val structured = JsonLdParseResult(
            sourcePageUrl = pageUrl,
            entities = listOf(
                StructuredPageEntity(
                    sourceScriptIndex = 0,
                    sourcePath = "$",
                    schemaTypes = listOf("NewsArticle"),
                    headline = "Structured headline",
                    description = "Structured description",
                    authorNames = listOf("A. Writer"),
                    datePublished = "2026-11-01",
                    resourceReferences = listOf(
                        StructuredResourceReference(
                            url = "https://cdn.example.com/article-hero.jpg",
                            role = StructuredResourceRole.IMAGE,
                            sourceProperty = "image"
                        )
                    )
                )
            )
        )
        val classification = PageClassifier.classify(metadata, structured)

        val preview = PagePreviewResolver.resolve(pageUrl, metadata, structured, classification)

        assertEquals(PageKind.ARTICLE, classification.kind)
        assertEquals("https://cdn.example.com/article-hero.jpg", preview.imageUrl)
        assertEquals(PagePreviewImageRole.ARTICLE_HERO, preview.imageRole)
        assertEquals(PagePreviewSource.STRUCTURED_DATA, preview.imageEvidence?.source)
        assertEquals("image", preview.imageEvidence?.property)
        assertEquals(0, preview.imageEvidence?.sourceScriptIndex)
        assertEquals("$", preview.imageEvidence?.sourcePath)
        assertEquals("Structured headline", preview.title)
        assertEquals("Structured description", preview.description)
        assertEquals("A. Writer", preview.author)
        assertEquals("2026-11-01", preview.publishedAt)
    }

    @Test
    fun videoThumbnailIsAPosterPreviewAndContentOrEmbedUrlsAreNeverSelected() {
        val pageUrl = "https://example.com/watch"
        val metadata = PageMetadata(
            sourcePageUrl = pageUrl,
            openGraphType = "video.movie",
            previewImageUrl = "https://cdn.example.com/og-poster.jpg",
            previewImageSource = PreviewImageSource.OPEN_GRAPH
        )
        val structured = JsonLdParseResult(
            sourcePageUrl = pageUrl,
            entities = listOf(
                StructuredPageEntity(
                    sourceScriptIndex = 0,
                    sourcePath = "$",
                    schemaTypes = listOf("VideoObject"),
                    name = "Example video",
                    resourceReferences = listOf(
                        StructuredResourceReference(
                            "https://cdn.example.com/video-poster.jpg",
                            StructuredResourceRole.THUMBNAIL,
                            "thumbnailUrl"
                        ),
                        StructuredResourceReference(
                            "https://cdn.example.com/video.mp4",
                            StructuredResourceRole.CONTENT,
                            "contentUrl"
                        ),
                        StructuredResourceReference(
                            "https://player.example.com/embed/42",
                            StructuredResourceRole.EMBED,
                            "embedUrl"
                        )
                    )
                )
            )
        )
        val classification = PageClassifier.classify(metadata, structured)

        val preview = PagePreviewResolver.resolve(pageUrl, metadata, structured, classification)

        assertEquals(PageKind.VIDEO, classification.kind)
        assertEquals("https://cdn.example.com/video-poster.jpg", preview.imageUrl)
        assertEquals(PagePreviewImageRole.VIDEO_POSTER, preview.imageRole)
        assertEquals("thumbnailUrl", preview.imageEvidence?.property)
        assertTrue(preview.imageUrl != "https://cdn.example.com/video.mp4")
        assertTrue(preview.imageUrl != "https://player.example.com/embed/42")
    }

    @Test
    fun contentOnlyStructuredReferencesFallBackToThePagePreview() {
        val pageUrl = "https://example.com/watch"
        val metadata = PageMetadata(
            sourcePageUrl = pageUrl,
            previewImageUrl = "https://cdn.example.com/page-preview.jpg",
            previewImageSource = PreviewImageSource.TWITTER_CARD
        )
        val structured = JsonLdParseResult(
            sourcePageUrl = pageUrl,
            entities = listOf(
                StructuredPageEntity(
                    sourceScriptIndex = 0,
                    sourcePath = "$",
                    schemaTypes = listOf("VideoObject"),
                    resourceReferences = listOf(
                        StructuredResourceReference(
                            "https://cdn.example.com/video.mp4",
                            StructuredResourceRole.CONTENT,
                            "contentUrl"
                        )
                    )
                )
            )
        )
        val classification = PageClassifier.classify(metadata, structured)

        val preview = PagePreviewResolver.resolve(pageUrl, metadata, structured, classification)

        assertEquals("https://cdn.example.com/page-preview.jpg", preview.imageUrl)
        assertEquals(PagePreviewImageRole.PAGE_PREVIEW, preview.imageRole)
        assertEquals(PagePreviewSource.TWITTER_CARD, preview.imageEvidence?.source)
    }

    @Test
    fun conflictingStructuredPageKindsDoNotOverrideTheMetadataPreview() {
        val pageUrl = "https://example.com/mixed"
        val metadata = PageMetadata(
            sourcePageUrl = pageUrl,
            previewImageUrl = "https://cdn.example.com/fallback.jpg",
            previewImageSource = PreviewImageSource.OPEN_GRAPH
        )
        val structured = JsonLdParseResult(
            sourcePageUrl = pageUrl,
            entities = listOf(
                StructuredPageEntity(
                    sourceScriptIndex = 0,
                    sourcePath = "$.article",
                    schemaTypes = listOf("Article"),
                    resourceReferences = listOf(
                        StructuredResourceReference(
                            "https://cdn.example.com/article.jpg",
                            StructuredResourceRole.IMAGE,
                            "image"
                        )
                    )
                ),
                StructuredPageEntity(
                    sourceScriptIndex = 0,
                    sourcePath = "$.product",
                    schemaTypes = listOf("Product"),
                    resourceReferences = listOf(
                        StructuredResourceReference(
                            "https://cdn.example.com/product.jpg",
                            StructuredResourceRole.IMAGE,
                            "image"
                        )
                    )
                )
            )
        )
        val classification = PageClassifier.classify(metadata, structured)

        val preview = PagePreviewResolver.resolve(pageUrl, metadata, structured, classification)

        assertTrue(classification.isAmbiguous)
        assertEquals("https://cdn.example.com/fallback.jpg", preview.imageUrl)
        assertEquals(PagePreviewSource.OPEN_GRAPH, preview.imageEvidence?.source)
    }

    @Test
    fun textOnlyStructuredPageProducesAMetadataPreviewWithoutAnImage() {
        val pageUrl = "https://example.com/text"
        val structured = JsonLdParseResult(
            sourcePageUrl = pageUrl,
            entities = listOf(
                StructuredPageEntity(
                    sourceScriptIndex = 0,
                    sourcePath = "$",
                    schemaTypes = listOf("Article"),
                    headline = "Text-only headline",
                    description = "No image is available"
                )
            )
        )
        val classification = PageClassifier.classify(null, structured)

        val preview = PagePreviewResolver.resolve(pageUrl, null, structured, classification)

        assertEquals("Text-only headline", preview.title)
        assertEquals("No image is available", preview.description)
        assertNull(preview.imageUrl)
        assertNull(preview.imageRole)
        assertNull(preview.imageEvidence)
    }

    @Test
    fun privateOrUnsafeImageUrlsAreNotSelected() {
        val pageUrl = "https://example.com/private"
        val metadata = PageMetadata(
            sourcePageUrl = pageUrl,
            previewImageUrl = "http://127.0.0.1/private.jpg",
            previewImageSource = PreviewImageSource.OPEN_GRAPH
        )
        val classification = PageClassification(
            kind = PageKind.UNKNOWN,
            confidence = PageClassificationConfidence.NONE,
            evidence = emptyList(),
            candidates = emptyList(),
            isAmbiguous = false
        )

        val preview = PagePreviewResolver.resolve(pageUrl, metadata, null, classification)

        assertNull(preview.imageUrl)
        assertNull(preview.imageRole)
        assertNull(preview.imageEvidence)
    }
}
