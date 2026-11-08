package com.agent.mobile.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.model.ToolResult
import com.agent.mobile.ui.theme.*

/**
 * One tool call as a single line (status, command, chevron). It is open while it runs and when it
 * failed, so the output and the error are visible. Once it succeeded it folds away. A tap on the
 * line opens or closes it.
 */
@Composable
fun StreamingTerminalCard(
    toolCall: ToolCall,
    toolResult: ToolResult?,
    liveOutput: String,
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val command = AgentTools.describe(toolCall)
    val output = when {
        liveOutput.isNotEmpty() -> liveOutput
        toolResult != null -> toolResult.stdout.ifEmpty { toolResult.stderr }
        else -> ""
    }
    val running = toolResult == null
    val failed = toolResult != null && (toolResult.isError || toolResult.exitCode != 0)

    // Null means "follow the default"; a tap stores the choice.
    var userChoice by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userChoice ?: (running || failed)
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = amcSpring<Float>(),
        label = "chevron"
    )
    val scroll = rememberScrollState()

    LaunchedEffect(output, expanded) {
        if (expanded && output.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = DarkCard) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userChoice = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    running -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentPrimary)
                    failed -> Icon(Icons.Default.ErrorOutline, contentDescription = "Failed", tint = RedText, modifier = Modifier.size(16.dp))
                    else -> Icon(Icons.Default.Check, contentDescription = "Done", tint = StatusOnline, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = command,
                    style = CodeStyle.copy(fontSize = 13.sp),
                    color = TextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (failed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Failed", style = MaterialTheme.typography.labelMedium, color = RedText)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide output" else "Show output",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp).rotate(chevronAngle)
                )
            }

            AnimatedVisibility(visible = expanded, enter = amcEnter(), exit = amcExit()) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = TerminalBg, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = output.ifEmpty { if (running) "Waiting for output…" else "No output." },
                            style = CodeStyle,
                            color = if (output.isEmpty()) TextMuted else TerminalText,
                            modifier = Modifier
                                .padding(10.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(scroll)
                        )
                    }
                    if (output.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Command output", output))
                                Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Copy output", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
