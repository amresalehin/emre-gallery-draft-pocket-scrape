package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.GalleryDetector
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaDiscoveryCandidate
import com.amresalehin.emregallery.data.MediaGalleryKind
import com.amresalehin.emregallery.data.MediaVerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDetectorTest {

    @Test
    fun emptyAndUnverifiedCandidatesDoNotCreateGallery() {
        assertEquals(MediaGalleryKind.NONE, GalleryDetector.assess(emptyList()).kind)

        val assessment = GalleryDetector.assess(
            listOf(candidate("photo", "https://cdn.example.com/photo.jpg", state = MediaVerificationState.NEEDS_VERIFICATION))
        )

        assertEquals(MediaGalleryKind.NONE, assessment.kind)
        assertEquals(0, assessment.eligibleAssetCount)
        assertFalse(assessment.hasGallery)
    }

    @Test
    fun oneOpenGraphImageOrVideoPosterDoesNotCreateGallery() {
        val assessment = GalleryDetector.assess(
            listOf(
                candidate("og", "https://cdn.example.com/og.jpg", role = MediaAssetRole.ARTICLE_HERO),
                candidate("poster", "https://cdn.example.com/poster.jpg", role = MediaAssetRole.VIDEO_POSTER)
            )
        )

        assertEquals(MediaGalleryKind.NONE, assessment.kind)
        assertEquals(0, assessment.eligibleAssetCount)
        assertFalse(assessment.hasGallery)
    }

    @Test
    fun oneVerifiedContentImageIsASingleAssetNotAGallery() {
        val assessment = GalleryDetector.assess(
            listOf(candidate("photo", "https://cdn.example.com/photo.jpg"))
        )

        assertEquals(MediaGalleryKind.SINGLE_ASSET, assessment.kind)
        assertEquals(1, assessment.eligibleAssetCount)
        assertEquals(1, assessment.contentImageCount)
        assertFalse(assessment.hasGallery)
    }

    @Test
    fun twoDistinctVerifiedImagesCreateAnImageGallery() {
        val assessment = GalleryDetector.assess(
            listOf(
                candidate("one", "https://cdn.example.com/one.jpg"),
                candidate("two", "https://cdn.example.com/two.jpg")
            )
        )

        assertEquals(MediaGalleryKind.IMAGE_GALLERY, assessment.kind)
        assertEquals(2, assessment.eligibleAssetCount)
        assertEquals(2, assessment.contentImageCount)
        assertTrue(assessment.hasGallery)
    }

    @Test
    fun duplicateResourceUrlsDoNotInflateGalleryCount() {
        val assessment = GalleryDetector.assess(
            listOf(
                candidate("one", "https://cdn.example.com/photo.jpg"),
                candidate("duplicate", "https://cdn.example.com/photo.jpg")
            )
        )

        assertEquals(MediaGalleryKind.SINGLE_ASSET, assessment.kind)
        assertEquals(1, assessment.eligibleAssetCount)
        assertFalse(assessment.hasGallery)
    }

    @Test
    fun mixedVerifiedMediaIsACollectionRatherThanAnImageGallery() {
        val assessment = GalleryDetector.assess(
            listOf(
                candidate("image", "https://cdn.example.com/photo.jpg"),
                candidate(
                    "video",
                    "https://cdn.example.com/clip.mp4",
                    role = MediaAssetRole.CONTENT_VIDEO,
                    mediaType = MediaCategory.VIDEOS
                ),
                candidate(
                    "audio",
                    "https://cdn.example.com/track.mp3",
                    role = MediaAssetRole.CONTENT_AUDIO,
                    mediaType = MediaCategory.AUDIO
                )
            )
        )

        assertEquals(MediaGalleryKind.MEDIA_COLLECTION, assessment.kind)
        assertEquals(3, assessment.eligibleAssetCount)
        assertEquals(1, assessment.contentImageCount)
        assertEquals(1, assessment.contentVideoCount)
        assertEquals(1, assessment.contentAudioCount)
        assertTrue(assessment.hasGallery)
    }

    @Test
    fun oneVerifiedVideoOrPlayableEmbedRemainsASingleAsset() {
        val video = GalleryDetector.assess(
            listOf(
                candidate(
                    "video",
                    "https://cdn.example.com/clip.mp4",
                    role = MediaAssetRole.CONTENT_VIDEO,
                    mediaType = MediaCategory.VIDEOS
                )
            )
        )
        val embed = GalleryDetector.assess(
            listOf(
                candidate(
                    "embed",
                    "https://player.example.com/embed/1",
                    role = MediaAssetRole.EMBED,
                    mediaType = MediaCategory.STREAMS,
                    state = MediaVerificationState.PLAYABLE_EMBED
                )
            )
        )

        assertEquals(MediaGalleryKind.SINGLE_ASSET, video.kind)
        assertEquals(MediaGalleryKind.SINGLE_ASSET, embed.kind)
        assertFalse(video.hasGallery)
        assertFalse(embed.hasGallery)
    }

    private fun candidate(
        id: String,
        url: String,
        role: MediaAssetRole = MediaAssetRole.CONTENT_IMAGE,
        mediaType: MediaCategory = when (role) {
            MediaAssetRole.CONTENT_VIDEO -> MediaCategory.VIDEOS
            MediaAssetRole.CONTENT_AUDIO -> MediaCategory.AUDIO
            MediaAssetRole.EMBED -> MediaCategory.STREAMS
            else -> MediaCategory.IMAGES
        },
        state: MediaVerificationState = MediaVerificationState.VERIFIED_RESOURCE
    ) = MediaDiscoveryCandidate(
        itemId = id,
        resourceUrl = url,
        sourcePageUrl = "https://example.com/source",
        mediaType = mediaType,
        thumbnailUrl = null,
        role = role,
        verificationState = state,
        evidence = emptyList()
    )
}
