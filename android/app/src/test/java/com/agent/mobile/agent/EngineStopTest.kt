package com.agent.mobile.agent

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.testutil.FakeBridgeDaemon
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
import java.util.concurrent.Executors

/** #33: stopping a run stops only the agent's own command, never others on the shared connection. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EngineStopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var llm: MockWebServer
    private lateinit var daemon: FakeBridgeDaemon
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        llm = MockWebServer().also { it.start() }
        daemon = FakeBridgeDaemon().start()
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
    }

    @After
    fun tearDown() {
        daemon.shutdown()
        llm.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun toolCall(id: String, command: String) {
        val chunk = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "delta", JSONObject().put(
                        "tool_calls", JSONArray().put(
                            JSONObject().put("index", 0).put("id", id).put(
                                "function", JSONObject().put("name", "execute_command")
                                    .put("arguments", JSONObject().put("command", command).toString())
                            )
                        )
                    )
                )
            )
        )
        llm.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
    }

    @Test
    fun timeLimitInterruptsOnlyTheAgentsOwnCommand() = runBlocking {
        // The agent's command never finishes on its own.
        daemon.onExecute = { cmd -> if (cmd.startsWith("sleep")) null else "ok\n" to 0 }
        val bridge = TermuxBridgeClient(host = daemon.host, port = daemon.port, token = "test-token")
        bridge.connect()
        assertTrue(bridge.awaitConnected(5_000L))

        toolCall("call_1", "sleep 100")
        val engine = AutonomousAgentEngine(bridgeClient = bridge, memoryStore = MemoryStore(tmp.newFile()), headless = true)
        engine.setModelConfig(ModelConfig(ProviderType.OPENAI, "m", "k", llm.url("/v1").toString()))
        engine.setSecurityPreset(SecurityPreset.TURBO)
        try {
            val result = engine.runHeadless(title = "t", prompt = "wait", mode = RunMode.UNATTENDED, timeoutMs = 2_000L)
            assertTrue(result.budgetExhausted)
            val deadline = System.currentTimeMillis() + 3_000L
            while (daemon.interrupts().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val interrupts = daemon.interrupts()
            assertTrue("The agent's command must be interrupted", interrupts.isNotEmpty())
            val agentExec = daemon.received.first { it.optString("action") == "execute" && it.optString("command").startsWith("sleep") }.optString("execution_id")
            assertTrue(
                "Every interrupt names the agent's execution (a bare interrupt stops the terminal tab too): $interrupts",
                interrupts.all { it.optString("execution_id") == agentExec }
            )
        } finally {
            engine.closeAndAwait()
            bridge.disconnect()
        }
    }
}
