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
    fun testRepeatWithChangingResultsIsNotALoop() {
        val history = listOf("termux-battery-status", "termux-battery-status")
        assertFalse(
            "Polling a status whose output changes is progress, not a loop",
            engine.isRunawayOrLoopDetected("termux-battery-status", history, 0, results = listOf(1, 2))
        )
        assertTrue(
            "The same command returning the same output each time is a loop",
            engine.isRunawayOrLoopDetected("termux-battery-status", history, 0, results = listOf(7, 7))
        )
    }

    @Test
    fun testPingPongWithChangingResultsIsNotALoop() {
        // test -> fix -> test, and the model wants to fix again: the test output changed.
        val history = listOf("pytest", "edit main.py", "pytest")
        assertFalse(engine.isRunawayOrLoopDetected("edit main.py", history, 0, results = listOf(1, 5, 2)))
        assertTrue(engine.isRunawayOrLoopDetected("edit main.py", history, 0, results = listOf(1, 5, 1)))
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

    /** One agent step: assistant tool call plus its result. */
    private fun step(n: Int): List<ChatMessage> {
        val call = ToolCall(id = "c$n", name = AgentTools.EXECUTE_COMMAND, arguments = mapOf("command" to "echo $n"))
        return listOf(
            ChatMessage(role = MessageRole.ASSISTANT, text = "", toolCall = call),
            ChatMessage(
                role = MessageRole.TOOL, text = "$n",
                toolResult = ToolResult(toolCallId = "c$n", command = "echo $n", toolName = AgentTools.EXECUTE_COMMAND, stdout = "$n")
            )
        )
    }

    @Test
    fun testTaskStaysPinnedAfterABackgroundContinuation() {
        val task = ChatMessage(role = MessageRole.USER, text = "Back up all photos to ~/backup and verify the checksums")
        val msgs = listOf(task) + (1..25).flatMap { step(it) } +
            ChatMessage(role = MessageRole.USER, text = ResumeRequest.CONTINUE_NOTE) + (26..29).flatMap { step(it) }
        val window = engine.prepareContextMessages(msgs)
        assertEquals("The original task must be the first message", task.text, window.first().text)
        assertTrue(window.size <= 40)
        assertTrue("Recent progress is kept", window.any { it.toolResult?.toolCallId == "c29" })
        assertEquals(MessageRole.ASSISTANT, window[1].role)
    }

    @Test
    fun testTaskStaysPinnedWhenAScreenshotOrContinueStartsTheWindow() {
        val task = ChatMessage(role = MessageRole.USER, text = "Order my usual pizza")
        val screenshot = ChatMessage(role = MessageRole.USER, text = "📱 Screenshot of the phone screen (screen_capture):", imageBase64 = "AAAA")
        val viaScreenshot = listOf(task) + (1..21).flatMap { step(it) } + screenshot + (22..30).flatMap { step(it) }
        assertEquals(task.text, engine.prepareContextMessages(viaScreenshot).first().text)

        // Step limit reached, the user types "continue": still the same task.
        val viaContinue = listOf(task) + (1..30).flatMap { step(it) } +
            ChatMessage(role = MessageRole.USER, text = "continue") + (31..33).flatMap { step(it) }
        val window = engine.prepareContextMessages(viaContinue)
        assertEquals(task.text, window.first().text)
        assertTrue(window.any { it.text == "continue" })
    }

    @Test
    fun testANewTaskAfterAFinalAnswerIsTheRoot() {
        val first = ChatMessage(role = MessageRole.USER, text = "first task")
        val second = ChatMessage(role = MessageRole.USER, text = "second task")
        val msgs = listOf(first) + (1..10).flatMap { step(it) } +
            ChatMessage(role = MessageRole.ASSISTANT, text = "First task done.") + second + (11..30).flatMap { step(it) }
        assertEquals(msgs.indexOf(second), engine.taskRootIndex(msgs))
        val window = engine.prepareContextMessages(msgs)
        assertEquals(second.text, window.first().text)
        assertFalse(window.any { it.text == first.text })
    }

    @Test
    fun testCharBudgetKeepsTheTaskWhenNotesFollow() {
        val task = ChatMessage(role = MessageRole.USER, text = "Analyse the logs")
        val big = "x".repeat(20_000)
        val msgs = listOf(task) + (1..3).flatMap { step(it) }.map { if (it.role == MessageRole.TOOL) it.copy(text = big, toolResult = it.toolResult!!.copy(stdout = big)) else it } +
            ChatMessage(role = MessageRole.USER, text = ResumeRequest.CONTINUE_NOTE) + (4..5).flatMap { step(it) }
        val window = engine.prepareContextMessages(msgs, maxCharBudget = 30_000)
        assertEquals("The [AMC] note is no turn boundary: the task stays", task.text, window.first().text)
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
    fun olderScreenReadingsAreMaskedButTheLatestStays() {
        fun screen(id: String, headline: String, body: String) = ChatMessage(
            role = MessageRole.TOOL,
            text = "[UNTRUSTED_OUTPUT_START]\n$headline\n\nScreen: Notes (com.example.notes) · 3 elements\n$body\n[UNTRUSTED_OUTPUT_END]",
            toolResult = ToolResult(toolCallId = id, command = "screen_tap", toolName = PhoneTools.SCREEN_TAP, stdout = "x")
        )
        fun call(id: String) = ChatMessage(role = MessageRole.ASSISTANT, text = "", toolCalls = listOf(ToolCall(id = id, name = PhoneTools.SCREEN_TAP)))
        val msgs = listOf(
            ChatMessage(role = MessageRole.USER, text = "Add milk"),
            call("a"), screen("a", "Tapped [e1] Button \"Add\".", "[e1] Button \"Old\""),
            call("b"), screen("b", "Typed.", "[e2] Field \"Milk\""),
            call("c"), screen("c", "Tapped [e3] Button \"Save\".", "[e3] Text \"Saved\"")
        )
        val window = engine.prepareContextMessages(msgs)
        val tools = window.filter { it.role == MessageRole.TOOL }
        assertEquals(3, tools.size)
        assertTrue(tools[0].text.contains("Tapped [e1] Button"))
        assertTrue(tools[0].text.contains("omitted"))
        assertFalse(tools[0].text.contains("Old"))
        assertTrue(tools[0].text.endsWith("[UNTRUSTED_OUTPUT_END]"))
        assertFalse(tools[1].text.contains("Milk\""))
        assertTrue("the latest reading is complete", tools[2].text.contains("[e3] Text \"Saved\""))
    }

    @Test
    fun localModelsGetTheCompactToolProfile() {
        val profile = AgentTools.LOCAL_PROFILE
        assertTrue(profile.size in 8..14)
        assertTrue(profile.all { AgentTools.isKnown(it) })
        assertTrue(PhoneTools.SCREEN_TAP in profile)
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

    @Test
    fun testFinishNudgeOnlyWhenTheReplyDoesNotLookLikeAnEnd() {
        val open = listOf(
            com.agent.mobile.data.model.PlanStep("download", PlanStepStatus.DONE),
            com.agent.mobile.data.model.PlanStep("verify checksums", PlanStepStatus.PENDING)
        )
        val done = open.map { it.copy(status = PlanStepStatus.DONE) }
        // Announcement without a tool call.
        assertNotNull(engine.finishNudge("Downloaded.\n\nLet me now verify the checksums.", done, RunMode.INTERACTIVE, 0))
        assertNotNull(engine.finishNudge("Ich werde jetzt die Datei prüfen", emptyList(), RunMode.UNATTENDED, 0))
        // Open plan steps.
        val nudge = engine.finishNudge("Downloaded the photos.", open, RunMode.UNATTENDED, 0)
        assertTrue(nudge!!.contains("verify checksums"))
        // A question in a background run.
        assertNotNull(engine.finishNudge("Which folder should I use?", emptyList(), RunMode.UNATTENDED, 0))
        // Legitimate ends.
        assertNull(engine.finishNudge("All photos are backed up and verified.", done, RunMode.UNATTENDED, 0))
        assertNull(engine.finishNudge("Which folder should I use?", open, RunMode.INTERACTIVE, 0))
        assertNull(engine.finishNudge("BLOCKED: the SD card is not mounted.", open, RunMode.UNATTENDED, 0))
        assertNull("At most two nudges per run", engine.finishNudge("Let me check.", open, RunMode.UNATTENDED, 2))
        assertNull("Sub-agents report back as they are", engine.finishNudge("Let me check.", open, RunMode.SUBAGENT, 0))
    }

    @Test
    fun testTheLatestSummaryIsPinnedBehindTheTask() {
        val task = ChatMessage(role = MessageRole.USER, text = "Migrate the database")
        val old = ChatMessage(role = MessageRole.USER, text = AutonomousAgentEngine.SUMMARY_PREFIX + " (…):\nfirst summary")
        val latest = ChatMessage(role = MessageRole.USER, text = AutonomousAgentEngine.SUMMARY_PREFIX + " (…):\nschema v2 applied")
        val msgs = listOf(task) + (1..10).flatMap { step(it) } + old + (11..25).flatMap { step(it) } + latest + (26..50).flatMap { step(it) }
        val window = engine.prepareContextMessages(msgs)
        assertEquals(task.text, window[0].text)
        assertEquals(latest.text, window[1].text)
        assertFalse(window.any { it.text == old.text })
        assertTrue(window.size <= 40)
        assertTrue(window.any { it.toolResult?.toolCallId == "c50" })
        assertEquals("Pairs stay valid after the pinned notes", MessageRole.ASSISTANT, window[2].role)
    }

    @Test
    fun testSummaryRequestContainsTaskPreviousSummaryAndSteps() {
        val req = engine.buildSummaryRequest("Back up photos", "copied 10 files", step(1) + step(2))
        assertTrue(req.contains("TASK:\nBack up photos"))
        assertTrue(req.contains("SUMMARY SO FAR:\ncopied 10 files"))
        assertTrue(req.contains("call: echo 1"))
        assertTrue(req.contains("result: 2"))
    }
}
