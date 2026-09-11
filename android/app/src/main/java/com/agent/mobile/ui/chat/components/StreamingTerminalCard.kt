package com.agent.mobile.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.model.ToolResult
import com.agent.mobile.ui.theme.GreenPrimary
import com.agent.mobile.ui.theme.RedEmergency
import com.agent.mobile.ui.theme.TerminalBg
import com.agent.mobile.ui.theme.TerminalGreen

@Composable
fun StreamingTerminalCard(
    toolCall: ToolCall,
    toolResult: ToolResult?,
    liveOutput: String,
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    val command = toolCall.arguments["command"] ?: ""
    val displayText = when {
        liveOutput.isNotEmpty() -> liveOutput
        toolResult != null -> (toolResult.stdout.ifEmpty { toolResult.stderr })
        else -> "Warte auf Ausführung..."
    }

    val scrollState = rememberScrollState()

    // Auto-scroll to bottom as output streams in
    LaunchedEffect(displayText) {
        if (isExpanded && displayText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Terminal",
                        tint = GreenPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$ $command",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status == MessageStatus.EXECUTING_TOOL) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = GreenPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (toolResult != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (toolResult.exitCode == 0) GreenPrimary.copy(alpha = 0.2f) else RedEmergency.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (toolResult.exitCode == 0) "Exit 0" else "Exit ${toolResult.exitCode}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = if (toolResult.exitCode == 0) GreenPrimary else RedEmergency,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand/Collapse",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded Terminal Body
            AnimatedVisibility(visible = isExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 220.dp)
                        .background(TerminalBg)
                        .padding(8.dp)
                ) {
                    Text(
                        text = displayText,
                        color = TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}
