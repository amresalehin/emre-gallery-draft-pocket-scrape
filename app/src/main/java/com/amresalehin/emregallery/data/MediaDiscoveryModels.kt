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
    ACCESS_RESTRICTED,
    RATE_LIMITED,
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
    val evidence: List<MediaDiscoveryEvidence>,
    val sourcePageUrls: List<String> = emptyList()
) {
    companion object {
        fun from(item: ScrapedMediaItem): MediaDiscoveryCandidate {
            val explicitRole = MediaDiscoveryPolicy.roleHint(item.metadata)
            val role = explicitRole ?: when {
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

            val sourcePageUrls = (
                item.provenance.sourcePageUrls + item.provenance.pageUrl
            ).filter { it.isNotBlank() }.distinct()
            val primarySourcePageUrl = item.provenance.pageUrl.takeIf { it.isNotBlank() }
                ?: sourcePageUrls.firstOrNull().orEmpty()

            return MediaDiscoveryCandidate(
                itemId = item.id,
                resourceUrl = item.url,
                sourcePageUrl = primarySourcePageUrl,
                mediaType = item.category,
                thumbnailUrl = item.thumbnailUrl,
                role = role,
                verificationState = state,
                evidence = evidenceFor(item),
                sourcePageUrls = sourcePageUrls
            )
        }

        private fun hasSuccessfulDirectResponse(item: ScrapedMediaItem): Boolean {
            val status = item.metadata["http_status_code"]?.toIntOrNull()
            return item.metadata["direct_response_confirmed"] == "true" &&
                status != null && status in 200..299
        }

        private fun evidenceFor(item: ScrapedMediaItem): List<MediaDiscoveryEvidence> {
            val selectors = (
                item.provenance.domSelectors + listOfNotNull(item.provenance.domSelector)
            ).filter { it.isNotBlank() }.distinct()
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
                    detail = if (source == MediaDiscoveryEvidenceSource.DOM && selectors.isNotEmpty()) {
                        "$label: ${selectors.joinToString(", ")}"
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
    private val presentationOnlyRoles = setOf(
        MediaAssetRole.PAGE_COVER,
        MediaAssetRole.ARTICLE_HERO,
        MediaAssetRole.THUMBNAIL,
        MediaAssetRole.VIDEO_POSTER,
        MediaAssetRole.EMBED,
        MediaAssetRole.AVATAR,
        MediaAssetRole.UI_DECORATION,
        MediaAssetRole.AD_OR_TRACKER
    )

    private val manualDownloadBlockedStates = setOf(
        MediaVerificationState.BROWSER_ONLY,
        MediaVerificationState.AUTH_REQUIRED,
        MediaVerificationState.ACCESS_RESTRICTED,
        MediaVerificationState.RATE_LIMITED,
        MediaVerificationState.EXPIRED,
        MediaVerificationState.UNSUPPORTED,
        MediaVerificationState.REJECTED
    )

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

    /**
     * Whether a resource may appear in the user's explicit download-selection list.
     * This is not a verification or enqueue decision; weak content candidates still require
     * a separate user action before any download can start.
     */
    private val manualDownloadRoles = setOf(
        MediaAssetRole.CONTENT_IMAGE,
        MediaAssetRole.PRODUCT_IMAGE,
        MediaAssetRole.CONTENT_VIDEO,
        MediaAssetRole.CONTENT_AUDIO,
        MediaAssetRole.DOCUMENT_ATTACHMENT
    )

    /** Parse only recognized semantic role hints; invalid values remain unknown evidence. */
    fun roleHint(metadata: Map<String, String>): MediaAssetRole? =
        metadata["asset_role"]
            ?.trim()
            ?.uppercase(java.util.Locale.ROOT)
            ?.let { value -> runCatching { MediaAssetRole.valueOf(value) }.getOrNull() }

    fun isPresentationOnlyRole(role: MediaAssetRole): Boolean = role in presentationOnlyRoles

    /**
     * Merge explicit role hints conservatively. A presentation-only hint always wins over a
     * content hint; incompatible content roles become UNKNOWN instead of being promoted.
     */
    fun mergeRoleHints(
        existing: MediaAssetRole?,
        incoming: MediaAssetRole?
    ): MediaAssetRole? {
        if (existing == null) return incoming
        if (incoming == null || existing == incoming) return existing

        val presentationRoles = listOf(existing, incoming).filter(::isPresentationOnlyRole)
        if (presentationRoles.isNotEmpty()) {
            val priority = mapOf(
                MediaAssetRole.AD_OR_TRACKER to 8,
                MediaAssetRole.AVATAR to 7,
                MediaAssetRole.UI_DECORATION to 6,
                MediaAssetRole.PAGE_COVER to 5,
                MediaAssetRole.ARTICLE_HERO to 4,
                MediaAssetRole.VIDEO_POSTER to 3,
                MediaAssetRole.THUMBNAIL to 2,
                MediaAssetRole.EMBED to 1
            )
            return presentationRoles.maxByOrNull { priority[it] ?: 0 }
        }

        if (setOf(existing, incoming) == setOf(MediaAssetRole.CONTENT_IMAGE, MediaAssetRole.PRODUCT_IMAGE)) {
            return MediaAssetRole.PRODUCT_IMAGE
        }

        // UNKNOWN is deliberately sticky: conflicting explicit evidence must not promote a resource.
        return MediaAssetRole.UNKNOWN
    }

    fun shouldOfferForManualDownload(candidate: MediaDiscoveryCandidate): Boolean =
        candidate.role in manualDownloadRoles &&
            candidate.verificationState !in manualDownloadBlockedStates

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
            MediaVerificationState.ACCESS_RESTRICTED,
            MediaVerificationState.RATE_LIMITED,
            MediaVerificationState.EXPIRED,
            MediaVerificationState.UNSUPPORTED,
            MediaVerificationState.REJECTED
        )
}
