package com.agent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.service.AgentForegroundService
import com.agent.mobile.ui.chat.ChatScreen
import com.agent.mobile.ui.settings.SettingsScreen
import com.agent.mobile.ui.setup.SetupWizardScreen
import com.agent.mobile.ui.theme.AutonomousAgentTheme
import com.agent.mobile.ui.theme.DarkBackground
import com.agent.mobile.ui.theme.DarkSurface
import com.agent.mobile.ui.theme.GreenPrimary

class MainActivity : ComponentActivity() {

    private lateinit var bridgeClient: TermuxBridgeClient
    private lateinit var agentEngine: AutonomousAgentEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read preferences
        val prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        val savedToken = prefs.getString("auth_token", "") ?: ""

        bridgeClient = TermuxBridgeClient(token = savedToken)
        agentEngine = AutonomousAgentEngine(bridgeClient)

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
                        NavigationBar(
                            containerColor = DarkSurface,
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                                label = { Text("Chat", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GreenPrimary,
                                    selectedTextColor = GreenPrimary,
                                    indicatorColor = GreenPrimary.copy(alpha = 0.15f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Build, contentDescription = "Setup") },
                                label = { Text("Setup", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GreenPrimary,
                                    selectedTextColor = GreenPrimary,
                                    indicatorColor = GreenPrimary.copy(alpha = 0.15f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Einstellungen") },
                                label = { Text("Einstellungen", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GreenPrimary,
                                    selectedTextColor = GreenPrimary,
                                    indicatorColor = GreenPrimary.copy(alpha = 0.15f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
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
                                onNavigateSetup = { selectedTab = 1 },
                                onNavigateSettings = { selectedTab = 2 }
                            )
                            1 -> SetupWizardScreen(
                                bridgeClient = bridgeClient,
                                savedToken = currentToken,
                                onTokenChanged = { newToken ->
                                    currentToken = newToken
                                    prefs.edit().putString("auth_token", newToken).apply()
                                    bridgeClient.connect(newToken)
                                }
                            )
                            2 -> SettingsScreen(
                                agentEngine = agentEngine
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridgeClient.disconnect()
    }
}
