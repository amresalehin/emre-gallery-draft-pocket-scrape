package com.amresalehin.emregallery.data

/**
 * Semantic role of a discovered resource. A role describes how the resource belongs to the
 * saved page; it does not by itself prove that the resource is accessible or playable.
 */
enum class MediaAssetRole {
    PAGE_COVER,
    ARTICLE_HERO,
    PRODUCT_IMAGE,
    THUMBNAIL,
    VIDEO_POSTER,
    CONTENT_IMAGE,
    CONTENT_VIDEO,
    CONTENT_AUDIO,
    DOCUMENT_ATTACHMENT,
    EMBED,
    AVATAR,
    UI_DECORATION,
    AD_OR_TRACKER,
    UNKNOWN
}

/**
 * Verification and access state for a media candidate.
 *
 * Discovery never implies playability. Only a later playback resolver may promote a resource
 * to PLAYABLE_DIRECT or PLAYABLE_EMBED.
 */
enum class MediaVerificationState {
    DISCOVERED,
    NEEDS_VERIFICATION,
    VERIFIED_RESOURCE,
    PLAYABLE_DIRECT,
    PLAYABLE_EMBED,
    BROWSER_ONLY,
    AUTH_REQUIRED,
    EXPIRED,
    UNSUPPORTED,
    REJECTED
}

enum class MediaDiscoveryEvidenceSource {
    STATIC_HTML,
    STRUCTURED_DATA,
    OEMBED,
    PROVIDER_EXTRACTOR,
    DOM,
    NETWORK,
    DIRECT_RESPONSE,
    USER_CONFIRMATION,
    UNKNOWN
}

/** Evidence is descriptive only; it deliberately has no cookie or authorization-header field. */
data class MediaDiscoveryEvidence(
    val source: MediaDiscoveryEvidenceSource,
    val detail: String,
    val observedMimeType: String? = null,
    val httpStatusCode: Int? = null,
    val contentLengthBytes: Long? = null
)

/**
 * Safe discovery projection from the legacy ScrapedMediaItem transport model.
 *
 * It copies only resource identity, presentation hints, semantic role, and provenance evidence.
 * Download headers and ambient session credentials remain in the download path and are never
 * included in discovery state.
 */
