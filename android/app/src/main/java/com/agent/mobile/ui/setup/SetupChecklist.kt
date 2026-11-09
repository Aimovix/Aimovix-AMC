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
import com.agent.mobile.phone.AmcNotificationListener
import com.agent.mobile.phone.PhoneControlService
import com.agent.mobile.phone.ShizukuShell
import com.agent.mobile.ui.theme.*

/**
 * The steps a new user goes through, in order. The first five set up Termux; the rest set up
 * phone control (a second group, shown below the Termux steps).
 */
enum class SetupStep {
    TERMUX, TERMUX_API, RUN_COMMAND, BACKGROUND, PAIRED,
    RESTRICTED_SETTINGS, ACCESSIBILITY, NOTIFICATIONS, USAGE_STATS, SHIZUKU
}

/** What the app can observe about the setup right now. */
data class SetupFacts(
    val termuxInstalled: Boolean,
    val termuxApiInstalled: Boolean,
    val runCommandGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val bridgeConnected: Boolean,
    /** Android 13+ blocks accessibility and notification access for apps installed outside a store until allowed. */
    val restrictedSettingsApply: Boolean = false,
    val restrictedSettingsAllowed: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val shizuku: ShizukuShell.Status = ShizukuShell.Status.NOT_INSTALLED
)

data class SetupItem(val step: SetupStep, val done: Boolean, val optional: Boolean = false)

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

/**
 * Phone control steps. "Allow restricted settings" only appears on Android 13+ when it applies,
 * and counts as done once either permission behind it works. Usage access and Shizuku are optional.
 */
fun phoneControlItems(facts: SetupFacts): List<SetupItem> = buildList {
    if (facts.restrictedSettingsApply) {
        add(
            SetupItem(
                SetupStep.RESTRICTED_SETTINGS,
                facts.restrictedSettingsAllowed || facts.accessibilityEnabled || facts.notificationAccess
            )
        )
    }
    add(SetupItem(SetupStep.ACCESSIBILITY, facts.accessibilityEnabled))
    add(SetupItem(SetupStep.NOTIFICATIONS, facts.notificationAccess))
    add(SetupItem(SetupStep.USAGE_STATS, facts.usageAccess, optional = true))
    add(SetupItem(SetupStep.SHIZUKU, facts.shizuku == ShizukuShell.Status.READY, optional = true))
}

/** First open required step; once those are done, the first open optional one. */
fun nextPhoneControlStep(items: List<SetupItem>): SetupStep? =
    items.firstOrNull { !it.done && !it.optional }?.step ?: items.firstOrNull { !it.done }?.step

/** True when every required phone control step is done (optional ones may stay open). */
fun phoneControlReady(items: List<SetupItem>): Boolean = items.none { !it.done && !it.optional }

/** The one action of the Shizuku step, which depends on how far Shizuku is set up. */
fun shizukuAction(status: ShizukuShell.Status): String = when (status) {
    ShizukuShell.Status.NOT_INSTALLED -> "Get Shizuku"
    ShizukuShell.Status.NOT_RUNNING -> "Open Shizuku"
    ShizukuShell.Status.NO_PERMISSION -> "Allow AMC in Shizuku"
    ShizukuShell.Status.READY -> "Done"
}

fun shizukuHint(status: ShizukuShell.Status): String = when (status) {
    ShizukuShell.Status.NOT_INSTALLED ->
        "Optional. Shizuku gives AMC ADB rights without root (for example to change settings directly). Everything else works without it."
    ShizukuShell.Status.NOT_RUNNING ->
        "Shizuku is installed but not running. Open it and start it with wireless debugging, then come back."
    ShizukuShell.Status.NO_PERMISSION -> "Shizuku runs. Allow AMC in the dialog that appears."
    ShizukuShell.Status.READY -> "Shizuku is ready."
}

