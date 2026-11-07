package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.JsonLdParser
import com.amresalehin.emregallery.data.StructuredResourceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonLdParserTest {

    @Test
    fun parsesGraphArticleAndVideoWhilePreservingResourceRoles() {
        val result = JsonLdParser.parse(
            html = html(
                """
                {
                  "@context":"https://schema.org",
                  "@graph":[
                    {
                      "@type":["Article","BlogPosting"],
                      "@id":"https://example.com/story#article",
                      "headline":"A structured story",
                      "description":"Story description",
                      "url":"/story",
                      "mainEntityOfPage":{"@id":"/story"},
                      "author":[{"@type":"Person","name":"Writer One"},{"name":"Writer Two"}],
                      "publisher":{"@type":"Organization","name":"Example News"},
                      "datePublished":"2026-10-28T10:30:00Z",
                      "image":{"@type":"ImageObject","url":"/images/cover.jpg"}
                    },
                    {
                      "@type":"VideoObject",
                      "name":"Page video",
                      "thumbnailUrl":"/images/video-poster.jpg",
                      "contentUrl":"/media/video.mp4",
                      "embedUrl":"https://player.example.com/embed/42"
                    }
                  ]
                }
                """.trimIndent()
            ),
            pageUrl = "https://example.com/news/index.html"
        )

        assertEquals(2, result.entities.size)
        val article = result.entities[0]
        assertEquals(listOf("Article", "BlogPosting"), article.schemaTypes)
        assertEquals("A structured story", article.headline)
        assertEquals("https://example.com/story", article.url)
        assertEquals("https://example.com/story", article.mainEntityUrl)
        assertEquals(listOf("Writer One", "Writer Two"), article.authorNames)
        assertEquals("Example News", article.publisherName)
        assertEquals("2026-10-28T10:30:00Z", article.datePublished)
        assertEquals(StructuredResourceRole.IMAGE, article.resourceReferences.single().role)
        assertEquals("https://example.com/images/cover.jpg", article.resourceReferences.single().url)

        val video = result.entities[1]
        assertEquals("VideoObject", video.schemaTypes.single())
        assertEquals(
            setOf(StructuredResourceRole.THUMBNAIL, StructuredResourceRole.CONTENT, StructuredResourceRole.EMBED),
            video.resourceReferences.map { it.role }.toSet()
        )
        assertEquals(
            "https://example.com/media/video.mp4",
            video.resourceReferences.single { it.role == StructuredResourceRole.CONTENT }.url
        )
    }

    @Test
    fun parsesTopLevelArraysAndKeepsScriptAndEntityProvenance() {
        val result = JsonLdParser.parse(
            html = html(
                """
                [
                  {"@type":"WebPage","name":"First page","url":"/first"},
                  {"@type":"Product","name":"Second item","url":"/second"}
                ]
                """.trimIndent()
            ),
            pageUrl = "https://example.com/catalog"
        )

        assertEquals(2, result.entities.size)
        assertEquals(0, result.entities[0].sourceScriptIndex)
        assertEquals("$[0]", result.entities[0].sourcePath)
        assertEquals("https://example.com/first", result.entities[0].url)
        assertEquals("Product", result.entities[1].schemaTypes.single())
    }

    @Test
    fun malformedJsonLdScriptDoesNotDiscardLaterValidScripts() {
        val result = JsonLdParser.parse(
            html(
                "{ invalid json",
                """{"@type":"WebPage","name":"Valid page"}"""
            ),
            pageUrl = "https://example.com/"
        )

        assertEquals(1, result.malformedScriptCount)
        assertEquals(1, result.entities.size)
        assertEquals("Valid page", result.entities.single().name)
        assertEquals(1, result.entities.single().sourceScriptIndex)
    }

    @Test
    fun ignoresOversizedScriptsAndReportsTheLimit() {
        val oversized = " ".repeat(JsonLdParser.MAX_SCRIPT_CHARACTERS + 1)
        val result = JsonLdParser.parse(
            html(oversized, """{"@type":"WebPage","name":"Small valid"}"""),
            pageUrl = "https://example.com/"
        )

        assertEquals(1, result.ignoredOversizedScriptCount)
        assertEquals("Small valid", result.entities.single().name)
    }

    @Test
    fun capsEntityCountAndDoesNotRecurseThroughArbitraryNestedProperties() {
        val nodes = (0..JsonLdParser.MAX_ENTITY_COUNT + 4).joinToString(",") {
            """{"@type":"Thing","name":"Thing $it"}"""
        }
        val result = JsonLdParser.parse(
            html("""{"@graph":[$nodes]}"""),
            pageUrl = "https://example.com/"
        )

        assertEquals(JsonLdParser.MAX_ENTITY_COUNT, result.entities.size)
        assertEquals(5, result.truncatedEntityCount)
    }

    @Test
    fun unsafeResourceSchemesAreRejectedAndPreviewReferencesRemainNonAssets() {
        val result = JsonLdParser.parse(
            html(
                """
                {
                  "@type":"Article",
                  "image":["javascript:alert(1)","data:image/png;base64,AAAA","/safe-cover.jpg"],
                  "thumbnailUrl":"file:///private/thumbnail.jpg",
                  "contentUrl":"javascript:alert(2)"
                }
                """.trimIndent()
            ),
            pageUrl = "https://example.com/article"
        )

        val refs = result.entities.single().resourceReferences
        assertEquals(1, refs.size)
        assertEquals("https://example.com/safe-cover.jpg", refs.single().url)
        assertEquals(StructuredResourceRole.IMAGE, refs.single().role)
        assertFalse(refs.any { it.role == StructuredResourceRole.CONTENT })
        assertNull(result.entities.single().datePublished)
    }

    @Test
    fun malformedNestedGraphDepthIsBounded() {
        var nested = """{"@type":"Thing","name":"Deep"}"""
        repeat(40) { nested = """{"@graph":[$nested]}""" }
        val result = JsonLdParser.parse(html(nested), "https://example.com/")

        assertTrue(result.truncatedEntityCount > 0)
        assertTrue(result.entities.isEmpty())
    }

    private fun html(vararg scripts: String): String = buildString {
        append("<html><head>")
        scripts.forEach { script ->
            append("<script type=\"application/ld+json\">")
            append(script)
            append("</script>")
        }
        append("</head><body></body></html>")
    }
}