data class MediaDiscoveryCandidate(
    val itemId: String,
    val resourceUrl: String,
    val sourcePageUrl: String,
    val mediaType: MediaCategory,
    val thumbnailUrl: String?,
    val role: MediaAssetRole,
    val verificationState: MediaVerificationState,
    val evidence: List<MediaDiscoveryEvidence>
) {
    companion object {
        fun from(item: ScrapedMediaItem): MediaDiscoveryCandidate {
            val role = when {
                item.isAudio -> MediaAssetRole.CONTENT_AUDIO
                item.isVideo || item.isStream -> MediaAssetRole.CONTENT_VIDEO
                item.category == MediaCategory.IMAGES -> MediaAssetRole.CONTENT_IMAGE
                else -> MediaAssetRole.UNKNOWN
            }
            val isDirectResponse = hasSuccessfulDirectResponse(item)
            val state = if (isDirectResponse) {
                MediaVerificationState.VERIFIED_RESOURCE
            } else {
                MediaVerificationState.NEEDS_VERIFICATION
            }

            return MediaDiscoveryCandidate(
                itemId = item.id,
                resourceUrl = item.url,
                sourcePageUrl = item.provenance.pageUrl,
                mediaType = item.category,
                thumbnailUrl = item.thumbnailUrl,
                role = role,
                verificationState = state,
                evidence = evidenceFor(item)
            )
        }

        private fun hasSuccessfulDirectResponse(item: ScrapedMediaItem): Boolean {
            val status = item.metadata["http_status_code"]?.toIntOrNull()
            return item.metadata["direct_response_confirmed"] == "true" &&
                status != null && status in 200..299
        }

        private fun evidenceFor(item: ScrapedMediaItem): List<MediaDiscoveryEvidence> {
            val labels = (item.provenance.extractorEngine.split("+") + item.source)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val evidence = labels.mapNotNull { label ->
                val normalized = label.lowercase(java.util.Locale.ROOT)
                val source = when {
                    normalized.contains("direct") &&
                        (normalized.contains("http") || normalized.contains("okhttp")) &&
                        hasSuccessfulDirectResponse(item) ->
                        MediaDiscoveryEvidenceSource.DIRECT_RESPONSE
                    normalized.contains("json-ld") || normalized.contains("structured data") ->
                        MediaDiscoveryEvidenceSource.STRUCTURED_DATA
                    normalized.contains("oembed") ->
                        MediaDiscoveryEvidenceSource.OEMBED
                    normalized.contains("gallery-dl") || normalized.contains("yt-dlp") ->
                        MediaDiscoveryEvidenceSource.PROVIDER_EXTRACTOR
                    normalized.contains("network") ->
                        MediaDiscoveryEvidenceSource.NETWORK
                    normalized.contains("dom") || normalized.contains("srcset") ||
                        normalized.contains("css") || normalized.contains("iframe") ->
                        MediaDiscoveryEvidenceSource.DOM
                    normalized.contains("html") ->
                        MediaDiscoveryEvidenceSource.STATIC_HTML
                    else -> null
                } ?: return@mapNotNull null

                MediaDiscoveryEvidence(
                    source = source,
                    detail = if (source == MediaDiscoveryEvidenceSource.DOM &&
                        !item.provenance.domSelector.isNullOrBlank()
                    ) {
                        "$label: ${item.provenance.domSelector}"
                    } else {
                        label
                    },
                    observedMimeType = if (source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE) {
                        item.metadata["mime_hint"]?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    },
                    httpStatusCode = if (source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE) {
                        item.metadata["http_status_code"]?.toIntOrNull()
                    } else {
                        null
                    },
                    contentLengthBytes = item.sizeBytes
                )
            }.distinctBy { it.source to it.detail }

            return evidence.ifEmpty {
                listOf(
                    MediaDiscoveryEvidence(
                        source = MediaDiscoveryEvidenceSource.UNKNOWN,
                        detail = item.source.ifBlank { "Unknown discovery source" }
                    )
                )
            }
        }
    }
}

/** Conservative presentation and playback gates shared by discovery consumers. */
object MediaDiscoveryPolicy {
    private val galleryRoles = setOf(
        MediaAssetRole.CONTENT_IMAGE,
        MediaAssetRole.PRODUCT_IMAGE,
        MediaAssetRole.CONTENT_VIDEO,
        MediaAssetRole.CONTENT_AUDIO,
        MediaAssetRole.DOCUMENT_ATTACHMENT
    )

    fun isGalleryEligible(candidate: MediaDiscoveryCandidate): Boolean = when {
        candidate.role in galleryRoles &&
            candidate.verificationState == MediaVerificationState.VERIFIED_RESOURCE -> true
        candidate.role in setOf(MediaAssetRole.CONTENT_VIDEO, MediaAssetRole.CONTENT_AUDIO) &&
            candidate.verificationState == MediaVerificationState.PLAYABLE_DIRECT -> true
        candidate.role == MediaAssetRole.EMBED ->
            candidate.verificationState == MediaVerificationState.PLAYABLE_EMBED
        else -> false
    }

    fun canOfferDirectPlayback(candidate: MediaDiscoveryCandidate): Boolean =
        candidate.role in setOf(MediaAssetRole.CONTENT_VIDEO, MediaAssetRole.CONTENT_AUDIO) &&
            candidate.verificationState == MediaVerificationState.PLAYABLE_DIRECT

    fun canOpenEmbed(candidate: MediaDiscoveryCandidate): Boolean =
        candidate.role == MediaAssetRole.EMBED &&
            candidate.verificationState == MediaVerificationState.PLAYABLE_EMBED

    fun requiresManualAuthentication(candidate: MediaDiscoveryCandidate): Boolean =
        candidate.verificationState == MediaVerificationState.AUTH_REQUIRED

    fun shouldOpenSourcePage(candidate: MediaDiscoveryCandidate): Boolean =
        candidate.verificationState in setOf(
            MediaVerificationState.BROWSER_ONLY,
            MediaVerificationState.AUTH_REQUIRED,
            MediaVerificationState.EXPIRED,
            MediaVerificationState.UNSUPPORTED
        )
}
