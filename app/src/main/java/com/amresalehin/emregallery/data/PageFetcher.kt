package com.amresalehin.emregallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A fetched HTML document before metadata parsing or media discovery. */
data class FetchedPage(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val html: String,
    val bodyBytes: Long
)

enum class PageFetchFailureReason {
    INVALID_URL,
    BLOCKED_ADDRESS,
    TOO_MANY_REDIRECTS,
    REDIRECT_BLOCKED,
    REDIRECT_WITHOUT_LOCATION,
    HTTP_ERROR,
    NON_HTML_CONTENT,
    BODY_TOO_LARGE,
    EMPTY_BODY,
    TIMEOUT,
    NETWORK_ERROR
}

data class PageFetchFailure(
    val reason: PageFetchFailureReason,
    val message: String,
    val httpStatus: Int? = null
)

sealed interface PageFetchResult {
    data class Success(val page: FetchedPage) : PageFetchResult
    data class Failure(val failure: PageFetchFailure) : PageFetchResult
}

/**
 * Syntactic URL checks for the page-fetch boundary. DNS answers are checked separately by
 * [PublicInternetDns], at connection time, so redirects cannot silently enter a private network.
 */
object PageFetchUrlPolicy {
    fun parseHttpUrl(rawUrl: String): HttpUrl? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null

        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url
    }

    fun parseAllowedUrl(rawUrl: String): HttpUrl? {
        val url = parseHttpUrl(rawUrl) ?: return null
        if (url.host == "localhost" || url.host.endsWith(".localhost") || url.host.endsWith(".local")) {
            return null
        }

        // HttpUrl canonicalizes literal IP hosts. Reject non-public literals before connecting.
        parseIpLiteral(url.host)?.let { address ->
            if (!PublicInternetAddressPolicy.isPublic(address)) return null
        }
        return url
    }

    private fun parseIpLiteral(host: String): InetAddress? {
        val looksLikeIpv4 = host.matches(Regex("[0-9.]+"))
        val looksLikeIpv6 = host.contains(':')
        if (!looksLikeIpv4 && !looksLikeIpv6) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }
}

/**
 * Allows only globally routable address space. The check deliberately fails closed when DNS
 * returns a mixture of public and non-public addresses to prevent fallback onto a private IP.
 */
internal object PublicInternetAddressPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        val bytes = address.address
        return when {
            address is Inet4Address -> isPublicIpv4(bytes)
            address is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val blocked = listOf(
            intArrayOf(0, 8),
            intArrayOf(10, 8),
            intArrayOf(100, 64, 10), // Carrier-grade NAT: 100.64.0.0/10
            intArrayOf(127, 8),
            intArrayOf(169, 254, 16), // Link-local
            intArrayOf(172, 16, 12),
            intArrayOf(192, 0, 0, 24),
            intArrayOf(192, 0, 2, 24), // Documentation
            intArrayOf(192, 88, 99, 24),
            intArrayOf(192, 168, 16),
            intArrayOf(198, 18, 15), // Benchmarking
            intArrayOf(198, 51, 100, 24), // Documentation
            intArrayOf(203, 0, 113, 24), // Documentation
            intArrayOf(224, 4), // Multicast
            intArrayOf(240, 4) // Reserved / broadcast
        )
        return blocked.none { prefix -> matchesIpv4Prefix(bytes, prefix) }
    }

    /** Prefix arrays contain the network octets followed by the prefix length. */
    private fun matchesIpv4Prefix(bytes: ByteArray, networkAndPrefix: IntArray): Boolean {
        val prefixLength = networkAndPrefix.last()
        val fullOctets = prefixLength / 8
        val remainingBits = prefixLength % 8

        for (index in 0 until fullOctets) {
            if ((bytes[index].toInt() and 0xff) != networkAndPrefix[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        val actual = bytes[fullOctets].toInt() and 0xff
        return (actual and mask) == (networkAndPrefix[fullOctets] and mask)
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false

        // IPv4-mapped IPv6 addresses must pass the IPv4 policy as well.
        val isIpv4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        if (isIpv4Mapped) return isPublicIpv4(bytes.copyOfRange(12, 16))
        // Deprecated IPv4-compatible forms are not valid public unicast destinations.
        if ((0 until 12).all { bytes[it] == 0.toByte() }) return false

        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff

        // Unspecified/loopback, unique-local (fc00::/7), link-local (fe80::/10), multicast.
        if (bytes.all { it == 0.toByte() }) return false
        if (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return false
        if (first == 0x01 && bytes.slice(1..7).all { it == 0.toByte() }) return false // 100::/64 discard-only
        if ((first and 0xfe) == 0xfc) return false
        if (first == 0xfe && (second and 0xc0) == 0x80) return false
        if (first == 0xfe && (second and 0xc0) == 0xc0) return false // Deprecated site-local
        if (first == 0xff) return false

        // Documentation, deprecated transition, and local-use translation ranges.
        if (first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8
        ) return false // 2001:db8::/32
        if (first == 0x3f && second == 0xff && ((bytes[2].toInt() and 0xff) and 0xf0) == 0x00) return false // 3fff::/20 documentation
        if (first == 0x20 && second == 0x01 && (bytes[2].toInt() and 0xff) == 0x00) return false // 2001::/23
        if (first == 0x64 && second == 0xff && (bytes[2].toInt() and 0xff) == 0x9b) return false // NAT64 well-known prefix
        if (first == 0x20 && second == 0x02) return false // 6to4

        return true
    }
}

/** DNS resolver which prevents any HTTP request from connecting to a non-public address. */
internal class PublicInternetDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !PublicInternetAddressPolicy.isPublic(it) }) {
            throw UnknownHostException("Blocked non-public destination")
        }
        return addresses
    }
}

