package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

@Entity(
    tableName = "media_assets",
    indices = [Index(value = ["bookmarkId", "canonicalResourceKey"], unique = true)]
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val bookmarkId: String,
    val remoteUrl: String,
    val canonicalResourceKey: String,
    val mediaType: String,
    val role: String,
    val thumbnailUrl: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val verificationState: String,
    val evidenceJson: String,
    val sourcePageUrl: String,
    val sourcePageUrlsJson: String,
    val discoveredAtMillis: Long,
    val lastVerifiedAtMillis: Long?,
    val expiresAtMillis: Long?,
    val authenticationRequired: Boolean,
    val userApproved: Boolean
)

@Dao
interface MediaAssetDao {
    @Query(
        "SELECT * FROM media_assets " +
            "WHERE bookmarkId = :bookmarkId ORDER BY discoveredAtMillis ASC, id ASC"
    )
    suspend fun getForBookmark(bookmarkId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets ORDER BY discoveredAtMillis ASC, id ASC")
    suspend fun getAll(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets ORDER BY discoveredAtMillis ASC, id ASC")
    fun observeAll(): Flow<List<MediaAssetEntity>>

    @Query(
        "SELECT * FROM media_assets " +
            "WHERE bookmarkId = :bookmarkId ORDER BY discoveredAtMillis ASC, id ASC"
    )
    fun observeForBookmark(bookmarkId: String): Flow<List<MediaAssetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<MediaAssetEntity>)

    @Query("DELETE FROM media_assets WHERE bookmarkId = :bookmarkId")
    suspend fun removeForBookmark(bookmarkId: String)
}

@Database(
    entities = [MediaAssetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MediaAssetDatabase : RoomDatabase() {
    abstract fun mediaAssetDao(): MediaAssetDao
}

/**
 * Room is the source of truth for remote media assets.
 *
 * The previous checkpoint briefly wrote a JSON SharedPreferences snapshot. Its exact snapshot
 * is treated only as a one-time migration input and retained as a recovery backup after Room
 * verifies the imported rows. No new asset writes go to SharedPreferences.
 */
class MediaAssetRepository(
    private val dao: MediaAssetDao,
    private val legacyPrefs: SharedPreferences,
    private val database: MediaAssetDatabase
) {
    private val migrationMutex = Mutex()

    @Volatile
    private var migrationComplete = false

    val assets: Flow<List<MediaAsset>> = flow {
        ensureLegacyMigration()
        emitAll(
            dao.observeAll().map { rows ->
                rows.mapNotNull { it.toDomainOrNull() }
            }
        )
    }

    fun observeAssetsForBookmark(bookmarkId: String): Flow<List<MediaAsset>> = flow {
        ensureLegacyMigration()
        emitAll(
            dao.observeForBookmark(bookmarkId).map { rows ->
                rows.mapNotNull { it.toDomainOrNull() }
            }
        )
    }

    suspend fun assetsForBookmark(bookmarkId: String): List<MediaAsset> =
        withContext(Dispatchers.IO) {
            ensureLegacyMigration()
            dao.getForBookmark(bookmarkId).mapNotNull { it.toDomainOrNull() }
        }

    suspend fun galleryAssetsForBookmark(bookmarkId: String): List<MediaAsset> =
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
    suspend fun upsertCandidates(
        bookmarkId: String,
        candidates: List<MediaDiscoveryCandidate>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<MediaAsset> = withContext(Dispatchers.IO) {
        require(bookmarkId.isNotBlank()) { "bookmarkId must not be blank" }
        ensureLegacyMigration()

        database.withTransaction {
            val existingByKey = dao.getForBookmark(bookmarkId)
                .mapNotNull { it.toDomainOrNull() }
                .associateBy { it.canonicalResourceKey }
            val mergedByKey = linkedMapOf<String, MediaAsset>()

            candidates.forEach { candidate ->
                val incoming = MediaAsset.fromCandidate(bookmarkId, candidate, nowMillis)
                    ?: return@forEach
                val key = incoming.canonicalResourceKey
                val existing = mergedByKey[key] ?: existingByKey[key]
                mergedByKey[key] = if (existing == null) incoming else merge(existing, incoming)
            }

            if (mergedByKey.isNotEmpty()) {
                dao.upsertAll(mergedByKey.values.map { it.toEntity() })
            }
            dao.getForBookmark(bookmarkId).mapNotNull { it.toDomainOrNull() }
        }
    }

    suspend fun removeForBookmark(bookmarkId: String) {
        withContext(Dispatchers.IO) {
            ensureLegacyMigration()
            database.withTransaction {
                dao.removeForBookmark(bookmarkId)
            }
        }
    }

    private suspend fun ensureLegacyMigration() {
        if (migrationComplete) return

        migrationMutex.withLock {
            if (migrationComplete) return@withLock

            withContext(Dispatchers.IO) {
                if (legacyPrefs.getBoolean(KEY_ROOM_MIGRATION_COMPLETE, false)) {
                    migrationComplete = true
                    return@withContext
                }

                val rawJson = legacyPrefs.getString(KEY_ASSETS_JSON, null)
                if (rawJson.isNullOrBlank()) {
                    check(
                        legacyPrefs.edit()
                            .putBoolean(KEY_ROOM_MIGRATION_COMPLETE, true)
                            .commit()
                    ) { "Could not record completion of the media asset migration." }
                    migrationComplete = true
                    return@withContext
                }

                val legacyAssets = try {
                    readLegacySnapshot(rawJson)
                } catch (error: Exception) {
                    throw IllegalStateException(
                        "Legacy media assets are unreadable; the original snapshot was preserved.",
                        error
                    )
                }

                val normalized = linkedMapOf<Pair<String, String>, MediaAsset>()
                legacyAssets.forEach { incoming ->
                    val key = incoming.bookmarkId to incoming.canonicalResourceKey
                    val existing = normalized[key]
                    normalized[key] = if (existing == null) incoming else merge(existing, incoming)
                }
                val assetsToMigrate = normalized.values.toList()
                database.withTransaction {
                    if (assetsToMigrate.isNotEmpty()) {
                        dao.upsertAll(assetsToMigrate.map { it.toEntity() })
                    }

                    val persistedKeys = dao.getAll()
                        .map { it.bookmarkId to it.canonicalResourceKey }
                        .toSet()
                    check(
                        assetsToMigrate.all {
                            (it.bookmarkId to it.canonicalResourceKey) in persistedKeys
                        }
                    ) { "Room did not confirm every legacy media asset; the backup was preserved." }
                }

                check(
                    legacyPrefs.edit()
                        .putBoolean(KEY_ROOM_MIGRATION_COMPLETE, true)
                        .commit()
                ) { "Could not record completion of the media asset migration." }
                // Keep KEY_ASSETS_JSON intact as a recoverable backup; never clear it here.
                migrationComplete = true
            }
        }
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
            mediaType = if (existing.mediaType == incoming.mediaType) {
                existing.mediaType
            } else {
                MediaCategory.ALL
            },
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
        // A later weak observation cannot erase prior verification or a known access result.
        if (incoming in weakStates && existing !in weakStates) return existing
        return incoming
    }

    companion object {
        const val PREFERENCES_NAME = "media_asset_prefs"
        const val KEY_ASSETS_JSON = "media_assets_json"
        const val KEY_ROOM_MIGRATION_COMPLETE = "media_assets_room_migration_complete"
        const val DATABASE_NAME = "emre_content.db"

        @Volatile
        private var INSTANCE: MediaAssetRepository? = null

        fun getInstance(context: Context): MediaAssetRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val database = Room.databaseBuilder(
                        appContext,
                        MediaAssetDatabase::class.java,
                        DATABASE_NAME
                    ).build()
                    MediaAssetRepository(
                        dao = database.mediaAssetDao(),
                        legacyPrefs = appContext.getSharedPreferences(
                            PREFERENCES_NAME,
                            Context.MODE_PRIVATE
                        ),
                        database = database
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}

private fun readLegacySnapshot(rawJson: String): List<MediaAsset> {
    val array = JSONArray(rawJson)
    return (0 until array.length()).map { index ->
        readLegacyAsset(array.getJSONObject(index))
    }
}

private fun readLegacyAsset(json: JSONObject): MediaAsset {
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
    val sourcePageUrl = json.optString("sourcePageUrl", "")

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
        sourcePageUrl = sourcePageUrl,
        sourcePageUrls = sourcePages.ifEmpty {
            sourcePageUrl.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        },
        discoveredAtMillis = json.optLong("discoveredAtMillis", 0L),
        lastVerifiedAtMillis = json.nullableLong("lastVerifiedAtMillis"),
        expiresAtMillis = json.nullableLong("expiresAtMillis"),
        authenticationRequired = json.optBoolean("authenticationRequired", false),
        userApproved = json.optBoolean("userApproved", false)
    )
}

private fun MediaAsset.toEntity(): MediaAssetEntity = MediaAssetEntity(
    id = id,
    bookmarkId = bookmarkId,
    remoteUrl = remoteUrl,
    canonicalResourceKey = canonicalResourceKey,
    mediaType = mediaType.name,
    role = role.name,
    thumbnailUrl = thumbnailUrl,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    verificationState = verificationState.name,
    evidenceJson = JSONArray().apply {
        evidence.forEach { item ->
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
    }.toString(),
    sourcePageUrl = sourcePageUrl,
    sourcePageUrlsJson = JSONArray().apply {
        sourcePageUrls.forEach { put(it) }
    }.toString(),
    discoveredAtMillis = discoveredAtMillis,
    lastVerifiedAtMillis = lastVerifiedAtMillis,
    expiresAtMillis = expiresAtMillis,
    authenticationRequired = authenticationRequired,
    userApproved = userApproved
)

private fun MediaAssetEntity.toDomainOrNull(): MediaAsset? = runCatching {
    readLegacyAsset(
        JSONObject().apply {
            put("id", id)
            put("bookmarkId", bookmarkId)
            put("remoteUrl", remoteUrl)
            put("canonicalResourceKey", canonicalResourceKey)
            put("mediaType", mediaType)
            put("role", role)
            put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
            put("mimeType", mimeType ?: JSONObject.NULL)
            put("sizeBytes", sizeBytes ?: JSONObject.NULL)
            put("verificationState", verificationState)
            put("evidence", JSONArray(evidenceJson))
            put("sourcePageUrl", sourcePageUrl)
            put("sourcePageUrls", JSONArray(sourcePageUrlsJson))
            put("discoveredAtMillis", discoveredAtMillis)
            put("lastVerifiedAtMillis", lastVerifiedAtMillis ?: JSONObject.NULL)
            put("expiresAtMillis", expiresAtMillis ?: JSONObject.NULL)
            put("authenticationRequired", authenticationRequired)
            put("userApproved", userApproved)
        }
    )
}.getOrNull()

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
