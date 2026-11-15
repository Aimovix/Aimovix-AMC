package com.agent.mobile.agent

import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Two delegate_task calls in one turn run their sub-agents at the same time (#25). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ParallelSubAgentTest {

    private lateinit var server: MockWebServer
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun sse(delta: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\ndata: [DONE]\n\n")

    @Test
    fun delegationsOfOneTurnRunInParallel() = runBlocking {
        val childrenArrived = CountDownLatch(2)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    body.contains("MODE: SUB-AGENT") -> {
                        val now = inFlight.incrementAndGet()
                        maxInFlight.updateAndGet { maxOf(it, now) }
                        childrenArrived.countDown()
                        // Sequential sub-agents would never get here together: wait for the other one.
                        childrenArrived.await(3, TimeUnit.SECONDS)
                        inFlight.decrementAndGet()
                        val who = if (body.contains("source A")) "A" else "B"
                        sse(JSONObject().put("content", "report $who"))
                    }
                    body.contains("report A") && body.contains("report B") -> sse(JSONObject().put("content", "Both sources compared."))
                    else -> sse(
                        JSONObject().put(
                            "tool_calls", JSONArray()
                                .put(delegate(0, "d1", "Research source A"))
                                .put(delegate(1, "d2", "Research source B"))
                        )
                    )
                }
            }
        }
        server.start()
        val e = AutonomousAgentEngine(bridgeClient = TermuxBridgeClient(token = "offline"), headless = true).also {
            it.setModelConfig(ModelConfig(provider = ProviderType.OPENAI, modelName = "m", apiKey = "k", baseUrl = server.url("/v1/chat/completions").toString()))
            it.setSecurityPreset(SecurityPreset.AUTONOMOUS)
        }
        try {
            val started = System.currentTimeMillis()
            val result = e.runHeadless(title = "t", prompt = "compare two sources", mode = RunMode.UNATTENDED)
            assertEquals("Both sources compared.", result.summary)
            assertTrue("The run ends normally, not at its time limit", result.success)
            assertTrue(System.currentTimeMillis() - started < 60_000)
            assertEquals("Both sub-agents were running at the same time", 2, maxInFlight.get())
            val reports = e.messages.value.filter { it.role == MessageRole.TOOL }.map { it.toolResult!! }
            assertEquals(listOf("d1", "d2"), reports.map { it.toolCallId })
            assertTrue(reports[0].stdout.contains("report A"))
            assertTrue(reports[1].stdout.contains("report B"))
        } finally {
            e.closeAndAwait()
        }
    }

    private fun delegate(index: Int, id: String, task: String) = JSONObject().put("index", index).put("id", id).put(
        "function", JSONObject().put("name", "delegate_task").put("arguments", JSONObject().put("task", task).toString())
    )
}
