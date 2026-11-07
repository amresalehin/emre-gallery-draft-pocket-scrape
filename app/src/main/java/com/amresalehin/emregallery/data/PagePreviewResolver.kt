package com.amresalehin.emregallery.data

import java.util.Locale

/** Provenance for an image used only to represent a saved webpage. */
enum class PagePreviewSource {
    STRUCTURED_DATA,
    OPEN_GRAPH,
    TWITTER_CARD
}

/** A presentation role; none of these roles implies that the image is downloadable content. */
enum class PagePreviewImageRole {
    PAGE_PREVIEW,
    ARTICLE_HERO,
    PRODUCT_IMAGE,
    VIDEO_POSTER,
    GALLERY_COVER,
    THUMBNAIL
}

data class PagePreviewImageEvidence(
    val source: PagePreviewSource,
    val property: String,
    val sourceScriptIndex: Int? = null,
    val sourcePath: String? = null
)

/**
 * A presentation model for the saved webpage.
 *
 * [imageUrl] is deliberately separate from media candidates and assets. Even a structured
 * thumbnail or video poster remains a page preview until an independent discovery/verifier stage
 * establishes that a resource is actual content media.
 */
data class PagePreview(
    val sourcePageUrl: String,
    val title: String? = null,
    val description: String? = null,
    val siteName: String? = null,
    val author: String? = null,
    val publishedAt: String? = null,
    val imageUrl: String? = null,
    val imageRole: PagePreviewImageRole? = null,
    val imageEvidence: PagePreviewImageEvidence? = null
)

/**
 * Resolves a representative page image and descriptive fields without creating media assets.
 *
 * Selection is conservative: page-kind-matched JSON-LD image/thumbnail references are preferred,
 * then Open Graph/Twitter preview metadata. contentUrl and embedUrl are never preview candidates.
 * Conflicting page classification disables structured-data selection.
 */
object PagePreviewResolver {

    fun resolve(
        sourcePageUrl: String,
        metadata: PageMetadata?,
        structuredData: JsonLdParseResult?,
        classification: PageClassification
    ): PagePreview {
        val entity = selectEntities(structuredData, classification)
            .firstOrNull {
                it.headline != null || it.name != null || it.description != null ||
                    it.authorNames.isNotEmpty() || it.datePublished != null
            }

        val structuredImage = selectStructuredImage(structuredData, classification)
        val selectedImage = structuredImage ?: metadataImage(metadata)

        val structuredTitle = entity?.let { entityTitle(it, classification.kind) }
        return PagePreview(
            sourcePageUrl = sourcePageUrl.trim(),
            title = structuredTitle ?: metadata?.title.cleanOrNull(),
            description = entity?.description.cleanOrNull() ?: metadata?.description.cleanOrNull(),
            siteName = metadata?.siteName.cleanOrNull(),
            author = entity?.authorNames?.firstOrNull().cleanOrNull() ?: metadata?.author.cleanOrNull(),
            publishedAt = entity?.datePublished.cleanOrNull() ?: metadata?.publishedAt.cleanOrNull(),
            imageUrl = selectedImage?.url,
            imageRole = selectedImage?.role,
            imageEvidence = selectedImage?.evidence
        )
    }

    private data class SelectedImage(
        val url: String,
        val role: PagePreviewImageRole,
        val evidence: PagePreviewImageEvidence
    )

    private fun selectEntities(
        structuredData: JsonLdParseResult?,
        classification: PageClassification
    ): List<StructuredPageEntity> {
        if (classification.isAmbiguous) return emptyList()
        val entities = structuredData?.entities.orEmpty()
        val matching = entities.filter { matchesPageKind(it.schemaTypes, classification.kind) }
        val pageEntities = entities.filter { isWebPage(it.schemaTypes) }
        return (matching + pageEntities).distinctBy { it.sourceScriptIndex to it.sourcePath }
    }

    private fun selectStructuredImage(
        structuredData: JsonLdParseResult?,
        classification: PageClassification
    ): SelectedImage? {
        if (classification.isAmbiguous) return null

        val entities = selectEntities(structuredData, classification)
        val preferredRoles = if (classification.kind == PageKind.VIDEO) {
            listOf(StructuredResourceRole.THUMBNAIL, StructuredResourceRole.IMAGE)
        } else {
            listOf(StructuredResourceRole.IMAGE, StructuredResourceRole.THUMBNAIL)
        }

        for (entity in entities) {
            for (role in preferredRoles) {
                for (reference in entity.resourceReferences.filter { it.role == role }) {
                    val safeUrl = safePreviewUrl(reference.url) ?: continue
                    return SelectedImage(
                        url = safeUrl,
                        role = structuredImageRole(classification.kind, reference.role),
                        evidence = PagePreviewImageEvidence(
                            source = PagePreviewSource.STRUCTURED_DATA,
                            property = reference.sourceProperty,
                            sourceScriptIndex = entity.sourceScriptIndex,
                            sourcePath = entity.sourcePath
                        )
                    )
                }
            }
        }
        return null
    }

