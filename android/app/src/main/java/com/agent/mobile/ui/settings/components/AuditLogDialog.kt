package com.agent.mobile.ui.settings.components

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.mobile.audit.AuditDecision
import com.agent.mobile.audit.AuditExport
import com.agent.mobile.audit.ChainCheck
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Decision filters of the timeline ("User" covers approved, rejected, parked and skipped). */
internal enum class AuditFilter(val label: String, val decisions: Set<String>?) {
    ALL("All", null),
    ALLOWED("Allowed", setOf(AuditDecision.ALLOWED.name)),
    AUTO_ALLOWED("Auto-allowed", setOf(AuditDecision.AUTO_ALLOWED.name)),
    AUTO_DENIED("Auto-denied", setOf(AuditDecision.AUTO_DENIED.name)),
    BLOCKED("Blocked", setOf(AuditDecision.BLOCKED.name)),
    USER("User", setOf(AuditDecision.APPROVED.name, AuditDecision.REJECTED.name, AuditDecision.PARKED.name, AuditDecision.SKIPPED.name)),
    OTHER("Not run", setOf(AuditDecision.NOT_RUN.name, AuditDecision.INFO.name))
}

/** Filters [events] by decision, tool and a free text (app, tool, arguments, reason, result). */
internal fun filterAudit(events: List<AuditEventEntity>, filter: AuditFilter, tool: String?, query: String): List<AuditEventEntity> =
    events.filter { e ->
        (filter.decisions == null || e.decision in filter.decisions) &&
            (tool == null || e.tool == tool) &&
            (query.isBlank() || listOf(e.app.orEmpty(), e.tool, e.arguments, e.reason, e.resultSummary, e.rule)
                .any { it.contains(query.trim(), ignoreCase = true) })
    }

private fun decisionColor(decision: String): Color = when (decision) {
    AuditDecision.ALLOWED.name, AuditDecision.APPROVED.name -> AccentPrimary
    AuditDecision.AUTO_ALLOWED.name -> AccentSecondary
    AuditDecision.AUTO_DENIED.name, AuditDecision.REJECTED.name, AuditDecision.SKIPPED.name -> YellowWarning
    AuditDecision.BLOCKED.name -> RedText
    else -> TextMuted
}

/**
 * The audit log: a timeline per run with filters by decision, tool and app, a check of the hash
 * chain, retention and exports (JSONL, Markdown file, or Markdown through the share sheet).
 */
