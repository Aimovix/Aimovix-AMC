package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.MessageStatus
import com.agent.mobile.ui.theme.DarkCard
import com.agent.mobile.ui.theme.GreenPrimary
import com.agent.mobile.ui.theme.TextMuted

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
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(GreenPrimary.copy(alpha = 0.15f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                    )
                }
            }
        }

        MessageRole.ASSISTANT -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(DarkCard)
                        .padding(12.dp)
                ) {
                    if (message.text.isNotEmpty()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
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

        MessageRole.SYSTEM -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
            }
        }

        MessageRole.TOOL -> {
            // Tool observations are presented inside the assistant bubble, but if standalone, shown as compact info
        }
    }
}
