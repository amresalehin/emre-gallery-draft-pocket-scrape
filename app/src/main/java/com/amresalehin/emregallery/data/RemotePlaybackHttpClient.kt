package com.amresalehin.emregallery.data

import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Isolated transport for explicitly authorized remote playback.
 *
 * It never shares the browser/download cookie jar, proxy, authorization headers, or Referer.
 * Every request is checked before dispatch and every hostname is checked again by
 * [PublicInternetDns] when OkHttp resolves the connection destination.
 *
 * A small, bounded redirect policy supports common CDN URLs without delegating trust to OkHttp's
 * automatic redirect handling. Each destination is revalidated, HTTPS downgrade is rejected,
 * and browser credentials are stripped again on every hop.
 */
object RemotePlaybackHttpClient {
    const val USER_AGENT = "EmrePocket/1.0 (remote playback)"
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val REDIRECTABLE_METHODS = setOf("GET", "HEAD")

    val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildClient()
    }

    fun dataSourceFactory(): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).setUserAgent(USER_AGENT)

    internal fun resolveRedirectTarget(
        currentUrl: HttpUrl,
        location: String?,
        urlValidator: (HttpUrl) -> Boolean
    ): HttpUrl? {
        val nextUrl = location?.let { currentUrl.resolve(it) } ?: return null
        if (!urlValidator(nextUrl)) return null
        if (currentUrl.isHttps && !nextUrl.isHttps) return null
        return nextUrl
    }

    internal fun buildClient(
        dns: Dns = PublicInternetDns(),
        urlValidator: (HttpUrl) -> Boolean = {
            PageFetchUrlPolicy.parseAllowedUrl(it.toString()) != null
        }
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            var request = chain.request()
            val visitedUrls = mutableSetOf<String>()
            var redirectsFollowed = 0

            while (true) {
                if (!urlValidator(request.url)) {
                    throw IOException("Blocked remote playback destination")
                }
                if (!visitedUrls.add(request.url.toString())) {
                    throw IOException("Remote playback redirect loop detected")
                }

                // Do not allow a caller or Media3 request property to reintroduce browser credentials.
                val safeRequest = request.newBuilder()
                    .removeHeader("Cookie")
                    .removeHeader("Cookie2")
                    .removeHeader("Authorization")
                    .removeHeader("Proxy-Authorization")
                    .removeHeader("Referer")
                    .header("User-Agent", USER_AGENT)
                    .build()

                val response = chain.proceed(safeRequest)
                if (response.code !in REDIRECT_CODES ||
                    request.method !in REDIRECTABLE_METHODS
                ) {
                    return@addInterceptor response
                }

                val nextUrl = resolveRedirectTarget(
                    currentUrl = request.url,
                    location = response.header("Location"),
                    urlValidator = urlValidator
                )
                if (nextUrl == null) {
                    response.close()
                    throw IOException("Blocked remote playback redirect")
                }
                if (redirectsFollowed >= MAX_REDIRECTS ||
                    nextUrl.toString() in visitedUrls
                ) {
                    response.close()
                    throw IOException("Remote playback redirect limit or loop detected")
                }

                // Close the previous response before reusing the application interceptor chain.
                response.close()
                request = request.newBuilder().url(nextUrl).build()
                redirectsFollowed += 1
            }
        }
        .build()
}
