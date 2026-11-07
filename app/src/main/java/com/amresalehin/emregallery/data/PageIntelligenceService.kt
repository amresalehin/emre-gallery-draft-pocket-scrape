package com.amresalehin.emregallery.data

import java.util.concurrent.CancellationException

/** Why one independent page parser could not produce a result. */
enum class PageIntelligenceExtractionFailureReason {
    PARSER_ERROR
}

data class PageIntelligenceExtractionFailure(
    val reason: PageIntelligenceExtractionFailureReason,
    val exceptionType: String
)

/** A parser result is independent from the other enrichments for the same fetched page. */
sealed interface PageIntelligenceExtraction<out T> {
    data class Success<T>(val value: T) : PageIntelligenceExtraction<T>
    data class Failure(val failure: PageIntelligenceExtractionFailure) : PageIntelligenceExtraction<Nothing>
}

data class PageIntelligenceSnapshot(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val bodyBytes: Long,
    val metadata: PageIntelligenceExtraction<PageMetadata>,
    val structuredData: PageIntelligenceExtraction<JsonLdParseResult>,
    val classification: PageClassification,
    val preview: PagePreview
)

sealed interface PageIntelligenceResult {
    data class Success(val snapshot: PageIntelligenceSnapshot) : PageIntelligenceResult
    data class FetchFailure(
        val requestedUrl: String,
        val failure: PageFetchFailure
    ) : PageIntelligenceResult
}

/**
 * Coordinates static page fetching and independent metadata/JSON-LD parsing.
 *
 * This service does not persist bookmarks, discover media, or download files.
 * It discards the HTML body after parsing and returns only bounded fetch facts and parser outputs.
 * Parser failures are isolated so one broken enrichment does not discard the other result.
 */
class PageIntelligenceService(
    private val pageFetcher: PageFetcher = PageFetcher(),
    private val metadataParser: (String, String) -> PageMetadata = PageMetadataParser::parse,
    private val structuredDataParser: (String, String) -> JsonLdParseResult = JsonLdParser::parse
) {

    suspend fun inspect(url: String): PageIntelligenceResult {
        return when (val fetchResult = pageFetcher.fetch(url)) {
            is PageFetchResult.Failure -> PageIntelligenceResult.FetchFailure(
                requestedUrl = url.trim(),
                failure = fetchResult.failure
            )
            is PageFetchResult.Success -> {
                val fetched = fetchResult.page
                val metadata = extractSafely {
                    metadataParser(fetched.html, fetched.finalUrl)
                }
                val structuredData = extractSafely {
                    structuredDataParser(fetched.html, fetched.finalUrl)
                }
                val metadataValue = when (metadata) {
                    is PageIntelligenceExtraction.Success -> metadata.value
                    is PageIntelligenceExtraction.Failure -> null
                }
                val structuredDataValue = when (structuredData) {
                    is PageIntelligenceExtraction.Success -> structuredData.value
                    is PageIntelligenceExtraction.Failure -> null
                }
                val classification = PageClassifier.classify(metadataValue, structuredDataValue)
                val preview = PagePreviewResolver.resolve(
                    sourcePageUrl = fetched.finalUrl,
                    metadata = metadataValue,
                    structuredData = structuredDataValue,
                    classification = classification
                )

                PageIntelligenceResult.Success(
                    PageIntelligenceSnapshot(
                        requestedUrl = fetched.requestedUrl,
                        finalUrl = fetched.finalUrl,
                        statusCode = fetched.statusCode,
                        contentType = fetched.contentType,
                        bodyBytes = fetched.bodyBytes,
                        metadata = metadata,
                        structuredData = structuredData,
                        classification = classification,
                        preview = preview
                    )
                )
            }
        }
    }

    private inline fun <T> extractSafely(block: () -> T): PageIntelligenceExtraction<T> {
        return try {
            PageIntelligenceExtraction.Success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PageIntelligenceExtraction.Failure(
                PageIntelligenceExtractionFailure(
                    reason = PageIntelligenceExtractionFailureReason.PARSER_ERROR,
                    exceptionType = error::class.java.simpleName.ifBlank { "Exception" }
                )
            )
        }
    }
}
