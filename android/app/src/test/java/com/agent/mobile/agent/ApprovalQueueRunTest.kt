package com.agent.mobile.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.security.CommandSecurityFilter
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Background runs park actions that need approval instead of skipping them, and a later run
 * continues the same session: the approved call runs exactly once, a changed call never runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ApprovalQueueRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var db: AppDatabase
    private lateinit var repository: ChatRepository
    private val engines = mutableListOf<AutonomousAgentEngine>()
    private val notifications = CopyOnWriteArrayList<String>()

    private val platform = object : AgentPlatform {
        override fun notifyUser(title: String, message: String) {
            notifications += "$title|$message"
        }
        override fun scheduleAgentTask(title: String, prompt: String, delayMinutes: Long, repeatEveryMinutes: Long) = "id"
        override suspend fun listAgentTasks() = ""
        override suspend fun cancelAgentTask(taskId: String) = false
    }

    private class FakeQueue : ApprovalQueue {
        val parked = CopyOnWriteArrayList<ParkedApproval>()
        override suspend fun park(request: ParkedApproval): String {
            parked += request
            return "q${parked.size}"
        }
    }

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository(db)
    }

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun engine(queue: ApprovalQueue?, steps: Int = 30): AutonomousAgentEngine =
        AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "offline"),
            chatRepository = repository,
            memoryStore = MemoryStore(tmp.newFile()),
            platform = platform,
            headless = true,
            personaStore = TextFileStore(tmp.newFile())
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
            it.setMaxSteps(steps)
            it.approvalQueue = queue
            engines += it
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

    private fun reply(text: String) {
        val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
    }

    private var taken = 0

    /** Requests received since the last call (each request can be taken only once). */
    private fun requestBodies(): List<JSONObject> {
        val count = server.requestCount
        return (taken until count).map { JSONObject(server.takeRequest().body.readUtf8()) }.also { taken = count }
    }

    @Test
    fun unattendedRunParksTheActionInsteadOfSkippingIt() = runBlocking {
        toolCall("call_1", "execute_command", JSONObject().put("command", "termux-sms-send -n 123 hello"))
        val queue = FakeQueue()

        val result = engine(queue).runUnattended("⏰ Greet mom", "Send mom a hello")

        assertEquals("q1", result.parkedApprovalId)
        assertTrue("Parked actions are not skipped", result.skippedApprovals.isEmpty())
        assertFalse(result.budgetExhausted)
        assertEquals("The run stops after parking; no further model call", 1, server.requestCount)
        val parked = queue.parked.single()
        assertEquals("termux-sms-send -n 123 hello", parked.call.arguments["command"])
        assertEquals(ApprovalFingerprint.of(parked.call), parked.fingerprint)
        assertEquals("⏰ Greet mom", parked.runTitle)
        assertEquals(result.sessionId, parked.sessionId)
        val audits = db.commandAuditDao().getAllAuditsSync()
        assertTrue(audits.any { !it.wasApproved && it.riskReason.contains("parked") })
    }

    @Test
    fun withoutAQueueTheActionIsSkippedAsBefore() = runBlocking {
        toolCall("call_1", "execute_command", JSONObject().put("command", "termux-sms-send -n 123 hello"))
        reply("Skipped the SMS.")
        val result = engine(queue = null).runUnattended("t", "Send mom a hello")
        assertNull(result.parkedApprovalId)
        assertEquals(listOf("termux-sms-send -n 123 hello"), result.skippedApprovals)
    }

    @Test
    fun aRunNeverParksOnceTheTaskUsedItsRunBudget() = runBlocking {
        val first = engine(FakeQueue())
        reply("ok")
        val sessionId = first.runUnattended("t", "start").sessionId!!

        toolCall("call_1", "execute_command", JSONObject().put("command", "termux-sms-send -n 123 hello"))
        reply("Could not send it.")
        val queue = FakeQueue()
        val result = engine(queue).resumeUnattended(
            "t", ResumeRequest(sessionId, ResumeRequest.CONTINUE_NOTE, chain = ResumeRequest.MAX_CHAIN - 1)
        )
        assertTrue(queue.parked.isEmpty())
        assertNull(result.parkedApprovalId)
        assertEquals(listOf("termux-sms-send -n 123 hello"), result.skippedApprovals)
    }

    @Test
    fun approvedActionRunsOnceAndTheSameSessionContinues() = runBlocking {
        toolCall("call_1", "notify_user", JSONObject().put("title", "Hi").put("message", "from the agent"))
        reply("first run done")
        val sessionId = engine(FakeQueue()).runUnattended("t", "start").sessionId!!
        notifications.clear()
        requestBodies()

        val approved = ToolCall(name = AgentTools.NOTIFY_USER, arguments = mapOf("title" to "Hi", "message" to "approved"))
        reply("All done after the approval.")
        val result = engine(FakeQueue()).resumeUnattended(
            "t",
            ResumeRequest(
                sessionId = sessionId,
                note = ResumeRequest.approvedNote(AgentTools.describe(approved)),
                approvedCall = approved,
                approvedFingerprint = ApprovalFingerprint.of(approved),
                chain = 1
            )
        )

        assertEquals(sessionId, result.sessionId)
        assertEquals("All done after the approval.", result.summary)
        assertTrue(result.success)
        assertEquals("The approved call ran exactly once", listOf("Hi|approved"), notifications.toList())
        val body = requestBodies().single()
        val messages = body.getJSONArray("messages")
        val last = messages.getJSONObject(messages.length() - 1)
        assertEquals("user", last.getString("role"))
        assertTrue(last.getString("content").contains("The user approved the paused action"))
        assertTrue(last.getString("content").contains("AMC ran it now (exit code 0)"))
        assertTrue("The earlier turns are part of the context", body.toString().contains("first run done"))
        val turn = repository.getMessagesForSessionSync(sessionId).filter { it.role == MessageRole.USER }
        assertEquals(2, turn.size)
    }

    @Test
    fun aChangedActionIsNeverRun() = runBlocking {
        reply("ok")
        val sessionId = engine(FakeQueue()).runUnattended("t", "start").sessionId!!
        notifications.clear()

        val approved = ToolCall(name = AgentTools.NOTIFY_USER, arguments = mapOf("title" to "Hi", "message" to "approved"))
        val tampered = approved.copy(arguments = mapOf("title" to "Hi", "message" to "something else"))
        reply("Stopped.")
        engine(FakeQueue()).resumeUnattended(
            "t",
            ResumeRequest(
                sessionId, ResumeRequest.approvedNote("x"), approvedCall = tampered,
                approvedFingerprint = ApprovalFingerprint.of(approved), chain = 1
            )
        )
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun rejectionIsToldToTheModel() = runBlocking {
        reply("ok")
        val sessionId = engine(FakeQueue()).runUnattended("t", "start").sessionId!!
        requestBodies()
        reply("Understood, I will not send it.")
        val result = engine(FakeQueue()).resumeUnattended(
            "t", ResumeRequest(sessionId, ResumeRequest.rejectedNote("termux-sms-send -n 1 hi"), chain = 1)
        )
        assertEquals("Understood, I will not send it.", result.summary)
        assertTrue(requestBodies().single().toString().contains("The user rejected the paused action"))
    }

    @Test
    fun resumingAMissingSessionFailsCleanly() = runBlocking {
        val result = engine(FakeQueue()).resumeUnattended("t", ResumeRequest("does-not-exist", ResumeRequest.CONTINUE_NOTE, chain = 1))
        assertFalse(result.success)
        assertNull(result.sessionId)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun usedUpStepBudgetIsReportedForAContinuation() = runBlocking {
        repeat(5) { i -> toolCall("call_$i", "update_plan", JSONObject().put("plan", "- [>] step $i")) }
        val result = engine(FakeQueue(), steps = 5).runUnattended("t", "long task")
        assertTrue(result.budgetExhausted)
        assertNull(result.parkedApprovalId)
    }

    @Test
    fun fingerprintCoversNameAndEveryArgument() {
        val a = ToolCall(name = "execute_command", arguments = mapOf("command" to "ls", "timeout_seconds" to "5"))
        assertEquals(ApprovalFingerprint.of(a), ApprovalFingerprint.of(a.copy(id = "other", arguments = linkedMapOf("timeout_seconds" to "5", "command" to "ls"))))
        assertNotEquals(ApprovalFingerprint.of(a), ApprovalFingerprint.of(a.copy(arguments = mapOf("command" to "ls -la", "timeout_seconds" to "5"))))
        assertNotEquals(ApprovalFingerprint.of(a), ApprovalFingerprint.of(a.copy(name = "read_file")))
        assertEquals(a.arguments, ApprovalFingerprint.decodeArguments(ApprovalFingerprint.encodeArguments(a.arguments)))
    }
}
