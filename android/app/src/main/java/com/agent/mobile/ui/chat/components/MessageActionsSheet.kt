package com.agent.mobile.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.ui.theme.*

/**
 * Long-press menu for one message. Copying is always offered. Sending again is offered for the
 * user's own messages only; it sends the same request as a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: ChatMessage,
    onResend: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val preview = message.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: if (message.imageBase64 != null) "Image" else "Message"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        contentColor = TextWhite,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            Text(
                text = preview,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            SheetAction(label = "Copy text", icon = Icons.Default.ContentCopy) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            if (onResend != null) {
                SheetAction(label = "Send again", icon = Icons.Default.Replay) { onResend() }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressScale(source)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = source, indication = indication) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = TextWhite)
    }
}
