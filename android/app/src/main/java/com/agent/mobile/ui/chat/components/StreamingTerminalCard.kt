package com.agent.mobile.ui.chat.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.model.ToolResult
import com.agent.mobile.ui.theme.*

@Composable
fun StreamingTerminalCard(
    toolCall: ToolCall,
    toolResult: ToolResult?,
    liveOutput: String,
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(true) }
    val command = toolCall.arguments["command"] ?: ""
    val displayText = when {
        liveOutput.isNotEmpty() -> liveOutput
        toolResult != null -> (toolResult.stdout.ifEmpty { toolResult.stderr })
        else -> "Warte auf Ausführung..."
    }

    val scrollState = rememberScrollState()

    LaunchedEffect(displayText) {
        if (isExpanded && displayText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)),
        color = TerminalBg,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column {
            // macOS-style Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalBar)
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Minimal window dots
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "$ $command",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status == MessageStatus.EXECUTING_TOOL) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = AccentPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (toolResult != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (toolResult.exitCode == 0) AccentPrimary.copy(alpha = 0.15f) else RedEmergency.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (toolResult.exitCode == 0) "0" else "${toolResult.exitCode}",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                fontSize = 10.sp,
                                color = if (toolResult.exitCode == 0) AccentPrimary else RedEmergency,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Copy output button
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal Output", displayText))
                            Toast.makeText(context, "Terminal-Ausgabe kopiert", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Kopieren",
                            tint = TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand/Collapse",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Expanded Terminal Body
            AnimatedVisibility(visible = isExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp, max = 240.dp)
                        .padding(10.dp)
                ) {
                    Text(
                        text = displayText,
                        color = TerminalText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
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
