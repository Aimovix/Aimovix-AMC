package com.agent.mobile.ui.settings.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AuditLogDialog(
    chatRepository: ChatRepository?,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val allAudits by (chatRepository?.allAudits ?: remember { kotlinx.coroutines.flow.flowOf(emptyList()) }).collectAsState(initial = emptyList())
    var selectedRiskFilter by remember { mutableStateOf<String?>("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd.MM", Locale.getDefault()) }

    val filteredAudits = remember(allAudits, selectedRiskFilter, searchQuery) {
        allAudits.filter { audit ->
            val matchesRisk = selectedRiskFilter == "ALL" || audit.riskLevel == selectedRiskFilter
            val matchesSearch = searchQuery.isBlank() || audit.command.contains(searchQuery, ignoreCase = true) || audit.riskReason.contains(searchQuery, ignoreCase = true)
            matchesRisk && matchesSearch
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sicherheits-Audit-Log",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 14.5.sp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Row {
                        IconButton(
                            onClick = {
                                coroutineScope.launch { chatRepository?.clearAudits() }
                            }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Leeren", tint = TextMuted)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Schließen", tint = TextMuted)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Befehle oder Begründungen durchsuchen...", fontSize = 12.sp, color = TextMuted) },
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

                Spacer(modifier = Modifier.height(8.dp))

                // Risk Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val filters = listOf("ALL", RiskLevel.BLOCKED.name, RiskLevel.HIGH.name, RiskLevel.MEDIUM.name, RiskLevel.LOW.name)
                    filters.forEach { filter ->
                        val isChosen = selectedRiskFilter == filter
                        FilterChip(
                            selected = isChosen,
                            onClick = { selectedRiskFilter = filter },
                            label = { Text(filter, fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = AccentPrimary,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isChosen,
                                borderColor = BorderSubtle,
                                selectedBorderColor = AccentPrimary
                            )
                        )
                    }
                }

                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))

                // Audit Log List
                if (filteredAudits.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Keine Audit-Einträge vorhanden", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredAudits, key = { it.id }) { audit ->
                            val (levelColor, levelLabel) = when (audit.riskLevel) {
                                RiskLevel.BLOCKED.name -> Pair(RedEmergency, "BLOCKIERT")
                                RiskLevel.HIGH.name -> Pair(RedEmergency, "HOCH")
                                RiskLevel.MEDIUM.name -> Pair(YellowWarning, "MITTEL")
                                else -> Pair(StatusOnline, "GERING")
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkBackground,
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = levelColor.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = levelLabel,
                                                color = levelColor,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        Text(
                                            text = "${dateFormat.format(Date(audit.timestamp))} • ${audit.executionDurationMs}ms • Code ${audit.exitCode}",
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = audit.command,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = TextWhite,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )

                                    if (audit.riskReason.isNotEmpty()) {
                                        Text(
                                            text = "Grund: ${audit.riskReason}",
                                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontSize = 11.sp),
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }

                                    if (audit.stdout.isNotEmpty() || audit.stderr.isNotEmpty()) {
                                        val output = audit.stdout.ifEmpty { audit.stderr }
                                        Text(
                                            text = output.take(160),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            ),
                                            modifier = Modifier.padding(top = 4.dp),
                                            maxLines = 3
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
