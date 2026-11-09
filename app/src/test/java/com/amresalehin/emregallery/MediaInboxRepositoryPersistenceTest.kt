package com.amresalehin.emregallery

import android.content.Context
import java.io.File
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.MediaInboxStack
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
    fun stackMutationsAreCommittedAndReloadable() {
        val repository = MediaInboxRepository(context)
        val firstItem = mediaItem("first", "/tmp/first.jpg")
        val initial = MediaInboxStack(
            id = "stack-1",
            title = "Original",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/page",
            items = listOf(firstItem)
        )

        repository.addStack(initial)
        repository.updateStack(initial.copy(title = "Edited"))
        repository.addItemToStack(
            stackId = initial.id,
            item = mediaItem("second", "/tmp/second.jpg"),
            stackTitle = "Ignored for existing stack",
            sourceDomain = initial.sourceDomain,
            sourceUrl = initial.sourceUrl
        )

        val reloaded = MediaInboxRepository(context).stacks.value.single()
        assertEquals("Edited", reloaded.title)
        assertEquals(setOf("first", "second"), reloaded.items.map { it.id }.toSet())
    }

    @Test
    fun failedCommitsDoNotPublishChangesOrDeleteReferencedFiles() {
        val localFile = createLocalFile("media-inbox-")
        val originalItem = mediaItem("item-1", localFile.absolutePath, "https://cdn.example.com/photo.jpg")
        val original = MediaInboxStack(
            id = "stack-1",
            title = "Original",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/page",
            items = listOf(originalItem)
        )
        MediaInboxRepository(context).addStack(original)

        val repository = MediaInboxRepository(context)
        repository.snapshotCommitOverride = { false }

        val otherStack = original.copy(id = "stack-2", sourceUrl = "https://example.com/other")
        assertFalse(runCatching { repository.addStack(otherStack) }.isSuccess)
        assertFalse(runCatching { repository.updateStack(original.copy(title = "Not saved")) }.isSuccess)
        assertFalse(
            runCatching {
                repository.addItemToStack(
                    stackId = original.id,
                    item = mediaItem("item-1", "/tmp/replacement.jpg", originalItem.sourceUrl),
                    stackTitle = original.title,
                    sourceDomain = original.sourceDomain,
                    sourceUrl = original.sourceUrl
                )
            }.isSuccess
        )
        assertFalse(runCatching { repository.deleteStack(original.id) }.isSuccess)
        assertFalse(runCatching { repository.clearAll() }.isSuccess)

        assertEquals(listOf(original), repository.stacks.value)
        assertEquals(listOf(original), MediaInboxRepository(context).stacks.value)
        assertTrue("The previous local copy must survive a failed replacement/deletion", localFile.exists())
    }

    @Test
    fun clearAllCommitsRemovalAndThenDeletesAppManagedFiles() {
        val localFile = createLocalFile("media-inbox-clear-")
        val stack = MediaInboxStack(
            id = "stack-clear",
            title = "Clear me",
            sourceDomain = "example.com",
            sourceUrl = "https://example.com/page",
            items = listOf(mediaItem("item-clear", localFile.absolutePath))
        )
        val repository = MediaInboxRepository(context)
        repository.addStack(stack)

        repository.clearAll()

        assertTrue(repository.stacks.value.isEmpty())
        assertTrue(MediaInboxRepository(context).stacks.value.isEmpty())
        assertFalse("The local file is cleaned only after the empty snapshot commits", localFile.exists())
    }

    private fun createLocalFile(prefix: String): File =
        File.createTempFile(prefix, ".jpg", context.cacheDir).apply {
            writeText("test image bytes")
        }

    private fun mediaItem(
        id: String,
        localUri: String,
        sourceUrl: String = ""
    ) = MediaInboxItem(
        id = id,
        title = id,
        localUri = localUri,
        sourceUrl = sourceUrl,
        isVideo = false
    )

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
