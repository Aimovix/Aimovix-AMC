package com.agent.mobile.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.theme.DarkBackground
import com.agent.mobile.ui.theme.DarkCard
import com.agent.mobile.ui.theme.GreenPrimary
import com.agent.mobile.ui.theme.RedEmergency
import com.agent.mobile.ui.theme.TerminalBg
import com.agent.mobile.ui.theme.YellowWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    bridgeClient: TermuxBridgeClient,
    savedToken: String,
    onTokenChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionStatus by bridgeClient.connectionStatus.collectAsState()
    var inputToken by remember { mutableStateOf(savedToken) }
    val scrollState = rememberScrollState()

    // Standalone self-extracting one-click bash setup script
    val oneClickCommand = """
pkg update -y && pkg install -y python python-pip termux-api git curl jq && pip install websockets && curl -sL https://raw.githubusercontent.com/agent/mobile/main/termux-bridge/bridge_daemon.py -o ~/.termux_bridge.py 2>/dev/null || true; python -c "import urllib.request; urllib.request.urlretrieve('https://raw.githubusercontent.com/agent/mobile/main/termux-bridge/bridge_daemon.py', 'bridge.py')" 2>/dev/null || true; python ~/.termux_bridge.py
    """.trimIndent()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Termux Einrichtung", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Live Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Verbindungsstatus", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        when (val st = connectionStatus) {
                            is ConnectionStatus.Connected -> {
                                Text("✅ Verbunden (Akku: ${st.info.batteryPercentage ?: "?"}%)", color = GreenPrimary, fontWeight = FontWeight.Bold)
                            }
                            is ConnectionStatus.Connecting -> {
                                Text("⏳ Verbinde mit Termux...", color = YellowWarning, fontWeight = FontWeight.Bold)
                            }
                            is ConnectionStatus.AuthFailed -> {
                                Text("❌ Auth-Token ungültig", color = RedEmergency, fontWeight = FontWeight.Bold)
                            }
                            is ConnectionStatus.Error -> {
                                Text("⚠️ Fehler: ${st.message}", color = RedEmergency, fontSize = 12.sp)
                            }
                            else -> {
                                Text("⚪ Getrennt (ws://127.0.0.1:8765)", color = Color.Gray, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Button(
                        onClick = { bridgeClient.connect(inputToken) },
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Verbinden")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 1: Install Termux & Termux:API
            Text("Schritt 1: Apps installieren", style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Termux muss aus F-Droid installiert werden (die Version aus dem Google Play Store ist veraltet).",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color.LightGray)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("1. Termux", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux.api/"))
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("2. Termux:API", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 2: 1-Click Setup Command
            Text("Schritt 2: 1-Klick Setup in Termux ausführen", style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Kopiere diesen Befehl, öffne Termux und füge ihn ein. Er installiert Python, die Termux:API-Tools und startet den Hintergrund-Bridge-Dienst automatisch:",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color.LightGray)
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Command Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TerminalBg)
                    .padding(12.dp)
            ) {
                Text(
                    text = "curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix/main/termux-bridge/setup.sh | bash",
                    color = GreenPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(
                        "AMC Setup",
                        "curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix/main/termux-bridge/setup.sh | bash"
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "AMC-Befehl kopiert! Jetzt in Termux einfügen.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Setup-Befehl kopieren", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 3: Auth Token
            Text("Schritt 3: Sicherheits-Token (Optional)", style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Beim Start zeigt Termux deinen generierten Auth-Token an. Trage ihn hier ein, falls du die Bridge mit Token abgesichert hast:",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color.LightGray)
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = inputToken,
                onValueChange = {
                    inputToken = it
                    onTokenChanged(it)
                },
                label = { Text("Auth Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GreenPrimary,
                    unfocusedBorderColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
    }
}