@Composable
fun AuditLogDialog(
    chatRepository: ChatRepository?,
    onDismiss: () -> Unit,
    preferenceManager: PreferenceManager? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logger = chatRepository?.auditLogger
    var limit by remember { mutableIntStateOf(500) }
    val events by remember(limit, logger) { logger?.recent(limit) ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(AuditFilter.ALL) }
    var toolFilter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<ChainCheck?>(null) }
    var retention by remember { mutableIntStateOf(preferenceManager?.loadAuditRetentionDays() ?: 90) }
    var pendingExport by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val filtered = remember(events, filter, toolFilter, query) { filterAudit(events, filter, toolFilter, query) }
    val runs = remember(filtered) { AuditExport.groupByRun(filtered) }
    val tools = remember(events) { events.map { it.tool }.distinct().sorted() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && chatRepository != null) {
            scope.launch {
                val ok = chatRepository.saveExportToUri(context, uri, pendingExport)
                Toast.makeText(context, if (ok) "Audit log exported" else "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Audit log", style = MaterialTheme.typography.titleLarge.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
                        Text(
                            "Every tool call of every run, with the decision and the rule. Tamper-evident: entries are chained by hashes.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close audit log", tint = TextMuted) }
                }

                // Chain check and exports.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { scope.launch { check = logger?.verify() } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Verify chain", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pendingExport = AuditExport.toJsonl(logger?.since(0L).orEmpty())
                                exportLauncher.launch("amc-audit.jsonl")
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Export JSONL", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val all = logger?.since(0L).orEmpty()
                                val md = AuditExport.toMarkdown(all, check ?: logger?.verify())
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "AMC audit log")
                                    putExtra(Intent.EXTRA_TEXT, md.take(200_000))
                                }
                                context.startActivity(Intent.createChooser(send, "Share audit log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Share", fontSize = 12.sp) }
                }
                check?.let {
                    Text(
                        it.summary,
                        style = MaterialTheme.typography.bodySmall.copy(color = if (it.ok) AccentPrimary else RedText, fontWeight = FontWeight.SemiBold)
                    )
                }

                // Retention.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Keep", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                    PreferenceManager.AUDIT_RETENTION_CHOICES.forEach { days ->
                        FilterChip(
                            selected = retention == days,
                            onClick = {
                                retention = days
                                preferenceManager?.saveAuditRetentionDays(days)
                                scope.launch {
                                    val removed = logger?.rotate(days) ?: 0
                                    if (removed > 0) Toast.makeText(context, "Removed $removed old entries", Toast.LENGTH_SHORT).show()
                                }
                            },
                            label = { Text("$days days", fontSize = 12.sp) }
                        )
                    }
                }

                var daily by remember { mutableStateOf(preferenceManager?.loadDailyReport() ?: false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(
                        "Daily summary of all runs (notification, Telegram if delivery is on)",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = daily,
                        onCheckedChange = { on ->
                            daily = on
                            preferenceManager?.saveDailyReport(on)
                            val scheduler = com.agent.mobile.AgentRuntime.get(context).scheduler
                            if (on) scheduler.scheduleDailyReport() else scheduler.cancelDailyReport()
                        },
                        modifier = Modifier.semantics { contentDescription = "Daily summary" }
                    )
                }

                // Filters.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Decision: ${filter.label}", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            AuditFilter.entries.forEach { f ->
                                DropdownMenuItem(text = { Text(f.label) }, onClick = { filter = f; open = false })
                            }
                        }
                    }
                    var toolsOpen by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { toolsOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Tool: ${toolFilter ?: "all"}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                            DropdownMenuItem(text = { Text("All tools") }, onClick = { toolFilter = null; toolsOpen = false })
                            tools.forEach { t -> DropdownMenuItem(text = { Text(t) }, onClick = { toolFilter = t; toolsOpen = false }) }
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search app, command, reason…", fontSize = 12.sp, color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextWhite)
                )

                if (runs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (events.isEmpty()) "No entries yet. Every tool call of the agent appears here." else "No entries match the filters.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted)
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(runs, key = { it.runId }) { run ->
                            val isOpen = expanded[run.runId] ?: (runs.size <= 3)
                            AuditRunCard(run, isOpen) { expanded[run.runId] = !isOpen }
                        }
                        if (events.size >= limit) {
                            item {
                                TextButton(onClick = { limit += 500 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("Load older entries")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRunCard(run: AuditExport.RunGroup, open: Boolean, onToggle: () -> Unit) {
    Surface(color = DarkSurface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderSubtle)) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggle)
                    .semantics { contentDescription = "Run ${run.trigger.lowercase()} with ${run.events.size} entries, tap to ${if (open) "collapse" else "expand"}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${run.trigger.lowercase().replaceFirstChar { it.uppercase() }} · ${run.first.mode.lowercase()} · ${run.first.preset.lowercase()}",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "${AuditExport.time(run.first.timestamp)} · ${run.events.size} entries · " +
                            "${run.count(AuditDecision.AUTO_DENIED)} auto-denied · ${run.count(AuditDecision.BLOCKED)} blocked",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = TextMuted)
            }
            if (open) {
                run.events.forEach { e -> AuditEntryRow(e) }
            }
        }
    }
}

@Composable
private fun AuditEntryRow(e: AuditEventEntity) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AuditExport.time(e.timestamp).substring(11), style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontFamily = FontFamily.Monospace))
            Text(e.tool, style = MaterialTheme.typography.labelMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
            Text(
                AuditDecision.of(e.decision)?.label ?: e.decision,
                style = MaterialTheme.typography.labelSmall.copy(color = decisionColor(e.decision), fontWeight = FontWeight.Bold)
            )
            if (e.exitCode != null) Text("exit ${e.exitCode} · ${e.durationMs} ms", style = MaterialTheme.typography.labelSmall.copy(color = TextMuted))
        }
        if (e.app != null) Text("App: ${e.app}", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
        Text(e.arguments, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
        Text("${e.rule}: ${e.reason}", maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
        if (e.resultSummary.isNotBlank()) {
            Text("→ ${e.resultSummary}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
        }
    }
}
