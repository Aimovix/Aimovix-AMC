package com.agent.mobile.ui.chat.components

import com.agent.mobile.agent.RunRegistry
import com.agent.mobile.agent.RunTrigger
import com.agent.mobile.audit.AuditLogger
import com.agent.mobile.audit.RunReport
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.entity.AgentRunEntity

/**
 * The facts behind the run overview (Teil E, E6): which agent runs work right now (chat,
 * background, Telegram, sub-agents) and which ran lately, with their status and report.
 * Pure functions, so the screen stays a thin view.
 */
object RunsInbox {

    data class ActiveRow(val runId: String, val title: String, val detail: String, val sessionId: String?)

    data class RecentRow(val id: String, val sessionId: String, val title: String, val status: String, val label: String, val updatedAt: Long)

    fun triggerLabel(trigger: String): String = when (trigger) {
        RunTrigger.USER -> "Chat"
        RunTrigger.SCHEDULE -> "Schedule"
        RunTrigger.HEARTBEAT -> "Heartbeat"
        RunTrigger.NOTIFICATION -> "Notification trigger"
        RunTrigger.TELEGRAM -> "Telegram"
        RunTrigger.SUBAGENT -> "Sub-agent"
        RunTrigger.RESUME -> "Continuation"
        else -> trigger.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    /** Running now, longest-running first. */
    fun activeRows(active: List<RunRegistry.ActiveRun>, now: Long): List<ActiveRow> =
        active.sortedBy { it.startedAt }.map { run ->
            val parts = listOfNotNull(
                triggerLabel(run.trigger),
                "unattended".takeIf { run.unattended },
                "running for ${duration(now - run.startedAt)}"
            )
            ActiveRow(run.runId, run.title.ifBlank { "Agent run" }, parts.joinToString(" · "), run.sessionId)
        }

    fun statusLabel(status: String, workingNow: Boolean): String = when (status) {
        AgentRunEntity.STATUS_DONE -> "Done"
        AgentRunEntity.STATUS_FAILED -> "Failed"
        AgentRunEntity.STATUS_PARKED -> "Waiting for approval"
        AgentRunEntity.STATUS_CONTINUED -> "Continued"
        AgentRunEntity.STATUS_RUNNING -> if (workingNow) "Working" else "Interrupted · continues on next start"
        else -> status.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Recent runs; a run that works right now is shown above, not twice. */
    fun recentRows(recent: List<AgentRunEntity>, active: List<RunRegistry.ActiveRun>): List<RecentRow> {
        val activeSessions = active.mapNotNull { it.sessionId }.toSet()
        return recent
            .filterNot { it.status == AgentRunEntity.STATUS_RUNNING && it.sessionId in activeSessions }
            .map { RecentRow(it.id, it.sessionId, it.title.ifBlank { "Agent run" }, it.status, statusLabel(it.status, false), it.updatedAt) }
    }

    /** The report of a past run, from its session and its audit entries. */
    suspend fun report(run: RecentRow, chats: ChatRepository, audit: AuditLogger): String {
        val messages = chats.getMessagesForSessionSync(run.sessionId)
        val goal = messages.firstOrNull { it.role == MessageRole.USER }?.text.orEmpty()
        val summary = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.text.isNotBlank() && it.toolCalls.isEmpty() && it.toolCall == null
        }?.text ?: "No final reply."
        val events = audit.forSession(run.sessionId)
        return RunReport.build(run.title, goal, summary, run.status == AgentRunEntity.STATUS_DONE, events).toText()
    }
}
