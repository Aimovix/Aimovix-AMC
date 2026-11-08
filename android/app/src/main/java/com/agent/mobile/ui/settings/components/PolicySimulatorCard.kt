package com.agent.mobile.ui.settings.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.ui.theme.*

/** What a security mode does with a command. */
enum class PolicyVerdict { RUNS, ASKS, BLOCKED }

data class ModeVerdict(val preset: SecurityPreset, val verdict: PolicyVerdict)

data class PolicyResult(val reason: String, val verdicts: List<ModeVerdict>)

/**
 * Applies the same rules the agent uses to a command the user typed. Nothing is executed.
 */
object PolicySimulator {
    /** Null for an empty command; otherwise the verdict of every security mode. */
    fun evaluate(command: String): PolicyResult? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        val assessment = CommandSecurityFilter.analyze(trimmed)
        val verdicts = SecurityPreset.values().map { preset ->
            val verdict = when {
                assessment.isBlocked -> PolicyVerdict.BLOCKED
                CommandSecurityFilter.shouldRequireApproval(assessment, preset) -> PolicyVerdict.ASKS
                else -> PolicyVerdict.RUNS
            }
            ModeVerdict(preset, verdict)
        }
        return PolicyResult(assessment.reason, verdicts)
    }
}

private data class Sample(val label: String, val command: String)

private val SAMPLES = listOf(
    Sample("Read a file", "cat ~/notes.txt"),
    Sample("Install a package", "pkg install -y jq"),
    Sample("Delete a file", "rm ~/old-notes.txt"),
    Sample("Send an SMS", "termux-sms-send -n 0123456 \"Hi\""),
    Sample("Battery", "termux-battery-status"),
    Sample("Destructive", "rm -rf /")
)

/**
 * "Try a command": type or tap an example and see how every security mode treats it, with the
 * real rules. It makes the presets concrete before the user chooses one.
 */
@Composable
fun PolicySimulatorCard(modifier: Modifier = Modifier, initialCommand: String = "") {
    var command by rememberSaveable { mutableStateOf(initialCommand) }
    val result = remember(command) { PolicySimulator.evaluate(command) }

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Try a command", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "See how each security mode would treat it. The same rules the agent uses. Nothing runs here.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = {
                    Text("e.g. rm old-notes.txt", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                textStyle = CodeStyle,
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SAMPLES.forEach { sample ->
                    FilterChip(
                        selected = command == sample.command,
                        onClick = { command = sample.command },
                        label = { Text(sample.label, style = MaterialTheme.typography.labelLarge) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = DarkCardElevated,
                            labelColor = TextSecondary,
                            selectedContainerColor = AccentPrimary.copy(alpha = 0.18f),
                            selectedLabelColor = TextWhite
                        )
                    )
                }
            }
            AnimatedContent(targetState = result, label = "policyResult") { current ->
                if (current == null) {
                    Text(
                        text = "Type a command or tap an example.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.verdicts.forEach { mode -> PolicyRow(mode) }
                        Text(
                            text = "Why: ${current.reason}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyRow(mode: ModeVerdict) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = mode.preset.title,
            style = MaterialTheme.typography.bodyMedium,
            color = TextWhite,
            modifier = Modifier.weight(1f)
        )
        VerdictLabel(mode.verdict)
    }
}

@Composable
private fun VerdictLabel(verdict: PolicyVerdict) {
    val (text, tint) = when (verdict) {
        PolicyVerdict.RUNS -> "Runs" to StatusOnline
        PolicyVerdict.ASKS -> "Asks first" to YellowWarning
        PolicyVerdict.BLOCKED -> "Blocked" to RedText
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = tint.copy(alpha = 0.14f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
