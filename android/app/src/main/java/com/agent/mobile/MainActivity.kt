package com.agent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.service.AgentForegroundService
import com.agent.mobile.ui.chat.ChatScreen
import com.agent.mobile.ui.settings.SettingsScreen
import com.agent.mobile.ui.setup.SetupWizardScreen
import com.agent.mobile.ui.theme.*


class MainActivity : ComponentActivity() {

    private lateinit var bridgeClient: TermuxBridgeClient
    private lateinit var agentEngine: AutonomousAgentEngine
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read preferences
        preferenceManager = PreferenceManager(this)
        val savedToken = preferenceManager.loadAuthToken()

        bridgeClient = TermuxBridgeClient(token = savedToken)
        agentEngine = AutonomousAgentEngine(
            bridgeClient = bridgeClient,
            preferenceManager = preferenceManager
        )

        // Auto-connect to local Termux bridge
        bridgeClient.connect(savedToken)

        // Start Foreground Service
        AgentForegroundService.start(this)

        setContent {
            AutonomousAgentTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                var currentToken by remember { mutableStateOf(savedToken) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBackground,
                    bottomBar = {
                        Column {
                            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                            NavigationBar(
                                containerColor = DarkSurface,
                                tonalElevation = 0.dp
                            ) {
                                NavigationBarItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                                    label = { Text("Chat", fontSize = 11.sp, fontWeight = if (selectedTab == 0) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentPrimary,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary.copy(alpha = 0.12f),
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                                    label = { Text("Terminal", fontSize = 11.sp, fontWeight = if (selectedTab == 1) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentPrimary,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary.copy(alpha = 0.12f),
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    icon = { Icon(Icons.Default.Build, contentDescription = "Setup") },
                                    label = { Text("Setup", fontSize = 11.sp, fontWeight = if (selectedTab == 2) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentPrimary,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary.copy(alpha = 0.12f),
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 3,
                                    onClick = { selectedTab = 3 },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Einstellungen") },
                                    label = { Text("Einstellungen", fontSize = 11.sp, fontWeight = if (selectedTab == 3) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentPrimary,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary.copy(alpha = 0.12f),
                                        unselectedIconColor = TextMuted,
                                        unselectedTextColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = DarkBackground
                    ) {
                        when (selectedTab) {
                            0 -> ChatScreen(
                                agentEngine = agentEngine,
                                bridgeClient = bridgeClient,
                                onNavigateSetup = { selectedTab = 2 },
                                onNavigateSettings = { selectedTab = 3 }
                            )
                            1 -> com.agent.mobile.ui.terminal.TerminalScreen(
                                bridgeClient = bridgeClient
                            )
                            2 -> SetupWizardScreen(
                                bridgeClient = bridgeClient,
                                savedToken = currentToken,
                                onTokenChanged = { newToken ->
                                    currentToken = newToken
                                    preferenceManager.saveAuthToken(newToken)
                                    bridgeClient.connect(newToken)
                                }
                            )
                            3 -> SettingsScreen(
                                agentEngine = agentEngine,
                                preferenceManager = preferenceManager
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bridgeClient.reconnectIfDisconnected(force = true)
    }

    override fun onResume() {
        super.onResume()
        // Automatically reconnect the moment the user switches back from Termux to AMC
        bridgeClient.reconnectIfDisconnected(force = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        bridgeClient.disconnect()
    }
}

