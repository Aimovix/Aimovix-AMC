package com.agent.mobile.agent

import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.SecurityAssessment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Drives complete headless runs against a fake OpenAI-compatible server: channel (REMOTE)
 * approvals, sub-agents and the persona in the system prompt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HeadlessAgentRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val engines = mutableListOf<AutonomousAgentEngine>()

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
    }

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        server.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun engine(persona: String = ""): AutonomousAgentEngine {
        val personaStore = TextFileStore(tmp.newFile()).also { it.write(persona) }
        return AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "offline"),
            memoryStore = MemoryStore(tmp.newFile()),
            headless = true,
            personaStore = personaStore
        ).also {
            it.setModelConfig(
                ModelConfig(
                    provider = ProviderType.OPENAI,
                    modelName = "test-model",
                    apiKey = "test",
                    baseUrl = server.url("/v1/chat/completions").toString()
                )
            )
            it.setSecurityPreset(SecurityPreset.AUTONOMOUS)
            engines += it
        }
    }

    private fun toolCall(id: String, name: String, args: JSONObject) {
        val chunk = JSONObject().put(
            "choices", org.json.JSONArray().put(
                JSONObject().put(
                    "delta", JSONObject().put(
                        "tool_calls", org.json.JSONArray().put(
                            JSONObject().put("index", 0).put("id", id).put(
                                "function", JSONObject().put("name", name).put("arguments", args.toString())
                            )
                        )
                    )
                )
            )
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: [DONE]\n\n")
        )
    }

    private fun reply(text: String) {
        val chunk = JSONObject().put(
            "choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: [DONE]\n\n")
        )
    }

    private fun requestBodies(): List<JSONObject> =
        (0 until server.requestCount).map { JSONObject(server.takeRequest().body.readUtf8()) }

    private fun systemPrompt(body: JSONObject): String =
        body.getJSONArray("messages").getJSONObject(0).getString("content")

    private fun toolNames(body: JSONObject): Set<String> {
        val tools = body.getJSONArray("tools")
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    }

    @Test
    fun remoteRunAsksTheChannelForApprovalAndReturnsTheFinalReply() = runBlocking {
        toolCall("call_1", "execute_command", JSONObject().put("command", "termux-sms-send -n 123 hello"))
        reply("I did not send the SMS because you rejected it.")

        val asked = CopyOnWriteArrayList<Pair<ToolCall, SecurityAssessment>>()
        val e = engine(persona = "Your name is Max. Always answer in German.")
        e.channelName = "Telegram"
        e.approvalHandler = { call, assessment -> asked += call to assessment; false }

        val result = e.runHeadless(title = "Telegram chat", prompt = "Send mom a hello", mode = RunMode.REMOTE)

        assertEquals("I did not send the SMS because you rejected it.", result.summary)
        assertTrue(result.success)
        assertTrue(result.skippedApprovals.isEmpty())
        assertEquals(1, asked.size)
        assertEquals("termux-sms-send -n 123 hello", asked[0].first.arguments["command"])
        assertTrue("SMS always needs the user's confirmation", asked[0].second.alwaysConfirm)

        val bodies = requestBodies()
        assertEquals(2, bodies.size)
        val prompt = systemPrompt(bodies[0])
        assertTrue(prompt.contains("MODE: REMOTE CHAT via Telegram"))
        assertTrue(prompt.contains("Your name is Max. Always answer in German."))
        assertTrue(prompt.contains("### INSTALLED SKILLS"))
        assertTrue(toolNames(bodies[0]).containsAll(setOf("delegate_task", "web_search", "send_file", "search_history")))
        assertTrue(bodies[1].toString().contains("rejected by the user"))
    }

    @Test
    fun remoteRunWithoutApprovalHandlerSkipsSensitiveActions() = runBlocking {
        toolCall("call_1", "execute_command", JSONObject().put("command", "termux-camera-photo -c 0 ~/p.jpg"))
        reply("Skipped the photo.")
        val result = engine().runHeadless(title = "t", prompt = "photo", mode = RunMode.REMOTE)
        assertEquals(listOf("termux-camera-photo -c 0 ~/p.jpg"), result.skippedApprovals)
        assertEquals("Skipped the photo.", result.summary)
    }

    @Test
    fun delegateTaskRunsASubAgentWithAFreshContextAndReturnsItsReport() = runBlocking {
        toolCall("call_p1", "delegate_task", JSONObject().put("task", "Find the answer").put("context", "It is a number"))
        reply("child report: the answer is 42")   // sub-agent
        reply("The answer is 42.")               // parent

        val e = engine()
        val result = e.runHeadless(title = "t", prompt = "What is the answer?", mode = RunMode.UNATTENDED)
        assertEquals("The answer is 42.", result.summary)
        assertTrue(result.success)

        val bodies = requestBodies()
        assertEquals(3, bodies.size)
        val childBody = bodies[1]
        assertTrue(systemPrompt(childBody).contains("MODE: SUB-AGENT"))
        assertFalse("Sub-agents cannot delegate further", toolNames(childBody).contains("delegate_task"))
        val childMessages = childBody.getJSONArray("messages")
        assertEquals("Fresh context: system prompt + the delegated task only", 2, childMessages.length())
        assertTrue(childMessages.getJSONObject(1).getString("content").contains("Find the answer"))
        assertTrue(childMessages.getJSONObject(1).getString("content").contains("It is a number"))

        assertTrue(bodies[2].toString().contains("child report: the answer is 42"))
        val toolMsg = e.messages.value.first { it.role == MessageRole.TOOL && it.toolResult?.toolName == "delegate_task" }
        assertTrue(toolMsg.toolResult!!.stdout.contains("Sub-agent report"))
        assertFalse(toolMsg.toolResult!!.isError)
    }

    @Test
    fun subAgentApprovalsAreForwardedToTheParentChannel() = runBlocking {
        toolCall("call_p1", "delegate_task", JSONObject().put("task", "Text mom"))
        toolCall("call_c1", "execute_command", JSONObject().put("command", "termux-sms-send -n 1 hi"))  // sub-agent
        reply("Could not send: not approved.")   // sub-agent
        reply("Your mom was not texted.")        // parent

        val asked = CopyOnWriteArrayList<String>()
        val e = engine()
        e.approvalHandler = { call, _ -> asked += AgentTools.describe(call); false }
        val result = e.runHeadless(title = "t", prompt = "Text mom", mode = RunMode.REMOTE)

        assertEquals(listOf("termux-sms-send -n 1 hi"), asked)
        assertEquals("Your mom was not texted.", result.summary)
    }

    @Test
    fun sendFileWithoutBridgeFailsGracefully() = runBlocking {
        toolCall("call_1", "send_file", JSONObject().put("path", "~/report.pdf"))
        reply("Could not send the file.")
        val e = engine()
        e.setSecurityPreset(SecurityPreset.TURBO)
        val result = e.runHeadless(title = "t", prompt = "send report", mode = RunMode.REMOTE)
        assertEquals("Could not send the file.", result.summary)
        val tool = e.messages.value.first { it.role == MessageRole.TOOL }
        assertTrue(tool.toolResult!!.isError)
        assertTrue(tool.toolResult!!.stderr.contains("Termux"))
    }
}
