package com.agent.mobile.agent

import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.ExecutionMode
import com.agent.mobile.data.model.MessageRole
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
        val isLoop = engine.isRunawayOrLoopDetected(
            cmd = "grep foo bar",
            history = history,
            consecutiveErrors = 3
        )
        assertTrue("3 consecutive command failures must trigger abort", isLoop)
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
    fun testExecutionModeSwitching() {
        assertEquals(ExecutionMode.AUTOPILOT, engine.executionMode.value)
        engine.setExecutionMode(ExecutionMode.STEP_BY_STEP)
        assertEquals(ExecutionMode.STEP_BY_STEP, engine.executionMode.value)
        engine.setExecutionMode(ExecutionMode.AUTOPILOT)
        assertEquals(ExecutionMode.AUTOPILOT, engine.executionMode.value)
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
}
