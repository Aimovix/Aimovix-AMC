package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessageBubble(
    message: ChatMessage,
    pendingApproval: Pair<String, ToolCall>? = null,
    onApproveTool: (toolId: String) -> Unit = {},
    onRejectTool: (toolId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (message.role) {
        MessageRole.USER -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 40.dp, max = 340.dp),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = DarkCardElevated,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
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
                            val currentBitmap = bitmap
                            if (currentBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = currentBitmap,
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .padding(bottom = 6.dp)
                                 )
                            }
                        }
                        if (message.text.isNotEmpty()) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextWhite,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        MessageRole.ASSISTANT -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 48.dp, max = 560.dp),
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Subtle Agent header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AMC Agent",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }

                        if (message.text.isNotEmpty()) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextWhite,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.5.sp
                                )
                            )
                        }

                        // If tool calls are present
                        val allCalls = if (message.toolCalls.isNotEmpty()) message.toolCalls else listOfNotNull(message.toolCall)
                        if (allCalls.isNotEmpty()) {
                            if (message.text.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (tc in allCalls) {
                                    val isThisWaiting = pendingApproval?.first == message.id && pendingApproval.second.id == tc.id
                                    if (isThisWaiting) {
                                        ApprovalPromptCard(
                                            toolCall = pendingApproval.second,
                                            onApprove = { onApproveTool(tc.id) },
                                            onReject = { onRejectTool(tc.id) }
                                        )
                                    } else {
                                        val matchingResult = if (message.toolResult?.toolCallId == tc.id) message.toolResult else null
                                        StreamingTerminalCard(
                                            toolCall = tc,
                                            toolResult = matchingResult,
                                            liveOutput = if (isThisWaiting || message.status == MessageStatus.EXECUTING_TOOL) message.streamingTerminalOutput else "",
                                            status = if (matchingResult != null) {
                                                if (matchingResult.isError) MessageStatus.ERROR else MessageStatus.COMPLETED
                                            } else {
                                                message.status
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        MessageRole.SYSTEM -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 10.5.sp, lineHeight = 14.5.sp)
                    )
                }
            }
        }

        MessageRole.TOOL -> {
            // Handled inside the assistant card
        }
    }
}
