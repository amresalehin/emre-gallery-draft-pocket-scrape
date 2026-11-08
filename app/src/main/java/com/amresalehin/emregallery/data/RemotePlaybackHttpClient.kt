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
 * Redirects are intentionally not followed by OkHttp. The Media3 OkHttp data source receives a
 * 3xx response and fails closed; callers should return to the original source page. A future
 * explicit redirect implementation must validate each target and reject HTTPS downgrades.
 */
object RemotePlaybackHttpClient {
    const val USER_AGENT = "EmrePocket/1.0 (remote playback)"

    val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildClient()
    }

    fun dataSourceFactory(): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).setUserAgent(USER_AGENT)

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
            val original = chain.request()
            if (!urlValidator(original.url)) {
                throw IOException("Blocked remote playback destination")
            }

            // Do not allow a caller or Media3 request property to reintroduce browser credentials.
            val safeRequest = original.newBuilder()
                .removeHeader("Cookie")
                .removeHeader("Cookie2")
                .removeHeader("Authorization")
                .removeHeader("Proxy-Authorization")
                .removeHeader("Referer")
                .header("User-Agent", USER_AGENT)
                .build()

            chain.proceed(safeRequest)
        }
        .build()
}
