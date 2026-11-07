package com.amresalehin.emregallery.data

import java.util.Locale

/** The kind of webpage saved to Pocket; this is not a media-file category. */
enum class PageKind {
    ARTICLE,
    PRODUCT,
    VIDEO,
    AUDIO,
    IMAGE_GALLERY,
    DISCUSSION,
    UNKNOWN
}

enum class PageClassificationConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

enum class PageClassificationSource {
    OPEN_GRAPH_TYPE,
    TWITTER_CARD,
    JSON_LD_SCHEMA_TYPE
}

data class PageClassificationEvidence(
    val kind: PageKind,
    val source: PageClassificationSource,
    val value: String,
    val weight: Int,
    val sourceScriptIndex: Int? = null,
    val sourcePath: String? = null
)

data class PageClassificationCandidate(
    val kind: PageKind,
    val score: Int
)

data class PageClassification(
    val kind: PageKind,
    val confidence: PageClassificationConfidence,
    val evidence: List<PageClassificationEvidence>,
    val candidates: List<PageClassificationCandidate>,
    val isAmbiguous: Boolean
)

/**
 * Classifies the saved page using explicit page-level declarations only.
 *
 * It intentionally does not infer a page kind from preview images, JSON-LD image/thumbnail
 * references, media URLs, file extensions, favicons, or generic HTML titles. Those signals belong
 * to the preview and media-discovery layers. Conflicting top scores remain ambiguous rather than
 * being silently resolved by an arbitrary ordering.
 */
object PageClassifier {
    private const val OPEN_GRAPH_WEIGHT = 4
    private const val JSON_LD_WEIGHT = 5
    private const val TWITTER_PLAYER_WEIGHT = 2

    private val articleTypes = setOf(
        "article", "newsarticle", "blogposting", "techarticle", "scholarlyarticle",
        "report", "review", "analysisnewsarticle", "satiricalarticle", "advertisercontentarticle"
    )
    private val productTypes = setOf("product")
    private val videoTypes = setOf("videoobject", "clip", "movie")
    private val audioTypes = setOf("audioobject", "musicrecording", "musicplaylist", "musicalbum", "podcastepisode")
    private val galleryTypes = setOf("imagegallery", "imagecollection", "mediagallery")
    private val discussionTypes = setOf("discussionforumposting", "forumposting", "qapage")

    fun classify(
        metadata: PageMetadata?,
        structuredData: JsonLdParseResult?
    ): PageClassification {
        val evidence = buildList {
            metadata?.openGraphType?.let { rawType ->
                openGraphKind(rawType)?.let { kind ->
                    add(
                        PageClassificationEvidence(
                            kind = kind,
                            source = PageClassificationSource.OPEN_GRAPH_TYPE,
                            value = rawType,
                            weight = OPEN_GRAPH_WEIGHT
                        )
                    )
                }
            }

            metadata?.twitterCard?.takeIf { it.equals("player", ignoreCase = true) }?.let { card ->
                add(
                    PageClassificationEvidence(
                        kind = PageKind.VIDEO,
                        source = PageClassificationSource.TWITTER_CARD,
                        value = card,
                        weight = TWITTER_PLAYER_WEIGHT
                    )
                )
            }

            structuredData?.entities.orEmpty().forEach { entity ->
                val entityKinds = entity.schemaTypes.mapNotNull(::schemaKind).distinct()
                entityKinds.forEach { kind ->
                    add(
                        PageClassificationEvidence(
                            kind = kind,
                            source = PageClassificationSource.JSON_LD_SCHEMA_TYPE,
                            value = entity.schemaTypes.filter { schemaKind(it) == kind }.joinToString(","),
                            weight = JSON_LD_WEIGHT,
                            sourceScriptIndex = entity.sourceScriptIndex,
                            sourcePath = entity.sourcePath
                        )
                    )
                }
            }
        }.distinctBy {
            listOf(it.kind, it.source, it.value, it.sourceScriptIndex, it.sourcePath).joinToString("|")
        }

        val candidates = evidence
            .groupBy { it.kind }
            .map { (kind, signals) -> PageClassificationCandidate(kind, signals.sumOf { it.weight }) }
            .sortedWith(compareByDescending<PageClassificationCandidate> { it.score }.thenBy { it.kind.name })

        val leader = candidates.firstOrNull()
            ?: return PageClassification(
                kind = PageKind.UNKNOWN,
                confidence = PageClassificationConfidence.NONE,
                evidence = emptyList(),
                candidates = emptyList(),
                isAmbiguous = false
            )
        val runnerUpScore = candidates.getOrNull(1)?.score ?: 0
        if (leader.score == runnerUpScore) {
            return PageClassification(
                kind = PageKind.UNKNOWN,
                confidence = PageClassificationConfidence.LOW,
                evidence = evidence,
                candidates = candidates,
                isAmbiguous = true
            )
        }

        val margin = leader.score - runnerUpScore
        val confidence = when {
            leader.score >= 5 && margin >= 2 -> PageClassificationConfidence.HIGH
            leader.score >= 4 && margin >= 1 -> PageClassificationConfidence.MEDIUM
            else -> PageClassificationConfidence.LOW
        }

        return PageClassification(
            kind = leader.kind,
            confidence = confidence,
            evidence = evidence,
            candidates = candidates,
            isAmbiguous = false
        )
    }

    private fun openGraphKind(rawType: String): PageKind? {
        val type = rawType.trim().lowercase(Locale.ROOT)
        return when {
            type == "article" -> PageKind.ARTICLE
            type == "product" -> PageKind.PRODUCT
            type.startsWith("video.") || type == "video" -> PageKind.VIDEO
            type.startsWith("music.") || type == "music" -> PageKind.AUDIO
            else -> null
        }
    }

    private fun schemaKind(rawType: String): PageKind? {
        val type = rawType.trim().substringAfterLast('/').substringAfterLast(':').lowercase(Locale.ROOT)
        return when (type) {
            in articleTypes -> PageKind.ARTICLE
            in productTypes -> PageKind.PRODUCT
            in videoTypes -> PageKind.VIDEO
            in audioTypes -> PageKind.AUDIO
            in galleryTypes -> PageKind.IMAGE_GALLERY
            in discussionTypes -> PageKind.DISCUSSION
            else -> null
        }
    }
}
