package com.amresalehin.emregallery.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Provenance for a field extracted from a page's static HTML. */
enum class PageMetadataSource {
    OPEN_GRAPH,
    TWITTER_CARD,
    HTML_TITLE,
    META_DESCRIPTION,
    HTML_LINK
}

/** Identifies where a representative preview image came from. It is not a media-asset role. */
enum class PreviewImageSource {
    OPEN_GRAPH,
    TWITTER_CARD
}

/**
 * Metadata describing the saved webpage itself.
 *
 * In particular, [previewImageUrl] is only a representative page preview. It is not proof that
 * the URL is a content image, gallery item, or downloadable media asset.
 */
data class PageMetadata(
    val sourcePageUrl: String,
    val title: String? = null,
    val titleSource: PageMetadataSource? = null,
    val description: String? = null,
    val descriptionSource: PageMetadataSource? = null,
    val siteName: String? = null,
    val canonicalUrl: String? = null,
    val canonicalSource: PageMetadataSource? = null,
    val faviconUrl: String? = null,
    val previewImageUrl: String? = null,
    val previewImageSource: PreviewImageSource? = null,
    val openGraphType: String? = null,
    val twitterCard: String? = null,
    val author: String? = null,
    val publishedAt: String? = null,
    val language: String? = null
)

/**
 * Parses inexpensive, static HTML metadata for a saved page.
 *
 * This parser does not fetch URLs, execute JavaScript, classify media, or create media assets.
 * It is deliberately deterministic so the result can be tested with local HTML fixtures.
 */
object PageMetadataParser {

    fun parse(html: String, pageUrl: String): PageMetadata {
        val sourceUrl = pageUrl.trim()
        val document = Jsoup.parse(html, sourceUrl)
        val meta = collectMetaValues(document)

        val openGraphTitle = meta.first("og:title")
        val twitterTitle = meta.first("twitter:title")
        val htmlTitle = document.title().cleanOrNull()
        val (title, titleSource) = when {
            openGraphTitle != null -> openGraphTitle to PageMetadataSource.OPEN_GRAPH
            twitterTitle != null -> twitterTitle to PageMetadataSource.TWITTER_CARD
            htmlTitle != null -> htmlTitle to PageMetadataSource.HTML_TITLE
            else -> null to null
        }

        val openGraphDescription = meta.first("og:description")
        val twitterDescription = meta.first("twitter:description")
        val standardDescription = meta.first("description")
        val (description, descriptionSource) = when {
            openGraphDescription != null -> openGraphDescription to PageMetadataSource.OPEN_GRAPH
            twitterDescription != null -> twitterDescription to PageMetadataSource.TWITTER_CARD
            standardDescription != null -> standardDescription to PageMetadataSource.META_DESCRIPTION
            else -> null to null
        }

        val linkCanonical = findLinkUrl(document, sourceUrl, setOf("canonical"))
        val openGraphCanonical = meta.first("og:url")?.let { resolveHttpUrl(it, sourceUrl) }
        val (canonicalUrl, canonicalSource) = when {
            linkCanonical != null -> linkCanonical to PageMetadataSource.HTML_LINK
            openGraphCanonical != null -> openGraphCanonical to PageMetadataSource.OPEN_GRAPH
            else -> null to null
        }

        val openGraphImage = firstResolvedMetaUrl(
            meta,
            sourceUrl,
            "og:image",
            "og:image:url",
            "og:image:secure_url"
        )
        val twitterImage = firstResolvedMetaUrl(
            meta,
            sourceUrl,
            "twitter:image",
            "twitter:image:src"
        )
        val (previewImageUrl, previewImageSource) = when {
            openGraphImage != null -> openGraphImage to PreviewImageSource.OPEN_GRAPH
            twitterImage != null -> twitterImage to PreviewImageSource.TWITTER_CARD
            else -> null to null
        }

        val faviconUrl = findFaviconUrl(document, sourceUrl)
        val author = meta.first("author", "article:author", "parsely-author")
        val publishedAt = meta.first(
            "article:published_time",
            "datepublished",
            "date"
        )

        return PageMetadata(
            sourcePageUrl = sourceUrl,
            title = title,
            titleSource = titleSource,
            description = description,
            descriptionSource = descriptionSource,
            siteName = meta.first("og:site_name"),
            canonicalUrl = canonicalUrl,
            canonicalSource = canonicalSource,
            faviconUrl = faviconUrl,
            previewImageUrl = previewImageUrl,
            previewImageSource = previewImageSource,
            openGraphType = meta.first("og:type"),
            twitterCard = meta.first("twitter:card"),
            author = author,
            publishedAt = publishedAt,
            language = document.selectFirst("html")?.attr("lang")?.cleanOrNull()
        )
    }

    private data class MetaValues(private val values: Map<String, String>) {
        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            values[key.lowercase(Locale.ROOT)]?.cleanOrNull()
        }
    }

    private fun collectMetaValues(document: Document): MetaValues {
        val values = linkedMapOf<String, String>()
        for (element in document.getElementsByTag("meta")) {
            val content = element.attr("content").cleanOrNull() ?: continue
            val keys = listOf(
                element.attr("property"),
                element.attr("name"),
                element.attr("itemprop")
            )
            for (rawKey in keys) {
                val key = rawKey.trim().lowercase(Locale.ROOT)
                if (key.isNotEmpty()) values.putIfAbsent(key, content)
            }
        }
        return MetaValues(values)
    }

    private fun firstResolvedMetaUrl(
        meta: MetaValues,
        baseUrl: String,
        vararg keys: String
    ): String? = keys.firstNotNullOfOrNull { key ->
        meta.first(key)?.let { resolveHttpUrl(it, baseUrl) }
    }

    private fun findLinkUrl(
        document: Document,
        baseUrl: String,
        acceptedRelTokens: Set<String>
    ): String? {
        for (link in document.getElementsByTag("link")) {
            val relTokens = link.attr("rel")
                .lowercase(Locale.ROOT)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            if (relTokens.any { it in acceptedRelTokens }) {
                resolveHttpUrl(link.attr("href"), baseUrl)?.let { return it }
            }
        }
        return null
    }

    private fun findFaviconUrl(document: Document, baseUrl: String): String? {
        val links = document.getElementsByTag("link")
        val priorities = listOf(
            setOf("icon"),
            setOf("shortcut", "icon"),
            setOf("apple-touch-icon")
        )
        for (accepted in priorities) {
            for (link in links) {
                val relTokens = link.attr("rel")
                    .lowercase(Locale.ROOT)
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                val matches = if (accepted.size == 2) {
                    relTokens.containsAll(accepted)
                } else {
                    relTokens.any { it in accepted }
                }
                if (matches) {
                    resolveHttpUrl(link.attr("href"), baseUrl)?.let { return it }
                }
            }
        }
        return null
    }

    /** Resolve relative references but accept only absolute HTTP(S) destinations. */
    private fun resolveHttpUrl(rawUrl: String, baseUrl: String): String? {
        val value = rawUrl.trim()
        if (value.isEmpty()) return null

        return runCatching {
            val reference = URI(value)
            val resolved = if (reference.isAbsolute) {
                reference
            } else {
                val base = URI(baseUrl)
                if (!base.isAbsolute || base.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
                    return null
                }
                base.resolve(reference)
            }
            val scheme = resolved.scheme?.lowercase(Locale.ROOT)
            if (scheme !in setOf("http", "https")) return null
            if (resolved.host.isNullOrBlank()) return null
            resolved.toASCIIString()
        }.getOrNull()
    }

    private fun String?.cleanOrNull(): String? = this
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
