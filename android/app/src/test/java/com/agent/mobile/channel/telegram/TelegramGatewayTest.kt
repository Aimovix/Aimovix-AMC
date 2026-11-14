package com.agent.mobile.channel.telegram

import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.channel.ChannelAgent
import com.agent.mobile.channel.TurnRequest
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.CommandSecurityFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class TelegramGatewayTest {

    private data class ApiCall(val method: String, val params: Map<String, String>)

    private lateinit var server: MockWebServer
    private val calls = CopyOnWriteArrayList<ApiCall>()
    private val nextMessageId = AtomicLong(100)
    private var savedState: String? = null
    private lateinit var store: TelegramStateStore
    private lateinit var scope: CoroutineScope
    private lateinit var agent: FakeAgent
    private lateinit var gateway: TelegramGateway
    private val pairedEvents = CopyOnWriteArrayList<PairedUser>()

    private val owner = 4242L
    private val stranger = 666L

    private class FakeAgent : ChannelAgent {
        val requests = CopyOnWriteArrayList<TurnRequest>()
        val steered = CopyOnWriteArrayList<String>()
        var behaviour: suspend (TurnRequest) -> AutonomousAgentEngine.UnattendedResult = { req ->
            AutonomousAgentEngine.UnattendedResult(req.sessionId ?: "session-1", "**Done:** ${req.text}", true, emptyList())
        }
        @Volatile var busy = false
        override val isBusy get() = busy
        override suspend fun run(request: TurnRequest): AutonomousAgentEngine.UnattendedResult {
            requests += request
            busy = true
            try {
                return behaviour(request)
            } finally {
                busy = false
            }
        }
        override fun steer(text: String): Boolean = busy.also { if (it) steered += text }
        override fun statusLine() = "Model: fake"
        override fun close() {}
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val method = request.path.orEmpty().substringAfterLast('/')
                val body = request.body.readUtf8()
                val params = if (body.contains("Content-Disposition")) mapOf("multipart" to body) else body.split('&')
                    .filter { it.contains('=') }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                calls += ApiCall(method, params)
                val result: Any = when (method) {
                    "sendMessage" -> JSONObject().put("message_id", nextMessageId.incrementAndGet())
                    "getMe" -> JSONObject().put("id", 1).put("username", "amc_test_bot").put("first_name", "AMC")
                    "getUpdates" -> JSONArray()
                    else -> true
                }
                return MockResponse().setBody(JSONObject().put("ok", true).put("result", result).toString())
            }
        }
        server.start()
        store = TelegramStateStore(load = { savedState }, save = { savedState = it })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        agent = FakeAgent()
        gateway = TelegramGateway(
            api = TelegramBotApi("TEST:TOKEN", baseUrl = server.url("/").toString().trimEnd('/')),
            store = store,
            agentFactory = { agent },
            scope = scope,
            approvalTimeoutMs = 5_000L,
            onPaired = { pairedEvents += it }
        )
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun message(from: Long, text: String, chatType: String = "private", chatId: Long = from) =
        TelegramBotApi.Update(
            updateId = nextMessageId.incrementAndGet(),
            message = TelegramBotApi.Message(
                messageId = 1, chatId = chatId, chatType = chatType, fromId = from,
                fromName = "User $from", text = text
            )
        )

    private fun sentTexts(chatId: Long): List<String> =
        calls.filter { it.method == "sendMessage" && it.params["chat_id"] == chatId.toString() }.map { it.params["text"].orEmpty() }

    private fun pairOwner() {
        store.update { it.copy(paired = listOf(PairedUser(owner, "Owner", 1L))) }
    }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    }

    private fun callback(from: Long, data: String) = TelegramBotApi.Update(
        updateId = nextMessageId.incrementAndGet(),
        callback = TelegramBotApi.CallbackQuery(id = "cb", fromId = from, chatId = from, messageId = 7, data = data)
    )

    @Test
    fun ownersGetBackgroundResultsAndStrangersNothing() = runBlocking {
        pairOwner()
        gateway.notifyOwners("🔔 Task done")
        assertEquals(listOf("🔔 Task done"), sentTexts(owner))
        assertTrue(sentTexts(stranger).isEmpty())
    }

    @Test
    fun parkedApprovalButtonsReachTheQueueOnlyFromPairedAccounts() = runBlocking {
        pairOwner()
        val decisions = CopyOnWriteArrayList<Pair<String, Boolean>>()
        gateway.onParkedDecision = { id, approved -> decisions += id to approved; decisions.size == 1 }
        gateway.askParkedApproval("abc-123", "Background: send SMS")
        val ask = calls.last { it.method == "sendMessage" }
        assertEquals(owner.toString(), ask.params["chat_id"])
        assertTrue(ask.params["reply_markup"].orEmpty().contains("pk:abc-123:y"))

        gateway.handleUpdate(callback(stranger, "pk:abc-123:y"))
        assertTrue("Strangers cannot decide", decisions.isEmpty())

        gateway.handleUpdate(callback(owner, "pk:abc-123:n"))
        assertEquals(listOf("abc-123" to false), decisions.toList())
        gateway.handleUpdate(callback(owner, "pk:abc-123:y"))
        assertEquals("A later answer is passed on but loses", 2, decisions.size)
        val answers = calls.filter { it.method == "answerCallbackQuery" }.map { it.params["text"] }
        assertTrue(answers.contains("Rejected"))
        assertTrue(answers.contains("Already decided."))
    }

    @Test
    fun stateRoundTripsThroughJson() {
        val state = TelegramState(
            offset = 77, paired = listOf(PairedUser(1, "A", 5)), sessions = mapOf(1L to "s"),
            pairingCode = "ABCD2345", pairingExpiresAt = 9, pairingAttempts = 2
        )
        assertEquals(state, TelegramState.fromJson(state.toJson()))
        assertEquals(TelegramState(), TelegramState.fromJson("not json"))
        assertEquals(8, TelegramStateStore.newPairingCode().length)
    }

    @Test
    fun parsesUpdatesFromTheBotApi() {
        val json = JSONArray(
            """[{"update_id":5,"message":{"message_id":9,"chat":{"id":42,"type":"private"},"from":{"id":42,"first_name":"Ann"},"caption":"look","photo":[{"file_id":"small"},{"file_id":"big"}]}},
               {"update_id":6,"callback_query":{"id":"cb1","from":{"id":42},"data":"ap:x:y","message":{"message_id":10,"chat":{"id":42}}}}]"""
        )
        val updates = TelegramBotApi.parseUpdates(json)
        assertEquals(2, updates.size)
        assertEquals("big", updates[0].message?.photoFileId)
        assertEquals("look", updates[0].message?.text)
        assertEquals("ap:x:y", updates[1].callback?.data)
        assertEquals(42L, updates[1].callback?.chatId)
    }

    @Test
    fun strangersOnlyGetAPairingHintAndNeverReachTheAgent() = runBlocking {
        gateway.handleUpdate(message(stranger, "rm -rf everything please"))
        gateway.handleUpdate(message(stranger, "hello again"))
        assertTrue(agent.requests.isEmpty())
        val texts = sentTexts(stranger)
        assertEquals("The hint is rate-limited", 1, texts.size)
        assertTrue(texts[0].contains("private AMC agent"))
    }

    @Test
    fun groupChatsAreIgnored() = runBlocking {
        pairOwner()
        gateway.handleUpdate(message(owner, "hi", chatType = "group", chatId = -100))
        assertTrue(calls.isEmpty())
        assertTrue(agent.requests.isEmpty())
    }

    @Test
    fun pairingWithOneTimeCode() = runBlocking {
        store.update { it.copy(pairingCode = "ABCD2345", pairingExpiresAt = System.currentTimeMillis() + 60_000) }

        gateway.handleUpdate(message(stranger, "/pair WRONG123"))
        assertTrue(sentTexts(stranger).last().contains("Wrong code"))
        assertEquals(1, store.get().pairingAttempts)

        gateway.handleUpdate(message(owner, "/start abcd-2345"))
        assertTrue(store.get().isPaired(owner))
        assertNull("The code is single-use", store.get().pairingCode)
        assertEquals(owner, pairedEvents.single().id)
        assertTrue(sentTexts(owner).last().contains("Paired"))
    }

    @Test
    fun tooManyWrongCodesInvalidateTheCode() = runBlocking {
        store.update { it.copy(pairingCode = "ABCD2345", pairingExpiresAt = System.currentTimeMillis() + 60_000) }
        repeat(TelegramStateStore.MAX_PAIRING_ATTEMPTS) { gateway.handleUpdate(message(stranger, "/pair NOPE000$it")) }
        assertNull(store.get().pairingCode)
        gateway.handleUpdate(message(stranger, "/pair ABCD2345"))
        assertFalse(store.get().isPaired(stranger))
    }

    @Test
    fun expiredCodeIsRejected() = runBlocking {
        store.update { it.copy(pairingCode = "ABCD2345", pairingExpiresAt = System.currentTimeMillis() - 1) }
        gateway.handleUpdate(message(owner, "/pair ABCD2345"))
        assertFalse(store.get().isPaired(owner))
    }

    @Test
    fun pairedUserMessagesRunTheAgentAndContinueTheSession() = runBlocking {
        pairOwner()
        gateway.handleUpdate(message(owner, "check battery"))
        waitUntil { sentTexts(owner).any { it.contains("Done:") } && !gateway.isTurnActive(owner) }
        assertNull(agent.requests[0].sessionId)
        assertEquals("check battery", agent.requests[0].text)
        assertEquals("session-1", store.get().sessions[owner])

        val reply = calls.last { it.method == "sendMessage" && it.params["text"].orEmpty().contains("Done:") }
        assertEquals("HTML", reply.params["parse_mode"])
        assertEquals("<b>Done:</b> check battery", reply.params["text"])

        gateway.handleUpdate(message(owner, "and storage?"))
        waitUntil { agent.requests.size == 2 && !gateway.isTurnActive(owner) }
        assertEquals("session-1", agent.requests[1].sessionId)

        gateway.handleUpdate(message(owner, "/new"))
        assertNull(store.get().sessions[owner])
    }

    @Test
    fun approvalsAreAskedWithButtonsAndAnsweredByCallback() = runBlocking {
        pairOwner()
        val decision = CompletableDeferred<Boolean>()
        agent.behaviour = { req ->
            val call = ToolCall(name = "execute_command", arguments = mapOf("command" to "termux-sms-send -n 123 hi"))
            decision.complete(req.approval(call, CommandSecurityFilter.analyze("termux-sms-send -n 123 hi")))
            AutonomousAgentEngine.UnattendedResult("s", "ok", true, emptyList())
        }
        gateway.handleUpdate(message(owner, "text mom"))
        waitUntil { calls.any { it.params["reply_markup"] != null } }
        val ask = calls.first { it.params["reply_markup"] != null }
        assertTrue(ask.params["text"].orEmpty().contains("termux-sms-send"))
        assertTrue(ask.params["text"].orEmpty().contains("other people"))
        val approveData = JSONObject(ask.params["reply_markup"]!!).getJSONArray("inline_keyboard")
            .getJSONArray(0).getJSONObject(0).getString("callback_data")

        // A callback from someone else (e.g. a forwarded message) is ignored.
        gateway.handleUpdate(
            TelegramBotApi.Update(900, callback = TelegramBotApi.CallbackQuery("cbX", stranger, owner, 101, approveData))
        )
        assertFalse(decision.isCompleted)

        gateway.handleUpdate(
            TelegramBotApi.Update(901, callback = TelegramBotApi.CallbackQuery("cb1", owner, owner, 101, approveData))
        )
        assertTrue(withTimeout(5_000) { decision.await() })
        assertTrue(calls.any { it.method == "editMessageText" && it.params["text"].orEmpty().contains("Approved") })
    }

    @Test
    fun unansweredApprovalCountsAsRejected() = runBlocking {
        pairOwner()
        val fast = TelegramGateway(
            api = TelegramBotApi("TEST:TOKEN", baseUrl = server.url("/").toString().trimEnd('/')),
            store = store, agentFactory = { agent }, scope = scope, approvalTimeoutMs = 200L
        )
        val call = ToolCall(name = "execute_command", arguments = mapOf("command" to "rm notes.txt"))
        assertFalse(fast.askApproval(owner, call, CommandSecurityFilter.analyze("rm notes.txt")))
        assertTrue(calls.any { it.method == "editMessageText" && it.params["text"].orEmpty().contains("No answer") })
    }

    @Test
    fun messagesDuringATaskSteerItAndStopCancelsIt() = runBlocking {
        pairOwner()
        val started = CompletableDeferred<Unit>()
        agent.behaviour = {
            started.complete(Unit)
            awaitCancellation()
        }
        gateway.handleUpdate(message(owner, "long task"))
        withTimeout(5_000) { started.await() }

        gateway.handleUpdate(message(owner, "also include photos"))
        assertEquals(listOf("also include photos"), agent.steered)

        gateway.handleUpdate(message(owner, "/stop"))
        waitUntil { sentTexts(owner).any { it.contains("Stopped") } }
        gateway.handleUpdate(message(owner, "/stop"))
        assertTrue(sentTexts(owner).last().contains("Nothing is running"))
    }

    @Test
    fun unpairRemovesAccess() = runBlocking {
        pairOwner()
        gateway.handleUpdate(message(owner, "/unpair"))
        assertFalse(store.get().isPaired(owner))
        gateway.handleUpdate(message(owner, "do something"))
        assertTrue(agent.requests.isEmpty())
    }
}
