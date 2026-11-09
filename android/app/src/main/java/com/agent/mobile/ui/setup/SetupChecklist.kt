package com.agent.mobile.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.agent.mobile.core.termux.TermuxRunCommand
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.theme.*

/** The steps a new user goes through, in order. */
enum class SetupStep { TERMUX, TERMUX_API, RUN_COMMAND, BACKGROUND, PAIRED }

/** What the app can observe about the setup right now. */
data class SetupFacts(
    val termuxInstalled: Boolean,
    val termuxApiInstalled: Boolean,
    val runCommandGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val bridgeConnected: Boolean
)

data class SetupItem(val step: SetupStep, val done: Boolean)

/** A message under the open step: a result, an error, or a command to run in Termux. */
data class SetupNotice(
    val message: String,
    val command: String? = null,
    val canInstallBridge: Boolean = false
)

/** The checklist in the order the user goes through it. */
fun setupItems(facts: SetupFacts): List<SetupItem> = listOf(
    SetupItem(SetupStep.TERMUX, facts.termuxInstalled),
    SetupItem(SetupStep.TERMUX_API, facts.termuxApiInstalled),
    SetupItem(SetupStep.RUN_COMMAND, facts.runCommandGranted),
    SetupItem(SetupStep.BACKGROUND, facts.batteryUnrestricted),
    SetupItem(SetupStep.PAIRED, facts.bridgeConnected)
)

/** The first step that is still open. The screen opens its row and offers its action. */
fun nextSetupStep(items: List<SetupItem>): SetupStep? = items.firstOrNull { !it.done }?.step

fun setupStepTitle(step: SetupStep): String = when (step) {
    SetupStep.TERMUX -> "Install Termux"
    SetupStep.TERMUX_API -> "Install Termux:API"
    SetupStep.RUN_COMMAND -> "Allow AMC to run commands in Termux"
    SetupStep.BACKGROUND -> "Let Termux run in the background"
    SetupStep.PAIRED -> "Start the bridge and pair"
}

fun setupStepHint(step: SetupStep): String = when (step) {
    SetupStep.TERMUX -> "AMC runs commands inside Termux. Install it from F-Droid, not the Play Store."
    SetupStep.TERMUX_API -> "Needed for the camera, location, SMS and other phone features. Use the same source as Termux."
    SetupStep.RUN_COMMAND -> "Android asks once. After that AMC can start the bridge in Termux for you."
    SetupStep.BACKGROUND -> "Set Termux's battery use to Unrestricted, so the bridge keeps running while the phone sleeps."
    SetupStep.PAIRED -> "AMC starts the bridge in Termux and fetches the token. Nothing to copy."
}

fun setupStepAction(step: SetupStep): String = when (step) {
    SetupStep.TERMUX, SetupStep.TERMUX_API -> "Open F-Droid"
    SetupStep.RUN_COMMAND -> "Allow"
    SetupStep.BACKGROUND -> "Open Termux settings"
    SetupStep.PAIRED -> "Start & pair"
}

/** The official F-Droid pages for the two apps; null for steps that have no page. */
fun setupStepUrl(step: SetupStep): String? = when (step) {
    SetupStep.TERMUX -> "https://f-droid.org/packages/com.termux/"
    SetupStep.TERMUX_API -> "https://f-droid.org/packages/com.termux.api/"
    else -> null
}

/** Reads the facts from the system. Cheap enough to call on resume and on every connection change. */
fun readSetupFacts(context: Context, bridgeConnected: Boolean): SetupFacts = SetupFacts(
    termuxInstalled = TermuxBridgeClient.isTermuxInstalled(context),
    termuxApiInstalled = isPackageInstalled(context, "com.termux.api"),
    runCommandGranted = ContextCompat.checkSelfPermission(context, TermuxRunCommand.PERMISSION) == PackageManager.PERMISSION_GRANTED,
    batteryUnrestricted = TermuxBridgeClient.isIgnoringBatteryOptimizations(context, "com.termux"),
    bridgeConnected = bridgeConnected
)

private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/**
 * A live checklist: each step shows whether it is done. The first open step is expanded and offers
 * one action. Nothing is performed without a tap.
 */
@Composable
fun SetupChecklist(
    facts: SetupFacts,
    busy: Boolean,
    notice: SetupNotice?,
    onAction: (SetupStep) -> Unit,
    onInstallBridge: () -> Unit,
    onContinueToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = setupItems(facts)
    val next = nextSetupStep(steps)
    val done = steps.count { it.done }
    val fraction by animateFloatAsState(
        targetValue = done.toFloat() / steps.size,
        animationSpec = amcSpring(),
        label = "setupProgress"
    )

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (next == null) "All set" else "$done of ${steps.size} done",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = if (next == null) StatusOnline else AccentPrimary,
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
                    busy = busy && isNext,
                    notice = if (isNext) notice else null,
                    onAction = { onAction(item.step) },
                    onInstallBridge = onInstallBridge
                )
                if (index < steps.lastIndex) HorizontalDivider(color = BorderSubtle)
            }
            AnimatedVisibility(visible = next == null, enter = amcEnter(), exit = amcExit()) {
                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Ask for a task in Chat, or message your bot in Telegram.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onContinueToChat,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black)
                    ) {
                        Text("Open chat", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(
    number: Int,
    item: SetupItem,
    isNext: Boolean,
    busy: Boolean,
    notice: SetupNotice?,
    onAction: () -> Unit,
    onInstallBridge: () -> Unit
) {
    val source = remember(item.step) { MutableInteractionSource() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge(number = number, done = item.done, active = isNext)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = setupStepTitle(item.step),
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    item.done -> TextMuted
                    isNext -> TextWhite
                    else -> TextSecondary
                }
            )
        }
        AnimatedVisibility(visible = isNext, enter = amcEnter(), exit = amcExit()) {
            Column(modifier = Modifier.padding(start = 38.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = setupStepHint(item.step),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Button(
                    onClick = onAction,
                    enabled = !busy,
                    interactionSource = source,
                    modifier = Modifier.pressScale(source),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (busy) "Working…" else setupStepAction(item.step),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                notice?.let { NoticeBlock(it, onInstallBridge) }
            }
        }
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean, active: Boolean) {
    val container by animateColorAsState(
        targetValue = when {
            done -> StatusOnline
            active -> AccentPrimary.copy(alpha = 0.18f)
            else -> DarkCardElevated
        },
        label = "stepBadge"
    )
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.Black, modifier = Modifier.size(16.dp))
        } else {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) AccentPrimary else TextMuted
            )
        }
    }
}

@Composable
private fun NoticeBlock(notice: SetupNotice, onInstallBridge: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = notice.message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        notice.command?.let { CommandBox(it) }
        if (notice.canInstallBridge) {
            OutlinedButton(onClick = onInstallBridge, shape = MaterialTheme.shapes.medium) {
                Text("Install the bridge in Termux", style = MaterialTheme.typography.labelLarge, color = TextWhite)
            }
        }
    }
}

@Composable
private fun CommandBox(command: String) {
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.small, color = TerminalBg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = command,
                style = CodeStyle.copy(fontSize = 12.sp),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Termux command", command))
                Toast.makeText(context, "Copied. Paste it in Termux and press Enter.", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy command", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
