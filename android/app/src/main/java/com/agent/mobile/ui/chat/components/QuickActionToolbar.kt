package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.agent.mobile.ui.theme.*

data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val prompt: String
)

@Composable
fun QuickActionToolbar(
    onActionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val actions = listOf(
        QuickAction(
            label = "Battery",
            icon = Icons.Default.BatteryChargingFull,
            prompt = "Check my battery level and tell me whether the phone is charging."
        ),
        QuickAction(
            label = "Wi-Fi",
            icon = Icons.Default.Wifi,
            prompt = "Show details of my Wi-Fi connection (SSID, IP address, signal strength)."
        ),
        QuickAction(
            label = "Photo",
            icon = Icons.Default.CameraAlt,
            prompt = "Take a photo with the rear camera and save it in the Termux home directory."
        ),
        QuickAction(
            label = "Clipboard",
            icon = Icons.Default.ContentPaste,
            prompt = "Read the current Android clipboard."
        ),
        QuickAction(
            label = "Notify",
            icon = Icons.Default.Notifications,
            prompt = "Send an Android notification titled 'AMC Agent' with the text 'Ready'."
        ),
        QuickAction(
            label = "Read aloud",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            prompt = "Read your last response aloud through the phone speaker."
        )
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            val source = remember(action.label) { MutableInteractionSource() }
            Surface(
                onClick = { onActionSelected(action.prompt) },
                shape = RoundedCornerShape(percent = 50),
                color = DarkCard,
                interactionSource = source,
                modifier = Modifier.pressScale(source)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextWhite
                    )
                }
            }
        }
    }
}
