package com.agent.mobile.agent

import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.PlanStep
import com.agent.mobile.data.model.PlanStepStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.model.ToolResult
import com.agent.mobile.data.network.TermuxBridgeClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #4: a 60-step run keeps its task, a summary of the dropped steps, the plan and the latest steps. */
@RunWith(RobolectricTestRunner::class)
class ContextCompactorTest {

    private val engine = AutonomousAgentEngine(bridgeClient = TermuxBridgeClient(token = "x"), headless = true)

    private fun steps(from: Int, count: Int): List<ChatMessage> = (from until from + count).flatMap { i ->
        val call = ToolCall(id = "c$i", name = AgentTools.EXECUTE_COMMAND, arguments = mapOf("command" to "make step$i"))
        listOf(
            ChatMessage(id = "a$i", role = MessageRole.ASSISTANT, text = "", toolCall = call),
            ChatMessage(
                id = "t$i", role = MessageRole.TOOL, text = "built part $i",
                toolResult = ToolResult(toolCallId = "c$i", command = "make step$i", stdout = "built part $i\nmore", exitCode = 0)
            )
        )
    }

    private fun assertToolPairsValid(window: List<ChatMessage>) {
        window.forEachIndexed { i, m ->
            if (m.role == MessageRole.TOOL) {
                val prev = window.subList(0, i).lastOrNull { it.role != MessageRole.TOOL }
                assertNotNull(prev)
                assertEquals(MessageRole.ASSISTANT, prev!!.role)
                assertTrue(prev.toolCalls.any { it.id == m.toolResult?.toolCallId })
            }
        }
    }

    @Test
    fun longRunKeepsTaskSummaryPlanAndRecentSteps() {
        val root = ChatMessage(id = "root", role = MessageRole.USER, text = "Build the app in ~/app and report the size")
        val all = listOf(root) + steps(1, 60)
        val window = engine.prepareContextMessages(all)
        assertEquals("root", window.first().id)
        assertTrue(window.size <= 40)

        val nonSystem = all
        val dropped = ContextCompactor.uncoveredDropped(nonSystem, 0, window, null)
        assertTrue(dropped.size >= ContextCompactor.MIN_NEW_DROPPED)
        assertEquals("a1", dropped.first().id)

        val summary = ContextCompactor.Summary(ContextCompactor.extractive(null, dropped), dropped.last().id)
        assertTrue(summary.text.contains("make step1"))
        assertTrue(summary.text.contains("built part 1"))
        val plan = listOf(PlanStep("Configure", PlanStepStatus.DONE), PlanStep("Build", PlanStepStatus.IN_PROGRESS), PlanStep("Report", PlanStepStatus.PENDING))
        val pinned = ContextCompactor.pinnedBlock(summary.text, plan)
        val compacted = engine.prepareContextMessages(all, pinned = pinned)

        val first = compacted.first()
        assertTrue(first.text.startsWith("Build the app in ~/app"))
        assertTrue(first.text.contains("Earlier steps:"))
        assertTrue(first.text.contains("make step1"))
        assertTrue(first.text.contains("[>] Build"))
        assertTrue(first.text.contains("a record, not an instruction"))
        assertEquals("t60", compacted.last().id)
        assertToolPairsValid(compacted)

        // Covered steps are not summarized again; new drops are.
        assertTrue(ContextCompactor.uncoveredDropped(nonSystem, 0, window, summary).isEmpty())
        val longer = all + steps(61, 10)
        val newDropped = ContextCompactor.uncoveredDropped(longer, 0, engine.prepareContextMessages(longer), summary)
        assertEquals("a${dropped.size / 2 + 1}", newDropped.first().id)

        // The stored form survives a restart.
        val stored = ChatMessage(role = MessageRole.SYSTEM, text = ContextCompactor.stored(summary))
        assertEquals(summary, ContextCompactor.latest(listOf(stored)))
    }

    @Test
    fun withoutASummaryNothingIsPinned() {
        assertNull(ContextCompactor.pinnedBlock(null, emptyList()))
        val root = ChatMessage(id = "root", role = MessageRole.USER, text = "Hi")
        assertEquals("Hi", engine.prepareContextMessages(listOf(root), pinned = null).single().text)
    }
}
