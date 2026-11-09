package com.amresalehin.emregallery.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** A resource reference reported by JSON-LD, with its semantic property preserved. */
enum class StructuredResourceRole {
    IMAGE,
    THUMBNAIL,
    CONTENT,
    EMBED
}

data class StructuredResourceReference(
    val url: String,
    val role: StructuredResourceRole,
    val sourceProperty: String
)

/** A single top-level JSON-LD entity. This is structured page data, not a media asset. */
data class StructuredPageEntity(
    val sourceScriptIndex: Int,
    val sourcePath: String,
    val schemaTypes: List<String>,
    val schemaId: String? = null,
    val name: String? = null,
    val headline: String? = null,
    val description: String? = null,
    val url: String? = null,
    val mainEntityUrl: String? = null,
    val authorNames: List<String> = emptyList(),
    val publisherName: String? = null,
    val datePublished: String? = null,
    val dateModified: String? = null,
    val resourceReferences: List<StructuredResourceReference> = emptyList()
)

data class JsonLdParseResult(
    val sourcePageUrl: String,
    val entities: List<StructuredPageEntity>,
    val malformedScriptCount: Int = 0,
    val ignoredOversizedScriptCount: Int = 0,
    val ignoredScriptCount: Int = 0,
    val truncatedEntityCount: Int = 0
)

/**
 * Extracts JSON-LD entities embedded in static HTML without interpreting their resources as
 * discovered media assets. image, thumbnailUrl, contentUrl, and embedUrl are returned as
 * role-labelled references; downstream policy must decide whether any are actual media resources.
 *
 * The parser is deliberately bounded and failure-isolated: malformed or oversized scripts are
 * skipped without discarding valid JSON-LD blocks elsewhere in the same document.
 */
object JsonLdParser {
    const val MAX_SCRIPT_COUNT = 64
    const val MAX_SCRIPT_CHARACTERS = 512 * 1024
    const val MAX_ENTITY_COUNT = 100
    private const val MAX_GRAPH_DEPTH = 32

    fun parse(html: String, pageUrl: String): JsonLdParseResult {
        val sourceUrl = pageUrl.trim()
        val scripts = Jsoup.parse(html, sourceUrl)
            .select("script[type=application/ld+json]")

        val entities = mutableListOf<StructuredPageEntity>()
        var malformedCount = 0
        var oversizedCount = 0
        val truncatedCount = intArrayOf(0)

        scripts.take(MAX_SCRIPT_COUNT).forEachIndexed { scriptIndex, script ->
            val json = script.data().ifBlank { script.html() }
            if (json.length > MAX_SCRIPT_CHARACTERS) {
                oversizedCount += 1
                return@forEachIndexed
            }

            val root = try {
                JSONTokener(json).nextValue()
            } catch (_: JSONException) {
                malformedCount += 1
                return@forEachIndexed
            } catch (_: RuntimeException) {
                malformedCount += 1
                return@forEachIndexed
            }

            collectEntities(
                value = root,
                scriptIndex = scriptIndex,
                path = "\$",
                sourceUrl = sourceUrl,
                output = entities,
                truncatedCount = truncatedCount,
                depth = 0
            )
        }

        return JsonLdParseResult(
            sourcePageUrl = sourceUrl,
            entities = entities,
            malformedScriptCount = malformedCount,
            ignoredOversizedScriptCount = oversizedCount,
            ignoredScriptCount = (scripts.size - MAX_SCRIPT_COUNT).coerceAtLeast(0),
            truncatedEntityCount = truncatedCount[0]
        )
    }

