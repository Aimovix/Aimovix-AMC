package com.agent.mobile.ui.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.phone.AmcNotificationListener
import com.agent.mobile.phone.PackageBlocklist
import com.agent.mobile.phone.PhoneControlService
import com.agent.mobile.phone.PhoneControlSetup
import com.agent.mobile.phone.ShizukuShell
import com.agent.mobile.ui.setup.shizukuStatusText
import com.agent.mobile.ui.theme.*

/** Text for one blocked entry in the list. */
fun blocklistEntryLabel(entry: String): String = when {
    entry == PackageBlocklist.SECURITY_SETTINGS -> "Security settings pages"
    entry.contains('/') -> entry.substringBefore('/') + " (only " + entry.substringAfterLast('.').ifEmpty { "some screens" } + ")"
    else -> entry
}

/**
 * Settings → Phone control: what is on, where it may run (Telegram, background tasks), which
 * apps are blocked, and the notification triggers. Everything here is off or strict by default.
 */
@Composable
fun PhoneControlCard(
    preferenceManager: PreferenceManager,
    remoteEnabled: Boolean,
    onRemoteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibility = remember(refresh) { PhoneControlService.isEnabledInSettings(context) }
    val notifications = remember(refresh) { AmcNotificationListener.isEnabled(context) }
    val usage = remember(refresh) { PhoneControlSetup.hasUsageAccess(context) }
    val shizuku = remember(refresh) { ShizukuShell.status(context) }

    var background by remember { mutableStateOf(preferenceManager.loadPhoneControlBackground()) }
    var blocklist by remember { mutableStateOf(preferenceManager.loadPhoneBlocklist()) }
    var triggers by remember { mutableStateOf(preferenceManager.loadNotificationTriggers()) }
    var showBlocklist by remember { mutableStateOf(false) }
    var newEntry by remember { mutableStateOf("") }
    var entryError by remember { mutableStateOf(false) }

    fun saveBlocklist(list: PackageBlocklist) {
        blocklist = list
        preferenceManager.savePhoneBlocklist(list)
    }

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Phone control", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Lets the agent operate apps, read notifications and use system features. Turn the parts on in Setup.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            StatusRow("Screen control (accessibility)", if (accessibility) "On" else "Off", accessibility)
            StatusRow("Notification access", if (notifications) "On" else "Off", notifications)
            StatusRow("Usage access", if (usage) "On" else "Off (optional)", usage)
            StatusRow("Shizuku", shizukuStatusText(shizuku), shizuku == ShizukuShell.Status.READY)

            HorizontalDivider(color = BorderSubtle)

            ToggleRow(
                title = "Allow in Telegram",
                subtitle = "Off by default. When on, every screen action asks you with a button in the chat, and " +
                    "screenshots are only sent when you ask for one.",
                checked = remoteEnabled,
                onChange = onRemoteChange
            )
            ToggleRow(
                title = "Allow in background tasks",
                subtitle = "Scheduled tasks and notification triggers may operate the screen while the banner shows. " +
                    "Actions that need you are skipped.",
                checked = background,
                onChange = {
                    background = it
                    preferenceManager.savePhoneControlBackground(it)
                }
            )

            HorizontalDivider(color = BorderSubtle)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Blocked apps", style = MaterialTheme.typography.titleSmall, color = TextWhite)
                    Text(
                        "${blocklist.entries.size} entries. Banking, payment, password and authenticator apps are also " +
                            "blocked by name unless you release them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                TextButton(onClick = { showBlocklist = !showBlocklist }) {
                    Text(if (showBlocklist) "Hide" else "Edit", style = MaterialTheme.typography.labelLarge, color = AccentPrimary)
                }
            }
            AnimatedVisibility(visible = showBlocklist, enter = amcEnter(), exit = amcExit()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newEntry,
                            onValueChange = { newEntry = it; entryError = false },
                            placeholder = { Text("com.example.app", style = MaterialTheme.typography.bodyMedium, color = TextMuted) },
                            singleLine = true,
                            isError = entryError,
                            textStyle = CodeStyle,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            )
                        )
                        Button(
                            onClick = {
                                val normalized = PackageBlocklist.normalize(newEntry)
                                if (normalized == null) entryError = true else {
                                    saveBlocklist(blocklist.withEntry(normalized))
                                    newEntry = ""
                                }
                            },
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = androidx.compose.ui.graphics.Color.Black)
                        ) { Text("Block", style = MaterialTheme.typography.labelLarge) }
                    }
                    if (entryError) {
                        Text("Enter a package name such as com.example.app.", style = MaterialTheme.typography.labelMedium, color = RedText)
                    }
                    blocklist.entries.forEach { entry ->
                        EntryRow(blocklistEntryLabel(entry), "Release") { saveBlocklist(blocklist.withoutEntry(entry)) }
                    }
                    if (blocklist.allowed.isNotEmpty()) {
                        Text("Released by you", style = MaterialTheme.typography.labelLarge, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
                        blocklist.allowed.sorted().forEach { pkg ->
                            EntryRow(pkg, "Block again") { saveBlocklist(blocklist.withEntry(pkg)) }
                        }
                    }
                    TextButton(onClick = { saveBlocklist(PackageBlocklist.DEFAULT) }) {
                        Text("Restore the default list", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = BorderSubtle)

            Text("Notification triggers", style = MaterialTheme.typography.titleSmall, color = TextWhite)
            if (triggers.isEmpty()) {
                Text(
                    "None. Ask the agent, for example: \"When Gmail shows an invoice, save it to Documents.\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                triggers.forEach { t ->
                    val label = t.appLabel + (t.contains?.let { " · \"$it\"" } ?: "") + " → " + t.prompt.take(80)
                    EntryRow(label, "Remove") {
                        triggers = triggers.filter { it.id != t.id }
                        preferenceManager.saveNotificationTriggers(triggers)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, value: String, on: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = TextWhite, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = if (on) StatusOnline else TextMuted)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextWhite)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AccentPrimary, checkedThumbColor = androidx.compose.ui.graphics.Color.Black)
        )
    }
}

@Composable
private fun EntryRow(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = CodeStyle, color = TextSecondary, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        }
    }
}
