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
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.agent.RunMode
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.phone.PhoneRiskContext
import com.agent.mobile.phone.PhoneToolPolicy
import com.agent.mobile.phone.TargetInfo
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.SecurityAssessment
import com.agent.mobile.ui.theme.*

/** What a security mode does with a command. [OFF]: the tool is not offered at all. */
enum class PolicyVerdict { RUNS, ASKS, BLOCKED, OFF }

data class ModeVerdict(val preset: SecurityPreset, val verdict: PolicyVerdict)

/**
 * [remote] is the verdict for Telegram conversations when it differs from the presets
 * (phone screen tools: off by default, otherwise every action asks). [grantNote] explains a
 * per-task approval (one approval covers reading that app for the rest of the task).
 */
data class PolicyResult(
    val reason: String,
    val verdicts: List<ModeVerdict>,
    val remote: PolicyVerdict? = null,
    val grantNote: String? = null
)

/**
 * Applies the same rules the agent uses to a command the user typed. Nothing is executed.
 *
 * Shell commands are checked as typed. Agent tools use `tool [main argument] [key=value…] [@app]`,
 * for example `screen_tap Send @com.whatsapp` (tap "Send" while WhatsApp is in front),
 * `open_app Sparkasse` or `screen_type hunter2 password=true @com.example.shop`.
 */
object PolicySimulator {
    /** Null for an empty command; otherwise the verdict of every security mode. */
    fun evaluate(command: String, remoteScreenControl: Boolean = false): PolicyResult? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        val toolName = trimmed.substringBefore(' ')
        if (toolName != AgentTools.EXECUTE_COMMAND && AgentTools.isKnown(toolName)) {
            return evaluateTool(trimmed, remoteScreenControl)
        }
        val shell = if (toolName == AgentTools.EXECUTE_COMMAND) trimmed.removePrefix(toolName).trim() else trimmed
        if (shell.isEmpty()) return null
        val assessment = CommandSecurityFilter.analyze(shell)
        return PolicyResult(assessment.reason, verdictsFor(assessment))
    }

    private fun verdictsFor(assessment: SecurityAssessment): List<ModeVerdict> = SecurityPreset.values().map { preset ->
        val verdict = when {
            assessment.isBlocked -> PolicyVerdict.BLOCKED
            CommandSecurityFilter.shouldRequireApproval(assessment, preset) -> PolicyVerdict.ASKS
            else -> PolicyVerdict.RUNS
        }
        ModeVerdict(preset, verdict)
    }

    /** Parses the simulator's tool syntax into a call and the phone context it describes. */
    internal fun parseTool(input: String): Pair<ToolCall, PhoneRiskContext> {
        val tokens = input.trim().split(Regex("\\s+"))
        val name = tokens.first()
        var app: String? = null
        var password = false
        var locked = false
        val args = mutableMapOf<String, String>()
        val free = mutableListOf<String>()
        tokens.drop(1).forEach { token ->
            when {
                token.startsWith("@") && token.length > 1 -> app = token.drop(1)
                token == "password=true" -> password = true
                token == "locked=true" -> locked = true
                Regex("^[a-z_0-9]+=.*").matches(token) -> args[token.substringBefore('=')] = token.substringAfter('=').trim('"')
                else -> free += token.trim('"')
            }
        }
        val main = free.joinToString(" ")
        val primary = PRIMARY_PARAM[name]
        if (primary != null && main.isNotEmpty() && primary !in args) args[primary] = main
        val call = ToolCall(name = name, arguments = args)
        val targetLabel = args["target"] ?: args["into"]
        val isPackage = { s: String? -> s != null && s.contains('.') && !s.contains(' ') }
        val ctx = PhoneRiskContext(
            keyguardLocked = locked,
            foregroundPackage = app,
            foregroundLabel = app,
            target = when {
                name == PhoneTools.SCREEN_TYPE -> TargetInfo(label = args["into"].orEmpty(), password = password, editable = true)
                targetLabel != null -> TargetInfo(label = targetLabel, password = password)
                else -> null
            },
            targetPackage = if (name == PhoneTools.OPEN_APP) args["name"]?.takeIf { isPackage(it) } ?: app else app,
            targetLabel = if (name == PhoneTools.OPEN_APP) args["name"] else null
        )
        return call to ctx
    }

    private fun evaluateTool(input: String, remoteScreenControl: Boolean): PolicyResult {
        val (call, ctx) = parseTool(input)
        val assessment = AgentTools.assess(call, ctx)
        val remote = when {
            call.name !in PhoneTools.SCREEN_TOOLS -> null
            !remoteScreenControl -> PolicyVerdict.OFF
            assessment.isBlocked -> PolicyVerdict.BLOCKED
            PhoneToolPolicy.requiresApprovalInMode(call.name, RunMode.REMOTE) -> PolicyVerdict.ASKS
            else -> null
        }
        val grantNote = if (assessment.grantKey != null) "One approval covers this app for the rest of the task." else null
        return PolicyResult(assessment.reason, verdictsFor(assessment), remote, grantNote)
    }

    /** The argument a bare word after the tool name fills. */
    private val PRIMARY_PARAM = mapOf(
        PhoneTools.SCREEN_READ to "filter",
        PhoneTools.SCREEN_TAP to "target",
        PhoneTools.SCREEN_TYPE to "text",
        PhoneTools.SCREEN_SCROLL to "direction",
        PhoneTools.SCREEN_GLOBAL to "action",
        PhoneTools.OPEN_APP to "name",
        PhoneTools.APPS_LIST to "filter",
        PhoneTools.WAIT_FOR to "text",
        PhoneTools.NOTIFICATIONS_LIST to "app",
        PhoneTools.NOTIFICATION_OPEN to "id",
        PhoneTools.NOTIFICATION_REPLY to "text",
        PhoneTools.NOTIFICATION_DISMISS to "id",
        PhoneTools.NOTIFICATION_TRIGGER to "action",
        PhoneTools.SHARE_TEXT to "text",
        PhoneTools.OPEN_URL to "url",
        PhoneTools.OPEN_SETTINGS to "page",
        PhoneTools.CLIPBOARD to "action",
        PhoneTools.USAGE_STATS to "hours",
        AgentTools.READ_FILE to "path",
        AgentTools.WRITE_FILE to "path",
        AgentTools.WEB_FETCH to "url",
        AgentTools.WEB_SEARCH to "query",
        AgentTools.SEND_FILE to "path"
    )
}