fun setupStepTitle(step: SetupStep): String = when (step) {
    SetupStep.TERMUX -> "Install Termux"
    SetupStep.TERMUX_API -> "Install Termux:API"
    SetupStep.RUN_COMMAND -> "Allow AMC to run commands in Termux"
    SetupStep.BACKGROUND -> "Let Termux run in the background"
    SetupStep.PAIRED -> "Start the bridge and pair"
    SetupStep.RESTRICTED_SETTINGS -> "Allow restricted settings"
    SetupStep.ACCESSIBILITY -> "Turn on AMC Phone Control"
    SetupStep.NOTIFICATIONS -> "Allow notification access"
    SetupStep.USAGE_STATS -> "Allow usage access"
    SetupStep.SHIZUKU -> "Shizuku (ADB rights)"
}

fun setupStepHint(step: SetupStep): String = when (step) {
    SetupStep.TERMUX -> "AMC runs commands inside Termux. Install it from F-Droid, not the Play Store."
    SetupStep.TERMUX_API -> "Needed for the camera, location, SMS and other phone features. Use the same source as Termux."
    SetupStep.RUN_COMMAND -> "Android asks once. After that AMC can start the bridge in Termux for you."
    SetupStep.BACKGROUND -> "Set Termux's battery use to Unrestricted, so the bridge keeps running while the phone sleeps."
    SetupStep.PAIRED -> "AMC starts the bridge in Termux and fetches the token. Nothing to copy."
    SetupStep.RESTRICTED_SETTINGS ->
        "AMC comes from GitHub, not from an app store. Android 13 and later block accessibility and notification " +
            "access for such apps until you allow it: in App info tap ⋮ (top right), then \"Allow restricted settings\", " +
            "and confirm. Then come back here."
    SetupStep.ACCESSIBILITY ->
        "Lets the agent see the app in front and tap, type and scroll for you, only during tasks you give it. " +
            "You will see what it can read and do before Android's settings open."
    SetupStep.NOTIFICATIONS ->
        "Lets the agent read, open and answer notifications when a task needs it. Answering always asks you first."
    SetupStep.USAGE_STATS -> "Optional. Lets the agent tell you which apps you used and for how long."
    SetupStep.SHIZUKU -> shizukuHint(ShizukuShell.Status.NOT_INSTALLED)
}

fun setupStepAction(step: SetupStep): String = when (step) {
    SetupStep.TERMUX, SetupStep.TERMUX_API -> "Open F-Droid"
    SetupStep.RUN_COMMAND -> "Allow"
    SetupStep.BACKGROUND -> "Open Termux settings"
    SetupStep.PAIRED -> "Start & pair"
    SetupStep.RESTRICTED_SETTINGS -> "Open App info"
    SetupStep.ACCESSIBILITY -> "Review and turn on"
    SetupStep.NOTIFICATIONS -> "Open notification access"
    SetupStep.USAGE_STATS -> "Open usage access"
    SetupStep.SHIZUKU -> shizukuAction(ShizukuShell.Status.NOT_INSTALLED)
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
    bridgeConnected = bridgeConnected,
    restrictedSettingsApply = com.agent.mobile.phone.PhoneControlSetup.restrictedSettingsApply(context),
    restrictedSettingsAllowed = com.agent.mobile.phone.PhoneControlSetup.restrictedSettingsAllowed(context),
    accessibilityEnabled = PhoneControlService.isEnabledInSettings(context),
    notificationAccess = AmcNotificationListener.isEnabled(context),
    usageAccess = com.agent.mobile.phone.PhoneControlSetup.hasUsageAccess(context),
    shizuku = ShizukuShell.status(context)
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
internal fun SetupRow(
    number: Int,
    item: SetupItem,
    isNext: Boolean,
    busy: Boolean,
    notice: SetupNotice?,
    onAction: () -> Unit,
    onInstallBridge: () -> Unit,
    hint: String = setupStepHint(item.step),
    action: String = setupStepAction(item.step)
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
                },
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.optional && !item.done) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Optional", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            }
        }
        AnimatedVisibility(visible = isNext, enter = amcEnter(), exit = amcExit()) {
            Column(modifier = Modifier.padding(start = 38.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = hint,
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
                        text = if (busy) "Working…" else action,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                notice?.let { NoticeBlock(it, onInstallBridge) }
            }
        }
    }
}

@Composable
internal fun StepBadge(number: Int, done: Boolean, active: Boolean) {
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
