package com.agent.mobile.ui.chat

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.agent.RunMode
import com.agent.mobile.agent.RunRegistry
import com.agent.mobile.agent.RunTrigger
import com.agent.mobile.audit.AuditDecision
import com.agent.mobile.audit.AuditEntry
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.ui.chat.components.RunsInbox
import com.agent.mobile.ui.chat.components.RunsInboxDialog
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Teil E, E6: the overview of running and recent agent runs. */
@RunWith(RobolectricTestRunner::class)
class RunsInboxTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ChatRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private val now = 10_000_000L
    private val telegram = RunRegistry.ActiveRun("run-t", "✈️ Check the server", RunMode.REMOTE, RunTrigger.TELEGRAM, "s-t", unattended = true, startedAt = now - 12 * 60_000)
    private val chat = RunRegistry.ActiveRun("run-c", "Build the app", RunMode.INTERACTIVE, RunTrigger.USER, "s-c", unattended = false, startedAt = now - 30_000)

    @Test
    fun rowsDescribeWhatRunsAndWhatRan() {
        val active = RunsInbox.activeRows(listOf(chat, telegram), now)
        assertEquals("Longest-running first", "run-t", active.first().runId)
        assertEquals("Telegram · unattended · running for 12 min", active.first().detail)
        assertEquals("Chat · running for 30 s", active.last().detail)
        assertEquals("2 h 5 min", RunsInbox.duration(125 * 60_000L))

        val recent = listOf(
            AgentRunEntity("w1", "s-t", "✈️ Check the server", "worker", AgentRunEntity.STATUS_RUNNING),
            AgentRunEntity("w2", "s-old", "⏰ Backup", "worker", AgentRunEntity.STATUS_FAILED),
            AgentRunEntity("w3", "s-gone", "⏰ Report", "worker", AgentRunEntity.STATUS_RUNNING)
        )
        val rows = RunsInbox.recentRows(recent, listOf(telegram))
        assertEquals("A run working now is not listed twice", listOf("w2", "w3"), rows.map { it.id })
        assertEquals("Failed", rows[0].label)
        assertEquals("Interrupted · continues on next start", rows[1].label)
    }

    @Test
    fun theReportComesFromTheSessionAndItsAuditEntries() = runBlocking {
        val session = repo.createNewSession("⏰ Backup")
        repo.saveMessage(session.id, ChatMessage(role = MessageRole.USER, text = "Back up my photos"))
        repo.saveMessage(session.id, ChatMessage(role = MessageRole.ASSISTANT, text = "Copied 120 photos; the call was refused."))
        repo.auditLogger.log(
            AuditEntry(
                runId = "r", sessionId = session.id, trigger = RunTrigger.SCHEDULE, mode = "UNATTENDED", preset = "UNATTENDED",
                tool = "execute_command", app = null, arguments = """{"command":"termux-telephony-call 0800"}""",
                decision = AuditDecision.AUTO_DENIED, rule = "unattended:calls", reason = "Calls are refused in unattended mode.",
                riskLevel = "HIGH", resultSummary = "", durationMs = 0, exitCode = null
            )
        )
        val row = RunsInbox.RecentRow("w", session.id, "⏰ Backup", AgentRunEntity.STATUS_DONE, "Done", now)
        val text = RunsInbox.report(row, repo, repo.auditLogger)
        assertTrue(text, text.contains("Goal: Back up my photos"))
        assertTrue(text.contains("Copied 120 photos"))
        assertTrue(text.contains("Refused by the unattended rules (1)"))
    }

    @Test
    fun stopAndReportButtonsWork() {
        val stopped = mutableListOf<String>()
        val recent = listOf(AgentRunEntity("w2", "s-old", "⏰ Backup", "worker", AgentRunEntity.STATUS_FAILED))
        compose.setContent {
            RunsInboxDialog(
                active = listOf(telegram),
                recentFlow = flowOf(recent),
                chats = null,
                audit = null,
                canOpenChat = true,
                onStop = { stopped += it },
                onStopAll = {},
                onOpenChat = {},
                onDismiss = {}
            )
        }
        compose.onNodeWithText("✈️ Check the server").assertExists()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("run-t"), stopped)
        compose.onNodeWithText("⏰ Backup").assertExists()
        compose.onNodeWithText("Report").performClick()
        compose.onNodeWithText("Hide report").assertExists()
        compose.onNodeWithText("Open chat").assertExists()
    }
}