private data class Sample(val label: String, val command: String)

private val SAMPLES = listOf(
    Sample("Read a file", "cat ~/notes.txt"),
    Sample("Install a package", "pkg install -y jq"),
    Sample("Delete a file", "rm ~/old-notes.txt"),
    Sample("Send an SMS", "termux-sms-send -n 0123456 \"Hi\""),
    Sample("Battery", "termux-battery-status"),
    Sample("Destructive", "rm -rf /"),
    Sample("Read the screen", "screen_read @com.android.chrome"),
    Sample("Screenshot", "screen_capture @com.google.android.apps.maps"),
    Sample("Tap in a shop app", "screen_tap \"Add to list\" @com.example.shop"),
    Sample("Send in WhatsApp", "screen_tap Send @com.whatsapp"),
    Sample("Open WhatsApp", "open_app com.whatsapp"),
    Sample("Open a banking app", "open_app Sparkasse"),
    Sample("Type a password", "screen_type hunter2 password=true @com.example.shop"),
    Sample("Change a setting", "screen_tap Wi-Fi @com.android.settings"),
    Sample("Read notifications", "notifications_list"),
    Sample("Reply to a message", "notification_reply \"On my way\" id=n1 @com.whatsapp"),
    Sample("Set an alarm", "set_alarm hour=7 minute=30"),
    Sample("Read the clipboard", "clipboard get")
)

/**
 * "Try a command": type or tap an example and see how every security mode treats it, with the
 * real rules. It makes the presets concrete before the user chooses one.
 */
@Composable
fun PolicySimulatorCard(
    modifier: Modifier = Modifier,
    initialCommand: String = "",
    remoteScreenControl: Boolean = false
) {
    var command by rememberSaveable { mutableStateOf(initialCommand) }
    val result = remember(command, remoteScreenControl) { PolicySimulator.evaluate(command, remoteScreenControl) }

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Try a command", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "See how each security mode would treat it. The same rules the agent uses. Nothing runs here. " +
                        "Phone actions use tool syntax, e.g. screen_tap Send @com.whatsapp.",
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
                        current.verdicts.forEach { mode -> PolicyRow(mode.preset.title, mode.verdict) }
                        current.remote?.let { PolicyRow("Telegram (remote)", it) }
                        current.grantNote?.let {
                            Text(text = it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        }
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
private fun PolicyRow(title: String, verdict: PolicyVerdict) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = TextWhite,
            modifier = Modifier.weight(1f)
        )
        VerdictLabel(verdict)
    }
}

@Composable
private fun VerdictLabel(verdict: PolicyVerdict) {
    val (text, tint) = when (verdict) {
        PolicyVerdict.RUNS -> "Runs" to StatusOnline
        PolicyVerdict.ASKS -> "Asks first" to YellowWarning
        PolicyVerdict.BLOCKED -> "Blocked" to RedText
        PolicyVerdict.OFF -> "Off" to TextMuted
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
