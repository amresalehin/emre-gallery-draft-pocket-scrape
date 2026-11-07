package com.amresalehin.emregallery.data

import java.net.URI
import java.util.Locale

/**
 * Builds a conservative identity key for HTTP(S) bookmark URLs.
 *
 * The key normalizes only URL components that are safe to treat as equivalent:
 * scheme/host casing, default ports, empty root paths, and dot segments.
 * Query parameters and fragments are deliberately preserved because they can
 * identify distinct content or client-side routes.
 *
 * The original URL remains the value stored on the bookmark.
 */
object BookmarkUrlIdentity {

    fun canonicalKey(rawUrl: String): String? {
        val value = rawUrl.trim()
        if (value.isEmpty()) return null

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = uri.port
        if (port !in -1..65535) return null

        val normalizedPath = uri.normalize().rawPath.orEmpty().ifEmpty { "/" }
        val normalizedPort = port.takeUnless {
            it < 0 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443)
        }

        return buildString {
            append(scheme)
            append("://")
            uri.rawUserInfo?.let {
                append(it)
                append('@')
            }
            append(host)
            normalizedPort?.let {
                append(':')
                append(it)
            }
            append(normalizedPath)
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
            uri.rawFragment?.let {
                append('#')
                append(it)
            }
        }
    }

    /**
     * Compares two saved source URLs without merging blank or unrelated links.
     * For legacy non-HTTP values, only an exact trimmed match is considered
     * equivalent; new canonicalization is limited to HTTP(S) URLs.
     */
    fun areSameSource(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false

        val firstKey = canonicalKey(first)
        val secondKey = canonicalKey(second)
        return if (firstKey != null && secondKey != null) {
            firstKey == secondKey
        } else {
            first.trim() == second.trim()
        }
    }
}
