package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.JsonLdParseResult
import com.amresalehin.emregallery.data.PageClassificationConfidence
import com.amresalehin.emregallery.data.PageClassificationSource
import com.amresalehin.emregallery.data.PageClassifier
import com.amresalehin.emregallery.data.PageKind
import com.amresalehin.emregallery.data.PageMetadata
import com.amresalehin.emregallery.data.StructuredPageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageClassifierTest {

    @Test
    fun explicitArticleSchemaProducesHighConfidenceArticleClassification() {
        val result = PageClassifier.classify(
            metadata = PageMetadata(
                sourcePageUrl = "https://example.com/story",
                openGraphType = "article"
            ),
            structuredData = jsonLd("NewsArticle", "BlogPosting")
        )

        assertEquals(PageKind.ARTICLE, result.kind)
        assertEquals(PageClassificationConfidence.HIGH, result.confidence)
        assertFalse(result.isAmbiguous)
        assertTrue(result.evidence.any { it.source == PageClassificationSource.OPEN_GRAPH_TYPE })
        assertTrue(result.evidence.any { it.source == PageClassificationSource.JSON_LD_SCHEMA_TYPE })
    }

    @Test
    fun explicitProductDeclarationClassifiesProductWithoutUsingItsImages() {
        val result = PageClassifier.classify(
            metadata = PageMetadata(
                sourcePageUrl = "https://shop.example.com/item",
                openGraphType = "product",
                previewImageUrl = "https://cdn.example.com/item.jpg"
            ),
            structuredData = jsonLd("Product")
        )

        assertEquals(PageKind.PRODUCT, result.kind)
        assertEquals(PageClassificationConfidence.HIGH, result.confidence)
        assertFalse(result.evidence.any { it.value.contains("item.jpg") })
    }

    @Test
    fun videoSchemaAndOpenGraphVideoProduceHighConfidenceVideoClassification() {
        val result = PageClassifier.classify(
            metadata = PageMetadata(
                sourcePageUrl = "https://video.example.com/watch/1",
                openGraphType = "video.movie"
            ),
            structuredData = jsonLd("VideoObject")
        )

        assertEquals(PageKind.VIDEO, result.kind)
        assertEquals(PageClassificationConfidence.HIGH, result.confidence)
    }

    @Test
    fun twitterPlayerCardIsOnlyLowConfidenceVideoEvidence() {
        val result = PageClassifier.classify(
            metadata = PageMetadata(
                sourcePageUrl = "https://example.com/embed",
                twitterCard = "player"
            ),
            structuredData = null
        )

        assertEquals(PageKind.VIDEO, result.kind)
        assertEquals(PageClassificationConfidence.LOW, result.confidence)
        assertEquals(PageClassificationSource.TWITTER_CARD, result.evidence.single().source)
    }

    @Test
    fun conflictingExplicitDeclarationsRemainAmbiguous() {
        val result = PageClassifier.classify(
            metadata = null,
            structuredData = jsonLd("Article", "Product")
        )

        assertEquals(PageKind.UNKNOWN, result.kind)
        assertTrue(result.isAmbiguous)
        assertEquals(2, result.candidates.size)
        assertEquals(result.candidates[0].score, result.candidates[1].score)
    }

    @Test
    fun genericWebsiteAndPreviewImageDoNotCreateAnInventedPageKind() {
        val result = PageClassifier.classify(
            metadata = PageMetadata(
                sourcePageUrl = "https://example.com/",
                openGraphType = "website",
                twitterCard = "summary_large_image",
                previewImageUrl = "https://cdn.example.com/cover.jpg"
            ),
            structuredData = jsonLd("WebPage", "ImageObject")
        )

        assertEquals(PageKind.UNKNOWN, result.kind)
        assertEquals(PageClassificationConfidence.NONE, result.confidence)
        assertFalse(result.isAmbiguous)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun explicitGalleryAndDiscussionTypesAreClassifiedSeparately() {
        val gallery = PageClassifier.classify(null, jsonLd("ImageGallery"))
        val discussion = PageClassifier.classify(null, jsonLd("DiscussionForumPosting"))

        assertEquals(PageKind.IMAGE_GALLERY, gallery.kind)
        assertEquals(PageKind.DISCUSSION, discussion.kind)
    }

    private fun jsonLd(vararg types: String) = JsonLdParseResult(
        sourcePageUrl = "https://example.com/",
        entities = listOf(
            StructuredPageEntity(
                sourceScriptIndex = 0,
                sourcePath = "$.@graph[0]",
                schemaTypes = types.toList()
            )
        )
    )
}
