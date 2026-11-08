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
            // Bypass any JVM-wide proxy so the fake DNS below is actually used.
            .proxy(java.net.Proxy.NO_PROXY)
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

    @Test
    fun testClaudeRequestCachesSystemPromptAndSendsAllTools() = runBlocking {
        mockServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n" +
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Done\"}}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        ))
        val config = ModelConfig(provider = ProviderType.CLAUDE, modelName = "claude-opus-5-5", apiKey = "k",
            baseUrl = mockServer.url("/v1").toString())
        val events = llmClient.streamRequest(config, "SYSTEM", listOf(ChatMessage(role = MessageRole.USER, text = "hi"))).toList()
        assertTrue(events.last() is LlmClient.LlmStreamEvent.Completed)

        val body = org.json.JSONObject(mockServer.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8())
        val system = body.getJSONArray("system").getJSONObject(0)
        assertEquals("SYSTEM", system.getString("text"))
        assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
        assertEquals(com.agent.mobile.agent.AgentTools.ALL.size, body.getJSONArray("tools").length())
        assertFalse("Current Claude models reject sampling parameters", body.has("temperature"))
    }

    @Test
    fun testClaudeRefusalIsReportedAsError() = runBlocking {
        mockServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":0}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        ))
        val config = ModelConfig(provider = ProviderType.CLAUDE, modelName = "claude-opus-5-5", apiKey = "k",
            baseUrl = mockServer.url("/v1").toString())
        val events = llmClient.streamRequest(config, "S", listOf(ChatMessage(role = MessageRole.USER, text = "hi"))).toList()
        val error = events.last() as LlmClient.LlmStreamEvent.Error
        assertFalse(error.isRetryable)
    }

    @Test
    fun testGeminiFunctionResponseUsesToolName() = runBlocking {
        mockServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n"
        ))
        val call = com.agent.mobile.data.model.ToolCall(id = "t1", name = "read_file", arguments = mapOf("path" to "a.txt"))
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, text = "read it"),
            ChatMessage(role = MessageRole.ASSISTANT, text = "", toolCall = call),
            ChatMessage(role = MessageRole.TOOL, text = "content", toolResult = com.agent.mobile.data.model.ToolResult(
                toolCallId = "t1", command = "read_file a.txt", toolName = "read_file", stdout = "content"))
        )
        val config = ModelConfig(provider = ProviderType.GEMINI, modelName = "gemini-2.5-flash", apiKey = "AIzaTest",
            baseUrl = mockServer.url("/v1beta").toString())
        llmClient.streamRequest(config, "S", messages).toList()
        val body = org.json.JSONObject(mockServer.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8())
        val contents = body.getJSONArray("contents")
        val response = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("read_file", response.getString("name"))
    }

    @Test
    fun testReasoningModelsOmitTemperature() = runBlocking {
        mockServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n"))
        val config = ModelConfig(provider = ProviderType.OPENAI, modelName = "gpt-5-mini", apiKey = "k",
            baseUrl = mockServer.url("/v1").toString())
        llmClient.streamRequest(config, "S", listOf(ChatMessage(role = MessageRole.USER, text = "hi"))).toList()
        val body = org.json.JSONObject(mockServer.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8())
        assertFalse(body.has("temperature"))
        assertEquals(com.agent.mobile.agent.AgentTools.ALL.size, body.getJSONArray("tools").length())
    }
}

