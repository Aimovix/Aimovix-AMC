package com.agent.mobile.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One chat entry. The user's words sit in a quiet bubble on the right. The agent's answer is plain
 * text on the left, which reads better at length, with its tool calls and approval requests below.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    pendingApproval: Pair<String, ToolCall>? = null,
    onApproveTool: (toolId: String) -> Unit = {},
    onRejectTool: (toolId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, modifier)
        MessageRole.ASSISTANT -> AssistantMessage(message, pendingApproval, onApproveTool, onRejectTool, modifier)
        MessageRole.SYSTEM -> SystemNote(message.text, modifier)
        MessageRole.TOOL -> Unit // shown inside the assistant message that made the call
    }
}

@Composable
private fun UserMessage(message: ChatMessage, modifier: Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.weight(1f, fill = false).widthIn(min = 40.dp, max = 320.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = DarkCardElevated
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.imageBase64 != null) {
                    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = message.imageBase64) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                val bytes = android.util.Base64.decode(message.imageBase64, android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    bitmap?.let { current ->
                        Image(
                            bitmap = current,
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .padding(bottom = 8.dp)
                        )
                    }
                }
                if (message.text.isNotEmpty()) {
                    Text(text = message.text, style = MaterialTheme.typography.bodyLarge, color = TextWhite)
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    pendingApproval: Pair<String, ToolCall>?,
    onApproveTool: (toolId: String) -> Unit,
    onRejectTool: (toolId: String) -> Unit,
    modifier: Modifier
) {
    val allCalls = if (message.toolCalls.isNotEmpty()) message.toolCalls else listOfNotNull(message.toolCall)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.text.isNotEmpty()) {
            Text(text = message.text, style = MaterialTheme.typography.bodyLarge, color = TextWhite)
        } else if (message.status == MessageStatus.STREAMING && allCalls.isEmpty()) {
            Text(text = "Thinking…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }

        if (allCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val transition = amcContentTransform()
                for (tc in allCalls) {
                    // The approval request and the result share one slot, so the swap is a short crossfade.
                    val waitingCall = pendingApproval?.takeIf { it.first == message.id && it.second.id == tc.id }?.second
                    AnimatedContent(
                        targetState = waitingCall != null,
                        transitionSpec = { transition },
                        label = "tool-${tc.id}"
                    ) { waiting ->
                        if (waiting && waitingCall != null) {
                            ApprovalPromptCard(
                                toolCall = waitingCall,
                                onApprove = { onApproveTool(tc.id) },
                                onReject = { onRejectTool(tc.id) }
                            )
                        } else {
                            val matchingResult = if (message.toolResult?.toolCallId == tc.id) message.toolResult else null
                            StreamingTerminalCard(
                                toolCall = tc,
                                toolResult = matchingResult,
                                liveOutput = if (message.status == MessageStatus.EXECUTING_TOOL) message.streamingTerminalOutput else "",
                                status = if (matchingResult != null) {
                                    if (matchingResult.isError) MessageStatus.ERROR else MessageStatus.COMPLETED
                                } else {
                                    message.status
                                },
                                startedAt = message.timestamp
                            )
                        }
                    }
                }
                // Approval requested by a sub-agent working for one of these calls.
                val forwarded = pendingApproval?.takeIf { p ->
                    p.first == message.id && allCalls.none { it.id == p.second.id }
                }
                if (forwarded != null) {
                    ApprovalPromptCard(
                        toolCall = forwarded.second,
                        onApprove = { onApproveTool(forwarded.second.id) },
                        onReject = { onRejectTool(forwarded.second.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemNote(text: String, modifier: Modifier) {
    val head = text.trimStart()
    val tint = when {
        head.startsWith("🛡") || head.startsWith("❌") -> RedText
        head.startsWith("⚠") -> YellowWarning
        else -> TextMuted
    }
    Text(
        text = cleanSystemText(text),
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

// ℹ (information source) is a letter in Unicode, so it is listed explicitly.
private val LEADING_SYMBOLS = Regex("^[\\p{So}\\p{Mn}\\p{Cf}\\p{Cs}\\u2139\\s]+")

/** Drops decorative emoji from the start of a system message. The wording itself is unchanged. */
internal fun cleanSystemText(text: String): String = text.replace(LEADING_SYMBOLS, "").ifBlank { text }
