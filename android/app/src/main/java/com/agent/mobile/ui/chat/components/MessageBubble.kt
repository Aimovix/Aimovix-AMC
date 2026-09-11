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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.ui.theme.*

@Composable
fun MessageBubble(
    message: ChatMessage,
    onApproveTool: () -> Unit,
    onRejectTool: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (message.role) {
        MessageRole.USER -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 320.dp),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = DarkCardElevated,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (message.imageBase64 != null) {
                            val bitmap = remember(message.imageBase64) {
                                try {
                                    val bytes = android.util.Base64.decode(message.imageBase64, android.util.Base64.DEFAULT)
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap,
                                    contentDescription = "Angehängtes Bild",
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
                                    lineHeight = 20.sp
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
                    modifier = Modifier.fillMaxWidth(0.95f),
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Subtle Agent header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AMC Agent",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }

                        if (message.text.isNotEmpty()) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextWhite,
                                    lineHeight = 21.sp
                                )
                            )
                        }

                        // If a tool call is present
                        if (message.toolCall != null) {
                            if (message.text.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            if (message.status == MessageStatus.WAITING_FOR_APPROVAL) {
                                ApprovalPromptCard(
                                    toolCall = message.toolCall,
                                    onApprove = onApproveTool,
                                    onReject = onRejectTool
                                )
                            } else {
                                StreamingTerminalCard(
                                    toolCall = message.toolCall,
                                    toolResult = message.toolResult,
                                    liveOutput = message.streamingTerminalOutput,
                                    status = message.status
                                )
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
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
            }
        }

        MessageRole.TOOL -> {
            // Handled inside the assistant card
        }
    }
}
