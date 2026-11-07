package com.amresalehin.emregallery.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MediaInboxItem(
    val id: String,
    val title: String,
    val localUri: String,
    val sourceUrl: String,
    val isVideo: Boolean,
    val isAudio: Boolean = false,
    val isStream: Boolean = false,
    val format: String = "",
    val sizeBytes: Long = 0L,
    val dateAddedMillis: Long = System.currentTimeMillis()
)

data class MediaInboxStack(
    val id: String,
    val title: String,
    val sourceDomain: String,
    val sourceUrl: String,
    val items: List<MediaInboxItem>,
    val timestampMillis: Long = System.currentTimeMillis(),
    val description: String = ""
)

class MediaInboxRepository(private val context: Context) {
    @Volatile
    private var loadFailed: Boolean = false

    private val prefs: SharedPreferences =
        context.getSharedPreferences("media_inbox_prefs", Context.MODE_PRIVATE)

    private val _stacks = MutableStateFlow<List<MediaInboxStack>>(emptyList())
    val stacks: StateFlow<List<MediaInboxStack>> = _stacks.asStateFlow()

    // Test seam for exercising failed durable writes without depending on filesystem failures.
    @Volatile
    internal var snapshotCommitOverride: ((String?) -> Boolean)? = null

    init {
        loadStacks()
    }

