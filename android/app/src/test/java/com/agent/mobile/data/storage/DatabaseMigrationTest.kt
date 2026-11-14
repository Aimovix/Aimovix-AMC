package com.agent.mobile.data.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.data.storage.db.entity.PendingApprovalEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Updating AMC must keep every chat, message and audit entry. Builds a version 1 database with
 * the original tables, migrates it with the shipped migrations and lets Room validate the schema.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    /** CREATE statements of the version 1 tables (unchanged in version 2) from the exported schema. */
    private fun version1Statements(): List<String> {
        val schema = JSONObject(File("schemas/com.agent.mobile.data.storage.db.AppDatabase/2.json").readText())
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val v1Tables = setOf("chat_sessions", "chat_messages", "command_audits")
        val out = mutableListOf<String>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            if (table !in v1Tables) continue
            out += e.getString("createSql").replace("\${TABLE_NAME}", table)
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) out += indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
        }
        assertEquals(3, v1Tables.size)
        return out
    }

    @Test
    fun migrationFrom1To2KeepsChatsAndAddsTheQueueTables() = runBlocking {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        version1Statements().forEach { db.execSQL(it) }
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO chat_sessions (id, title, createdAt, updatedAt, modelProvider, modelName, totalPromptTokens, " +
                    "totalCompletionTokens, estimatedCostUsd) VALUES ('s1', 'Old chat', 1, 2, 'OPENAI', 'm', 0, 0, 0.0)"
            )
            execSQL(
                "INSERT INTO chat_messages (id, sessionId, role, text, streamingTerminalOutput, timestamp, status) " +
                    "VALUES ('m1', 's1', 'USER', 'hello', '', 3, 'COMPLETED')"
            )
            execSQL(
                "INSERT INTO command_audits (sessionId, command, riskLevel, riskReason, executionDurationMs, exitCode, " +
                    "stdout, stderr, timestamp, wasApproved) VALUES ('s1', 'ls', 'LOW', 'r', 1, 0, '', '', 4, 1)"
            )
        }
        helper.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening validates every table against the entities (throws on a mismatch).
            assertEquals("Old chat", db.chatSessionDao().getSessionById("s1")?.title)
            assertEquals("hello", db.chatMessageDao().getMessagesForSessionSync("s1").single().text)
            assertEquals("ls", db.commandAuditDao().getAllAuditsSync().single().command)

            val queue = db.agentQueueDao()
            queue.insertApproval(
                PendingApprovalEntity(
                    id = "a1", sessionId = "s1", runTitle = "t", toolName = "execute_command", argumentsJson = "{}",
                    description = "d", riskLevel = "HIGH", riskReason = "r", fingerprint = "f"
                )
            )
            assertEquals(1, queue.decideApproval("a1", PendingApprovalEntity.STATUS_APPROVED, 5))
            assertEquals("The first answer wins", 0, queue.decideApproval("a1", PendingApprovalEntity.STATUS_REJECTED, 6))
            assertEquals(PendingApprovalEntity.STATUS_APPROVED, queue.getApproval("a1")?.status)
            queue.upsertRun(AgentRunEntity(id = "w1", sessionId = "s1", title = "t", kind = "task", status = AgentRunEntity.STATUS_RUNNING))
            assertEquals(AgentRunEntity.STATUS_RUNNING, queue.getRun("w1")?.status)
        } finally {
            db.close()
        }
    }
}
