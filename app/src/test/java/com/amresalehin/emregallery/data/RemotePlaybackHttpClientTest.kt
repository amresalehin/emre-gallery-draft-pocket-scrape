package com.amresalehin.emregallery.data

import okhttp3.Dns
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class RemotePlaybackHttpClientTest {

    @Test
    fun stripsAmbientCredentialsAndDoesNotPersistResponseCookies() = withServer { server ->
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "session=must-not-persist; Path=/")
                .setBody("first")
        )
        server.enqueue(MockResponse().setBody("second"))

        val client = testClient(server)
        val firstRequest = Request.Builder()
            .url(server.url("/first"))
            .header("Cookie", "manual=secret")
            .header("Cookie2", "legacy=secret")
            .header("Authorization", "Bearer secret")
            .header("Proxy-Authorization", "Basic secret")
            .header("Referer", "https://page.example/article?token=secret")
            .build()

        client.newCall(firstRequest).execute().use { assertTrue(it.isSuccessful) }
        client.newCall(Request.Builder().url(server.url("/second")).build())
            .execute().use { assertTrue(it.isSuccessful) }

        val first = server.takeRequest()
        val second = server.takeRequest()

        assertNull(first.getHeader("Cookie"))
        assertNull(first.getHeader("Cookie2"))
        assertNull(first.getHeader("Authorization"))
        assertNull(first.getHeader("Proxy-Authorization"))
        assertNull(first.getHeader("Referer"))
        assertEquals(RemotePlaybackHttpClient.USER_AGENT, first.getHeader("User-Agent"))

        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("Authorization"))
        assertNull(second.getHeader("Referer"))
    }

    @Test
    fun followsSafeRedirectsAndPreservesRangeRequests() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/stream")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-3/4")
                .setBody("data")
        )

        val request = Request.Builder()
            .url(server.url("/start"))
            .header("Range", "bytes=0-3")
            .header("Cookie", "manual=secret")
            .header("Authorization", "Bearer secret")
            .header("Referer", "https://page.example/article")
            .build()
        val responseCode = testClient(server).newCall(request).execute().use { it.code }

        assertEquals(206, responseCode)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/start", first.path)
        assertEquals("/stream", second.path)
        assertEquals("bytes=0-3", second.getHeader("Range"))
        assertNull(first.getHeader("Cookie"))
        assertNull(first.getHeader("Authorization"))
        assertNull(first.getHeader("Referer"))
        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("Authorization"))
        assertNull(second.getHeader("Referer"))
    }

    @Test
    fun doesNotFollowRedirectsForMethodsOutsideGetAndHead() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/should-not-be-requested")
        )

        val request = Request.Builder()
            .url(server.url("/submit"))
            .post("payload".toRequestBody("text/plain".toMediaType()))
            .build()
        val responseCode = testClient(server).newCall(request).execute().use { it.code }

        assertEquals(302, responseCode)
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun rejectsRedirectToDisallowedDestinationBeforeConnecting() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://127.0.0.1/private")
        )
        val allowedHost = server.url("/").host
        val client = RemotePlaybackHttpClient.buildClient(
            dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == allowedHost) {
                        listOf(InetAddress.getByName("127.0.0.1"))
                    } else {
                        throw UnknownHostException("Unexpected DNS lookup")
                    }
            },
            urlValidator = { it.host == allowedHost }
        )

        val failure = runCatching {
            client.newCall(Request.Builder().url(server.url("/start")).build())
                .execute().use { }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsRedirectLoopsWithoutRepeatingARequest() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "/next")
        )
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "/start")
        )

        val failure = runCatching {
            testClient(server).newCall(
                Request.Builder().url(server.url("/start")).build()
            ).execute().use { }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun enforcesTheRedirectLimit() = withServer { server ->
        repeat(6) { index ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/hop${index + 1}")
            )
        }

        val failure = runCatching {
            testClient(server).newCall(
                Request.Builder().url(server.url("/start")).build()
            ).execute().use { }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun rejectsHttpsToHttpRedirectDowngrade() {
        val secureUrl = Request.Builder()
            .url("https://media.example/video")
            .build()
            .url

        val target = RemotePlaybackHttpClient.resolveRedirectTarget(
            currentUrl = secureUrl,
            location = "http://media.example/video",
            urlValidator = { true }
        )

        assertNull(target)
    }

    @Test
    fun rejectsPrivateIpRedirectTargets() {
        val secureUrl = Request.Builder()
            .url("https://media.example/video")
            .build()
            .url

        val target = RemotePlaybackHttpClient.resolveRedirectTarget(
            currentUrl = secureUrl,
            location = "https://127.0.0.1/private",
            urlValidator = {
                PageFetchUrlPolicy.parseAllowedUrl(it.toString()) != null
            }
        )

        assertNull(target)
    }

    @Test
    fun rejectsDnsAnswersContainingPrivateAddressesBeforeConnecting() {
        val privateDns = PublicInternetDns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName("127.0.0.1"))
            }
        )
        val client = RemotePlaybackHttpClient.buildClient(dns = privateDns)

        val failure = runCatching {
            client.newCall(
                Request.Builder().url("https://media.example/video.mp4").build()
            ).execute().use { }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            generateSequence(failure) { it.cause }.any { it is UnknownHostException }
        )
    }

    @Test
    fun rejectsPrivateIpLiteralBeforeDnsLookup() {
        var dnsLookups = 0
        val client = RemotePlaybackHttpClient.buildClient(
            dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dnsLookups += 1
                    return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }
        )

        val failure = runCatching {
            client.newCall(
                Request.Builder().url("http://127.0.0.1/private.mp4").build()
            ).execute().use { }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(0, dnsLookups)
    }

    private fun testClient(server: MockWebServer) = RemotePlaybackHttpClient.buildClient(
        dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == server.url("/").host) {
                    listOf(InetAddress.getByName("127.0.0.1"))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
        },
        urlValidator = { true }
    )

    private fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server)
        }
    }
}
