package com.agent.mobile.agent

import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.PlanStepStatus
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.model.ToolResult
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.storage.db.entity.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousAgentEngineTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var engine: AutonomousAgentEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val bridgeClient = TermuxBridgeClient(token = "dummy-token")
        engine = AutonomousAgentEngine(bridgeClient = bridgeClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLoopDetectionOnRepeatedCommand() {
        val history = listOf("cat file.txt", "cat file.txt")
        val isLoop = engine.isRunawayOrLoopDetected(
            cmd = "cat file.txt",
            history = history,
            consecutiveErrors = 0
        )
        assertTrue("Repeating the same command 3 times should trigger runaway loop detection", isLoop)
    }

    @Test
    fun testLoopDetectionOnPingPongCycle() {
        // Ping-pong sequence: A, B, A, and next command is B
        val history = listOf("echo step1", "echo step2", "echo step1")
        val isLoop = engine.isRunawayOrLoopDetected(
            cmd = "echo step2",
            history = history,
            consecutiveErrors = 0
        )
        assertTrue("Alternating ping-pong loop (A -> B -> A -> B) must be detected", isLoop)
    }

    @Test
    fun testLoopDetectionOnPersistentErrors() {
        val history = listOf("grep foo bar")
        assertFalse(
            "3 failures are not yet a loop: the agent gets a reflection hint and may self-correct",
            engine.isRunawayOrLoopDetected(cmd = "grep foo baz", history = history, consecutiveErrors = 3)
        )
        val isLoop = engine.isRunawayOrLoopDetected(
            cmd = "grep foo bar",
            history = history,
            consecutiveErrors = 5
        )
        assertTrue("5 consecutive command failures must trigger abort", isLoop)
    }

    @Test
    fun testNormalCommandProgressionIsNotDetectedAsLoop() {
        val history = listOf("mkdir project", "cd project", "touch main.py")
        val isLoop = engine.isRunawayOrLoopDetected(
            cmd = "python3 main.py",
            history = history,
            consecutiveErrors = 0
        )
        assertFalse("Progressive diverse commands must not trigger loop detection", isLoop)
    }

    @Test
    fun testExtractPhotoPathFromTermuxCommand() {
        val path1 = engine.extractPhotoPathFromCommand("termux-camera-photo -c 0 /sdcard/captured.jpg")
        assertEquals("/sdcard/captured.jpg", path1)

        val path2 = engine.extractPhotoPathFromCommand("termux-camera-photo output.png")
        assertEquals("output.png", path2)

        val pathDefault = engine.extractPhotoPathFromCommand("termux-camera-photo")
        assertEquals("photo.jpg", pathDefault)

        val nonCamera = engine.extractPhotoPathFromCommand("ls -la")
        assertNull(nonCamera)
    }

    @Test
    fun testSecurityPresetSwitching() {
        assertEquals("Autonomous is the default mode", SecurityPreset.AUTONOMOUS, engine.securityPreset.value)
        engine.setSecurityPreset(SecurityPreset.TURBO)
        assertEquals(SecurityPreset.TURBO, engine.securityPreset.value)
        engine.setSecurityPreset(SecurityPreset.FULL_MACHINE)
        assertEquals(SecurityPreset.FULL_MACHINE, engine.securityPreset.value)
        engine.setSecurityPreset(SecurityPreset.CUSTOM)
        assertEquals(SecurityPreset.CUSTOM, engine.securityPreset.value)
    }

    @Test
    fun testLoadSessionUpdatesCurrentSession() {
        val session = ChatSession(id = "sess-123", title = "New Agent Session")
        engine.loadSession(session)
        assertEquals("sess-123", engine.currentSession.value?.id)
        assertEquals("New Agent Session", engine.currentSession.value?.title)
    }

    @Test
    fun testCompactToolOutputShortTextUnchanged() {
        val short = "Line 1: all good\nLine 2: finished."
        val result = engine.compactToolOutput(short, maxChars = 4000)
        assertEquals(short, result)
    }

    @Test
    fun testCompactToolOutputLongTextTruncated() {
        val head = "START_OF_OUTPUT: " + "A".repeat(2000)
        val middle = "M".repeat(6000)
        val tail = "Z".repeat(1500) + " :END_OF_OUTPUT"
        val hugeText = head + middle + tail

        val compacted = engine.compactToolOutput(hugeText, maxChars = 4000, headChars = 2000, tailChars = 1500)

        assertTrue("Compacted output must be shorter than original", compacted.length < hugeText.length)
        assertTrue("Compacted output must contain truncation notice", compacted.contains("... [Output truncated:"))
        assertTrue("Compacted output must preserve head", compacted.startsWith("START_OF_OUTPUT"))
        assertTrue("Compacted output must preserve tail", compacted.endsWith(":END_OF_OUTPUT"))
    }

    @Test
    fun testPrepareContextMessagesPrunesOrphanedToolResult() {
        val msgs = listOf(
            ChatMessage(role = MessageRole.USER, text = "Initial task"),
            ChatMessage(role = MessageRole.TOOL, text = "Orphaned output"),
            ChatMessage(role = MessageRole.ASSISTANT, text = "Working on it"),
            ChatMessage(role = MessageRole.USER, text = "Follow up")
        )
        val window = engine.prepareContextMessages(msgs, maxMessages = 3)
        assertFalse("Window must not start with an orphaned TOOL result", window.first().role == com.agent.mobile.data.model.MessageRole.TOOL)
    }

    @Test
    fun testLoadArtifactContentImageVsText() = kotlinx.coroutines.test.runTest {
        val textItem = com.agent.mobile.data.model.ArtifactItem(
            filename = "report.txt",
            path = "/sdcard/report.txt",
            type = com.agent.mobile.data.model.ArtifactType.TEXT,
            content = "Hello Report"
        )
        val content = engine.loadArtifactContent(textItem)
        assertEquals("Hello Report", content)
    }

    @Test
    fun testEmergencyStopPreventsImmediateStartTask() = kotlinx.coroutines.test.runTest {
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val customScope = CoroutineScope(testDispatcher + SupervisorJob())
        val testEngine = AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "dummy-token"),
            scope = customScope
        )
        testEngine.emergencyStop()
        assertTrue("Engine must be stopping immediately after emergencyStop", testEngine.isStopping.value)
        testEngine.startTask("echo should_not_run")
        assertFalse("Task should not run while engine is stopping", testEngine.isBusy.value)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse("Engine should no longer be stopping after cancellation finishes", testEngine.isStopping.value)
    }

    @Test
    fun testPrepareContextSynthesisesMissingToolResults() {
        val call = ToolCall(id = "call-1", name = AgentTools.EXECUTE_COMMAND, arguments = mapOf("command" to "ls"))
        val msgs = listOf(
            ChatMessage(role = MessageRole.USER, text = "List files"),
            ChatMessage(role = MessageRole.ASSISTANT, text = "", toolCall = call),
            // The app was killed before the result arrived; then the user wrote again.
            ChatMessage(role = MessageRole.USER, text = "Are you still there?")
        )
        val window = engine.prepareContextMessages(msgs)
        assertEquals(4, window.size)
        assertEquals(MessageRole.TOOL, window[2].role)
        assertEquals("call-1", window[2].toolResult?.toolCallId)
        assertEquals(AgentTools.EXECUTE_COMMAND, window[2].toolResult?.toolName)
    }

    @Test
    fun testPrepareContextDropsToolResultsWithoutMatchingCall() {
        val msgs = listOf(
            ChatMessage(role = MessageRole.USER, text = "Hi"),
            ChatMessage(role = MessageRole.ASSISTANT, text = "Hello"),
            ChatMessage(role = MessageRole.TOOL, text = "stray", toolResult = ToolResult(toolCallId = "nope", command = "x")),
            ChatMessage(role = MessageRole.USER, text = "Next")
        )
        val window = engine.prepareContextMessages(msgs)
        assertTrue(window.none { it.role == MessageRole.TOOL })
    }

    @Test
    fun testPrepareContextKeepsLatestUserRequestInLongRuns() {
        val msgs = mutableListOf(ChatMessage(role = MessageRole.USER, text = "Build the project"))
        repeat(30) { i ->
            val call = ToolCall(id = "c$i", name = AgentTools.EXECUTE_COMMAND, arguments = mapOf("command" to "step $i"))
            msgs += ChatMessage(role = MessageRole.ASSISTANT, text = "", toolCall = call)
            msgs += ChatMessage(role = MessageRole.TOOL, text = "ok $i", toolResult = ToolResult(toolCallId = "c$i", command = "step $i"))
        }
        val window = engine.prepareContextMessages(msgs, maxMessages = 10)
        assertEquals("Build the project", window.first().text)
        assertEquals(MessageRole.USER, window.first().role)
        assertNotEquals("A tool result must never directly follow the user turn", MessageRole.TOOL, window[1].role)
        assertEquals("ok 29", window.last().text)
        assertTrue(window.size <= 10)
    }

    @Test
    fun testPrepareContextRespectsCharBudget() {
        val big = "x".repeat(5_000)
        val msgs = mutableListOf<ChatMessage>()
        repeat(10) { i ->
            msgs += ChatMessage(role = MessageRole.USER, text = "question $i $big")
            msgs += ChatMessage(role = MessageRole.ASSISTANT, text = "answer $i $big")
        }
        val window = engine.prepareContextMessages(msgs, maxCharBudget = 25_000)
        assertEquals(MessageRole.USER, window.first().role)
        assertTrue(window.sumOf { it.text.length } <= 25_000)
        assertTrue(window.last().text.startsWith("answer 9"))
    }

    @Test
    fun testOnlyRecentImagesAreReplayed() {
        val msgs = (1..5).flatMap { i ->
            listOf(
                ChatMessage(role = MessageRole.USER, text = "img $i", imageBase64 = "AAAA", imageMimeType = "image/jpeg"),
                ChatMessage(role = MessageRole.ASSISTANT, text = "seen $i")
            )
        }
        val window = engine.prepareContextMessages(msgs)
        assertEquals(3, window.count { it.imageBase64 != null })
        assertNotNull(window.last { it.role == MessageRole.USER }.imageBase64)
    }

    @Test
    fun testParsePlan() {
        val plan = engine.parsePlan("[x] Inspect files\n[>] Write script\n[ ] Test script\n- Deploy")
        assertEquals(4, plan.size)
        assertEquals(PlanStepStatus.DONE, plan[0].status)
        assertEquals("Inspect files", plan[0].text)
        assertEquals(PlanStepStatus.IN_PROGRESS, plan[1].status)
        assertEquals(PlanStepStatus.PENDING, plan[2].status)
        assertEquals("Deploy", plan[3].text)
    }

    @Test
    fun testMessagesSentWhileBusyAreQueued() {
        // A dispatcher that is never advanced: the run stays "busy" deterministically.
        val paused = kotlinx.coroutines.test.StandardTestDispatcher()
        val busyEngine = AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "dummy-token"),
            scope = CoroutineScope(paused + SupervisorJob())
        )
        paused.scheduler.runCurrent() // finish init
        assertTrue(busyEngine.startTask("first task"))
        assertTrue(busyEngine.isBusy.value)
        assertTrue("Input while busy is accepted", busyEngine.startTask("extra instruction"))
        assertEquals(1, busyEngine.queuedInputs.value)
        assertEquals("Queued input is not shown before the agent picks it up", 1, busyEngine.messages.value.count { it.role == MessageRole.USER })
        busyEngine.close()
    }
}
