package com.agent.mobile.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.unattended.UnattendedCategory
import com.agent.mobile.unattended.UnattendedLimits
import com.agent.mobile.unattended.UnattendedSettings
import com.agent.mobile.ui.theme.*
import kotlin.math.roundToInt

/**
 * Opt-in for unattended mode. Shows exactly what will run without asking, what is refused and
 * what is never done. Both answers are the same kind of button with the same size and weight
 * (no dark patterns, Feature 25).
 */
@Composable
fun UnattendedOptInDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val defaults = UnattendedSettings()
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = DarkCard,
        title = { Text("Turn on unattended mode?", color = TextWhite, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Runs you start (in the app, on a schedule, by a notification trigger or from Telegram) then finish " +
                        "without asking you. Instead of a question, a fixed rule allows or refuses each sensitive action. " +
                        "Every action is logged, and you get a report after each run.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                CategoryList("Allowed without asking", UnattendedCategory.entries.filter { defaults.isAllowed(it) }.map { it.title })
                CategoryList("Refused (you can unlock them later)", UnattendedCategory.entries.filter { !defaults.isAllowed(it) }.map { it.title })
                CategoryList("Never done unattended, not changeable", UnattendedCategory.FLOOR)
                Text(
                    "Content the agent reads (web pages, files, screens, notifications) can never unlock refused actions or " +
                        "create memory entries, schedules or triggers. Stop all runs any time: \"Stop all agents\" in the AMC " +
                        "notification, Stop on the phone control banner, or /stop in Telegram.",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        },
        confirmButton = {
            // Same component, same size, side by side: neither answer is pushed.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Keep current mode", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onAccept, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Turn on unattended mode", fontSize = 13.sp)
                }
            }
        }
    )
}

@Composable
private fun CategoryList(title: String, items: List<String>) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)) }
    }
}

/**
 * Settings of unattended mode: one switch per category (allow / refuse) and the per-run limits.
 * The floor is listed but has no switch.
 */
@Composable
fun UnattendedSettingsPanel(
    settings: UnattendedSettings,
    onChange: (UnattendedSettings) -> Unit,
    onTurnOff: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Unattended mode is on: nothing asks. Allowed actions run and are logged; refused ones are skipped, logged " +
                "and the agent works around them. Every run ends with a report.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
        )
        UnattendedCategory.entries.forEach { c ->
            val allowed = settings.isAllowed(c)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite))
                    Text(
                        c.description + if (!c.defaultAllowed) " Refused by default." else "",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
                Switch(
                    checked = allowed,
                    onCheckedChange = { on ->
                        onChange(settings.copy(allowed = if (on) settings.allowed + c else settings.allowed - c))
                    },
                    modifier = Modifier.semantics { contentDescription = "${c.title}: ${if (allowed) "allowed" else "refused"}" }
                )
            }
        }
        Text("Never done unattended", style = MaterialTheme.typography.labelLarge.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
        UnattendedCategory.FLOOR.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)) }

        Text("Limits per run (0 = no limit)", style = MaterialTheme.typography.labelLarge.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
        val l = settings.limits
        LimitSlider("Maximum duration", l.maxDurationMinutes, "min", 0f..720f, 23) {
            onChange(settings.copy(limits = l.copy(maxDurationMinutes = it)))
        }
        LimitSlider("Maximum phone actions", l.maxPhoneActions, "", 0f..1000f, 19) {
            onChange(settings.copy(limits = l.copy(maxPhoneActions = it)))
        }
        LimitSlider("Maximum messages sent", l.maxMessagesSent, "", 0f..100f, 19) {
            onChange(settings.copy(limits = l.copy(maxMessagesSent = it)))
        }
        OutlinedButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Turn off unattended mode")
        }
    }
}

@Composable
private fun LimitSlider(label: String, value: Int, unit: String, range: ClosedFloatingPointRange<Float>, steps: Int, onSet: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text(
            "$label: ${if (current.roundToInt() == 0) "no limit" else "${current.roundToInt()} $unit".trim()}",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onSet(current.roundToInt()) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = label },
            colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
        )
    }
}

/** Default limits, shown in docs and tests. */
val DEFAULT_UNATTENDED_LIMITS = UnattendedLimits()
