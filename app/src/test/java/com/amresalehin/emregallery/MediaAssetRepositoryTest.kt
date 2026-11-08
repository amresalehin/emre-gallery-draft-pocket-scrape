package com.amresalehin.emregallery

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.MediaAssetDao
import com.amresalehin.emregallery.data.MediaAssetDatabase
import com.amresalehin.emregallery.data.MediaAssetEntity
import com.amresalehin.emregallery.data.MediaAssetRepository
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryCandidate
import com.amresalehin.emregallery.data.MediaDiscoveryEvidence
import com.amresalehin.emregallery.data.MediaDiscoveryEvidenceSource
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.MediaVerificationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaAssetRepositoryTest {
    private lateinit var context: Context
    private lateinit var legacyPrefs: SharedPreferences
    private lateinit var databaseName: String
    private var database: MediaAssetDatabase? = null
    private lateinit var repository: MediaAssetRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        legacyPrefs = context.getSharedPreferences(
            MediaAssetRepository.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        legacyPrefs.edit().clear().commit()
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        databaseName = "media-assets-test-${UUID.randomUUID()}.db"
        database = newDatabase()
        repository = newRepository(database!!)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
        legacyPrefs.edit().clear().commit()
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun discoveredAssetPersistsWithBookmarkAndExactRemoteUrl() = runBlocking {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved article",
            url = "https://example.com/article"
        )
        val remoteUrl = "https://cdn.example.com/photo.jpg?signature=keep-this-query"

        val saved = repository.upsertCandidates(
            bookmark.id,
            listOf(
                candidate(
                    remoteUrl = remoteUrl,
                    sourcePageUrl = bookmark.sourceUrl,
                    verificationState = MediaVerificationState.VERIFIED_RESOURCE,
                    evidence = listOf(
                        MediaDiscoveryEvidence(
                            source = MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
                            detail = "Bounded response probe",
                            observedMimeType = "image/jpeg",
                            httpStatusCode = 200,
                            contentLengthBytes = 4096L
                        )
                    )
                )
            ),
            nowMillis = 100L
        ).single()

        assertEquals(bookmark.id, saved.bookmarkId)
        assertEquals(remoteUrl, saved.remoteUrl)
        assertEquals(remoteUrl, saved.canonicalResourceKey)
        assertEquals("https://example.com/article", saved.sourcePageUrl)
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals(4096L, saved.sizeBytes)
        assertEquals(100L, saved.lastVerifiedAtMillis)

        database?.close()
        database = newDatabase()
        val reloaded = newRepository(database!!).assetsForBookmark(bookmark.id).single()
        assertEquals(saved, reloaded)
    }

    @Test
    fun repeatedDiscoveryMergesEvidenceAndSourcePagesWithoutDowngradingVerification() = runBlocking {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved page",
            url = "https://example.com/article"
        )
        val remoteUrl = "https://cdn.example.com/gallery/image.jpg?variant=full"

        val first = repository.upsertCandidates(
            bookmark.id,
            listOf(
                candidate(
                    remoteUrl = remoteUrl,
                    sourcePageUrl = bookmark.sourceUrl,
                    role = MediaAssetRole.CONTENT_IMAGE,
                    verificationState = MediaVerificationState.NEEDS_VERIFICATION,
                    evidence = listOf(
                        MediaDiscoveryEvidence(
                            MediaDiscoveryEvidenceSource.DOM,
                            "img.gallery-image"
                        )
                    )
                )
            ),
            nowMillis = 100L
        ).single()

        val verified = repository.upsertCandidates(
            bookmark.id,
            listOf(
                candidate(
                    remoteUrl = remoteUrl,
                    sourcePageUrl = "https://example.com/alternate",
                    sourcePageUrls = listOf("https://example.com/alternate"),
                    role = MediaAssetRole.PRODUCT_IMAGE,
                    verificationState = MediaVerificationState.VERIFIED_RESOURCE,
                    evidence = listOf(
                        MediaDiscoveryEvidence(
                            MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
                            "HEAD response",
                            observedMimeType = "image/jpeg",
                            httpStatusCode = 200,
                            contentLengthBytes = 2048L
                        )
                    )
                )
            ),
            nowMillis = 200L
        ).single()

        val weakRefresh = repository.upsertCandidates(
            bookmark.id,
            listOf(
                candidate(
                    remoteUrl = remoteUrl,
                    sourcePageUrl = "https://example.com/third",
                    sourcePageUrls = listOf("https://example.com/third"),
                    role = MediaAssetRole.CONTENT_IMAGE,
                    verificationState = MediaVerificationState.NEEDS_VERIFICATION
                )
            ),
            nowMillis = 300L
        ).single()

        assertEquals(first.id, verified.id)
        assertEquals(MediaAssetRole.PRODUCT_IMAGE, verified.role)
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, verified.verificationState)
        assertEquals(100L, verified.discoveredAtMillis)
        assertEquals(200L, verified.lastVerifiedAtMillis)
        assertEquals(bookmark.sourceUrl, verified.sourcePageUrl)
        assertEquals(
            listOf(bookmark.sourceUrl, "https://example.com/alternate"),
            verified.sourcePageUrls
        )
        assertEquals(2, verified.evidence.size)
        assertEquals(MediaVerificationState.VERIFIED_RESOURCE, weakRefresh.verificationState)
        assertEquals(200L, weakRefresh.lastVerifiedAtMillis)
        assertTrue(weakRefresh.sourcePageUrls.contains("https://example.com/third"))
    }

    @Test
    fun freshAccessRestrictionReplacesPreviouslyVerifiedState() = runBlocking {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved page",
            url = "https://example.com/article"
        )
        val remoteUrl = "https://cdn.example.com/video.mp4"

        repository.upsertCandidates(
            bookmark.id,
            listOf(
                candidate(
                    remoteUrl = remoteUrl,
                    role = MediaAssetRole.CONTENT_VIDEO,
                    mediaType = MediaCategory.VIDEOS,
                    verificationState = MediaVerificationState.VERIFIED_RESOURCE
                )
            ),
            nowMillis = 100L
        )

        val restricted = repository.upsertCandidates(
            bookmark.id,
            listOf(
                candidate(
                    remoteUrl = remoteUrl,
                    role = MediaAssetRole.CONTENT_VIDEO,
                    mediaType = MediaCategory.VIDEOS,
                    verificationState = MediaVerificationState.ACCESS_RESTRICTED,
                    evidence = listOf(
                        MediaDiscoveryEvidence(
                            MediaDiscoveryEvidenceSource.DIRECT_RESPONSE,
                            "HTTP 403",
                            httpStatusCode = 403
                        )
                    )
                )
            ),
            nowMillis = 200L
        ).single()

        assertEquals(MediaVerificationState.ACCESS_RESTRICTED, restricted.verificationState)
        assertFalse(restricted.authenticationRequired)
        assertEquals(100L, restricted.lastVerifiedAtMillis)
    }

    @Test
    fun failedDaoWriteDoesNotPersistCandidate() = runBlocking {
        val failingRepository = MediaAssetRepository(
            dao = FailingWriteDao(database!!.mediaAssetDao()),
            legacyPrefs = legacyPrefs,
            database = database!!
        )

        val result = runCatching {
            failingRepository.upsertCandidates(
                "bookmark-existing",
                listOf(candidate(remoteUrl = "https://cdn.example.com/image.jpg")),
                nowMillis = 100L
            )
        }

        assertTrue(result.isFailure)
        assertTrue(database!!.mediaAssetDao().getForBookmark("bookmark-existing").isEmpty())
    }

    @Test
    fun legacyJsonSnapshotMigratesToRoomAndRemainsAsRecoveryBackup() = runBlocking {
        val remoteUrl = "https://cdn.example.com/legacy.jpg?signature=preserve"
        val rawSnapshot = legacySnapshot(
            id = "legacy-asset-id",
            bookmarkId = "legacy-bookmark-id",
            remoteUrl = remoteUrl,
            sourcePageUrl = "https://example.com/legacy"
        )
        legacyPrefs.edit()
            .putString(MediaAssetRepository.KEY_ASSETS_JSON, rawSnapshot)
            .commit()

        val migrated = repository.assetsForBookmark("legacy-bookmark-id").single()

        assertEquals(remoteUrl, migrated.remoteUrl)
        assertEquals("legacy-asset-id", migrated.id)
        assertTrue(legacyPrefs.getBoolean(MediaAssetRepository.KEY_ROOM_MIGRATION_COMPLETE, false))
        assertEquals(
            rawSnapshot,
            legacyPrefs.getString(MediaAssetRepository.KEY_ASSETS_JSON, null)
        )
    }

    @Test
    fun corruptLegacySnapshotFailsClosedAndKeepsBackup() = runBlocking {
        val corruptSnapshot = "{"
        legacyPrefs.edit()
            .putString(MediaAssetRepository.KEY_ASSETS_JSON, corruptSnapshot)
            .commit()

        val result = runCatching { repository.assetsForBookmark("bookmark-existing") }

        assertTrue(result.isFailure)
        assertFalse(legacyPrefs.getBoolean(MediaAssetRepository.KEY_ROOM_MIGRATION_COMPLETE, false))
        assertEquals(
            corruptSnapshot,
            legacyPrefs.getString(MediaAssetRepository.KEY_ASSETS_JSON, null)
        )
        assertTrue(database!!.mediaAssetDao().getAll().isEmpty())
    }

    @Test
    fun unsafeUrlsAreNotPersistedButSafeRejectedEvidenceIsRetained() = runBlocking {
        val saved = repository.upsertCandidates(
            "bookmark-existing",
            listOf(
                candidate(remoteUrl = "file:///tmp/photo.jpg"),
                candidate(
                    remoteUrl = "https://cdn.example.com/rejected.jpg",
                    verificationState = MediaVerificationState.REJECTED
                ),
                candidate(remoteUrl = "https://cdn.example.com/valid.jpg")
            ),
            nowMillis = 100L
        )

        assertEquals(2, saved.size)
        assertTrue(saved.any { it.remoteUrl == "https://cdn.example.com/valid.jpg" })
        assertTrue(saved.any {
            it.remoteUrl == "https://cdn.example.com/rejected.jpg" &&
                it.verificationState == MediaVerificationState.REJECTED
        })
        assertFalse(saved.any { it.remoteUrl.startsWith("file:") })
    }

    @Test
    fun galleryReadModelExcludesPreviewAndUnverifiedCandidates() = runBlocking {
        repository.upsertCandidates(
            "bookmark-gallery",
            listOf(
                candidate(
                    remoteUrl = "https://cdn.example.com/verified.jpg",
                    verificationState = MediaVerificationState.VERIFIED_RESOURCE
                ),
                candidate(
                    remoteUrl = "https://cdn.example.com/unverified.jpg",
                    verificationState = MediaVerificationState.NEEDS_VERIFICATION
                ),
                candidate(
                    remoteUrl = "https://cdn.example.com/poster.jpg",
                    role = MediaAssetRole.VIDEO_POSTER,
                    verificationState = MediaVerificationState.VERIFIED_RESOURCE
                ),
                candidate(
                    remoteUrl = "https://cdn.example.com/avatar.jpg",
                    role = MediaAssetRole.AVATAR,
                    verificationState = MediaVerificationState.VERIFIED_RESOURCE
                )
            ),
            nowMillis = 100L
        )

        assertEquals(
            listOf("https://cdn.example.com/verified.jpg"),
            repository.galleryAssetsForBookmark("bookmark-gallery").map { it.remoteUrl }
        )
        assertEquals(4, repository.assetsForBookmark("bookmark-gallery").size)
    }

    @Test
    fun bookmarkLookupUsesConservativeIdentityAndNeverCreatesOne() {
        val bookmarkRepository = MediaInboxRepository(context)
        val originalUrl = "HTTPS://EXAMPLE.COM:443/article?item=1"
        val bookmark = bookmarkRepository.addBookmark("Saved page", originalUrl)

        assertEquals(
            bookmark.id,
            bookmarkRepository.findBookmarkBySourceUrl("https://example.com/article?item=1")?.id
        )
        assertNull(bookmarkRepository.findBookmarkBySourceUrl("https://example.com/article?item=2"))
        assertNull(bookmarkRepository.findBookmarkBySourceUrl("https://missing.example/article"))
        assertEquals(originalUrl, bookmarkRepository.stacks.value.single().sourceUrl)
    }

    private fun newDatabase(): MediaAssetDatabase =
        Room.databaseBuilder(context, MediaAssetDatabase::class.java, databaseName).build()

    private fun newRepository(database: MediaAssetDatabase): MediaAssetRepository =
        MediaAssetRepository(database.mediaAssetDao(), legacyPrefs, database)

    private fun legacySnapshot(
        id: String,
        bookmarkId: String,
        remoteUrl: String,
        sourcePageUrl: String
    ): String = JSONArray().put(
        JSONObject().apply {
            put("id", id)
            put("bookmarkId", bookmarkId)
            put("remoteUrl", remoteUrl)
            put("canonicalResourceKey", remoteUrl)
            put("mediaType", MediaCategory.IMAGES.name)
            put("role", MediaAssetRole.CONTENT_IMAGE.name)
            put("thumbnailUrl", JSONObject.NULL)
            put("mimeType", "image/jpeg")
            put("sizeBytes", 1024L)
            put("verificationState", MediaVerificationState.VERIFIED_RESOURCE.name)
            put(
                "evidence",
                JSONArray().put(
                    JSONObject().apply {
                        put("source", MediaDiscoveryEvidenceSource.DIRECT_RESPONSE.name)
                        put("detail", "legacy response")
                        put("observedMimeType", "image/jpeg")
                        put("httpStatusCode", 200)
                        put("contentLengthBytes", 1024L)
                    }
                )
            )
            put("sourcePageUrl", sourcePageUrl)
            put("sourcePageUrls", JSONArray().put(sourcePageUrl))
            put("discoveredAtMillis", 50L)
            put("lastVerifiedAtMillis", 50L)
            put("expiresAtMillis", JSONObject.NULL)
            put("authenticationRequired", false)
            put("userApproved", false)
        }
    ).toString()

    private fun candidate(
        remoteUrl: String,
        sourcePageUrl: String = "https://example.com/article",
        sourcePageUrls: List<String> = listOf(sourcePageUrl),
        role: MediaAssetRole = MediaAssetRole.CONTENT_IMAGE,
        mediaType: MediaCategory = MediaCategory.IMAGES,
        verificationState: MediaVerificationState = MediaVerificationState.NEEDS_VERIFICATION,
        evidence: List<MediaDiscoveryEvidence> = listOf(
            MediaDiscoveryEvidence(
                source = MediaDiscoveryEvidenceSource.DOM,
                detail = "img.content"
            )
        )
    ) = MediaDiscoveryCandidate(
        itemId = "candidate",
        resourceUrl = remoteUrl,
        sourcePageUrl = sourcePageUrl,
        mediaType = mediaType,
        thumbnailUrl = null,
        role = role,
        verificationState = verificationState,
        evidence = evidence,
        sourcePageUrls = sourcePageUrls
    )

    private class FailingWriteDao(
        private val delegate: MediaAssetDao
    ) : MediaAssetDao {
        override suspend fun getForBookmark(bookmarkId: String) =
            delegate.getForBookmark(bookmarkId)

        override suspend fun getAll() = delegate.getAll()

        override fun observeAll(): Flow<List<MediaAssetEntity>> = delegate.observeAll()

        override fun observeForBookmark(bookmarkId: String): Flow<List<MediaAssetEntity>> =
            delegate.observeForBookmark(bookmarkId)

        override suspend fun upsertAll(assets: List<MediaAssetEntity>) {
            throw IOException("Simulated Room write failure")
        }

        override suspend fun removeForBookmark(bookmarkId: String) {
            delegate.removeForBookmark(bookmarkId)
        }
    }
}
