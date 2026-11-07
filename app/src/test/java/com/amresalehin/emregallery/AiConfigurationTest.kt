package com.amresalehin.emregallery

import com.amresalehin.emregallery.data.CloudAiConnectionTester
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.CloudProviderStatus
import com.amresalehin.emregallery.data.FlorenceModelState
import com.amresalehin.emregallery.data.FlorenceStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiConfigurationTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testCloudAiProviderDefaultsAndCopy() {
        val provider = CloudAiProvider(
            id = "prov-1",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-123",
            modelId = "gpt-4o"
        )

        assertEquals("prov-1", provider.id)
        assertEquals("OpenAI", provider.name)
        assertEquals("https://api.openai.com/v1", provider.baseUrl)
        assertEquals("sk-123", provider.apiKey)
        assertEquals("gpt-4o", provider.modelId)
        assertFalse(provider.isActive)
        assertEquals(CloudProviderStatus.UNCONFIGURED, provider.status)

        val active = provider.copy(isActive = true, status = CloudProviderStatus.CONNECTED)
        assertTrue(active.isActive)
        assertEquals(CloudProviderStatus.CONNECTED, active.status)
    }

    @Test
    fun testCloudConnectionTesterSuccess() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"gpt-4o"}]}""")
        )

        val testBaseUrl = mockWebServer.url("/v1").toString()
        val provider = CloudAiProvider(
            name = "MockServer",
            baseUrl = testBaseUrl,
            apiKey = "test-token",
            modelId = "gpt-4o"
        )

        val result = CloudAiConnectionTester.testConnection(provider)
        assertEquals(CloudProviderStatus.CONNECTED, result.status)
        assertTrue(result.message.contains("Connected (HTTP 200"))
        assertTrue(result.latencyMs >= 0L)

        // Verify request received the authorization header
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer test-token", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun testCloudConnectionTesterUnauthorized() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )

        val testBaseUrl = mockWebServer.url("/v1").toString()
        val provider = CloudAiProvider(
            name = "MockServer",
            baseUrl = testBaseUrl,
            apiKey = "bad-token",
            modelId = "gpt-4o"
        )

        val result = CloudAiConnectionTester.testConnection(provider)
        assertEquals(CloudProviderStatus.ERROR, result.status)
        assertTrue(result.message.contains("Unauthorized (HTTP 401)"))
    }

    @Test
    fun testCloudConnectionTesterBlankUrl() = runBlocking {
        val provider = CloudAiProvider(
            name = "BlankServer",
            baseUrl = "   ",
            apiKey = "token",
            modelId = "gpt-4o"
        )

        val result = CloudAiConnectionTester.testConnection(provider)
        assertEquals(CloudProviderStatus.ERROR, result.status)
        assertEquals("Base URL cannot be blank", result.message)
    }

    @Test
    fun testFlorenceModelStateTransitions() {
        var state = FlorenceModelState(status = FlorenceStatus.NOT_INSTALLED)
        assertEquals(FlorenceStatus.NOT_INSTALLED, state.status)
        assertEquals(0f, state.progress, 0.001f)

        state = state.copy(status = FlorenceStatus.DOWNLOADING, progress = 0.45f, bytesDownloaded = 45000L, totalBytes = 100000L)
        assertEquals(FlorenceStatus.DOWNLOADING, state.status)
        assertEquals(0.45f, state.progress, 0.001f)
        assertEquals(45000L, state.bytesDownloaded)

        state = state.copy(status = FlorenceStatus.INSTALLED, progress = 1.0f, installedSizeBytes = 100000L, installedPath = "/path/florence2")
        assertEquals(FlorenceStatus.INSTALLED, state.status)
        assertEquals(1.0f, state.progress, 0.001f)
        assertEquals("/path/florence2", state.installedPath)
        assertEquals(100000L, state.installedSizeBytes)
    }

    @Test
    fun testSupportedLocalModelsList() {
        val models = com.amresalehin.emregallery.data.FlorenceModelManager.SUPPORTED_MODELS
        assertEquals(4, models.size)

        val modelNames = models.map { it.displayName }
        assertTrue(modelNames.contains("Gemma 4 E2B"))
        assertTrue(modelNames.contains("LFM2.5-VL-450M-Extract"))
        assertTrue(modelNames.contains("Qwen3.5-0.8B"))
        assertTrue(modelNames.contains("Florence-2 Base"))
    }

    @Test
    fun testLocalModelSpecLookup() {
        val gemma = com.amresalehin.emregallery.data.FlorenceModelManager.getSpec("gemma_4_e2b")
        assertEquals("Gemma 4 E2B", gemma.displayName)
        assertEquals("gemma", gemma.testTagPrefix)

        val lfm = com.amresalehin.emregallery.data.FlorenceModelManager.getSpec("lfm2_5_vl_450m_extract")
        assertEquals("LFM2.5-VL-450M-Extract", lfm.displayName)
        assertEquals("lfm", lfm.testTagPrefix)

        val qwen = com.amresalehin.emregallery.data.FlorenceModelManager.getSpec("qwen3_5_0_8b")
        assertEquals("Qwen3.5-0.8B", qwen.displayName)
        assertEquals("qwen", qwen.testTagPrefix)

        val florence = com.amresalehin.emregallery.data.FlorenceModelManager.getSpec("florence2")
        assertEquals("Florence-2 Base", florence.displayName)
        assertEquals("florence", florence.testTagPrefix)
    }
}
