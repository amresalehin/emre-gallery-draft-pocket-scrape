package com.amresalehin.emregallery.data

import java.util.Locale

/**
 * A playback decision for a persisted remote asset.
 *
 * Resolving playback is deliberately separate from downloading and from the local gallery viewer.
 * This policy never performs network I/O, mutates a MediaAsset, or starts a player.
 */
sealed interface PlaybackResolution {
    data class DirectRemote(
        /** Exact observed URL, including signed query parameters. */
        val resourceUrl: String,
        /** Original saved page URL, retained for attribution and browser fallback. */
        val sourcePageUrl: String,
        val mimeType: String
    ) : PlaybackResolution

    data class OpenSourcePage(
        /** Exact original page URL; the browser remains responsible for user authentication. */
        val sourcePageUrl: String,
        val reason: PlaybackFallbackReason
    ) : PlaybackResolution

    data class Unavailable(
        val reason: PlaybackFallbackReason
    ) : PlaybackResolution
}

enum class PlaybackFallbackReason {
    INVALID_SOURCE_PAGE,
    INVALID_RESOURCE_URL,
    AUTHENTICATION_REQUIRED,
    ACCESS_RESTRICTED,
    RATE_LIMITED,
    EXPIRED,
    BROWSER_ONLY,
    EMBED_REQUIRES_BROWSER,
    NOT_VERIFIED,
    RESOURCE_NOT_PLAYABLE,
    PRESENTATION_ONLY,
    UNSUPPORTED_ROLE,
    UNSUPPORTED_RESOURCE,
    MISSING_DIRECT_RESPONSE_EVIDENCE,
    UNSUCCESSFUL_DIRECT_RESPONSE,
    MISSING_MEDIA_MIME,
    MIME_ROLE_MISMATCH,
    REJECTED
}

/**
 * Evidence-backed resolver for remote video/audio assets.
 *
 * A discovered or merely verified resource is not automatically playable. Direct playback
 * requires an explicit PLAYABLE_DIRECT state plus a successful direct-response record whose
 * MIME type agrees with the semantic role. Embeds and access-restricted resources stay in the
 * source browser; this resolver never tries to bypass authentication, paywalls, DRM, or challenges.
 */
object PlaybackResolver {
    private val streamMimeTypes = setOf(
        "application/dash+xml",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl"
    )

    fun resolve(
        asset: MediaAsset,
        nowMillis: Long = System.currentTimeMillis()
    ): PlaybackResolution {
        val sourcePageUrl = asset.sourcePageUrl.takeIf(::isAllowedOriginalUrl)
            ?: return PlaybackResolution.Unavailable(
                PlaybackFallbackReason.INVALID_SOURCE_PAGE
            )

        fun openSource(reason: PlaybackFallbackReason) =
            PlaybackResolution.OpenSourcePage(sourcePageUrl, reason)

        if (asset.authenticationRequired ||
            asset.verificationState == MediaVerificationState.AUTH_REQUIRED
        ) {
            return openSource(PlaybackFallbackReason.AUTHENTICATION_REQUIRED)
        }

        if (asset.verificationState == MediaVerificationState.ACCESS_RESTRICTED) {
            return openSource(PlaybackFallbackReason.ACCESS_RESTRICTED)
        }
        if (asset.verificationState == MediaVerificationState.RATE_LIMITED) {
            return openSource(PlaybackFallbackReason.RATE_LIMITED)
        }
        if (asset.verificationState == MediaVerificationState.EXPIRED ||
            asset.expiresAtMillis?.let { it <= nowMillis } == true
        ) {
            return openSource(PlaybackFallbackReason.EXPIRED)
        }
        if (asset.verificationState == MediaVerificationState.BROWSER_ONLY) {
            return openSource(PlaybackFallbackReason.BROWSER_ONLY)
        }
        if (asset.verificationState == MediaVerificationState.REJECTED) {
            return openSource(PlaybackFallbackReason.REJECTED)
        }

        if (asset.role == MediaAssetRole.EMBED ||
            asset.verificationState == MediaVerificationState.PLAYABLE_EMBED
        ) {
            return openSource(PlaybackFallbackReason.EMBED_REQUIRES_BROWSER)
        }

        if (asset.role in PRESENTATION_ONLY_ROLES) {
            return openSource(PlaybackFallbackReason.PRESENTATION_ONLY)
        }

        if (asset.verificationState != MediaVerificationState.PLAYABLE_DIRECT) {
            val reason = when (asset.verificationState) {
                MediaVerificationState.DISCOVERED,
                MediaVerificationState.NEEDS_VERIFICATION ->
                    PlaybackFallbackReason.NOT_VERIFIED
                MediaVerificationState.VERIFIED_RESOURCE ->
                    PlaybackFallbackReason.RESOURCE_NOT_PLAYABLE
                MediaVerificationState.UNSUPPORTED ->
                    PlaybackFallbackReason.UNSUPPORTED_RESOURCE
                else -> PlaybackFallbackReason.RESOURCE_NOT_PLAYABLE
            }
            return openSource(reason)
        }

        if (asset.role !in DIRECT_PLAYBACK_ROLES) {
            return openSource(PlaybackFallbackReason.UNSUPPORTED_ROLE)
        }

        if (!isAllowedOriginalUrl(asset.remoteUrl)) {
            return openSource(PlaybackFallbackReason.INVALID_RESOURCE_URL)
        }

        val directEvidence = asset.evidence.lastOrNull {
            it.source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE
        } ?: return openSource(PlaybackFallbackReason.MISSING_DIRECT_RESPONSE_EVIDENCE)

        val statusCode = directEvidence.httpStatusCode
        if (statusCode == null || statusCode !in 200..299) {
            return openSource(PlaybackFallbackReason.UNSUCCESSFUL_DIRECT_RESPONSE)
        }

        // Require MIME on the same successful response. A stale MediaAsset.mimeType from an
        // earlier observation must not promote a later response with missing type information.
        val mimeType = directEvidence.observedMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return openSource(PlaybackFallbackReason.MISSING_MEDIA_MIME)

        if (!isMimeCompatible(asset.role, mimeType)) {
            return openSource(PlaybackFallbackReason.MIME_ROLE_MISMATCH)
        }

        return PlaybackResolution.DirectRemote(
            resourceUrl = asset.remoteUrl,
            sourcePageUrl = asset.sourcePageUrl,
            mimeType = mimeType
        )
    }

    private fun isAllowedOriginalUrl(rawUrl: String): Boolean =
        rawUrl == rawUrl.trim() && PageFetchUrlPolicy.parseAllowedUrl(rawUrl) != null

    private fun isMimeCompatible(role: MediaAssetRole, mimeType: String): Boolean = when (role) {
        MediaAssetRole.CONTENT_VIDEO ->
            mimeType.startsWith("video/") || mimeType in streamMimeTypes
        MediaAssetRole.CONTENT_AUDIO ->
            mimeType.startsWith("audio/") || mimeType in streamMimeTypes
        else -> false
    }

    private val DIRECT_PLAYBACK_ROLES = setOf(
        MediaAssetRole.CONTENT_VIDEO,
        MediaAssetRole.CONTENT_AUDIO
    )

    private val PRESENTATION_ONLY_ROLES = setOf(
        MediaAssetRole.PAGE_COVER,
        MediaAssetRole.ARTICLE_HERO,
        MediaAssetRole.THUMBNAIL,
        MediaAssetRole.VIDEO_POSTER,
        MediaAssetRole.AVATAR,
        MediaAssetRole.UI_DECORATION,
        MediaAssetRole.AD_OR_TRACKER
    )
}
