package com.agent.mobile.ui.settings.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.channel.ChannelGatewayService
import com.agent.mobile.channel.TelegramChannelManager
import com.agent.mobile.channel.telegram.TelegramGateway
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Messaging channels: talk to the phone's agent from Telegram (like OpenClaw's gateways),
 * with one-time-code pairing and approvals as chat buttons.
 */
@Composable
fun ChannelsCard(
    preferenceManager: PreferenceManager,
    telegram: TelegramChannelManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val status by telegram.status.collectAsState()
    val state by telegram.state.collectAsState()

    var token by remember { mutableStateOf(preferenceManager.loadTelegramBotToken()) }
    var tokenVisible by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(preferenceManager.loadTelegramEnabled()) }
    var checking by remember { mutableStateOf(false) }
    var botName by remember { mutableStateOf((status as? TelegramGateway.Status.Running)?.botUsername.orEmpty()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        telegram.refreshState()
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
            telegram.refreshState()
        }
    }
    LaunchedEffect(status) {
        (status as? TelegramGateway.Status.Running)?.let { botName = it.botUsername }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentPrimary,
        unfocusedBorderColor = BorderSubtle,
        focusedTextColor = TextWhite,
        unfocusedTextColor = TextWhite,
        focusedContainerColor = DarkSurface,
        unfocusedContainerColor = DarkSurface
    )

    fun setEnabled(on: Boolean) {
        enabled = on
        preferenceManager.saveTelegramEnabled(on)
        if (on) ChannelGatewayService.start(context) else ChannelGatewayService.stop(context)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Forum, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Channels", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite))
                    Text(
                        "Talk to your agent from anywhere via Telegram. It runs on this phone; no server needed.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                    )
                }
            }

            // --- Bot token ----------------------------------------------------------------
            Text(
                "1. In Telegram, open @BotFather, send /newbot and copy the token it gives you.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.5.sp)
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("Telegram bot token", fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = TextMuted)
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = token.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        coroutineScope.launch {
                            val result = telegram.verifyToken(token)
                            checking = false
                            result.onSuccess { info ->
                                val changed = token != preferenceManager.loadTelegramBotToken()
                                preferenceManager.saveTelegramBotToken(token)
                                if (changed) {
                                    // A different bot: old pairings and sessions don't apply.
                                    telegram.resetState()
                                    if (enabled) telegram.restart()
                                }
                                botName = info.username
                                Toast.makeText(context, "Connected to @${info.username}", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(context, "Token check failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                ) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentPrimary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Check & save", fontSize = 12.sp, color = TextWhite)
                }
                if (botName.isNotEmpty()) {
                    Text("@$botName", style = MaterialTheme.typography.bodySmall.copy(color = AccentPrimary, fontWeight = FontWeight.SemiBold))
                }
            }

            HorizontalDivider(color = BorderSubtle)

            // --- On/off and status ------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("2. Telegram channel", style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
                    val (statusText, statusColor) = when (val s = status) {
                        is TelegramGateway.Status.Running -> "Listening as @${s.botUsername}" to AccentPrimary
                        TelegramGateway.Status.Starting -> "Connecting…" to TextSecondary
                        is TelegramGateway.Status.Error -> s.message to RedEmergency
                        TelegramGateway.Status.Stopped -> (if (enabled) "Starting…" else "Off") to TextMuted
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall.copy(color = statusColor, fontSize = 11.sp))
                }
                Switch(
                    checked = enabled,
                    enabled = preferenceManager.loadTelegramBotToken().isNotBlank() || enabled,
                    onCheckedChange = { setEnabled(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentPrimary)
                )
            }
            Text(
                "Tip: set Android Settings → Apps → AMC → Battery to Unrestricted so messages arrive while the phone sleeps.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.5.sp)
            )
            var delivery by remember { mutableStateOf(preferenceManager.loadTelegramDelivery()) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send results to Telegram", style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite))
                    Text(
                        "Results of background tasks, agent notifications and approval questions of background tasks " +
                            "also go to your paired accounts. Their content then passes through Telegram's servers.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.5.sp)
                    )
                }
                Switch(
                    checked = delivery,
                    onCheckedChange = {
                        delivery = it
                        preferenceManager.saveTelegramDelivery(it)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentPrimary)
                )
            }

            HorizontalDivider(color = BorderSubtle)

            // --- Pairing ----------------------------------------------------------------------
            Text("3. Pair your Telegram account", style = MaterialTheme.typography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
            Text(
                "Only paired accounts can talk to the agent. Create a one-time code (valid 15 minutes) and send it to your bot.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
            )
            val activeCode = state.pairingCode?.takeIf { now < state.pairingExpiresAt }
            if (activeCode != null) {
                Surface(color = DarkSurface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("/pair $activeCode", style = MaterialTheme.typography.titleMedium.copy(color = AccentPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                        val minutesLeft = ((state.pairingExpiresAt - now) / 60_000L).coerceAtLeast(0) + 1
                        Text("Valid for about $minutesLeft min.", style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.5.sp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (botName.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$botName?start=$activeCode"))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Telegram is not installed", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                                ) { Text("Open in Telegram", fontSize = 12.sp, color = TextWhite) }
                            }
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("AMC pairing", "/pair $activeCode"))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                border = BorderStroke(1.dp, BorderSubtle)
                            ) { Text("Copy", fontSize = 12.sp, color = TextWhite) }
                        }
                    }
                }
            } else {
                OutlinedButton(
                    enabled = enabled,
                    onClick = {
                        telegram.createPairingCode()
                        now = System.currentTimeMillis()
                    },
                    border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("Pair account", fontSize = 12.sp, color = TextWhite)
                }
            }

            if (state.paired.isNotEmpty()) {
                Text("Paired accounts", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontWeight = FontWeight.SemiBold))
                state.paired.forEach { user ->
                    Surface(color = DarkSurface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BorderSubtle), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(user.name, style = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
                                Text(
                                    "id ${user.id} · since " + java.text.DateFormat.getDateInstance().format(java.util.Date(user.pairedAt)),
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.5.sp)
                                )
                            }
                            IconButton(onClick = { telegram.unpair(user.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = RedEmergency, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Text(
                "Approvals arrive in Telegram as ✅/❌ buttons (unanswered after 10 minutes = rejected). " +
                    "Messages pass through Telegram's servers; don't use the channel for data you wouldn't send via Telegram.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.5.sp)
            )
        }
    }
}
