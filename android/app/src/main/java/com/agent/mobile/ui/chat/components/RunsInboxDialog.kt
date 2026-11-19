package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agent.mobile.agent.RunRegistry
import com.agent.mobile.audit.AuditLogger
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Date

/**
 * Overview of every agent run (Teil E, E6): what works right now, with Stop, and what ran
 * lately, with its status, its report and a way to open its chat.
 */
@Composable
fun RunsInboxDialog(
    active: List<RunRegistry.ActiveRun>,
    recentFlow: Flow<List<AgentRunEntity>>,
    chats: ChatRepository?,
    audit: AuditLogger?,
    canOpenChat: Boolean,
    onStop: (String) -> Unit,
    onStopAll: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val recent by recentFlow.collectAsState(initial = emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val activeRows = RunsInbox.activeRows(active, now)
    val recentRows = RunsInbox.recentRows(recent, active)
    var openReport by remember { mutableStateOf<String?>(null) }
    var reportText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openReport) {
        reportText = null
        val row = recentRows.firstOrNull { it.id == openReport } ?: return@LaunchedEffect
        if (chats != null && audit != null) {
            reportText = runCatching { RunsInbox.report(row, chats, audit) }.getOrElse { "The report could not be built." }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("Agent runs", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                item {
                    Text("Running now", style = MaterialTheme.typography.titleSmall, color = TextSecondary, modifier = Modifier.semantics { heading() })
                }
                if (activeRows.isEmpty()) {
                    item { Text("No agent is working right now.", style = MaterialTheme.typography.bodyMedium, color = TextMuted) }
                }
                items(activeRows, key = { "a-" + it.runId }) { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = TextWhite)
                            Text(row.detail, style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        }
                        OutlinedButton(onClick = { onStop(row.runId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
                    }
                }
                if (activeRows.size > 1) {
                    item {
                        OutlinedButton(onClick = onStopAll, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Stop all ${activeRows.size} runs")
                        }
                    }
                }
                item {
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(Modifier.height(6.dp))
                    Text("Recent", style = MaterialTheme.typography.titleSmall, color = TextSecondary, modifier = Modifier.semantics { heading() })
                }
                if (recentRows.isEmpty()) {
                    item { Text("No background runs yet.", style = MaterialTheme.typography.bodyMedium, color = TextMuted) }
                }
                items(recentRows, key = { "r-" + it.id }) { row ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge, color = TextWhite)
                        Text(
                            row.label + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.updatedAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = when (row.status) {
                                AgentRunEntity.STATUS_FAILED -> RedText
                                AgentRunEntity.STATUS_PARKED -> YellowWarning
                                else -> TextMuted
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { openReport = if (openReport == row.id) null else row.id },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(if (openReport == row.id) "Hide report" else "Report") }
                            if (canOpenChat) {
                                TextButton(onClick = { onOpenChat(row.sessionId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open chat") }
                            }
                        }
                        if (openReport == row.id) {
                            Text(
                                reportText ?: "Loading…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** For previews and tests without a database. */
internal val NoRecentRuns: Flow<List<AgentRunEntity>> = flowOf(emptyList())
