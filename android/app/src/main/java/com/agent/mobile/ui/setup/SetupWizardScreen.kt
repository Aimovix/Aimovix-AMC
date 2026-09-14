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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val connectionStatus by bridgeClient.connectionStatus.collectAsState()
    var inputToken by remember { mutableStateOf(savedToken) }
    var isTermuxIgnoringBattery by remember {
        mutableStateOf(TermuxBridgeClient.isIgnoringBatteryOptimizations(context, "com.termux"))
    }
    val scrollState = rememberScrollState()

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isTermuxIgnoringBattery = TermuxBridgeClient.isIgnoringBatteryOptimizations(context, "com.termux")
                bridgeClient.reconnectIfDisconnected(force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Termux setup",
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "CONNECTION STATUS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp,
                                letterSpacing = 0.8.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        when (val st = connectionStatus) {
                            is ConnectionStatus.Connected -> {
                                Text(
                                    "Connected (battery: ${st.info.batteryPercentage ?: "?"}%)",
                                    color = StatusOnline,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            is ConnectionStatus.Connecting -> {
                                Text("Connecting to Termux...", color = YellowWarning, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            is ConnectionStatus.AuthFailed -> {
                                Text("Invalid authentication token", color = RedEmergency, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            is ConnectionStatus.Error -> {
                                Text("Error: ${st.message}", color = RedEmergency, fontSize = 11.5.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            else -> {
                                Text("Disconnected (ws://127.0.0.1:8765)", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                TermuxBridgeClient.openTermuxApp(context)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, BorderSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Termux", fontSize = 11.5.sp, maxLines = 1)
                        }

                        Button(
                            onClick = { bridgeClient.connect(inputToken) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reconnect", fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 1: Install Termux & Termux:API
            Text(
                "Step 1: Install the apps",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Install Termux and Termux:API from F-Droid for this setup. Use the same source for both apps.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("1. Termux", fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux.api/"))
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("2. Termux:API", fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 2: 1-Click Setup Command
            Text(
                "Step 2: Run setup in Termux",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Copy this command, open Termux, and paste it. It installs Python and the Termux:API tools, then starts the bridge service:",
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
                    Toast.makeText(context, "Setup command copied. Paste it in Termux.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy setup command", fontWeight = FontWeight.Bold)
            }

            // Warning Box: Keep Termux in background, do not exit
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = YellowWarning.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, YellowWarning.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = YellowWarning, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "After setup, leave Termux running in the background using the Home button. Avoid exiting or force-stopping it.",
                        color = TextWhite,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 3: Android Battery Optimization (Critical for background persistence)
            Text(
                "Step 3: Disable battery optimization for Termux",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Android may suspend background apps. Adjust these settings to improve connection reliability:",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = TextSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic Battery Optimization Status Card
            if (isTermuxIgnoringBattery) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = StatusOnline.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StatusOnline.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusOnline, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Battery optimization disabled",
                                color = StatusOnline,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Battery optimization for Termux is disabled. Android or device-specific limits may still stop background processes.",
                                color = TextWhite,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = RedEmergency.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, RedEmergency.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = RedEmergency, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Battery optimization for Termux is enabled",
                                color = RedEmergency,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Android may suspend Termux in the background. Select 'Unrestricted' or 'Not optimized' in its battery settings.",
                                color = TextWhite,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    try {
                        context.startActivity(TermuxBridgeClient.getTermuxBatterySettingsIntent())
                    } catch (e: Exception) {
                        try {
                            context.startActivity(TermuxBridgeClient.getIgnoreBatteryOptimizationListIntent())
                        } catch (e2: Exception) {
                            Toast.makeText(context, "Open Android Settings -> Apps -> Termux -> Battery -> Unrestricted", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Disable battery optimization for Termux",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 2,
                    softWrap = true,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(TermuxBridgeClient.getTermuxNotificationSettingsIntent())
                        } catch (e: Exception) {
                            try {
                                context.startActivity(TermuxBridgeClient.getTermuxBatterySettingsIntent())
                            } catch (e2: Exception) {
                                Toast.makeText(context, "Open Settings -> Apps -> Termux -> Notifications", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Notifications", fontSize = 11.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(TermuxBridgeClient.getDeveloperOptionsIntent())
                        } catch (e: Exception) {
                            Toast.makeText(context, "Open Developer options in Android Settings", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Developer tools", fontSize = 11.sp, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 4: Auth Token
            Text(
                "Step 4: Pair with the required security token",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Run 'amc token' in Termux to display your pairing token. Enter it here. Every connection requires this token.",
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
                            Toast.makeText(context, "Token pasted!", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard", tint = AccentPrimary)
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
                Text("Connect now", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 5: Termux Commands Cheatsheet
            Text(
                "Termux CLI commands (`amc`)",
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
                    Text("amc boost    -> Restore wake lock and battery exemption", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc start    -> Start the bridge in the background (wake lock)", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc status   -> Show service status and authentication requirement", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc restart  -> Restart the service", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc logs     -> Show live daemon output", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("amc stop     -> Stop the background service", color = TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}


