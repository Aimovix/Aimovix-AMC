package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.mobile.ui.theme.*

val EMPTY_STATE_SUGGESTIONS = listOf(
    "How full is my storage? Show the largest folders in my home directory.",
    "Remind me tomorrow at 9:00 to check my bills.",
    "What is my Wi-Fi network, and how strong is the signal?",
    "Find the newest photo and tell me its size."
)

/**
 * Example requests on an empty chat. A tap puts the text into the input, so the user can change it
 * and decides when to send.
 */
@Composable
fun EmptyStateSuggestions(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Tap an example to edit it before you send.",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted
        )
        EMPTY_STATE_SUGGESTIONS.forEach { suggestion ->
            val source = remember(suggestion) { MutableInteractionSource() }
            Surface(
                onClick = { onPick(suggestion) },
                shape = MaterialTheme.shapes.medium,
                color = DarkCard,
                interactionSource = source,
                modifier = Modifier.fillMaxWidth().pressScale(source)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextWhite,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
