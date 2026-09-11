package com.agent.mobile.ui.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityCockpitCard(
    agentEngine: AutonomousAgentEngine,
    preferenceManager: PreferenceManager,
    chatRepository: ChatRepository?
) {
    var whitelist by remember { mutableStateOf(preferenceManager.loadWhitelist()) }
    var blacklist by remember { mutableStateOf(preferenceManager.loadBlacklist()) }
    var isStrictMode by remember { mutableStateOf(preferenceManager.loadStrictMode()) }

    var newWhitelistPattern by remember { mutableStateOf("") }
    var newBlacklistPattern by remember { mutableStateOf("") }
    var showAuditLogDialog by remember { mutableStateOf(false) }

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
                    Icon(Icons.Default.Security, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Security controls",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 14.sp),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(
                    onClick = { showAuditLogDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Audit-Log", color = AccentPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Strict Mode Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict security mode",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Require approval for every command, including diagnostics and allowlisted operations",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isStrictMode,
                    onCheckedChange = {
                        isStrictMode = it
                        preferenceManager.saveStrictMode(it)
                        agentEngine.reloadSecurityRules()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = AccentPrimary
                    )
                )
            }

            HorizontalDivider(color = BorderSubtle)

            // Whitelist Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Custom allowlist (recognized operations only)",
                    style = MaterialTheme.typography.labelSmall.copy(color = AccentPrimary, fontWeight = FontWeight.Bold)
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWhitelistPattern,
                        onValueChange = { newWhitelistPattern = it },
                        placeholder = { Text("Full-match regex, e.g. ^curl https://example[.]com$", fontSize = 12.sp, color = TextMuted) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newWhitelistPattern.isNotBlank() && !whitelist.contains(newWhitelistPattern.trim())) {
                                val updated = whitelist + newWhitelistPattern.trim()
                                whitelist = updated
                                preferenceManager.saveWhitelist(updated)
                                agentEngine.reloadSecurityRules()
                                newWhitelistPattern = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    whitelist.forEach { pattern ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(pattern, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextWhite) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            val updated = whitelist - pattern
                                            whitelist = updated
                                            preferenceManager.saveWhitelist(updated)
                                            agentEngine.reloadSecurityRules()
                                        },
                                    tint = TextMuted
                                )
                            },
                            shape = RoundedCornerShape(6.dp),
                            colors = InputChipDefaults.inputChipColors(containerColor = DarkSurface),
                            border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = BorderSubtle)
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // Blacklist Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Custom blocklist (always blocked)",
                    style = MaterialTheme.typography.labelSmall.copy(color = RedEmergency, fontWeight = FontWeight.Bold)
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newBlacklistPattern,
                        onValueChange = { newBlacklistPattern = it },
                        placeholder = { Text("Regex, e.g. ^ssh\\b, dropdb", fontSize = 12.sp, color = TextMuted) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TextWhite),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RedEmergency,
                            unfocusedBorderColor = BorderSubtle,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newBlacklistPattern.isNotBlank() && !blacklist.contains(newBlacklistPattern.trim())) {
                                val updated = blacklist + newBlacklistPattern.trim()
                                blacklist = updated
                                preferenceManager.saveBlacklist(updated)
                                agentEngine.reloadSecurityRules()
                                newBlacklistPattern = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RedEmergency, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("+", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    blacklist.forEach { pattern ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(pattern, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextWhite) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            val updated = blacklist - pattern
                                            blacklist = updated
                                            preferenceManager.saveBlacklist(updated)
                                            agentEngine.reloadSecurityRules()
                                        },
                                    tint = TextMuted
                                )
                            },
                            shape = RoundedCornerShape(6.dp),
                            colors = InputChipDefaults.inputChipColors(containerColor = DarkSurface),
                            border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = BorderSubtle)
                        )
                    }
                }
            }
        }
    }

    if (showAuditLogDialog) {
        AuditLogDialog(
            chatRepository = chatRepository,
            onDismiss = { showAuditLogDialog = false }
        )
    }
}
