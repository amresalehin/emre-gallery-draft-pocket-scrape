package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.MediaAssetRepository
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryCandidate
import com.amresalehin.emregallery.data.MediaDiscoveryEvidence
import com.amresalehin.emregallery.data.MediaDiscoveryEvidenceSource
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.MediaVerificationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaAssetRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun discoveredAssetPersistsWithBookmarkAndExactRemoteUrl() {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved article",
            url = "https://example.com/article"
        )
        val remoteUrl = "https://cdn.example.com/photo.jpg?signature=keep-this-query"
        val repository = MediaAssetRepository(context)

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

        assertNotNull(
            context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(MediaAssetRepository.KEY_ASSETS_JSON, null)
        )
        assertEquals(bookmark.id, saved.bookmarkId)
        assertEquals(remoteUrl, saved.remoteUrl)
        assertEquals(remoteUrl, saved.canonicalResourceKey)
        assertEquals("https://example.com/article", saved.sourcePageUrl)
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals(4096L, saved.sizeBytes)
        assertEquals(100L, saved.lastVerifiedAtMillis)

        val reloaded = MediaAssetRepository(context).assetsForBookmark(bookmark.id).single()
        assertEquals(saved, reloaded)
    }

    @Test
    fun repeatedDiscoveryMergesEvidenceAndSourcePagesWithoutDowngradingVerification() {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved page",
            url = "https://example.com/article"
        )
        val repository = MediaAssetRepository(context)
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
    fun freshAccessRestrictionReplacesPreviouslyVerifiedState() {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved page",
            url = "https://example.com/article"
        )
        val repository = MediaAssetRepository(context)
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
    fun failedDurableCommitDoesNotPublishCandidateOrOverwritePreviousSnapshot() {
        val bookmark = MediaInboxRepository(context).addBookmark(
            title = "Saved page",
            url = "https://example.com/article"
        )
        MediaAssetRepository(context).upsertCandidates(
            bookmark.id,
            listOf(candidate(remoteUrl = "https://cdn.example.com/first.jpg")),
            nowMillis = 100L
        )
        val repository = MediaAssetRepository(context)
        val before = repository.assets.value
        val rawBefore = context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(MediaAssetRepository.KEY_ASSETS_JSON, null)
        repository.snapshotCommitOverride = { false }

        val result = runCatching {
            repository.upsertCandidates(
                bookmark.id,
                listOf(candidate(remoteUrl = "https://cdn.example.com/second.jpg")),
                nowMillis = 200L
            )
        }

        assertTrue(result.isFailure)
        assertEquals(before, repository.assets.value)
        assertEquals(
            rawBefore,
            context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(MediaAssetRepository.KEY_ASSETS_JSON, null)
        )
        assertEquals(before, MediaAssetRepository(context).assets.value)
    }

    @Test
    fun corruptSnapshotFailsClosedWithoutOverwritingOriginalData() {
        val rawCorruptData = "{"
        context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(MediaAssetRepository.KEY_ASSETS_JSON, rawCorruptData).commit()
        val repository = MediaAssetRepository(context)

        val result = runCatching {
            repository.upsertCandidates(
                "bookmark-existing",
                listOf(candidate(remoteUrl = "https://cdn.example.com/image.jpg")),
                nowMillis = 100L
            )
        }

        assertTrue(result.isFailure)
        assertEquals(
            rawCorruptData,
            context.getSharedPreferences(MediaAssetRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(MediaAssetRepository.KEY_ASSETS_JSON, null)
        )
    }

    @Test
    fun unsafeUrlsAreNotPersistedButSafeRejectedEvidenceIsRetained() {
        val repository = MediaAssetRepository(context)
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
    fun galleryReadModelExcludesPreviewAndUnverifiedCandidates() {
        val repository = MediaAssetRepository(context)
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
        val repository = MediaInboxRepository(context)
        val originalUrl = "HTTPS://EXAMPLE.COM:443/article?item=1"
        val bookmark = repository.addBookmark("Saved page", originalUrl)

        assertEquals(
            bookmark.id,
            repository.findBookmarkBySourceUrl("https://example.com/article?item=1")?.id
        )
        assertNull(repository.findBookmarkBySourceUrl("https://example.com/article?item=2"))
        assertNull(repository.findBookmarkBySourceUrl("https://missing.example/article"))
        assertEquals(originalUrl, repository.stacks.value.single().sourceUrl)
    }

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
}
