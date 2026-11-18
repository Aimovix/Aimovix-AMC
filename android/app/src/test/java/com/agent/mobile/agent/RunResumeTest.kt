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
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.testutil.FakeBridgeDaemon
import com.agent.mobile.testutil.ScriptedLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/** #7: chat runs survive the app being closed, killed or the phone restarting. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RunResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private val enqueued = CopyOnWriteArrayList<Triple<String, String, String>>()
    private lateinit var checkpointer: RoomRunCheckpointer

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        checkpointer = RoomRunCheckpointer(db.agentQueueDao()) { s, t, n -> enqueued += Triple(s, t, n) }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test
    fun runsThatDiedWithTheirProcessContinueAfterARestart() = runBlocking {
        val dao = db.agentQueueDao()
        val now = System.currentTimeMillis()
        dao.upsertRun(AgentRunEntity("dead", "s1", "Chat task", RoomRunCheckpointer.KIND, AgentRunEntity.STATUS_RUNNING, updatedAt = now - 60_000))
        dao.upsertRun(AgentRunEntity("alive", "s2", "Other", RoomRunCheckpointer.KIND, AgentRunEntity.STATUS_RUNNING, updatedAt = now))
        dao.upsertRun(AgentRunEntity("old", "s3", "Old", RoomRunCheckpointer.KIND, AgentRunEntity.STATUS_RUNNING, updatedAt = now - 7 * 60 * 60_000))
        dao.upsertRun(AgentRunEntity("worker", "s4", "Task", "task", AgentRunEntity.STATUS_RUNNING, updatedAt = now))

        assertEquals(1, checkpointer.resumeInterrupted(activeRunIds = setOf("alive"), now = now))
        assertEquals(listOf(Triple("s1", "Chat task", ResumeRequest.RESTART_NOTE)), enqueued.toList())
        assertEquals(AgentRunEntity.STATUS_CONTINUED, dao.getRun("dead")?.status)
        assertEquals(AgentRunEntity.STATUS_RUNNING, dao.getRun("alive")?.status)
        // A second start does not continue it twice.
        assertEquals(0, checkpointer.resumeInterrupted(setOf("alive"), now))
    }

    @Test
    fun closingTheAppHandsTheRunToTheBackgroundInsteadOfCancellingIt() = runBlocking {
        val llm = ScriptedLlm().start()
        val daemon = FakeBridgeDaemon(onExecute = { cmd -> if (cmd.startsWith("sleep")) null else "ok\n" to 0 }).start()
        val bridge = TermuxBridgeClient(host = daemon.host, port = daemon.port, token = "test-token")
        bridge.connect()
        assertTrue(bridge.awaitConnected(5_000L))
        val repo = ChatRepository(db)
        val engine = AutonomousAgentEngine(bridgeClient = bridge, chatRepository = repo, memoryStore = MemoryStore(tmp.newFile()))
        try {
            engine.setModelConfig(ModelConfig(ProviderType.OPENAI, "m", "k", llm.baseUrl))
            engine.setSecurityPreset(SecurityPreset.TURBO)
            engine.runCheckpointer = checkpointer
            withTimeout(5_000) { while (!engine.isInitialized.value || engine.currentSession.value == null) delay(20) }
            llm.tool("execute_command", JSONObject().put("command", "sleep 600"))
            assertTrue(engine.startTask("Back up my notes"))
            withTimeout(10_000) { while (daemon.running.isEmpty()) delay(20) }
            val sessionId = engine.currentSession.value!!.id
            val runId = db.agentQueueDao().runsWithStatus(AgentRunEntity.STATUS_RUNNING).single().id

            engine.handOffAndClose()

            assertEquals(listOf(Triple(sessionId, engine.currentSession.value!!.title, ResumeRequest.HANDOFF_NOTE)), enqueued.toList())
            assertEquals(AgentRunEntity.STATUS_CONTINUED, db.agentQueueDao().getRun(runId)?.status)
            val stored = withTimeout(5_000) {
                var msgs = repo.getMessagesForSessionSync(sessionId)
                while (msgs.none { it.role == MessageRole.TOOL }) { delay(50); msgs = repo.getMessagesForSessionSync(sessionId) }
                msgs
            }
            assertTrue("Not reported as a user cancellation", stored.none { it.text.contains("canceled by the user") })
            assertTrue(stored.any { it.text.contains("continues in the background") })
        } finally {
            engine.closeAndAwait()
            bridge.disconnect()
            daemon.shutdown()
            llm.shutdown()
        }
    }
}
