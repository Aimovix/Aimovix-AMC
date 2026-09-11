package com.agent.mobile.data.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.dao.ChatMessageDao
import com.agent.mobile.data.storage.db.dao.ChatSessionDao
import com.agent.mobile.data.storage.db.dao.CommandAuditDao
import com.agent.mobile.data.storage.db.entity.ChatMessageEntity
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class RoomDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: ChatSessionDao
    private lateinit var messageDao: ChatMessageDao
    private lateinit var auditDao: CommandAuditDao
    private lateinit var repository: ChatRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = db.chatSessionDao()
        messageDao = db.chatMessageDao()
        auditDao = db.commandAuditDao()
        repository = ChatRepository(db)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testChatSessionCrudAndMetrics() = runBlocking {
        val session = ChatSession(
            id = "test-session-1",
            title = "Initial Title",
            totalPromptTokens = 100,
            totalCompletionTokens = 50,
            estimatedCostUsd = 0.001
        )
        sessionDao.insertSession(session)

        val fetched = sessionDao.getSessionById("test-session-1")
        assertNotNull(fetched)
        assertEquals("Initial Title", fetched?.title)
        assertEquals(100, fetched?.totalPromptTokens)

        // Update Title
        sessionDao.updateTitle("test-session-1", "Updated Title")
        assertEquals("Updated Title", sessionDao.getSessionById("test-session-1")?.title)

        // Add Metrics
        sessionDao.addMetrics("test-session-1", 50, 25, 0.0005)
        val withMetrics = sessionDao.getSessionById("test-session-1")
        assertEquals(150, withMetrics?.totalPromptTokens)
        assertEquals(75, withMetrics?.totalCompletionTokens)
        assertEquals(0.0015, withMetrics?.estimatedCostUsd ?: 0.0, 0.00001)

        // Flow list
        val all = sessionDao.getAllSessions().first()
        assertEquals(1, all.size)
    }

    @Test
    fun testChatMessageInsertAndFulltextSearch() = runBlocking {
        val session = ChatSession(id = "sess-search", title = "Search Session")
        sessionDao.insertSession(session)

        val msg1 = ChatMessageEntity(
            id = "m1",
            sessionId = "sess-search",
            role = MessageRole.USER.name,
            text = "Compile the Rust project with cargo build",
            status = MessageStatus.COMPLETED.name
        )
        val msg2 = ChatMessageEntity(
            id = "m2",
            sessionId = "sess-search",
            role = MessageRole.ASSISTANT.name,
            text = "Run command",
            streamingTerminalOutput = "Compiling aimovix-core v1.0.0 finished in 2.3s",
            status = MessageStatus.COMPLETED.name
        )
        messageDao.insertMessage(msg1)
        messageDao.insertMessage(msg2)

        val messages = messageDao.getMessagesForSessionSync("sess-search")
        assertEquals(2, messages.size)

        // Search across text
        val resultsRust = messageDao.searchMessages("Rust")
        assertEquals(1, resultsRust.size)
        assertEquals("m1", resultsRust[0].id)

        // Search across terminal output
        val resultsCargo = messageDao.searchMessages("aimovix-core")
        assertEquals(1, resultsCargo.size)
        assertEquals("m2", resultsCargo[0].id)
    }

    @Test
    fun testCascadeDeleteSessionRemovesMessages() = runBlocking {
        val session = ChatSession(id = "sess-cascade", title = "To Delete")
        sessionDao.insertSession(session)

        val msg = ChatMessageEntity(
            id = "m-cascade",
            sessionId = "sess-cascade",
            role = MessageRole.USER.name,
            text = "Should be deleted",
            status = MessageStatus.COMPLETED.name
        )
        messageDao.insertMessage(msg)
        assertEquals(1, messageDao.getMessagesForSessionSync("sess-cascade").size)

        sessionDao.deleteSessionById("sess-cascade")
        assertNull(sessionDao.getSessionById("sess-cascade"))
        assertEquals(0, messageDao.getMessagesForSessionSync("sess-cascade").size)
    }

    @Test
    fun testCommandAuditLoggingAndFiltering() = runBlocking {
        val audit1 = CommandAuditEntity(
            id = 0L,
            command = "cat /proc/cpuinfo",
            riskLevel = "LOW",
            riskReason = "Read only",
            wasApproved = true,
            exitCode = 0
        )
        val audit2 = CommandAuditEntity(
            id = 0L,
            command = "rm -rf /sdcard/temp",
            riskLevel = "HIGH",
            riskReason = "Recursive delete",
            wasApproved = false,
            exitCode = 1
        )
        auditDao.insertAudit(audit1)
        auditDao.insertAudit(audit2)

        val allAudits = auditDao.getAllAudits().first()
        assertEquals(2, allAudits.size)

        val highRiskOnly = auditDao.getAuditsByRiskLevel("HIGH").first()
        assertEquals(1, highRiskOnly.size)
        assertEquals("HIGH", highRiskOnly[0].riskLevel)

        val searched = auditDao.searchAudits("cpuinfo")
        assertEquals(1, searched.size)
        assertEquals("LOW", searched[0].riskLevel)
    }

    @Test
    fun testChatRepositoryTitleGenerationAndExport() = runBlocking {
        // Title generation
        val generatedTitle = repository.generateConciseTitle("Can you write a script to automate Termux backups?")
        assertFalse(generatedTitle.isBlank())
        assertTrue(generatedTitle.length <= 45)

        // Setup session for export
        val session = repository.createNewSession("Export Test Session")
        val domainMessages = listOf(
            ChatMessage(
                role = MessageRole.USER,
                text = "Hello Agent!",
                status = MessageStatus.COMPLETED
            )
        )

        // Export Markdown
        val md = repository.exportToMarkdown(session, domainMessages)
        assertTrue(md.contains("# Export Test Session"))
        assertTrue(md.contains("User"))
        assertTrue(md.contains("Hello Agent!"))

        // Export JSON
        val json = repository.exportToJson(session, domainMessages)
        assertTrue(json.contains("\"title\": \"Export Test Session\""))
        assertTrue(json.contains("\"text\": \"Hello Agent!\""))
    }
}
