package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.MediaInboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaInboxRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savingEquivalentUrlReturnsExistingBookmarkAndPreservesUserData() {
        val repository = MediaInboxRepository(context)

        val original = repository.addBookmark(
            title = "Original title",
            url = "HTTPS://EXAMPLE.COM:443/article",
            domain = "example.com",
            description = "User-authored note"
        )
        val duplicate = repository.addBookmark(
            title = "Replacement title",
            url = "https://example.com/article",
            domain = "example.com",
            description = "Replacement note"
        )

        assertEquals(original.id, duplicate.id)
        assertEquals(original.sourceUrl, duplicate.sourceUrl)
        assertEquals(original.title, duplicate.title)
        assertEquals(original.description, duplicate.description)
        assertEquals(1, repository.stacks.value.size)

        val reloaded = MediaInboxRepository(context)
        assertEquals(1, reloaded.stacks.value.size)
        assertEquals(original.id, reloaded.stacks.value.single().id)
        assertEquals(original.sourceUrl, reloaded.stacks.value.single().sourceUrl)
    }

    @Test
    fun differentBookmarkUrlsReceiveDifferentIds() {
        val repository = MediaInboxRepository(context)

        val first = repository.addBookmark(
            title = "First",
            url = "https://example.com/first"
        )
        val second = repository.addBookmark(
            title = "Second",
            url = "https://example.com/second"
        )

        assertNotEquals(first.id, second.id)
        assertEquals(2, repository.stacks.value.size)
    }
}
