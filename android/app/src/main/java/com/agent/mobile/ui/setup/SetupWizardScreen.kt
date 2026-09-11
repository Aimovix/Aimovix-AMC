package com.agent.mobile.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import com.agent.mobile.ui.theme.*

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Termux Einrichtung",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "VERBINDUNGSSTATUS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextMuted,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        when (val st = connectionStatus) {
                            is ConnectionStatus.Connected -> {
                                Text(
                                    "Verbunden (Akku: ${st.info.batteryPercentage ?: "?"}%)",
                                    color = StatusOnline,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            is ConnectionStatus.Connecting -> {
                                Text("Verbinde mit Termux...", color = YellowWarning, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            is ConnectionStatus.AuthFailed -> {
                                Text("Auth-Token ungültig", color = RedEmergency, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            is ConnectionStatus.Error -> {
                                Text("Fehler: ${st.message}", color = RedEmergency, fontSize = 12.sp)
                            }
                            else -> {
                                Text("Getrennt (ws://127.0.0.1:8765)", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                        ) {
                            Text("Termux", fontSize = 13.sp)
                        }

                        Button(
                            onClick = { bridgeClient.connect(inputToken) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Verbinden", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 1: Install Termux & Termux:API
            Text(
                "Schritt 1: Apps installieren",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Termux muss aus F-Droid installiert werden (die Version aus dem Google Play Store ist veraltet).",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
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
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("2. Termux:API", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 2: 1-Click Setup Command
            Text(
                "Schritt 2: 1-Klick Setup in Termux ausführen",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Kopiere diesen Befehl, öffne Termux und füge ihn ein. Er installiert Python, die Termux:API-Tools und startet den Hintergrund-Bridge-Dienst automatisch:",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Command Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = TerminalBg,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Text(
                    text = "curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh | bash",
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(
                        "AMC Setup",
                        "curl -sL https://raw.githubusercontent.com/Aimovix/Aimovix-AMC/main/termux-bridge/setup.sh | bash"
                    )
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "AMC-Befehl kopiert! Jetzt in Termux einfügen.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Setup-Befehl kopieren", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 3: Android Battery Optimization (Critical for background persistence)
            Text(
                "Schritt 3: Wichtig – Termux Akku-Optimierung deaktivieren",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Android pausiert oder friert Hintergrund-Apps beim Wechseln ein, wenn die Akku-Optimierung aktiv ist. Um dauerhafte Verbindung zu sichern:",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "1. Öffne die Termux App-Info (Button unten)\n2. Tippe auf 'Akku' oder 'Akkunutzung'\n3. Wähle 'Uneingeschränkt' / 'Nicht optimiert'",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontSize = 12.sp, lineHeight = 18.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:com.termux")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Öffne Android-Einstellungen -> Apps -> Termux -> Akku", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderSubtle),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Termux App-Info öffnen (Akku)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 4: Auth Token
            Text(
                "Schritt 4: Sicherheits-Token (Optional)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Beim Start zeigt Termux deinen generierten Auth-Token an. Trage ihn hier ein, falls du die Bridge mit Token abgesichert hast:",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = inputToken,
                onValueChange = {
                    val trimmed = it.trim()
                    inputToken = trimmed
                    onTokenChanged(trimmed)
                },
                label = { Text("Auth Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        if (clip.isNotEmpty()) {
                            inputToken = clip
                            onTokenChanged(clip)
                            Toast.makeText(context, "Token eingefügt!", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Aus Zwischenablage einfügen", tint = AccentPrimary)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPrimary,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    onTokenChanged(inputToken)
                    bridgeClient.connect(inputToken)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Jetzt verbinden", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 5: Termux Commands Cheatsheet
            Text(
                "Termux CLI-Befehle (`amc`)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = TerminalBg,
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("amc start    -> Startet Bridge im Hintergrund", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc status   -> Prüft PID, Port 8765 & Akku", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc restart  -> Startet den Dienst neu", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc logs     -> Zeigt Live-Ausgaben des Daemons", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc stop     -> Beendet den Hintergrunddienst", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}


