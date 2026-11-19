package com.agent.mobile.agent

import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.storage.db.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The list_runs answer (#23): latest runs with status and the last reply of their session. */
object RunHistory {
    suspend fun format(db: AppDatabase, limit: Int): String {
        val runs = db.agentQueueDao().recentRuns(limit.coerceIn(1, 30))
        if (runs.isEmpty()) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = mutableListOf<String>()
        for (run in runs) {
            val outcome = db.chatMessageDao().getMessagesForSessionSync(run.sessionId)
                .lastOrNull { it.role == MessageRole.ASSISTANT.name && it.text.isNotBlank() && it.toolCallJson == null }
                ?.text?.replace(Regex("\\s+"), " ")?.take(160)
            lines += buildString {
                append("- ").append(fmt.format(Date(run.updatedAt))).append(' ').append(run.title)
                append(": ").append(run.status)
                append(" (").append(run.kind).append(", session ").append(run.sessionId.take(8)).append(')')
                if (!outcome.isNullOrBlank()) append(" — ").append(outcome)
            }
        }
        return lines.joinToString("\n")
    }
}
