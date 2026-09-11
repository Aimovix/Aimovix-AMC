package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.ui.theme.DarkCard
import com.agent.mobile.ui.theme.GreenPrimary

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
            label = "Akku & Health",
            icon = Icons.Default.BatteryChargingFull,
            prompt = "Prüfe meinen aktuellen Akkustand und zeige Ladezustand, Temperatur und Prozent an."
        ),
        QuickAction(
            label = "Foto machen",
            icon = Icons.Default.CameraAlt,
            prompt = "Mache ein Foto mit der Hauptkamera und speichere es ab."
        ),
        QuickAction(
            label = "GPS Standort",
            icon = Icons.Default.LocationOn,
            prompt = "Ermittle meinen aktuellen GPS-Standort und zeige die Koordinaten an."
        ),
        QuickAction(
            label = "System-Info",
            icon = Icons.Default.Memory,
            prompt = "Zeige mir den freien Speicherplatz, RAM und System-Details an."
        ),
        QuickAction(
            label = "Notification",
            icon = Icons.Default.Notifications,
            prompt = "Sende eine Android-Systembenachrichtigung mit dem Titel 'AMC Agent' und Text 'Bereit'."
        ),
        QuickAction(
            label = "Vorlesen (TTS)",
            icon = Icons.Default.VolumeUp,
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
            SuggestionChip(
                onClick = { onActionSelected(action.prompt) },
                label = { Text(action.label, fontSize = 11.sp, color = Color.White) },
                icon = {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = DarkCard
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = GreenPrimary.copy(alpha = 0.3f)
                )
            )
        }
    }
}
