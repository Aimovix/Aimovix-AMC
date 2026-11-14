package com.agent.mobile.agent

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.network.LlmClient
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import java.util.concurrent.Executors

/** Provider errors that may go away are retried with backoff before the fallback or a stop. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProviderRetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var fallbackServer: MockWebServer
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val engines = mutableListOf<AutonomousAgentEngine>()

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer().also { it.start() }
        fallbackServer = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        server.shutdown()
        fallbackServer.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun engine(withFallback: Boolean = false): AutonomousAgentEngine =
        AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "offline"),
            memoryStore = MemoryStore(tmp.newFile()),
            headless = true,
            personaStore = TextFileStore(tmp.newFile())
        ).also {
            it.setModelConfig(
                ModelConfig(
                    provider = ProviderType.OPENAI,
                    modelName = "primary",
                    apiKey = "k",
                    baseUrl = server.url("/v1/chat/completions").toString(),
                    fallbackProvider = if (withFallback) ProviderType.OPENAI else null,
                    fallbackModelName = "backup",
                    fallbackApiKey = "k2",
                    fallbackBaseUrl = fallbackServer.url("/v1/chat/completions").toString()
                )
            )
            it.setSecurityPreset(SecurityPreset.AUTONOMOUS)
            it.retryBaseDelayMs = 5
            engines += it
        }

    private fun reply(target: MockWebServer, text: String) {
        val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))
        target.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
    }

    private fun error(target: MockWebServer, code: Int, retryAfter: String? = null) {
        val r = MockResponse().setResponseCode(code).setBody("""{"error":{"message":"busy"}}""")
        if (retryAfter != null) r.setHeader("Retry-After", retryAfter)
        target.enqueue(r)
    }

    @Test
    fun rateLimitsAreRetriedOnTheSameProviderWithoutTheFallback() = runBlocking {
        error(server, 429)
        error(server, 503)
        reply(server, "Done.")
        val result = engine(withFallback = true).runUnattended("t", "hello")
        assertEquals("Done.", result.summary)
        assertTrue(result.success)
        assertEquals(3, server.requestCount)
        assertEquals("The fallback is not needed", 0, fallbackServer.requestCount)
        assertFalse(result.providerFailed)
    }

    @Test
    fun theFallbackTakesOverAfterTheRetries() = runBlocking {
        repeat(AutonomousAgentEngine.MAX_ATTEMPTS_PER_PROVIDER) { error(server, 500) }
        reply(fallbackServer, "From the fallback.")
        val result = engine(withFallback = true).runUnattended("t", "hello")
        assertEquals("From the fallback.", result.summary)
        assertEquals(AutonomousAgentEngine.MAX_ATTEMPTS_PER_PROVIDER, server.requestCount)
        assertEquals(1, fallbackServer.requestCount)
    }

    @Test
    fun aProviderThatStaysDownIsReportedForALaterRetry() = runBlocking {
        repeat(AutonomousAgentEngine.MAX_ATTEMPTS_PER_PROVIDER) { error(server, 502) }
        val result = engine().runUnattended("t", "hello")
        assertFalse(result.success)
        assertTrue(result.providerFailed)
        assertEquals(AutonomousAgentEngine.MAX_ATTEMPTS_PER_PROVIDER, server.requestCount)
    }

    @Test
    fun clientErrorsAreNotRetried() = runBlocking {
        error(server, 401)
        val result = engine(withFallback = true).runUnattended("t", "hello")
        assertFalse(result.success)
        assertFalse(result.providerFailed)
        assertEquals(1, server.requestCount)
        assertEquals(0, fallbackServer.requestCount)
    }

    @Test
    fun aVeryLongRetryAfterSkipsWaitingAndUsesTheFallback() = runBlocking {
        error(server, 429, retryAfter = "3600")
        reply(fallbackServer, "Fallback answered.")
        val result = engine(withFallback = true).runUnattended("t", "hello")
        assertEquals("Fallback answered.", result.summary)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBridgeToolTriesToBringTheBridgeBackFirst() = runBlocking {
        val toolChunk = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "delta", JSONObject().put(
                        "tool_calls", JSONArray().put(
                            JSONObject().put("index", 0).put("id", "c1").put(
                                "function", JSONObject().put("name", "execute_command")
                                    .put("arguments", JSONObject().put("command", "ls").toString())
                            )
                        )
                    )
                )
            )
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $toolChunk\n\ndata: [DONE]\n\n"))
        reply(server, "Listed.")
        var recoveries = 0
        val e = engine()
        e.bridgeRecovery = { recoveries++; false }
        val result = e.runUnattended("t", "list files")
        assertEquals("Listed.", result.summary)
        assertEquals(1, recoveries)
    }

    private fun toolCall(id: String, name: String, args: JSONObject) {
        val chunk = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "delta", JSONObject().put(
                        "tool_calls", JSONArray().put(
                            JSONObject().put("index", 0).put("id", id).put(
                                "function", JSONObject().put("name", name).put("arguments", args.toString())
                            )
                        )
                    )
                )
            )
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
    }

    @Test
    fun goingInCirclesGetsAHintAndThenStops() = runBlocking {
        val cycle = listOf("a", "b", "c")
        val total = cycle.size + AutonomousAgentEngine.NO_PROGRESS_STOP
        repeat(total) { i ->
            toolCall("c$i", "forget", JSONObject().put("text", "note ${cycle[i % cycle.size]}"))
        }
        reply(server, "never reached")
        val e = engine()
        e.setMaxSteps(30)
        val result = e.runUnattended("t", "go")
        assertEquals("Stopped after the last repeat", total, server.requestCount)
        assertFalse(result.success)
        val bodies = (0 until server.requestCount).map { server.takeRequest().body.readUtf8() }
        assertTrue(bodies.any { it.contains("you are going in circles") })
        assertTrue(e.messages.value.any { it.text.contains("No progress") })
    }

    @Test
    fun retryDelayGrowsHonoursRetryAfterAndIsCapped() {
        val r = Random(1)
        val first = AutonomousAgentEngine.retryDelayMs(1, 0, 2_000, r)!!
        val second = AutonomousAgentEngine.retryDelayMs(2, 0, 2_000, r)!!
        assertTrue(first in 1_500..2_500)
        assertTrue(second in 3_000..5_000)
        assertEquals(20_000L, AutonomousAgentEngine.retryDelayMs(1, 20_000, 2_000, r))
        assertTrue(AutonomousAgentEngine.retryDelayMs(10, 0, 2_000, r)!! <= 37_500)
        assertNull(AutonomousAgentEngine.retryDelayMs(1, 120_000, 2_000, r))
    }

    @Test
    fun retryAfterHeadersAreParsed() {
        assertEquals(1_500L, LlmClient.parseRetryAfterMillis("1500", null))
        assertEquals(7_000L, LlmClient.parseRetryAfterMillis(null, "7"))
        assertEquals(0L, LlmClient.parseRetryAfterMillis(null, null))
        assertEquals(0L, LlmClient.parseRetryAfterMillis(null, "soon"))
        val now = 1_700_000_000_000L
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        assertEquals(30_000L, LlmClient.parseRetryAfterMillis(null, fmt.format(java.util.Date(now + 30_000)), now))
    }
}
