package com.agent.mobile.ui.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agent.mobile.phone.ShizukuShell
import com.agent.mobile.ui.theme.*

/**
 * Second group of the setup: phone control. Same pattern as the Termux checklist: live state,
 * the first open step is expanded, one action per step, nothing happens without a tap.
 */
@Composable
fun PhoneControlChecklist(
    facts: SetupFacts,
    notice: SetupNotice?,
    onAction: (SetupStep) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = phoneControlItems(facts)
    val next = nextPhoneControlStep(steps)
    val required = steps.filter { !it.optional }
    val done = required.count { it.done }
    val ready = phoneControlReady(steps)
    val fraction by animateFloatAsState(
        targetValue = if (required.isEmpty()) 1f else done.toFloat() / required.size,
        animationSpec = amcSpring(),
        label = "phoneSetupProgress"
    )

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Phone control", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (ready) "Ready. The agent can operate apps while you see the \"AMC is in control\" banner."
                else "$done of ${required.size} done · lets the agent operate apps like you do",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = if (ready) StatusOnline else AccentPrimary,
                trackColor = DarkCardElevated,
                drawStopIndicator = {}
            )
            Spacer(modifier = Modifier.height(4.dp))
            steps.forEachIndexed { index, item ->
                val isNext = item.step == next
                SetupRow(
                    number = index + 1,
                    item = item,
                    isNext = isNext,
                    busy = false,
                    notice = if (isNext) notice else null,
                    onAction = { onAction(item.step) },
                    onInstallBridge = {},
                    hint = if (item.step == SetupStep.SHIZUKU) shizukuHint(facts.shizuku) else setupStepHint(item.step),
                    action = if (item.step == SetupStep.SHIZUKU) shizukuAction(facts.shizuku) else setupStepAction(item.step)
                )
                if (index < steps.lastIndex) HorizontalDivider(color = BorderSubtle)
            }
        }
    }
}

/** What "AMC Phone Control" can read and do, shown before Android's accessibility settings open. */
val PHONE_CONTROL_DISCLOSURE: List<Pair<String, String>> = listOf(
    "What it reads" to "The text and buttons of the app in front of you, so the agent can find its way. Never the content " +
        "of password fields. Screenshots only when a task needs one.",
    "What it does" to "Taps, types, scrolls, swipes and presses Back, Home or Recents, only for tasks you give it.",
    "When" to "Only while the \"AMC is in control\" banner is visible. Its Stop button (and the notification) ends it at once. " +
        "In Telegram and background tasks it is off unless you switch it on in Settings.",
    "Always asks you" to "Sending messages, payments and purchases, installing or removing apps, deleting data, logins, " +
        "calls and photos, and changing system settings, in every security mode.",
    "Never" to "Banking, password manager and authenticator apps and security settings are blocked. AMC never unlocks or " +
        "bypasses your lock screen.",
    "Where it goes" to "Screen content is sent to the AI provider you chose, like other tool output. Every action is " +
        "recorded in the audit log (without passwords).",
    "Turning it off" to "Android settings → Accessibility → AMC Phone Control, at any time."
)

/**
 * Short, plain disclosure before the user enables the accessibility service. No pre-checked boxes,
 * no pressure: "Not now" is as easy as continuing.
 */
@Composable
fun PhoneControlDisclosureDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = { Text("Before you turn on Phone Control", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PHONE_CONTROL_DISCLOSURE.forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = title, style = MaterialTheme.typography.labelLarge, color = TextWhite)
                        Text(text = body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
                Text(
                    text = "Android shows its own warning next. Choose AMC Phone Control and switch it on there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black)
            ) { Text("Open accessibility settings", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            }
        }
    )
}

/** Short status line per Shizuku state, for Settings. */
fun shizukuStatusText(status: ShizukuShell.Status): String = when (status) {
    ShizukuShell.Status.NOT_INSTALLED -> "Not installed (optional)"
    ShizukuShell.Status.NOT_RUNNING -> "Installed, not running"
    ShizukuShell.Status.NO_PERMISSION -> "Running, AMC not allowed yet"
    ShizukuShell.Status.READY -> "Ready"
}
