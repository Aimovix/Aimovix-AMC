package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.ui.theme.*

/**
 * Asks before the agent runs something. Both answers have the same size, shape and surface, and
 * the labels say exactly what happens. The full command is always shown and can be scrolled.
 * Only the text colour of "Run" changes, and only for high-risk actions, to show the risk.
 */
@Composable
fun ApprovalPromptCard(
    toolCall: ToolCall,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preview = AgentTools.approvalPreview(toolCall)
    val highRisk = toolCall.riskLevel == "HIGH"
    val haptics = LocalHapticFeedback.current
    val answerColors = ButtonDefaults.filledTonalButtonColors(containerColor = DarkCardElevated, contentColor = TextWhite)

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = DarkCard) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (highRisk) "Check this before it runs" else "Allow this action?",
                style = MaterialTheme.typography.titleMedium
            )
            if (toolCall.riskReason.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolCall.riskReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highRisk) RedText else TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.small, color = TerminalBg, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = preview,
                    style = CodeStyle,
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onReject()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = answerColors
                ) {
                    Text("Don't run", style = MaterialTheme.typography.labelLarge, color = TextWhite)
                }
                FilledTonalButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onApprove()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = answerColors
                ) {
                    Text("Run", style = MaterialTheme.typography.labelLarge, color = if (highRisk) RedText else TextWhite)
                }
            }
        }
    }
}
