package com.amresalehin.emregallery.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Result of one bounded candidate-verification pass. */
data class MediaDiscoveryResult(
    val candidates: List<MediaDiscoveryCandidate>,
    val probesAttempted: Int,
    val probesFailed: Int,
    val unsafeCandidatesRejected: Int,
    val deferredCandidates: Int
)

/**
 * Converts normalized capture output into independently verified discovery candidates.
 *
 * This coordinator never downloads a resource, never copies download headers, and never changes
 * the legacy extraction items. Candidate probes use the isolated no-cookie/public-address client.
 */
class MediaDiscoveryCoordinator(
    private val probe: suspend (String) -> OkHttpDownloader.DirectMediaProbeResult = {
        OkHttpDownloader.probeDiscoveredMedia(it)
    },
    private val maxConcurrentProbes: Int = DEFAULT_MAX_CONCURRENT_PROBES,
    private val maxCandidatesToProbe: Int = DEFAULT_MAX_CANDIDATES_TO_PROBE
) {
    init {
        require(maxConcurrentProbes > 0) { "maxConcurrentProbes must be positive" }
        require(maxCandidatesToProbe > 0) { "maxCandidatesToProbe must be positive" }
    }

    suspend fun discover(
        items: List<ScrapedMediaItem>,
        sourcePageUrl: String? = null
    ): MediaDiscoveryResult = coroutineScope {
        val candidates = MediaNormalizer.deduplicate(items).map { item ->
            val candidate = MediaDiscoveryCandidate.from(item)
            if (!sourcePageUrl.isNullOrBlank()) {
                val sourcePages = (
                    listOf(sourcePageUrl) + candidate.sourcePageUrls + candidate.sourcePageUrl
                ).filter { it.isNotBlank() }.distinct()
                candidate.copy(sourcePageUrl = sourcePageUrl, sourcePageUrls = sourcePages)
            } else {
                candidate
            }
        }
        val probeable = candidates.filter { candidate ->
            candidate.verificationState == MediaVerificationState.NEEDS_VERIFICATION &&
                candidate.role in PROBEABLE_ROLES &&
                hasConsistentRoleAndMediaType(candidate) &&
                !candidate.resourceUrl.startsWith("data:", ignoreCase = true) &&
                !MediaClassifier.isMediaFragment(candidate.resourceUrl)
        }

        val uniqueProbeable = LinkedHashMap<String, MediaDiscoveryCandidate>()
        probeable.forEach { candidate ->
            if (!uniqueProbeable.containsKey(candidate.resourceUrl)) {
                uniqueProbeable[candidate.resourceUrl] = candidate
            }
        }

        val rejectedUrls = mutableSetOf<String>()
        val safeCandidates = mutableListOf<MediaDiscoveryCandidate>()
        uniqueProbeable.values.forEach { candidate ->
            if (PageFetchUrlPolicy.parseAllowedUrl(candidate.resourceUrl) == null) {
                rejectedUrls += candidate.resourceUrl
            } else if (safeCandidates.size < maxCandidatesToProbe) {
                safeCandidates += candidate
            }
        }

        val semaphore = Semaphore(maxConcurrentProbes)
        val probeResults = safeCandidates.map { candidate ->
            async(Dispatchers.IO) {
                val result = try {
                    semaphore.withPermit { probe(candidate.resourceUrl) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                candidate.resourceUrl to result
            }
        }.awaitAll().toMap()

        val enrichedCandidates = candidates.map { candidate ->
            when {
                candidate.verificationState != MediaVerificationState.NEEDS_VERIFICATION -> candidate
                candidate.resourceUrl in rejectedUrls ->
                    candidate.copy(verificationState = MediaVerificationState.REJECTED)
                else -> {
                    val result = probeResults[candidate.resourceUrl] ?: return@map candidate
                    val responseEvidence = if (result.statusCode >= 0) {
                        candidate.evidence + MediaDiscoveryEvidence(
                            source = MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
                            detail = "Unauthenticated bounded response probe",
                            observedMimeType = result.contentType
                                ?.substringBefore(";")
                                ?.trim()
                                ?.takeIf { it.isNotBlank() },
                            httpStatusCode = result.statusCode,
                            contentLengthBytes = result.contentLength
                        )
                    } else {
                        candidate.evidence
                    }
                    val verificationState = if (
                        result.isConfirmedDirectMedia && !isCompatibleMediaResponse(candidate, result)
                    ) {
                        MediaVerificationState.REJECTED
                    } else {
                        result.verificationState
                    }
                    candidate.copy(
                        verificationState = verificationState,
                        evidence = responseEvidence
                    )
                }
            }
        }

        MediaDiscoveryResult(
            candidates = enrichedCandidates,
            probesAttempted = safeCandidates.size,
            probesFailed = probeResults.values.count { it == null || it.statusCode < 0 },
            unsafeCandidatesRejected = rejectedUrls.size,
            deferredCandidates = (
                uniqueProbeable.size - rejectedUrls.size - safeCandidates.size
            ).coerceAtLeast(0)
        )
    }

    private fun hasConsistentRoleAndMediaType(candidate: MediaDiscoveryCandidate): Boolean =
        when (candidate.role) {
            MediaAssetRole.CONTENT_IMAGE, MediaAssetRole.PRODUCT_IMAGE ->
                candidate.mediaType == MediaCategory.IMAGES
            MediaAssetRole.CONTENT_VIDEO ->
                candidate.mediaType == MediaCategory.VIDEOS ||
                    candidate.mediaType == MediaCategory.STREAMS
            MediaAssetRole.CONTENT_AUDIO ->
                candidate.mediaType == MediaCategory.AUDIO ||
                    candidate.mediaType == MediaCategory.STREAMS
            else -> false
        }

    private fun isCompatibleMediaResponse(
        candidate: MediaDiscoveryCandidate,
        result: OkHttpDownloader.DirectMediaProbeResult
    ): Boolean {
        val contentType = result.contentType
            ?.substringBefore(";")
            ?.trim()
            ?.lowercase(java.util.Locale.ROOT)
        val extension = contentDispositionExtension(result.contentDisposition)
        val genericBinaryType = contentType?.let { GENERIC_BINARY_TYPES.contains(it) } ?: true
        val streamMime = contentType != null && contentType in STREAM_CONTENT_TYPES
        val streamExtension = candidate.mediaType == MediaCategory.STREAMS &&
            extension != null && extension in STREAM_EXTENSIONS

        return when (candidate.role) {
            MediaAssetRole.CONTENT_IMAGE, MediaAssetRole.PRODUCT_IMAGE ->
                contentType?.startsWith("image/") == true ||
                    (genericBinaryType && extension != null && extension in IMAGE_EXTENSIONS)
            MediaAssetRole.CONTENT_VIDEO ->
                contentType?.startsWith("video/") == true ||
                    (candidate.mediaType == MediaCategory.STREAMS && streamMime) ||
                    (genericBinaryType && extension != null &&
                        (extension in VIDEO_EXTENSIONS || streamExtension))
            MediaAssetRole.CONTENT_AUDIO ->
                contentType?.startsWith("audio/") == true ||
                    (candidate.mediaType == MediaCategory.STREAMS && streamMime) ||
                    (genericBinaryType && extension != null &&
                        (extension in AUDIO_EXTENSIONS || streamExtension))
            else -> false
        }
    }

    private fun contentDispositionExtension(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val filenameValue = contentDisposition.split(';').drop(1).firstNotNullOfOrNull { parameter ->
            val name = parameter.substringBefore("=").trim()
            if (name.equals("filename", ignoreCase = true) ||
                name.equals("filename*", ignoreCase = true)
            ) {
                parameter.substringAfter("=", "").trim()
            } else {
                null
            }
        } ?: return null
        val unquoted = filenameValue
            .removeSurrounding(34.toChar().toString())
            .removeSurrounding(39.toChar().toString())
        val decoded = unquoted.substringAfter("''", unquoted)
        val filename = decoded.substringAfterLast('/').substringAfterLast(92.toChar())
        return filename.substringAfterLast('.', "").substringBefore('?')
            .lowercase(java.util.Locale.ROOT)
            .takeIf { it.isNotBlank() }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_PROBES = 3
        const val DEFAULT_MAX_CANDIDATES_TO_PROBE = 9

        private val PROBEABLE_ROLES = setOf(
            MediaAssetRole.CONTENT_IMAGE,
            MediaAssetRole.PRODUCT_IMAGE,
            MediaAssetRole.CONTENT_VIDEO,
            MediaAssetRole.CONTENT_AUDIO
        )
        private val GENERIC_BINARY_TYPES = setOf(
            "application/octet-stream",
            "binary/octet-stream",
            "application/download",
            "application/x-download",
            "application/force-download"
        )
        private val STREAM_CONTENT_TYPES = setOf(
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/dash+xml"
        )
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif", "bmp", "svg"
        )
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "webm", "mkv", "ogv", "3gp"
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "oga", "ogg", "opus"
        )
        private val STREAM_EXTENSIONS = setOf("m3u8", "mpd")
    }
}
