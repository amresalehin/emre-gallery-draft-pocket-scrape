package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A remote resource discovered on a saved page.
 *
 * [remoteUrl] and [canonicalResourceKey] deliberately preserve the exact observed URL,
 * including its query string. Signed URLs and variant parameters must not be stripped
 * until a provider-specific identity rule proves that doing so is safe.
 *
 * This record contains no cookies, authorization headers, or local file path. A downloaded
 * file remains a separate optional LocalCopy and is not created by discovery.
 */
data class MediaAsset(
    val id: String,
    val bookmarkId: String,
    val remoteUrl: String,
    val canonicalResourceKey: String,
    val mediaType: MediaCategory,
    val role: MediaAssetRole,
    val thumbnailUrl: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val verificationState: MediaVerificationState,
    val evidence: List<MediaDiscoveryEvidence>,
    val sourcePageUrl: String,
    val sourcePageUrls: List<String>,
    val discoveredAtMillis: Long,
    val lastVerifiedAtMillis: Long?,
    val expiresAtMillis: Long?,
    val authenticationRequired: Boolean,
    val userApproved: Boolean
) {
    companion object {
        internal fun fromCandidate(
            bookmarkId: String,
            candidate: MediaDiscoveryCandidate,
            nowMillis: Long
        ): MediaAsset? {
            if (bookmarkId.isBlank()) return null
            val remoteUrl = candidate.resourceUrl
            if (PageFetchUrlPolicy.parseAllowedUrl(remoteUrl) == null) return null

            val sourcePages = (
                listOf(candidate.sourcePageUrl) + candidate.sourcePageUrls
            ).filter { it.isNotBlank() }.distinct()
            val primarySourcePage = candidate.sourcePageUrl
                .takeIf { it.isNotBlank() }
                ?: sourcePages.firstOrNull().orEmpty()
            val directEvidence = candidate.evidence.lastOrNull {
                it.source == MediaDiscoveryEvidenceSource.DIRECT_RESPONSE
            }
            val sizeBytes = candidate.evidence.asReversed().firstNotNullOfOrNull {
                it.contentLengthBytes?.takeIf { size -> size > 0L }
            }
            val lastVerifiedAt = nowMillis.takeIf {
                candidate.verificationState in VERIFIED_STATES
            }
            val id = UUID.nameUUIDFromBytes(
                "$bookmarkId\u0000$remoteUrl".toByteArray(Charsets.UTF_8)
            ).toString()

            return MediaAsset(
                id = id,
                bookmarkId = bookmarkId,
                remoteUrl = remoteUrl,
                canonicalResourceKey = remoteUrl,
                mediaType = candidate.mediaType,
                role = candidate.role,
                thumbnailUrl = candidate.thumbnailUrl,
                mimeType = directEvidence?.observedMimeType,
                sizeBytes = sizeBytes,
                verificationState = candidate.verificationState,
                evidence = candidate.evidence.distinct(),
                sourcePageUrl = primarySourcePage,
                sourcePageUrls = sourcePages,
                discoveredAtMillis = nowMillis,
                lastVerifiedAtMillis = lastVerifiedAt,
                expiresAtMillis = null,
                authenticationRequired = candidate.verificationState == MediaVerificationState.AUTH_REQUIRED,
                userApproved = false
            )
        }

        private val VERIFIED_STATES = setOf(
            MediaVerificationState.VERIFIED_RESOURCE,
            MediaVerificationState.PLAYABLE_DIRECT,
            MediaVerificationState.PLAYABLE_EMBED
        )

    }
}

/**
 * Durable remote-asset metadata, kept separate from bookmark snapshots and local downloads.
 *
 * The current app has no Room database dependency. This first repository uses its own
 * synchronously committed SharedPreferences snapshot, matching the existing bookmark store's
 * durability contract without mixing remote assets into MediaInboxItem (the legacy local-copy
 * record). The storage implementation can be migrated independently later.
 */
class MediaAssetRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _assets = MutableStateFlow<List<MediaAsset>>(emptyList())
    val assets: StateFlow<List<MediaAsset>> = _assets.asStateFlow()

    @Volatile
    private var loadFailed = false

    /** Test seam for deterministic failed-commit coverage. */
    @Volatile
    internal var snapshotCommitOverride: ((String) -> Boolean)? = null

    init {
        loadAssets()
    }

    private fun loadAssets() {
        val rawJson = prefs.getString(KEY_ASSETS_JSON, null)
        if (rawJson.isNullOrBlank()) {
            _assets.value = emptyList()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            _assets.value = buildList {
                for (index in 0 until jsonArray.length()) {
                    add(readAsset(jsonArray.getJSONObject(index)))
                }
            }
        } catch (_: Exception) {
            // Never replace an unreadable asset snapshot with an empty one on a later write.
            loadFailed = true
            _assets.value = emptyList()
        }
    }

    fun assetsForBookmark(bookmarkId: String): List<MediaAsset> =
        _assets.value.filter { it.bookmarkId == bookmarkId }

    /** Presentation-only and unverified records remain stored but are not exposed as gallery media. */
    fun galleryAssetsForBookmark(bookmarkId: String): List<MediaAsset> =
        assetsForBookmark(bookmarkId).filter { asset ->
            MediaDiscoveryPolicy.isGalleryEligible(
                MediaDiscoveryCandidate(
                    itemId = asset.id,
                    resourceUrl = asset.remoteUrl,
                    sourcePageUrl = asset.sourcePageUrl,
                    mediaType = asset.mediaType,
                    thumbnailUrl = asset.thumbnailUrl,
                    role = asset.role,
                    verificationState = asset.verificationState,
                    evidence = asset.evidence,
                    sourcePageUrls = asset.sourcePageUrls
                )
            )
        }

    /**
     * Upserts candidates under an existing bookmark. The exact remote URL is the initial
     * resource identity key; query parameters are preserved to avoid merging signed or variant
     * resources incorrectly. Repeated observations enrich the same stable asset record.
     */
    @Synchronized
    fun upsertCandidates(
        bookmarkId: String,
        candidates: List<MediaDiscoveryCandidate>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<MediaAsset> {
        require(bookmarkId.isNotBlank()) { "bookmarkId must not be blank" }
        if (candidates.isEmpty()) return assetsForBookmark(bookmarkId)

        val updated = _assets.value.toMutableList()
        var changed = false

        candidates.forEach { candidate ->
            val incoming = MediaAsset.fromCandidate(bookmarkId, candidate, nowMillis)
                ?: return@forEach
            val existingIndex = updated.indexOfFirst {
                it.bookmarkId == bookmarkId &&
                    it.canonicalResourceKey == incoming.canonicalResourceKey
            }

            if (existingIndex >= 0) {
                val existing = updated[existingIndex]
                val merged = merge(existing, incoming)
                if (merged != existing) {
                    updated[existingIndex] = merged
                    changed = true
                }
            } else {
                updated.add(incoming)
                changed = true
            }
        }

        if (changed) commitAndPublish(updated)
        return updated.filter { it.bookmarkId == bookmarkId }
    }

    /** Removes remote asset records for one bookmark; it never deletes local files. */
    @Synchronized
    fun removeForBookmark(bookmarkId: String) {
        val updated = _assets.value.filterNot { it.bookmarkId == bookmarkId }
        if (updated.size != _assets.value.size) commitAndPublish(updated)
    }

    private fun merge(existing: MediaAsset, incoming: MediaAsset): MediaAsset {
        val mergedState = mergeVerificationState(
            existing.verificationState,
            incoming.verificationState
        )
        val sourcePages = (
            existing.sourcePageUrls +
                listOf(existing.sourcePageUrl, incoming.sourcePageUrl) +
                incoming.sourcePageUrls
        ).filter { it.isNotBlank() }.distinct()

        return existing.copy(
            role = MediaDiscoveryPolicy.mergeRoleHints(existing.role, incoming.role) ?: existing.role,
            thumbnailUrl = incoming.thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
            mimeType = incoming.mimeType ?: existing.mimeType,
            sizeBytes = incoming.sizeBytes ?: existing.sizeBytes,
            verificationState = mergedState,
            evidence = (existing.evidence + incoming.evidence).distinct(),
            sourcePageUrl = existing.sourcePageUrl.ifBlank { incoming.sourcePageUrl },
            sourcePageUrls = sourcePages,
            lastVerifiedAtMillis = incoming.lastVerifiedAtMillis ?: existing.lastVerifiedAtMillis,
            expiresAtMillis = incoming.expiresAtMillis ?: existing.expiresAtMillis,
            authenticationRequired = mergedState == MediaVerificationState.AUTH_REQUIRED,
            userApproved = existing.userApproved
        )
    }

    private fun mergeVerificationState(
        existing: MediaVerificationState,
        incoming: MediaVerificationState
    ): MediaVerificationState {
        val weakStates = setOf(
            MediaVerificationState.DISCOVERED,
            MediaVerificationState.NEEDS_VERIFICATION
        )
        // A later weak observation cannot erase a prior verification or access result.
        if (incoming in weakStates && existing !in weakStates) return existing
        return incoming
    }

    private fun commitAndPublish(updated: List<MediaAsset>) {
        check(!loadFailed) {
            "Media assets could not be read; refusing to overwrite the persisted snapshot."
        }
        val serialized = serializeAssets(updated)
        val committed = snapshotCommitOverride?.invoke(serialized)
            ?: prefs.edit().putString(KEY_ASSETS_JSON, serialized).commit()
        check(committed) { "Media assets could not be durably updated. Please retry." }
        _assets.value = updated
    }

    private fun serializeAssets(assets: List<MediaAsset>): String {
        val array = JSONArray()
        assets.forEach { asset ->
            val sourcePages = JSONArray().apply {
                asset.sourcePageUrls.forEach { put(it) }
            }
            val evidence = JSONArray().apply {
                asset.evidence.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("source", item.source.name)
                            put("detail", item.detail)
                            put("observedMimeType", item.observedMimeType ?: JSONObject.NULL)
                            put("httpStatusCode", item.httpStatusCode ?: JSONObject.NULL)
                            put("contentLengthBytes", item.contentLengthBytes ?: JSONObject.NULL)
                        }
                    )
                }
            }
            array.put(
                JSONObject().apply {
                    put("id", asset.id)
                    put("bookmarkId", asset.bookmarkId)
                    put("remoteUrl", asset.remoteUrl)
                    put("canonicalResourceKey", asset.canonicalResourceKey)
                    put("mediaType", asset.mediaType.name)
                    put("role", asset.role.name)
                    put("thumbnailUrl", asset.thumbnailUrl ?: JSONObject.NULL)
                    put("mimeType", asset.mimeType ?: JSONObject.NULL)
                    put("sizeBytes", asset.sizeBytes ?: JSONObject.NULL)
                    put("verificationState", asset.verificationState.name)
                    put("evidence", evidence)
                    put("sourcePageUrl", asset.sourcePageUrl)
                    put("sourcePageUrls", sourcePages)
                    put("discoveredAtMillis", asset.discoveredAtMillis)
                    put("lastVerifiedAtMillis", asset.lastVerifiedAtMillis ?: JSONObject.NULL)
                    put("expiresAtMillis", asset.expiresAtMillis ?: JSONObject.NULL)
                    put("authenticationRequired", asset.authenticationRequired)
                    put("userApproved", asset.userApproved)
                }
            )
        }
        return array.toString()
    }

    private fun readAsset(json: JSONObject): MediaAsset {
        val remoteUrl = json.getString("remoteUrl")
        val evidenceArray = json.optJSONArray("evidence") ?: JSONArray()
        val evidence = buildList {
            for (index in 0 until evidenceArray.length()) {
                val item = evidenceArray.getJSONObject(index)
                add(
                    MediaDiscoveryEvidence(
                        source = enumValueOrDefault(
                            item.optString("source"),
                            MediaDiscoveryEvidenceSource.UNKNOWN
                        ),
                        detail = item.optString("detail", ""),
                        observedMimeType = item.nullableString("observedMimeType"),
                        httpStatusCode = item.nullableInt("httpStatusCode"),
                        contentLengthBytes = item.nullableLong("contentLengthBytes")
                    )
                )
            }
        }
        val sourcePagesArray = json.optJSONArray("sourcePageUrls") ?: JSONArray()
        val sourcePages = buildList {
            for (index in 0 until sourcePagesArray.length()) {
                sourcePagesArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        return MediaAsset(
            id = json.getString("id"),
            bookmarkId = json.getString("bookmarkId"),
            remoteUrl = remoteUrl,
            canonicalResourceKey = json.optString("canonicalResourceKey", remoteUrl),
            mediaType = enumValueOrDefault(json.optString("mediaType"), MediaCategory.ALL),
            role = enumValueOrDefault(json.optString("role"), MediaAssetRole.UNKNOWN),
            thumbnailUrl = json.nullableString("thumbnailUrl"),
            mimeType = json.nullableString("mimeType"),
            sizeBytes = json.nullableLong("sizeBytes"),
            verificationState = enumValueOrDefault(
                json.optString("verificationState"),
                MediaVerificationState.DISCOVERED
            ),
            evidence = evidence,
            sourcePageUrl = json.optString("sourcePageUrl", ""),
            sourcePageUrls = sourcePages,
            discoveredAtMillis = json.optLong("discoveredAtMillis", 0L),
            lastVerifiedAtMillis = json.nullableLong("lastVerifiedAtMillis"),
            expiresAtMillis = json.nullableLong("expiresAtMillis"),
            authenticationRequired = json.optBoolean("authenticationRequired", false),
            userApproved = json.optBoolean("userApproved", false)
        )
    }

    companion object {
        const val PREFERENCES_NAME = "media_asset_prefs"
        const val KEY_ASSETS_JSON = "media_assets_json"

        @Volatile
        private var INSTANCE: MediaAssetRepository? = null

        fun getInstance(context: Context): MediaAssetRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaAssetRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.nullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key, -1).takeIf { it >= 0 }
}

private fun JSONObject.nullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key, Long.MIN_VALUE).takeIf { it >= 0L }
}
