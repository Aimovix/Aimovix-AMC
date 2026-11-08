package com.agent.mobile.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.core.termux.TermuxRunCommand
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.launch

/**
 * One-tap pairing: AMC asks Termux (RUN_COMMAND) to start the bridge and hand over the token,
 * so the user does not have to copy anything between the apps.
 */
@Composable
fun QuickConnectCard(
    bridgeClient: TermuxBridgeClient,
    onTokenReceived: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by bridgeClient.connectionStatus.collectAsState()
    var availability by remember { mutableStateOf(TermuxRunCommand.availability(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var needsAllowExternalApps by remember { mutableStateOf(false) }
    var needsInstall by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        availability = TermuxRunCommand.availability(context)
        if (!granted) message = "Permission denied. You can still pair manually below (Step 4)."
    }

    LaunchedEffect(Unit) { availability = TermuxRunCommand.availability(context) }

    fun copy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Termux command", text))
        Toast.makeText(context, "Copied. Paste it in Termux and press Enter.", Toast.LENGTH_LONG).show()
    }

    fun autoConnect() {
        busy = true
        message = "Asking Termux for the bridge token…"
        needsAllowExternalApps = false
        needsInstall = false
        scope.launch {
            val result = TermuxRunCommand.run(context, TermuxRunCommand.FETCH_TOKEN_SCRIPT, "AMC: pair app", timeoutMs = 25_000L)
            busy = false
            val token = result?.let { TermuxRunCommand.extractToken(it.stdout) }
            when {
                result == null -> {
                    needsAllowExternalApps = true
                    message = "Termux did not answer. Allow external apps in Termux once (command below), then tap Auto-connect again."
                }
                result.errCode != -1 -> {
                    needsAllowExternalApps = result.errMessage.contains("allow-external-apps", ignoreCase = true)
                    message = "Termux refused: ${result.errMessage.ifBlank { "error ${result.errCode}" }.take(300)}"
                }
                result.exitCode == 127 -> {
                    needsInstall = true
                    message = "The AMC bridge is not installed in Termux yet."
                }
                token == null -> message = "No token received. Termux said: ${(result.stderr.ifBlank { result.stdout }).take(300)}"
                else -> {
                    onTokenReceived(token)
                    bridgeClient.forceReconnect(token)
                    message = "Token received. Connecting…"
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Quick connect (recommended)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite))
                    Text(
                        "AMC starts the bridge in Termux and pairs itself. It also restarts the bridge automatically when it stops.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.5.sp)
                    )
                }
            }

            when (availability) {
                TermuxRunCommand.Availability.TERMUX_MISSING -> Text(
                    "Termux is not installed. Install Termux and Termux:API from F-Droid first (Step 1 below).",
                    style = MaterialTheme.typography.bodySmall.copy(color = RedEmergency, fontSize = 12.sp)
                )
                TermuxRunCommand.Availability.PERMISSION_MISSING -> Button(
                    onClick = { permissionLauncher.launch(TermuxRunCommand.PERMISSION) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("1. Allow AMC to run commands in Termux", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                TermuxRunCommand.Availability.READY -> {
                    val connected = status is ConnectionStatus.Connected
                    Button(
                        onClick = { autoConnect() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                        } else {
                            Icon(if (connected) Icons.Default.CheckCircle else Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) "Connected · pair again" else "Auto-connect", fontWeight = FontWeight.Bold)
                    }
                }
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 12.sp))
            }

            if (needsAllowExternalApps) {
                CommandBox(
                    label = "Run once in Termux (allows AMC to start the bridge):",
                    command = TermuxRunCommand.ALLOW_EXTERNAL_APPS_COMMAND,
                    onCopy = ::copy
                )
            }
            if (needsInstall) {
                OutlinedButton(
                    onClick = {
                        val ok = TermuxRunCommand.start(context, TermuxRunCommand.INSTALL_SCRIPT, "AMC: install bridge", background = false)
                        message = if (ok) "The installer is running in Termux. Come back when it says 'Setup complete' and tap Auto-connect."
                        else "Could not open Termux. Run the installer from Step 3 manually."
                    },
                    border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("Install the bridge in Termux", color = TextWhite, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CommandBox(label: String, command: String, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp))
        Surface(color = TerminalBg, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BorderSubtle)) {
            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(command, color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { onCopy(command) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = AccentPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
