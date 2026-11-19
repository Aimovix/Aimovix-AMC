package com.agent.mobile.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.testutil.ScriptedLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.atomic.AtomicInteger

/** #19: a chat-channel task that runs out of steps continues on its own in the same session. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RemoteContinuationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var llm: ScriptedLlm
    private lateinit var db: AppDatabase
    private lateinit var repository: ChatRepository
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var engine: AutonomousAgentEngine
    private val step = AtomicInteger(0)
    private val progress = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ChatRepository(db)
        llm = ScriptedLlm().start()
        engine = AutonomousAgentEngine(
            bridgeClient = TermuxBridgeClient(token = "offline"),
            chatRepository = repository,
            memoryStore = MemoryStore(tmp.newFile()),
            headless = true,
            personaStore = TextFileStore(tmp.newFile())
        ).also {
            it.setModelConfig(ModelConfig(ProviderType.OPENAI, "m", "k", llm.baseUrl))
            it.setSecurityPreset(SecurityPreset.AUTONOMOUS)
            it.setMaxSteps(5)
        }
    }

    @After
    fun tearDown() {
        engine.closeAndAwait()
        llm.shutdown()
        db.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    /** One remember call per step until [doneAfter] steps, then the final reply. */
    private fun agentNeeding(doneAfter: Int) {
        llm.fallback = { body ->
            val system = body.getJSONArray("messages").getJSONObject(0).optString("content")
            if (system.startsWith("You compress")) {
                ScriptedLlm.sse(ScriptedLlm.textChunk("Done: saved notes."))
            } else {
                val s = step.incrementAndGet()
                if (s > doneAfter) ScriptedLlm.sse(ScriptedLlm.textChunk("All $doneAfter notes saved."))
                else ScriptedLlm.sse(ScriptedLlm.toolChunk("r$s", "remember", JSONObject().put("note", "note number $s")))
            }
        }
    }

    private suspend fun run(): AutonomousAgentEngine.UnattendedResult = RemoteContinuation.run(
        first = { engine.runHeadless("✈️ Notes", "Save twelve notes", RunMode.REMOTE) },
        next = { sid -> engine.runHeadless("✈️ Notes", RemoteContinuation.NOTE, RunMode.REMOTE, sessionId = sid) },
        onContinue = { round -> progress += RemoteContinuation.progressText(round) }
    )

    @Test
    fun aLongChatTaskContinuesUntilItIsDone() = runBlocking {
        agentNeeding(doneAfter = 12)
        val result = run()

        assertTrue(result.summary, result.success)
        assertEquals("All 12 notes saved.", result.summary)
        assertFalse(result.budgetExhausted)
        assertEquals(2, progress.size)
        assertTrue(progress.first().contains("round 1 of ${RemoteContinuation.MAX_ROUNDS}"))
        // Every round ran in the same session, which holds the continuation notes.
        val messages = repository.getMessagesForSessionSync(result.sessionId!!)
        assertEquals(2, messages.count { it.role == MessageRole.USER && it.text == RemoteContinuation.NOTE })
        assertEquals(12, messages.count { it.role == MessageRole.TOOL })
    }

    @Test
    fun continuationStopsAfterThreeRounds() = runBlocking {
        agentNeeding(doneAfter = 1_000)
        val result = run()

        assertTrue(result.budgetExhausted)
        assertFalse(result.success)
        assertEquals(RemoteContinuation.MAX_ROUNDS, progress.size)
        assertEquals(5 * (1 + RemoteContinuation.MAX_ROUNDS), step.get())
    }

    @Test
    fun aFinishedTurnIsNotContinued() = runBlocking {
        agentNeeding(doneAfter = 2)
        val result = run()
        assertTrue(result.success)
        assertTrue(progress.isEmpty())
    }
}
