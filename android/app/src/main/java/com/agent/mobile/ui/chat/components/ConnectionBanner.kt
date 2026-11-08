package com.agent.mobile.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.ui.theme.*

/**
 * Shown only while Termux cannot be reached: one plain sentence and the two actions that can fix
 * it. Nothing is shown when the bridge is connected.
 */
@Composable
fun ConnectionBanner(
    status: ConnectionStatus,
    onReconnect: () -> Unit,
    onBoost: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status is ConnectionStatus.Connected) return
    val (text, tone) = when (status) {
        is ConnectionStatus.Connecting -> "Connecting to Termux…" to TextSecondary
        is ConnectionStatus.AuthFailed -> "The token doesn't match. Check it in Setup." to RedText
        is ConnectionStatus.Error -> "Termux isn't reachable. Run 'amc start' in Termux." to RedText
        else -> "The Termux bridge isn't running." to YellowWarning
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = DarkCard
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (status is ConnectionStatus.Connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = AccentPrimary
                )
            } else {
                TextButton(onClick = onBoost) {
                    Text("Boost", style = MaterialTheme.typography.labelLarge, color = AccentPrimary)
                }
                TextButton(onClick = onReconnect) {
                    Text("Reconnect", style = MaterialTheme.typography.labelLarge, color = AccentPrimary)
                }
            }
        }
    }
}
