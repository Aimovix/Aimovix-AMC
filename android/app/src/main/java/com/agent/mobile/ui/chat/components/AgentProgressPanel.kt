package com.agent.mobile.ui.chat.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.mobile.data.model.PlanStep
import com.agent.mobile.data.model.PlanStepStatus
import com.agent.mobile.ui.theme.*

/**
 * The agent's plan and progress: a summary line with a progress bar, and the steps underneath when
 * open. Steps change their icon with a crossfade; the bar fills smoothly as steps complete.
 */
@Composable
fun AgentProgressPanel(
    plan: List<PlanStep>,
    isBusy: Boolean,
    currentStep: Int,
    maxSteps: Int,
    queuedInputs: Int,
    modifier: Modifier = Modifier
) {
    if (plan.isEmpty() && !isBusy) return
    var expanded by remember { mutableStateOf(true) }
    val done = plan.count { it.status == PlanStepStatus.DONE }
    val complete = plan.isNotEmpty() && done == plan.size
    val fraction by animateFloatAsState(
        targetValue = if (plan.isEmpty()) 0f else done.toFloat() / plan.size,
        animationSpec = amcSpring(),
        label = "planProgress"
    )

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = DarkCard
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = plan.isNotEmpty()) { expanded = !expanded }
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AccentPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = buildString {
                        when {
                            complete -> append("Plan complete")
                            plan.isNotEmpty() -> append("Plan $done of ${plan.size}")
                        }
                        if (isBusy) {
                            if (isNotEmpty()) append(" · ")
                            append("Step $currentStep of $maxSteps")
                        }
                        if (queuedInputs > 0) append(" · $queuedInputs queued")
                    },
                    style = MaterialTheme.typography.labelLarge.copy(color = TextSecondary),
                    modifier = Modifier.weight(1f)
                )
                if (plan.isNotEmpty()) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse plan" else "Expand plan",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (plan.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(androidx.compose.foundation.shape.CircleShape),
                    color = if (complete) StatusOnline else AccentPrimary,
                    trackColor = DarkCardElevated,
                    drawStopIndicator = {}
                )
            }
            if (expanded && plan.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(modifier = Modifier.heightIn(max = 180.dp)) {
                    plan.take(12).forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Crossfade(targetState = step.status, label = "planStepIcon") { status ->
                                val (icon, tint) = when (status) {
                                    PlanStepStatus.DONE -> Icons.Default.CheckCircle to AccentPrimary
                                    PlanStepStatus.IN_PROGRESS -> Icons.Default.PlayCircle to YellowWarning
                                    PlanStepStatus.PENDING -> Icons.Default.RadioButtonUnchecked to TextMuted
                                }
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = step.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (step.status == PlanStepStatus.DONE) TextMuted else TextWhite,
                                    textDecoration = if (step.status == PlanStepStatus.DONE) TextDecoration.LineThrough else null
                                )
                            )
                        }
                    }
                    if (plan.size > 12) {
                        Text(
                            text = "${plan.size - 12} more",
                            style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                        )
                    }
                }
            }
        }
    }
}
