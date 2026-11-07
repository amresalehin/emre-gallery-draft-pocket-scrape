package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.MediaInboxRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaInboxRepositoryPersistenceTest {
    private lateinit var context: Context
    private val preferencesName = "media_inbox_prefs"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun bookmarkIsCommittedAndReadableByANewRepositoryInstance() {
        val repository = MediaInboxRepository(context)

        val saved = repository.addBookmark(
            title = "Example article",
            url = "https://example.com/article",
            description = "A saved note"
        )

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString("inbox_stacks_json", null)
        assertNotNull("Bookmark JSON must be committed before addBookmark returns", raw)

        val reloaded = MediaInboxRepository(context).stacks.value.single()
        assertEquals(saved.id, reloaded.id)
        assertEquals("https://example.com/article", reloaded.sourceUrl)
        assertEquals("A saved note", reloaded.description)
    }

    @Test
    fun equivalentResavePreservesOriginalUrlAndEditedFieldsAfterReload() {
        val repository = MediaInboxRepository(context)
        val original = repository.addBookmark(
            title = "Original title",
            url = "HTTPS://EXAMPLE.COM:443/article",
            description = "Original description"
        )
        repository.updateStack(
            original.copy(title = "User-edited title", description = "User-edited note")
        )

        val duplicate = repository.addBookmark(
            title = "Generated title",
            url = "https://example.com/article",
            description = "Generated description"
        )

        assertEquals(original.id, duplicate.id)
        assertEquals("HTTPS://EXAMPLE.COM:443/article", duplicate.sourceUrl)
        assertEquals("User-edited title", duplicate.title)
        assertEquals("User-edited note", duplicate.description)

        val reloaded = MediaInboxRepository(context).stacks.value.single()
        assertEquals(duplicate.id, reloaded.id)
        assertEquals("HTTPS://EXAMPLE.COM:443/article", reloaded.sourceUrl)
        assertEquals("User-edited title", reloaded.title)
        assertEquals("User-edited note", reloaded.description)
    }

    @Test
    fun unreadableStoredLibraryIsNotOverwrittenByBookmarkCreation() {
        val rawCorruptData = """[{"id":"""
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().putString("inbox_stacks_json", rawCorruptData).commit()

        val repository = MediaInboxRepository(context)
        assertTrue(repository.stacks.value.isEmpty())

        val saveResult = runCatching {
            repository.addBookmark("New bookmark", "https://example.com/new")
        }
        assertFalse("Saving must fail closed while stored library data is unreadable", saveResult.isSuccess)
        assertEquals(
            rawCorruptData,
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .getString("inbox_stacks_json", null)
        )
    }
}