    private fun loadStacks() {
        val rawJson = prefs.getString(KEY_STACKS_JSON, null)
        if (rawJson.isNullOrBlank()) {
            _stacks.value = emptyList()
            return
        }

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<MediaInboxStack>()
            for (i in 0 until jsonArray.length()) {
                val stackObj = jsonArray.getJSONObject(i)
                val id = stackObj.getString("id")
                val title = stackObj.optString("title", "Untitled Stack")
                val sourceDomain = stackObj.optString("sourceDomain", "")
                val sourceUrl = stackObj.optString("sourceUrl", "")
                val timestamp = stackObj.optLong("timestampMillis", System.currentTimeMillis())
                val description = stackObj.optString("description", "")

                val itemsArray = stackObj.optJSONArray("items") ?: JSONArray()
                val itemsList = mutableListOf<MediaInboxItem>()
                for (j in 0 until itemsArray.length()) {
                    val itmObj = itemsArray.getJSONObject(j)
                    itemsList.add(
                        MediaInboxItem(
                            id = itmObj.getString("id"),
                            title = itmObj.optString("title", "Media"),
                            localUri = itmObj.getString("localUri"),
                            sourceUrl = itmObj.optString("sourceUrl", ""),
                            isVideo = itmObj.optBoolean("isVideo", false),
                            isAudio = itmObj.optBoolean("isAudio", false),
                            isStream = itmObj.optBoolean("isStream", false),
                            format = itmObj.optString("format", ""),
                            sizeBytes = itmObj.optLong("sizeBytes", 0L),
                            dateAddedMillis = itmObj.optLong("dateAddedMillis", timestamp)
                        )
                    )
                }

                list.add(
                    MediaInboxStack(
                        id = id,
                        title = title,
                        sourceDomain = sourceDomain,
                        sourceUrl = sourceUrl,
                        items = itemsList,
                        timestampMillis = timestamp,
                        description = description
                    )
                )
            }
            val consolidated = consolidateStacks(list)
            if (consolidated.size != list.size && !persist(consolidated)) {
                // Keep the exact loaded snapshot visible if consolidation could not be committed.
                _stacks.value = list
            } else {
                _stacks.value = consolidated
            }
        } catch (e: Exception) {
            // Keep the library unavailable rather than silently treating unreadable persisted
            // data as an empty library that a later save could overwrite.
            loadFailed = true
            _stacks.value = emptyList()
        }
    }

    private fun consolidateStacks(rawList: List<MediaInboxStack>): List<MediaInboxStack> {
        if (rawList.size <= 1) return rawList
        val merged = mutableListOf<MediaInboxStack>()
        for (stack in rawList) {
            val existingIndex = merged.indexOfFirst { existing ->
                existing.id == stack.id ||
                    BookmarkUrlIdentity.areSameSource(existing.sourceUrl, stack.sourceUrl)
            }
            if (existingIndex >= 0) {
                val existing = merged[existingIndex]
                val existingKeys = existing.items.map { it.id to it.localUri }.toSet()
                val newItems = stack.items.filter { (it.id to it.localUri) !in existingKeys }
                merged[existingIndex] = existing.copy(
                    items = existing.items + newItems,
                    timestampMillis = maxOf(existing.timestampMillis, stack.timestampMillis),
                    sourceUrl = if (existing.sourceUrl.isNotBlank()) existing.sourceUrl else stack.sourceUrl,
                    sourceDomain = if (existing.sourceDomain.isNotBlank()) existing.sourceDomain else stack.sourceDomain
                )
            } else {
                merged.add(stack)
            }
        }
        return merged
    }

    /**
     * Commits a complete serialized snapshot, or removes the stored snapshot when null.
     * No caller should publish a new in-memory state until this operation succeeds.
     */
    private fun commitSnapshot(serializedJson: String?): Boolean {
        check(!loadFailed) {
            "Saved library could not be read; refusing to overwrite its persisted contents."
        }
        snapshotCommitOverride?.let { return it(serializedJson) }

        val editor = prefs.edit()
        if (serializedJson == null) {
            editor.remove(KEY_STACKS_JSON)
        } else {
            editor.putString(KEY_STACKS_JSON, serializedJson)
        }
        return editor.commit()
    }

    private fun persist(stacks: List<MediaInboxStack>): Boolean {
        val jsonArray = JSONArray()
        stacks.forEach { stack ->
            val stackObj = JSONObject().apply {
                put("id", stack.id)
                put("title", stack.title)
                put("sourceDomain", stack.sourceDomain)
                put("sourceUrl", stack.sourceUrl)
                put("timestampMillis", stack.timestampMillis)
                put("description", stack.description)

                val itemsArr = JSONArray()
                stack.items.forEach { itm ->
                    val itmObj = JSONObject().apply {
                        put("id", itm.id)
                        put("title", itm.title)
                        put("localUri", itm.localUri)
                        put("sourceUrl", itm.sourceUrl)
                        put("isVideo", itm.isVideo)
                        put("isAudio", itm.isAudio)
                        put("isStream", itm.isStream)
                        put("format", itm.format)
                        put("sizeBytes", itm.sizeBytes)
                        put("dateAddedMillis", itm.dateAddedMillis)
                    }
                    itemsArr.put(itmObj)
                }
                put("items", itemsArr)
            }
            jsonArray.put(stackObj)
        }
        return commitSnapshot(jsonArray.toString())
    }

    private fun commitAndPublish(updated: List<MediaInboxStack>) {
        check(persist(updated)) {
            "Saved library could not be durably updated. Please retry."
        }
        _stacks.value = updated
    }

    @Synchronized
    fun addStack(stack: MediaInboxStack) {
        val updated = listOf(stack) + _stacks.value.filter { it.id != stack.id }
        commitAndPublish(consolidateStacks(updated))
    }

    @Synchronized
    fun addBookmark(
        title: String,
        url: String,
        domain: String = "",
        description: String = ""
    ): MediaInboxStack {
        val cleanUrl = url.trim()

        // Re-saving an equivalent source returns the existing durable bookmark.
        // Keep its original URL and user-edited metadata instead of creating a duplicate.
        if (cleanUrl.isNotBlank()) {
            _stacks.value.firstOrNull {
                BookmarkUrlIdentity.areSameSource(it.sourceUrl, cleanUrl)
            }?.let { return it }
        }

        val cleanDomain = domain.ifBlank { MediaClassifier.cleanDomainFromUrl(cleanUrl) }
        val bookmarkStack = MediaInboxStack(
            id = "bookmark_${UUID.randomUUID()}",
            title = title.ifBlank { cleanDomain.ifBlank { cleanUrl } },
            sourceDomain = cleanDomain,
            sourceUrl = cleanUrl,
            items = emptyList(),
            timestampMillis = System.currentTimeMillis(),
            description = description
        )
        val updated = consolidateStacks(listOf(bookmarkStack) + _stacks.value)
        check(persist(updated)) {
            "Bookmark could not be durably saved. Please retry."
        }
        _stacks.value = updated
        return bookmarkStack
    }

    @Synchronized
    fun updateStack(updated: MediaInboxStack) {
        val current = _stacks.value
        if (current.none { it.id == updated.id }) return
        commitAndPublish(current.map { if (it.id == updated.id) updated else it })
    }

    @Synchronized
    fun addItemToStack(
        stackId: String,
        item: MediaInboxItem,
        stackTitle: String,
        sourceDomain: String,
        sourceUrl: String
    ) {
        val current = _stacks.value.toMutableList()
        // 1. Try to find existing stack by stackId
        var existingIndex = current.indexOfFirst { it.id == stackId }
        // 2. If not found by stackId, match strictly by sourceUrl if non-blank
        if (existingIndex < 0 && sourceUrl.isNotBlank()) {
            existingIndex = current.indexOfFirst { it.sourceUrl == sourceUrl }
        }

        var replacedItems: List<MediaInboxItem> = emptyList()
        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            // Defer cleanup until the replacement snapshot has been committed successfully.
            replacedItems = existing.items.filter {
                (it.id == item.id || (item.sourceUrl.isNotBlank() && it.sourceUrl == item.sourceUrl)) &&
                    it.localUri != item.localUri
            }

            val updatedItems = existing.items.filter {
                it.id != item.id && (it.sourceUrl.isBlank() || it.sourceUrl != item.sourceUrl) && it.localUri != item.localUri
            } + item
            current[existingIndex] = existing.copy(
                items = updatedItems,
                timestampMillis = System.currentTimeMillis()
            )
        } else {
            val newStack = MediaInboxStack(
                id = stackId.ifBlank { "stack_${System.currentTimeMillis()}" },
                title = stackTitle.ifBlank { sourceDomain.ifBlank { "Discovered Media" } },
                sourceDomain = sourceDomain,
                sourceUrl = sourceUrl,
                items = listOf(item),
                timestampMillis = System.currentTimeMillis()
            )
            current.add(0, newStack)
        }
        val updated = consolidateStacks(current)
        commitAndPublish(updated)
        deleteLocalFiles(replacedItems, updated)
    }

    @Synchronized
    fun deleteStack(stackId: String) {
        val current = _stacks.value
        val stack = current.firstOrNull { it.id == stackId } ?: return
        val updated = current.filterNot { it.id == stackId }

        // Keep both the in-memory record and its local files if the durable write fails.
        commitAndPublish(updated)
        deleteLocalFiles(stack.items, updated)
    }

    @Synchronized
    fun clearAll() {
        val current = _stacks.value
        check(commitSnapshot(null)) {
            "Saved library could not be durably cleared. Please retry."
        }
        _stacks.value = emptyList()
        deleteLocalFiles(current.flatMap { it.items }, emptyList())
    }

    /**
     * Removes only app-managed file paths that are no longer referenced by the committed snapshot.
     * Content URIs and remote URLs are intentionally left untouched.
     */
    private fun deleteLocalFiles(
        items: List<MediaInboxItem>,
        remainingStacks: List<MediaInboxStack>
    ) {
        val retainedUris = remainingStacks.flatMap { it.items }.map { it.localUri }.toSet()
        items.asSequence()
            .map { it.localUri }
            .filterNot { it in retainedUris }
            .distinct()
            .forEach { uri ->
                val file = when {
                    uri.startsWith("file://") -> File(uri.removePrefix("file://"))
                    uri.startsWith("/") -> File(uri)
                    else -> null
                }
                runCatching { file?.takeIf { it.isFile }?.delete() }
            }
    }

    companion object {
        private const val KEY_STACKS_JSON = "inbox_stacks_json"

        @Volatile
        private var INSTANCE: MediaInboxRepository? = null

        fun getInstance(context: Context): MediaInboxRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaInboxRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
