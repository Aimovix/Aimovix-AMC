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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityCockpitCard(
    agentEngine: AutonomousAgentEngine,
    preferenceManager: PreferenceManager,
    chatRepository: ChatRepository?
) {
    val context = LocalContext.current
    val currentPreset by agentEngine.securityPreset.collectAsState()

    var whitelist by remember { mutableStateOf(preferenceManager.loadWhitelist()) }
    var blacklist by remember { mutableStateOf(preferenceManager.loadBlacklist()) }
    var isStrictMode by remember { mutableStateOf(preferenceManager.loadStrictMode()) }

    var newWhitelistPattern by remember { mutableStateOf("") }
    var newBlacklistPattern by remember { mutableStateOf("") }
    var showAuditLogDialog by remember { mutableStateOf(false) }
    var presetDropdownExpanded by remember { mutableStateOf(false) }

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
                    Icon(Icons.Default.Security, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Security Preset",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite, fontSize = 14.sp)
                        )
                        Text(
                            text = "Controls the actions the agent can take",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                        )
                    }
                }

                TextButton(
                    onClick = { showAuditLogDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Audit log", color = AccentPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Antigravity-styled Preset Dropdown Selector
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f)),
                    onClick = { presetDropdownExpanded = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            val presetIcon = when (currentPreset) {
                                SecurityPreset.DEFAULT -> Icons.Default.Shield
                                SecurityPreset.FULL_MACHINE -> Icons.Default.Storage
                                SecurityPreset.TURBO -> Icons.Default.Bolt
                                SecurityPreset.CUSTOM -> Icons.Default.Tune
                            }
                            Icon(presetIcon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = currentPreset.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
                                )
                                Text(
                                    text = currentPreset.description,
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp),
                                    maxLines = 2
                                )
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Preset", tint = TextMuted)
                    }
                }

                DropdownMenu(
                    expanded = presetDropdownExpanded,
                    onDismissRequest = { presetDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SecurityPreset.entries.forEach { preset ->
                        val isSelected = preset == currentPreset
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val icon = when (preset) {
                                            SecurityPreset.DEFAULT -> Icons.Default.Shield
                                            SecurityPreset.FULL_MACHINE -> Icons.Default.Storage
                                            SecurityPreset.TURBO -> Icons.Default.Bolt
                                            SecurityPreset.CUSTOM -> Icons.Default.Tune
                                        }
                                        Icon(icon, contentDescription = null, tint = if (isSelected) AccentPrimary else TextMuted, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = preset.title,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) AccentPrimary else TextWhite,
                                            fontSize = 13.sp
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = AccentPrimary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                                    )
                                }
                            },
                            onClick = {
                                agentEngine.setSecurityPreset(preset)
                                presetDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Preset status details
            when (currentPreset) {
                SecurityPreset.TURBO -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, YellowWarning.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = YellowWarning, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Turbo mode disables approval prompts for maximum execution speed. Catastrophic operations (root deletion, formatting) remain strictly blocked.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                            )
                        }
                    }
                }
                SecurityPreset.DEFAULT -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Default mode requires manual review for all terminal commands and file accesses outside working directories.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                            )
                        }
                    }
                }
                SecurityPreset.FULL_MACHINE -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Full machine grants unrestricted read and write file access across the device. All terminal commands require manual review.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                            )
                        }
                    }
                }
                SecurityPreset.CUSTOM -> {
                    // Custom granular settings
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HorizontalDivider(color = BorderSubtle)

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
                                        val pattern = newWhitelistPattern.trim()
                                        if (pattern.isNotBlank() && !whitelist.contains(pattern)) {
                                            if (CommandSecurityFilter.isValidRegex(pattern)) {
                                                val updated = whitelist + pattern
                                                whitelist = updated
                                                preferenceManager.saveWhitelist(updated)
                                                agentEngine.reloadSecurityRules()
                                                newWhitelistPattern = ""
                                            } else {
                                                Toast.makeText(context, "Invalid regex pattern syntax", Toast.LENGTH_SHORT).show()
                                            }
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
                                    val isValid = CommandSecurityFilter.isValidRegex(pattern)
                                    InputChip(
                                        selected = false,
                                        onClick = {},
                                        label = {
                                            Text(
                                                if (isValid) pattern else "$pattern (inactive)",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (isValid) TextWhite else YellowWarning
                                            )
                                        },
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
                                        border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = if (isValid) BorderSubtle else YellowWarning)
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
                                        val pattern = newBlacklistPattern.trim()
                                        if (pattern.isNotBlank() && !blacklist.contains(pattern)) {
                                            if (CommandSecurityFilter.isValidRegex(pattern)) {
                                                val updated = blacklist + pattern
                                                blacklist = updated
                                                preferenceManager.saveBlacklist(updated)
                                                agentEngine.reloadSecurityRules()
                                                newBlacklistPattern = ""
                                            } else {
                                                Toast.makeText(context, "Invalid regex pattern syntax", Toast.LENGTH_SHORT).show()
                                            }
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
                                    val isValid = CommandSecurityFilter.isValidRegex(pattern)
                                    InputChip(
                                        selected = false,
                                        onClick = {},
                                        label = {
                                            Text(
                                                if (isValid) pattern else "$pattern (inactive)",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (isValid) TextWhite else RedEmergency
                                            )
                                        },
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
                                        border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = if (isValid) BorderSubtle else RedEmergency)
                                    )
                                }
                            }
                        }
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
