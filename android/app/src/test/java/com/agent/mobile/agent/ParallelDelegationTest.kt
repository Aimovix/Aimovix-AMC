package com.agent.mobile.agent

import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.testutil.ScriptedLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** #25: two delegate_task calls in one turn run side by side and both reports come back. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ParallelDelegationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var llm: ScriptedLlm
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var engine: AutonomousAgentEngine

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        llm = ScriptedLlm().start()
        engine = AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "offline"),
            memoryStore = MemoryStore(tmp.newFile()),
            headless = true,
            personaStore = TextFileStore(tmp.newFile())
        ).also {
            it.setModelConfig(ModelConfig(ProviderType.OPENAI, "m", "k", llm.baseUrl))
            it.setSecurityPreset(SecurityPreset.AUTONOMOUS)
        }
    }

    @After
    fun tearDown() {
        engine.closeAndAwait()
        llm.shutdown()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun delegate(id: String, task: String, index: Int) = JSONObject().put("index", index).put("id", id).put(
        "function", JSONObject().put("name", AgentTools.DELEGATE_TASK).put("arguments", JSONObject().put("task", task).toString())
    )

    @Test
    fun twoSubTasksInOneTurnRunSideBySide() = runBlocking {
        val bothChildrenWaiting = CountDownLatch(2)
        val overlapped = AtomicBoolean(true)
        llm.fallback = { body ->
            val text = body.getJSONArray("messages").toString()
            when {
                text.contains("\"role\":\"tool\"") -> ScriptedLlm.sse(ScriptedLlm.textChunk("Both reports are in."))
                text.contains("Child task A") || text.contains("Child task B") -> {
                    // Each child's request waits until the other child asked too: only possible when they overlap.
                    bothChildrenWaiting.countDown()
                    if (!bothChildrenWaiting.await(10, TimeUnit.SECONDS)) overlapped.set(false)
                    val which = if (text.contains("Child task A")) "A" else "B"
                    ScriptedLlm.sse(ScriptedLlm.textChunk("Report $which: 42 items."))
                }
                else -> ScriptedLlm.sse(
                    JSONObject().put(
                        "choices", JSONArray().put(
                            JSONObject().put(
                                "delta", JSONObject().put(
                                    "tool_calls", JSONArray()
                                        .put(delegate("d1", "Child task A: count the items in list A", 0))
                                        .put(delegate("d2", "Child task B: count the items in list B", 1))
                                )
                            )
                        )
                    )
                )
            }
        }
        val asked = mutableListOf<ToolCall>()
        engine.approvalHandler = { c, _ -> asked += c; true }

        val result = engine.runHeadless("Parallel", "Count the items in list A and list B", RunMode.REMOTE)

        assertTrue(result.summary, result.success)
        assertEquals("Both reports are in.", result.summary)
        assertTrue("The two sub-agents overlapped", overlapped.get())
        val toolResults = engine.messages.value.filter { it.role == com.agent.mobile.data.model.MessageRole.TOOL }
            .mapNotNull { it.toolResult }.filter { it.toolName == AgentTools.DELEGATE_TASK }
        assertEquals(2, toolResults.size)
        assertTrue(toolResults.any { it.stdout.contains("Report A") && it.toolCallId == "d1" })
        assertTrue(toolResults.any { it.stdout.contains("Report B") && it.toolCallId == "d2" })
        assertTrue(asked.isEmpty())
    }

    @Test
    fun onlyConsecutiveDelegationsAreGroupedAndAtMostThree() {
        fun call(name: String) = ToolCall(id = name + System.nanoTime(), name = name, arguments = emptyMap())
        val d = AgentTools.DELEGATE_TASK
        val calls = listOf(call(d), call(d), call(d), call(d), call(AgentTools.EXECUTE_COMMAND), call(d))
        assertEquals(ParallelDelegation.MAX_PARALLEL, ParallelDelegation.groupSize(calls, 0))
        assertEquals("A single delegation runs alone", 1, ParallelDelegation.groupSize(calls, 3))
        assertEquals(1, ParallelDelegation.groupSize(calls, 4))
        assertEquals(1, ParallelDelegation.groupSize(calls, 5))
        assertEquals(2, ParallelDelegation.groupSize(calls, 2))
    }
}
