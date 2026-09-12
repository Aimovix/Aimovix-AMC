package com.agent.mobile.ui.settings.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.service.scheduler.SchedulerManager
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun CronSyncCard(
    bridgeClient: TermuxBridgeClient,
    schedulerManager: SchedulerManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var crontabContent by remember { mutableStateOf("") }
    var isLoadingCron by remember { mutableStateOf(false) }
    var isSavingCron by remember { mutableStateOf(false) }
    var cronDaemonStatus by remember { mutableStateOf<String?>("UNKNOWN") }

    LaunchedEffect(Unit) {
        cronDaemonStatus = bridgeClient.checkCronStatus()
    }

    var workflowTitle by remember { mutableStateOf("Morning routine") }
    var workflowCommand by remember { mutableStateOf("termux-battery-status") }
    var repeatHours by remember { mutableStateOf("6") }
    var requireWifi by remember { mutableStateOf(false) }
    var requireCharging by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Scheduler & Cron",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 14.sp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        isLoadingCron = true
                        coroutineScope.launch {
                            try {
                                crontabContent = bridgeClient.getCrontab()
                                cronDaemonStatus = bridgeClient.checkCronStatus()
                                Toast.makeText(context, "Crontab loaded from Termux", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isLoadingCron = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated, contentColor = AccentPrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Load", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Section 1: Termux Crontab
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Termux Crontab (Linux Cron-Daemon)",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                    )
                    val (statusColor, statusText) = when (cronDaemonStatus) {
                        "RUNNING" -> Pair(AccentPrimary, "crond running")
                        "STOPPED" -> Pair(YellowWarning, "crond stopped")
                        "NOT_INSTALLED" -> Pair(RedEmergency, "crond not installed")
                        else -> Pair(TextMuted, "crond unknown")
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    )
                }

                if (cronDaemonStatus == "STOPPED") {
                    Surface(
                        color = YellowWarning.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, YellowWarning.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ The cron daemon 'crond' is not running in Termux. Start it with: crond",
                            color = YellowWarning,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else if (cronDaemonStatus == "NOT_INSTALLED") {
                    Surface(
                        color = RedEmergency.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, RedEmergency.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ The 'cronie' package is not installed in Termux. Install with: pkg install cronie",
                            color = RedEmergency,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = crontabContent,
                    onValueChange = { crontabContent = it },
                    placeholder = { Text("# Example:\n0 8 * * * termux-battery-status\n*/30 * * * * python script.py", fontSize = 11.sp, color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = BorderSubtle,
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface
                    )
                )

                Text(
                    "Cron runs independently of AMC approval checks and the chat stop button. Review every entry before syncing.",
                    color = TextMuted, fontSize = 12.sp
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            isSavingCron = true
                            coroutineScope.launch {
                                try {
                                    val res = bridgeClient.setCrontab(crontabContent)
                                    if (res.exitCode == 0) {
                                        Toast.makeText(context, "✅ Crontab synchronized", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "⚠️ Error: ${res.stderr}", Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    isSavingCron = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Sync crontab", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // Section 2: Android WorkManager Automation
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Android WorkManager background automation",
                    style = MaterialTheme.typography.labelSmall.copy(color = AccentPrimary, fontWeight = FontWeight.Bold)
                )

                OutlinedTextField(
                    value = workflowTitle,
                    onValueChange = { workflowTitle = it },
                    label = { Text("Workflow name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface
                    )
                )

                OutlinedTextField(
                    value = workflowCommand,
                    onValueChange = { workflowCommand = it },
                    label = { Text("Shell command") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = requireWifi,
                            onCheckedChange = { requireWifi = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                        Text("Wi-Fi only", color = TextSecondary, fontSize = 12.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = requireCharging,
                            onCheckedChange = { requireCharging = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                        Text("Only while charging", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val interval = repeatHours.toLongOrNull() ?: 6L
                            schedulerManager.schedulePeriodicWorkflow(
                                title = workflowTitle.ifBlank { "Periodic Workflow" },
                                command = workflowCommand.ifBlank { "termux-battery-status" },
                                repeatIntervalHours = interval,
                                requiresWifi = requireWifi,
                                requiresCharging = requireCharging,
                                requiresBatteryNotLow = true
                            )
                            Toast.makeText(context, "✅ Scheduled: '$workflowTitle' every ${interval}h", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground)
                    ) {
                        Text(
                            text = "Schedule recurring",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            softWrap = true,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Button(
                        onClick = {
                            schedulerManager.scheduleOneTimeWorkflow(
                                title = workflowTitle.ifBlank { "OneTime Workflow" },
                                command = workflowCommand.ifBlank { "termux-battery-status" },
                                requiresWifi = requireWifi,
                                requiresCharging = requireCharging,
                                requiresBatteryNotLow = true
                            )
                            Toast.makeText(context, "✅ One-time task queued", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated, contentColor = TextWhite)
                    ) {
                        Text(
                            text = "Queue now",
                            fontSize = 11.sp,
                            maxLines = 2,
                            softWrap = true,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                Button(
                    onClick = {
                        schedulerManager.cancelAllWorkflowsByTag("amc_workflow")
                        Toast.makeText(context, "All scheduled automations cancelled", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedEmergency.copy(alpha = 0.15f), contentColor = RedEmergency)
                ) {
                    Text(
                        text = "Cancel all scheduled automations",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
