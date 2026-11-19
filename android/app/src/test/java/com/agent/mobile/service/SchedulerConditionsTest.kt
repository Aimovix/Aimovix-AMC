package com.agent.mobile.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.RunHistory
import com.agent.mobile.agent.TaskConditions
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.service.scheduler.SchedulerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #23: tasks can wait for Wi-Fi, charging and battery; the agent sees the outcome of earlier runs. */
@RunWith(RobolectricTestRunner::class)
class SchedulerConditionsTest {

    @Test
    fun conditionsBecomeWorkManagerConstraints() {
        val plain = SchedulerManager.constraintsFor(TaskConditions())
        assertEquals(NetworkType.CONNECTED, plain.requiredNetworkType)
        assertFalse(plain.requiresCharging())
        val backup = SchedulerManager.constraintsFor(TaskConditions(requiresWifi = true, requiresCharging = true, requiresBatteryNotLow = true))
        assertEquals(NetworkType.UNMETERED, backup.requiredNetworkType)
        assertTrue(backup.requiresCharging())
        assertTrue(backup.requiresBatteryNotLow())

        val call = ToolCall(name = AgentTools.SCHEDULE_TASK, arguments = mapOf("title" to "Backup", "prompt" to "x", "requires_wifi" to "true", "requires_charging" to "true"))
        assertEquals(TaskConditions(requiresWifi = true, requiresCharging = true), AgentTools.conditionsOf(call.arguments))
        assertTrue(AgentTools.describe(call).contains("when Wi-Fi, charging"))
    }

    @Test
    fun runHistoryListsStatusAndOutcomeIncludingFailures() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = ChatRepository(db)
            val ok = repo.createNewSession("⏰ Backup")
            val bad = repo.createNewSession("⏰ Sync")
            repo.saveMessage(ok.id, ChatMessage(role = MessageRole.ASSISTANT, text = "Backed up 12 files."))
            repo.saveMessage(bad.id, ChatMessage(role = MessageRole.ASSISTANT, text = "Could not reach the server."))
            db.agentQueueDao().upsertRun(AgentRunEntity("w1", ok.id, "⏰ Backup", "task", AgentRunEntity.STATUS_DONE, updatedAt = 1_000))
            db.agentQueueDao().upsertRun(AgentRunEntity("w2", bad.id, "⏰ Sync", "task", AgentRunEntity.STATUS_FAILED, updatedAt = 2_000))
            val text = RunHistory.format(db, 10)
            val lines = text.lines()
            assertEquals(2, lines.size)
            assertTrue("Newest first", lines[0].contains("⏰ Sync: FAILED"))
            assertTrue(lines[0].contains("Could not reach the server."))
            assertTrue(lines[1].contains("⏰ Backup: DONE"))
        } finally {
            db.close()
        }
    }
}