    private fun metadataImage(metadata: PageMetadata?): SelectedImage? {
        val safeUrl = safePreviewUrl(metadata?.previewImageUrl) ?: return null
        val source = when (metadata?.previewImageSource) {
            PreviewImageSource.OPEN_GRAPH -> PagePreviewSource.OPEN_GRAPH
            PreviewImageSource.TWITTER_CARD -> PagePreviewSource.TWITTER_CARD
            null -> return null
        }
        val property = when (source) {
            PagePreviewSource.OPEN_GRAPH -> "og:image"
            PagePreviewSource.TWITTER_CARD -> "twitter:image"
            PagePreviewSource.STRUCTURED_DATA -> return null
        }
        return SelectedImage(
            url = safeUrl,
            role = PagePreviewImageRole.PAGE_PREVIEW,
            evidence = PagePreviewImageEvidence(source = source, property = property)
        )
    }

    private fun structuredImageRole(
        pageKind: PageKind,
        resourceRole: StructuredResourceRole
    ): PagePreviewImageRole = when {
        resourceRole == StructuredResourceRole.THUMBNAIL && pageKind == PageKind.VIDEO ->
            PagePreviewImageRole.VIDEO_POSTER
        resourceRole == StructuredResourceRole.THUMBNAIL ->
            PagePreviewImageRole.THUMBNAIL
        pageKind == PageKind.ARTICLE -> PagePreviewImageRole.ARTICLE_HERO
        pageKind == PageKind.PRODUCT -> PagePreviewImageRole.PRODUCT_IMAGE
        pageKind == PageKind.IMAGE_GALLERY -> PagePreviewImageRole.GALLERY_COVER
        else -> PagePreviewImageRole.PAGE_PREVIEW
    }

    private fun entityTitle(entity: StructuredPageEntity, pageKind: PageKind): String? {
        return when (pageKind) {
            PageKind.ARTICLE, PageKind.DISCUSSION -> entity.headline ?: entity.name
            else -> entity.name ?: entity.headline
        }.cleanOrNull()
    }

    private fun matchesPageKind(types: List<String>, kind: PageKind): Boolean {
        val normalized = types.map { schemaTypeName(it) }
        return when (kind) {
            PageKind.ARTICLE -> normalized.any { it in ARTICLE_TYPES }
            PageKind.PRODUCT -> normalized.any { it == "product" }
            PageKind.VIDEO -> normalized.any { it in VIDEO_TYPES }
            PageKind.AUDIO -> normalized.any { it in AUDIO_TYPES }
            PageKind.IMAGE_GALLERY -> normalized.any { it in GALLERY_TYPES }
            PageKind.DISCUSSION -> normalized.any { it in DISCUSSION_TYPES }
            PageKind.UNKNOWN -> false
        }
    }

    private fun isWebPage(types: List<String>): Boolean =
        types.any { schemaTypeName(it) in WEB_PAGE_TYPES }

    private fun schemaTypeName(value: String): String =
        value.trim().substringAfterLast('/').substringAfterLast(':').lowercase(Locale.ROOT)

    /**
     * Reuse the fetch URL policy for syntactic checks, including credentials, localhost, and
     * literal private/special-use IP rejection. DNS is not resolved by this pure selection stage.
     */
    private fun safePreviewUrl(rawUrl: String?): String? {
        val value = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PageFetchUrlPolicy.parseAllowedUrl(value)?.toString()
    }

    private fun String?.cleanOrNull(): String? = this
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private val ARTICLE_TYPES = setOf(
        "article", "newsarticle", "blogposting", "techarticle", "scholarlyarticle",
        "report", "review", "analysisnewsarticle", "satiricalarticle", "advertisercontentarticle"
    )
    private val VIDEO_TYPES = setOf("videoobject", "clip", "movie")
    private val AUDIO_TYPES = setOf(
        "audioobject", "musicrecording", "musicplaylist", "musicalbum", "podcastepisode"
    )
    private val GALLERY_TYPES = setOf("imagegallery", "imagecollection", "mediagallery")
    private val DISCUSSION_TYPES = setOf("discussionforumposting", "forumposting", "qapage")
    private val WEB_PAGE_TYPES = setOf("webpage", "webpageelement")
}
