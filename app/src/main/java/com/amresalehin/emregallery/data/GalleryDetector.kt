package com.amresalehin.emregallery.data

/**
 * Classification of verified resources discovered on one source page.
 *
 * A single image, page preview, poster, avatar, or unverified URL is never enough to claim that
 * a page contains a gallery. This assessment is separate from PageKind and never changes assets.
 */
enum class MediaGalleryKind {
    NONE,
    SINGLE_ASSET,
    IMAGE_GALLERY,
    MEDIA_COLLECTION
}

/** A deterministic summary of gallery-eligible resources for one discovery pass. */
data class MediaGalleryAssessment(
    val kind: MediaGalleryKind = MediaGalleryKind.NONE,
    val eligibleAssetCount: Int = 0,
    val contentImageCount: Int = 0,
    val contentVideoCount: Int = 0,
    val contentAudioCount: Int = 0,
    val documentCount: Int = 0,
    val embedCount: Int = 0
) {
    val hasGallery: Boolean
        get() = kind == MediaGalleryKind.IMAGE_GALLERY ||
            kind == MediaGalleryKind.MEDIA_COLLECTION
}

/**
 * Detects collections only from resources that have already passed MediaDiscoveryPolicy.
 *
 * URL variants are collapsed with the repository's existing media deduplication key so responsive
 * sizes and tracking variants cannot inflate a single resource into a gallery.
 */
object GalleryDetector {
    private val imageRoles = setOf(
        MediaAssetRole.CONTENT_IMAGE,
        MediaAssetRole.PRODUCT_IMAGE
    )

    fun assess(candidates: List<MediaDiscoveryCandidate>): MediaGalleryAssessment {
        val eligible = candidates.asSequence()
            .filter { MediaDiscoveryPolicy.isGalleryEligible(it) }
            .filter { it.resourceUrl.isNotBlank() }
            .distinctBy { MediaClassifier.deduplicationKey(it.resourceUrl) }
            .toList()

        val images = eligible.count { it.role in imageRoles }
        val videos = eligible.count { it.role == MediaAssetRole.CONTENT_VIDEO }
        val audio = eligible.count { it.role == MediaAssetRole.CONTENT_AUDIO }
        val documents = eligible.count { it.role == MediaAssetRole.DOCUMENT_ATTACHMENT }
        val embeds = eligible.count { it.role == MediaAssetRole.EMBED }

        val kind = when {
            eligible.isEmpty() -> MediaGalleryKind.NONE
            eligible.size == 1 -> MediaGalleryKind.SINGLE_ASSET
            images == eligible.size -> MediaGalleryKind.IMAGE_GALLERY
            else -> MediaGalleryKind.MEDIA_COLLECTION
        }

        return MediaGalleryAssessment(
            kind = kind,
            eligibleAssetCount = eligible.size,
            contentImageCount = images,
            contentVideoCount = videos,
            contentAudioCount = audio,
            documentCount = documents,
            embedCount = embeds
        )
    }
}
