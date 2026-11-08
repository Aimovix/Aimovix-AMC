package com.agent.mobile.ui.settings.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import com.agent.mobile.agent.AgentPrompts
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.agent.MemoryStore
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.service.scheduler.SchedulerManager
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Settings for autonomous operation: step budget, heartbeat (periodic proactive check-ins),
 * long-term memory and the agent's own scheduled background tasks.
 */
@Composable
fun AutonomyCard(
    agentEngine: AutonomousAgentEngine,
    preferenceManager: PreferenceManager,
    memoryStore: MemoryStore?,
    schedulerManager: SchedulerManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val maxSteps by agentEngine.maxSteps.collectAsState()

    var heartbeatEnabled by remember { mutableStateOf(preferenceManager.loadHeartbeatEnabled()) }
    var heartbeatInterval by remember { mutableLongStateOf(preferenceManager.loadHeartbeatIntervalMinutes()) }
    var checklist by remember { mutableStateOf(preferenceManager.loadHeartbeatChecklist()) }
    var memoryText by remember { mutableStateOf("") }
    var memoryLoaded by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }

    LaunchedEffect(memoryStore) {
        memoryText = withContext(Dispatchers.IO) { memoryStore?.entries()?.joinToString("\n").orEmpty() }
        memoryLoaded = true
    }

    val agentTasks by remember { schedulerManager.getAgentTasksFlow() }.collectAsState(initial = emptyList())
    val activeTasks = agentTasks.filter { !it.state.isFinished }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentPrimary,
        unfocusedBorderColor = BorderSubtle,
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedContainerColor = DarkSurface,
        unfocusedContainerColor = DarkSurface
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Autonomy", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite))
                    Text(
                        "How independently the agent works, remembers and acts in the background",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
            }

            // --- Step budget ---------------------------------------------------------------
            Column {
                Text(
                    "Max. steps per task: $maxSteps",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "How many tool rounds the agent may use before it stops and asks you to continue.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                )
                Slider(
                    value = maxSteps.toFloat(),
                    onValueChange = { agentEngine.setMaxSteps(it.roundToInt()) },
                    valueRange = PreferenceManager.MIN_MAX_STEPS.toFloat()..PreferenceManager.MAX_MAX_STEPS.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
                )
            }

            HorizontalDivider(color = BorderSubtle)

            // --- Heartbeat -----------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Heartbeat", style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
                    Text(
                        "The agent wakes up periodically, works through your checklist and only notifies you when something needs attention.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
                Switch(
                    checked = heartbeatEnabled,
                    onCheckedChange = { enabled ->
                        heartbeatEnabled = enabled
                        preferenceManager.saveHeartbeatEnabled(enabled)
                        if (enabled) schedulerManager.scheduleHeartbeat(heartbeatInterval) else schedulerManager.cancelHeartbeat()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentPrimary)
                )
            }
            if (heartbeatEnabled) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(15L to "15 min", 30L to "30 min", 60L to "1 h", 120L to "2 h", 240L to "4 h", 720L to "12 h").forEach { (minutes, label) ->
                        FilterChip(
                            selected = heartbeatInterval == minutes,
                            onClick = {
                                heartbeatInterval = minutes
                                preferenceManager.saveHeartbeatIntervalMinutes(minutes)
                                schedulerManager.scheduleHeartbeat(minutes)
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = AccentPrimary,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
                OutlinedTextField(
                    value = checklist,
                    onValueChange = { checklist = it },
                    label = { Text("Heartbeat checklist", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    colors = fieldColors
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            preferenceManager.saveHeartbeatChecklist(checklist.trim().ifEmpty { AgentPrompts.DEFAULT_HEARTBEAT_CHECKLIST })
                            Toast.makeText(context, "Checklist saved", Toast.LENGTH_SHORT).show()
                        },
                        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                    ) { Text("Save checklist", fontSize = 12.sp, color = TextWhite) }
                    OutlinedButton(
                        onClick = {
                            preferenceManager.saveHeartbeatChecklist(checklist.trim().ifEmpty { AgentPrompts.DEFAULT_HEARTBEAT_CHECKLIST })
                            schedulerManager.runHeartbeatNow()
                            Toast.makeText(context, "Heartbeat started in the background", Toast.LENGTH_SHORT).show()
                        },
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
                        Spacer(Modifier.width(4.dp))
                        Text("Run now", fontSize = 12.sp, color = TextWhite)
                    }
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // --- Scheduled agent tasks -----------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Scheduled agent tasks (${activeTasks.size})",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold)
                )
                if (activeTasks.isEmpty()) {
                    Text(
                        "None yet. Ask the agent in chat, e.g. \"Remind me every morning at 8 about my meds\" or \"Check the weather every 2 hours\".",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                } else {
                    activeTasks.forEach { info -> ScheduledTaskRow(info) { schedulerManager.cancelWorkflowById(info.id.toString()) } }
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // --- Long-term memory ----------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    val count = memoryText.lines().count { it.isNotBlank() }
                    Text(
                        "Long-term memory ($count entries)",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "Facts the agent saved about you. It sees them in every conversation.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
                TextButton(onClick = { showMemory = !showMemory }) {
                    Text(if (showMemory) "Hide" else "Edit", color = AccentPrimary, fontSize = 12.sp)
                }
            }
            if (showMemory && memoryLoaded) {
                OutlinedTextField(
                    value = memoryText,
                    onValueChange = { memoryText = it },
                    placeholder = { Text("- [2026-01-01] The user's name is …", fontSize = 11.sp, color = TextMuted) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    colors = fieldColors
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) { memoryStore?.replaceAll(memoryText) }
                                memoryText = withContext(Dispatchers.IO) { memoryStore?.entries()?.joinToString("\n").orEmpty() }
                                Toast.makeText(context, "Memory saved", Toast.LENGTH_SHORT).show()
                            }
                        },
                        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                    ) { Text("Save", fontSize = 12.sp, color = TextWhite) }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) { memoryStore?.clear() }
                                memoryText = ""
                                Toast.makeText(context, "Memory cleared", Toast.LENGTH_SHORT).show()
                            }
                        },
                        border = BorderStroke(1.dp, RedEmergency.copy(alpha = 0.5f))
                    ) { Text("Clear all", fontSize = 12.sp, color = RedEmergency) }
                }
            }
        }
    }
}

@Composable
private fun ScheduledTaskRow(info: WorkInfo, onCancel: () -> Unit) {
    val title = info.tags.firstOrNull { it.startsWith("amc_title:") }?.removePrefix("amc_title:") ?: "Task"
    val repeat = info.tags.firstOrNull { it.startsWith("amc_repeat:") }?.removePrefix("amc_repeat:")
    Surface(
        color = DarkSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
                Text(
                    buildString {
                        append(info.state.name.lowercase())
                        if (repeat != null) append(" · every ").append(repeat).append(" min")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.5.sp)
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel task", tint = RedEmergency, modifier = Modifier.size(18.dp))
            }
        }
    }
}
