package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            label = "Akku Status",
            icon = Icons.Default.BatteryChargingFull,
            prompt = "Prüfe meinen Akkustand und sage mir, ob das Handy geladen wird."
        ),
        QuickAction(
            label = "WLAN Info",
            icon = Icons.Default.Wifi,
            prompt = "Zeige mir Details über mein verbundenes WLAN an (SSID, IP, Signalstärke)."
        ),
        QuickAction(
            label = "Kamera Foto",
            icon = Icons.Default.CameraAlt,
            prompt = "Mache ein Foto mit der Hauptkamera und speichere es im Termux Home-Verzeichnis."
        ),
        QuickAction(
            label = "Clipboard",
            icon = Icons.Default.ContentPaste,
            prompt = "Lies den aktuellen Inhalt der Android Zwischenablage aus."
        ),
        QuickAction(
            label = "Notification",
            icon = Icons.Default.Notifications,
            prompt = "Sende eine Android-Systembenachrichtigung mit dem Titel 'AMC Agent' und Text 'Bereit'."
        ),
        QuickAction(
            label = "Vorlesen (TTS)",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            prompt = "Lies deine letzte Antwort laut über den Smartphone-Lautsprecher vor."
        )
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            Surface(
                onClick = { onActionSelected(action.prompt) },
                shape = RoundedCornerShape(8.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = action.label,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

