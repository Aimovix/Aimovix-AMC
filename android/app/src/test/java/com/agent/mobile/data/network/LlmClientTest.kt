package com.agent.mobile.data.network

import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ModelConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var llmClient: LlmClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        llmClient = LlmClient()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testConsumerCanStopAfterFirstStreamToken() = runBlocking {
        mockServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"second\"}}]}\n\n" +
                "data: [DONE]\n\n"))
        val config = ModelConfig(provider = ProviderType.OPENAI, modelName = "test", apiKey = "test",
            baseUrl = mockServer.url("/v1").toString())
        val events = llmClient.streamRequest(config, "Test", emptyList()).take(1).toList()
        assertEquals(listOf(LlmClient.LlmStreamEvent.Token("first")), events)
    }

    @Test
    fun testDnsFailureExplainsLocalBridgeIsSeparate() = runBlocking {
        val client = okhttp3.OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    throw java.net.UnknownHostException("test DNS failure")
            })
            .build()
        val events = LlmClient(client).streamRequest(
            ModelConfig(provider = ProviderType.OPENAI, modelName = "test", apiKey = "test",
                baseUrl = "https://provider.invalid/v1"),
            "Test", emptyList()
        ).toList()
        val error = events.filterIsInstance<LlmClient.LlmStreamEvent.Error>().single()
        assertTrue(error.message.contains("DNS"))
        assertTrue(error.message.contains("local bridge"))
        assertTrue(error.isRetryable)
    }

    @Test
    fun testOpenAiSseStreamingTokens() = runBlocking {
        val sseBody = """
            data: {"choices":[{"delta":{"content":"Hello "}}]}
            
            data: {"choices":[{"delta":{"content":"Android!"}}]}
            
            data: [DONE]
            
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val config = ModelConfig(
            provider = ProviderType.OPENAI,
            modelName = "gpt-4o-mini",
            apiKey = "test-key",
            baseUrl = mockServer.url("/v1/chat/completions").toString()
        )

        val events = llmClient.streamRequest(
            config = config,
            systemPrompt = "You are an assistant",
            messages = listOf(ChatMessage(role = MessageRole.USER, text = "Hi"))
        ).toList()

        val tokens = events.filterIsInstance<LlmClient.LlmStreamEvent.Token>().map { it.textChunk }
        assertEquals(listOf("Hello ", "Android!"), tokens)

        val completed = events.filterIsInstance<LlmClient.LlmStreamEvent.Completed>().firstOrNull()
        assertNotNull(completed)
        assertEquals("Hello Android!", completed?.fullText)
    }

    @Test
    fun testOpenAiToolCallDetectionFromStream() = runBlocking {
        val sseBody = """
            data: {"choices":[{"delta":{"tool_calls":[{"id":"call_123","function":{"name":"execute_command","arguments":"{\"command\":\"ls -la\"}"}}]}}]}
            
            data: [DONE]
            
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val config = ModelConfig(
            provider = ProviderType.OPENROUTER,
            modelName = "anthropic/claude-3.5-sonnet",
            apiKey = "test-key",
            baseUrl = mockServer.url("/v1/chat/completions").toString()
        )

        val events = llmClient.streamRequest(
            config = config,
            systemPrompt = "Execute commands",
            messages = listOf(ChatMessage(role = MessageRole.USER, text = "List files"))
        ).toList()

        val toolEvent = events.filterIsInstance<LlmClient.LlmStreamEvent.ToolCallDetected>().firstOrNull()
        assertNotNull("Tool call should be detected from stream", toolEvent)
        assertEquals("execute_command", toolEvent?.toolCall?.name)
        assertEquals("ls -la", toolEvent?.toolCall?.arguments?.get("command"))
    }

    @Test
    fun testRateLimitReturnsErrorEventWithStatus429() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"message":"Rate limit exceeded"}}""")
        )

        val config = ModelConfig(
            provider = ProviderType.GROQ,
            modelName = "llama-3.3-70b-versatile",
            apiKey = "test-key",
            baseUrl = mockServer.url("/v1/chat/completions").toString()
        )

        val events = llmClient.streamRequest(
            config = config,
            systemPrompt = "System",
            messages = listOf(ChatMessage(role = MessageRole.USER, text = "Test"))
        ).toList()

        val errorEvent = events.filterIsInstance<LlmClient.LlmStreamEvent.Error>().firstOrNull()
        assertNotNull(errorEvent)
        assertEquals(429, errorEvent?.statusCode)
        assertTrue(errorEvent?.isRetryable == true)
    }

    @Test
    fun testMultimodalMessagePayloadInclusion() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Image analyzed.\"}}]}\n\ndata: [DONE]\n\n")
        )

        val config = ModelConfig(
            provider = ProviderType.OPENAI,
            modelName = "gpt-4o",
            apiKey = "test-key",
            baseUrl = mockServer.url("/v1/chat/completions").toString()
        )

        val fakeBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val userMsgWithImage = ChatMessage(
            role = MessageRole.USER,
            text = "What do you see in this image?",
            imageBase64 = fakeBase64,
            imageMimeType = "image/png"
        )

        llmClient.streamRequest(
            config = config,
            systemPrompt = "Vision Assistant",
            messages = listOf(userMsgWithImage)
        ).toList()

        val recordedRequest = mockServer.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: throw AssertionError("MockServer did not receive request within 5s")
        val requestBody = recordedRequest.body.readUtf8()
        assertTrue("Request body must contain image_url for multimodal OpenAI", requestBody.contains("image_url"))
        assertTrue("Request body must contain base64 data", requestBody.contains(fakeBase64))
    }

    @Test
    fun testOpenAiMultipleToolCallsDetected() = runBlocking {
        val sseBody = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"execute_command","arguments":"{\"command\":\"pwd\"}"}}]}}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"execute_command","arguments":"{\"command\":\"whoami\"}"}}]}}]}
            
            data: [DONE]
            
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val config = ModelConfig(
            provider = ProviderType.OPENAI,
            modelName = "gpt-4o",
            apiKey = "test-key",
            baseUrl = mockServer.url("/v1/chat/completions").toString()
        )

        val events = llmClient.streamRequest(
            config = config,
            systemPrompt = "Execute commands",
            messages = listOf(ChatMessage(role = MessageRole.USER, text = "pwd and whoami"))
        ).toList()

        val toolEvent = events.filterIsInstance<LlmClient.LlmStreamEvent.ToolCallDetected>().firstOrNull()
        assertNotNull("Tool call event should be detected", toolEvent)
        assertEquals(2, toolEvent?.toolCalls?.size)
        assertEquals("pwd", toolEvent?.toolCalls?.get(0)?.arguments?.get("command"))
        assertEquals("whoami", toolEvent?.toolCalls?.get(1)?.arguments?.get("command"))
    }

    @Test
    fun testOpenAiPrematureStreamTerminationReportsError() = runBlocking {
        // Stream ends without [DONE] and without content
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
        )

        val config = ModelConfig(
            provider = ProviderType.OPENAI,
            modelName = "gpt-4o",
            apiKey = "test-key",
            baseUrl = mockServer.url("/v1/chat/completions").toString()
        )

        val events = llmClient.streamRequest(
            config = config,
            systemPrompt = "System",
            messages = listOf(ChatMessage(role = MessageRole.USER, text = "Hello"))
        ).toList()

        val errorEvent = events.filterIsInstance<LlmClient.LlmStreamEvent.Error>().firstOrNull()
        assertNotNull("Premature stream closure should produce Error event", errorEvent)
        assertTrue(errorEvent?.message?.contains("prematurely") == true)
    }
}