/**
 * Fetches static HTML for Pocket page intelligence. This client is intentionally isolated from
 * the browser/download session: it sends no saved cookies or authorization headers, never follows
 * redirects automatically, and revalidates each redirect target before making another request.
 */
class PageFetcher internal constructor(
    private val client: OkHttpClient,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    private val urlValidator: (HttpUrl) -> Boolean = { PageFetchUrlPolicy.parseAllowedUrl(it.toString()) != null }
) {
    constructor() : this(buildSafeClient())

    init {
        require(maxResponseBytes > 0L) { "maxResponseBytes must be positive" }
        require(maxRedirects >= 0) { "maxRedirects cannot be negative" }
    }

    suspend fun fetch(url: String): PageFetchResult = withContext(Dispatchers.IO) {
        fetchBlocking(url)
    }

    private fun fetchBlocking(rawUrl: String): PageFetchResult {
        val initialUrl = PageFetchUrlPolicy.parseHttpUrl(rawUrl)
            ?: return failure(PageFetchFailureReason.INVALID_URL, "Only absolute HTTP(S) page URLs can be fetched")
        if (!urlValidator(initialUrl)) {
            return failure(PageFetchFailureReason.BLOCKED_ADDRESS, "The destination is not allowed for page fetching")
        }

        val visited = mutableSetOf<String>()
        var currentUrl = initialUrl
        var redirectCount = 0
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_TOTAL_FETCH_SECONDS)

        while (true) {
            if (System.nanoTime() >= deadlineNanos) {
                return failure(PageFetchFailureReason.TIMEOUT, "The page fetch exceeded the total time limit")
            }
            if (!visited.add(currentUrl.toString())) {
                return failure(PageFetchFailureReason.TOO_MANY_REDIRECTS, "A redirect loop was detected")
            }

            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9")
                .get()
                .build()

            val response = try {
                client.newCall(request).apply {
                    timeout().deadlineNanoTime(deadlineNanos)
                }.execute()
            } catch (error: IOException) {
                if (System.nanoTime() >= deadlineNanos) {
                    return failure(PageFetchFailureReason.TIMEOUT, "The page fetch exceeded the total time limit")
                }
                val blocked = hasBlockedDnsCause(error)
                return failure(
                    if (blocked) PageFetchFailureReason.BLOCKED_ADDRESS else PageFetchFailureReason.NETWORK_ERROR,
                    if (blocked) "The destination resolved to a non-public address" else "The page could not be fetched"
                )
            } catch (_: Exception) {
                return failure(PageFetchFailureReason.NETWORK_ERROR, "The page could not be fetched")
            }

            response.use { safeResponse ->
                if (safeResponse.code in REDIRECT_CODES) {
                    if (redirectCount >= maxRedirects) {
                        return failure(PageFetchFailureReason.TOO_MANY_REDIRECTS, "The page exceeded the redirect limit", safeResponse.code)
                    }
                    val location = safeResponse.header("Location")
                        ?: return failure(PageFetchFailureReason.REDIRECT_WITHOUT_LOCATION, "The redirect did not include a destination", safeResponse.code)
                    val nextUrl = currentUrl.resolve(location)
                        ?: return failure(PageFetchFailureReason.REDIRECT_BLOCKED, "The redirect destination is invalid", safeResponse.code)
                    if (!urlValidator(nextUrl) || (currentUrl.scheme == "https" && nextUrl.scheme == "http")) {
                        return failure(PageFetchFailureReason.REDIRECT_BLOCKED, "The redirect destination is not allowed", safeResponse.code)
                    }
                    currentUrl = nextUrl
                    redirectCount += 1
                    return@use
                }

                if (!safeResponse.isSuccessful) {
                    return failure(PageFetchFailureReason.HTTP_ERROR, "The server returned HTTP " + safeResponse.code, safeResponse.code)
                }

                val body = safeResponse.body
                    ?: return failure(PageFetchFailureReason.EMPTY_BODY, "The server returned an empty response", safeResponse.code)
                val contentType = body.contentType()
                val mimeType = contentType?.let { it.type.lowercase(Locale.ROOT) + "/" + it.subtype.lowercase(Locale.ROOT) }
                if (mimeType !in HTML_MIME_TYPES) {
                    return failure(PageFetchFailureReason.NON_HTML_CONTENT, "The response is not an HTML document", safeResponse.code)
                }

                val declaredLength = body.contentLength()
                if (declaredLength > maxResponseBytes) {
                    return failure(PageFetchFailureReason.BODY_TOO_LARGE, "The HTML response exceeds the configured size limit", safeResponse.code)
                }

                val bytes = try {
                    readBoundedBody(body, maxResponseBytes)
                } catch (_: IOException) {
                    return failure(
                        if (System.nanoTime() >= deadlineNanos) PageFetchFailureReason.TIMEOUT else PageFetchFailureReason.NETWORK_ERROR,
                        if (System.nanoTime() >= deadlineNanos) "The page fetch exceeded the total time limit" else "The HTML response could not be read",
                        safeResponse.code
                    )
                } ?: return failure(PageFetchFailureReason.BODY_TOO_LARGE, "The HTML response exceeds the configured size limit", safeResponse.code)
                if (bytes.isEmpty()) {
                    return failure(PageFetchFailureReason.EMPTY_BODY, "The server returned an empty HTML document", safeResponse.code)
                }

                val charset = runCatching { contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8 }
                    .getOrDefault(Charsets.UTF_8)
                val html = runCatching { String(bytes, charset) }.getOrElse {
                    return failure(PageFetchFailureReason.NETWORK_ERROR, "The HTML response could not be decoded", safeResponse.code)
                }

                return PageFetchResult.Success(
                    FetchedPage(
                        requestedUrl = initialUrl.toString(),
                        finalUrl = safeResponse.request.url.toString(),
                        statusCode = safeResponse.code,
                        contentType = mimeType!!,
                        html = html,
                        bodyBytes = bytes.size.toLong()
                    )
                )
            }
        }
    }

    private fun readBoundedBody(body: ResponseBody, limit: Long): ByteArray? {
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remaining = limit - total
            val readSize = if (remaining > 0L) minOf(8192L, remaining) else 1L
            val read = source.read(buffer, readSize)
            if (read == -1L) break
            total += read
            if (total > limit) return null
        }
        return buffer.readByteArray()
    }

    private fun hasBlockedDnsCause(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException && current.message?.contains("non-public", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun failure(
        reason: PageFetchFailureReason,
        message: String,
        status: Int? = null
    ) = PageFetchResult.Failure(PageFetchFailure(reason, message, status))

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_MAX_REDIRECTS = 5
        private const val MAX_TOTAL_FETCH_SECONDS = 30L
        private const val USER_AGENT = "EmrePocket/1.0 (page metadata)"
        private val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun buildSafeClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(PublicInternetDns())
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