    private fun collectEntities(
        value: Any?,
        scriptIndex: Int,
        path: String,
        sourceUrl: String,
        output: MutableList<StructuredPageEntity>,
        truncatedCount: IntArray,
        depth: Int
    ) {
        if (depth > MAX_GRAPH_DEPTH) {
            truncatedCount[0] += 1
            return
        }
        if (output.size >= MAX_ENTITY_COUNT) {
            truncatedCount[0] += 1
            return
        }
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    if (output.size >= MAX_ENTITY_COUNT) {
                        truncatedCount[0] += value.length() - index
                        break
                    }
                    collectEntities(
                        value.opt(index),
                        scriptIndex,
                        "$path[$index]",
                        sourceUrl,
                        output,
                        truncatedCount,
                        depth + 1
                    )
                }
            }
            is JSONObject -> {
                if (hasSchemaType(value)) {
                    parseEntity(value, scriptIndex, path, sourceUrl)?.let(output::add)
                }

                val graph = value.opt("@graph")
                if (graph != null) {
                    collectEntities(
                        graph,
                        scriptIndex,
                        "$path.@graph",
                        sourceUrl,
                        output,
                        truncatedCount,
                        depth + 1
                    )
                }
            }
        }
    }

    private fun hasSchemaType(node: JSONObject): Boolean = schemaTypes(node).isNotEmpty()

    private fun parseEntity(
        node: JSONObject,
        scriptIndex: Int,
        path: String,
        sourceUrl: String
    ): StructuredPageEntity? {
        val types = schemaTypes(node)
        if (types.isEmpty()) return null

        val references = buildList {
            addReferences(node.opt("image"), "image", StructuredResourceRole.IMAGE, sourceUrl)
            addReferences(node.opt("thumbnailUrl"), "thumbnailUrl", StructuredResourceRole.THUMBNAIL, sourceUrl)
            addReferences(node.opt("thumbnail"), "thumbnail", StructuredResourceRole.THUMBNAIL, sourceUrl)
            addReferences(node.opt("contentUrl"), "contentUrl", StructuredResourceRole.CONTENT, sourceUrl)
            addReferences(node.opt("embedUrl"), "embedUrl", StructuredResourceRole.EMBED, sourceUrl)
        }.distinctBy { Triple(it.url, it.role, it.sourceProperty) }

        return StructuredPageEntity(
            sourceScriptIndex = scriptIndex,
            sourcePath = path,
            schemaTypes = types,
            schemaId = stringValue(node.opt("@id")),
            name = stringValue(node.opt("name")),
            headline = stringValue(node.opt("headline")),
            description = stringValue(node.opt("description")),
            url = urlValue(node.opt("url"), sourceUrl),
            mainEntityUrl = urlValue(node.opt("mainEntityOfPage"), sourceUrl),
            authorNames = namesFrom(node.opt("author")),
            publisherName = namesFrom(node.opt("publisher")).firstOrNull(),
            datePublished = stringValue(node.opt("datePublished")),
            dateModified = stringValue(node.opt("dateModified")),
            resourceReferences = references
        )
    }

    private fun schemaTypes(node: JSONObject): List<String> {
        val raw = node.opt("@type") ?: return emptyList()
        val values = when (raw) {
            is String -> listOf(raw)
            is JSONArray -> (0 until raw.length()).mapNotNull { (raw.opt(it) as? String).cleanOrNull() }
            else -> emptyList()
        }
        return values.mapNotNull { it.cleanOrNull() }.distinct()
    }

    private fun stringValue(value: Any?): String? = when (value) {
        is String -> value.cleanOrNull()
        is JSONObject -> stringValue(value.opt("name"))
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { stringValue(value.opt(it)) }
            .firstOrNull()
        else -> null
    }

    private fun namesFrom(value: Any?): List<String> {
        val names = when (value) {
            is String -> listOfNotNull(value.cleanOrNull())
            is JSONObject -> listOfNotNull(stringValue(value.opt("name")))
            is JSONArray -> (0 until value.length()).flatMap { namesFrom(value.opt(it)) }
            else -> emptyList()
        }
        return names.distinct()
    }

    private fun urlValue(value: Any?, baseUrl: String): String? {
        val raw = when (value) {
            is String -> value
            is JSONObject -> stringValue(value.opt("url"))
                ?: stringValue(value.opt("@id"))
            is JSONArray -> (0 until value.length()).asSequence()
                .mapNotNull { urlValue(value.opt(it), baseUrl) }
                .firstOrNull()
            else -> null
        } ?: return null
        return resolveHttpUrl(raw, baseUrl)
    }

    private fun MutableList<StructuredResourceReference>.addReferences(
        value: Any?,
        property: String,
        role: StructuredResourceRole,
        baseUrl: String
    ) {
        when (value) {
            is String -> resolveHttpUrl(value, baseUrl)?.let {
                add(StructuredResourceReference(it, role, property))
            }
            is JSONObject -> {
                val sizeBefore = size
                val candidates = listOf("contentUrl", "url", "@id")
                for (key in candidates) {
                    val raw = stringValue(value.opt(key)) ?: continue
                    resolveHttpUrl(raw, baseUrl)?.let {
                        add(StructuredResourceReference(it, role, property))
                    }
                    if (size > sizeBefore) break
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    addReferences(value.opt(index), property, role, baseUrl)
                }
            }
        }
    }

    /** Resolve relative schema references but accept only HTTP(S) destinations with a host. */
    private fun resolveHttpUrl(rawUrl: String, baseUrl: String): String? {
        val value = rawUrl.trim()
        if (value.isEmpty()) return null
        return runCatching {
            val reference = URI(value)
            val resolved = if (reference.isAbsolute) reference else {
                val base = URI(baseUrl)
                if (!base.isAbsolute || base.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
                    return null
                }
                base.resolve(reference)
            }
            if (resolved.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
            if (resolved.host.isNullOrBlank()) return null
            resolved.toASCIIString()
        }.getOrNull()
    }

    private fun String?.cleanOrNull(): String? = this
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
