package com.agent.mobile.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AgentTools
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.model.ToolResult
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.delay

/** "12 s" or "1 min 5 s". Pure, so it can be tested. */
internal fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0L)
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
}

/**
 * One tool call as a single line: status, command, chevron. It is open while it runs and when it
 * failed, so output and error stay visible. After success it folds away. Tap the line to open or
 * close it; long-press it to copy the command. The output can be selected with a long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StreamingTerminalCard(
    toolCall: ToolCall,
    toolResult: ToolResult?,
    liveOutput: String,
    status: MessageStatus,
    modifier: Modifier = Modifier,
    startedAt: Long = 0L
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
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

    // Elapsed time while the command runs: refreshed once a second, and the loop ends when it finishes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsed = if (running && startedAt > 0L) formatElapsed(now - startedAt) else null

    LaunchedEffect(output, expanded) {
        if (expanded && output.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    fun copyCommand() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Command", command))
        Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
    }

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = DarkCard) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { userChoice = !expanded },
                        onLongClick = { copyCommand() }
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    running -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentPrimary)
                    failed -> Icon(Icons.Default.ErrorOutline, contentDescription = "Failed", tint = RedText, modifier = Modifier.size(16.dp))
                    else -> Icon(Icons.Default.Check, contentDescription = "Done", tint = StatusOnline, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = command,
                        style = CodeStyle.copy(fontSize = 13.sp),
                        color = TextWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (elapsed != null) {
                        Text(
                            text = "running · $elapsed",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
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
                        SelectionContainer {
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
