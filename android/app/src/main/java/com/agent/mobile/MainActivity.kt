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
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.ui.chat.ChatScreen
import com.agent.mobile.ui.settings.SettingsScreen
import com.agent.mobile.ui.setup.SetupWizardScreen
import com.agent.mobile.ui.theme.*


class MainViewModel : androidx.lifecycle.ViewModel() {
    lateinit var bridgeClient: TermuxBridgeClient
    lateinit var agentEngine: AutonomousAgentEngine
    lateinit var preferenceManager: PreferenceManager
    lateinit var database: AppDatabase
    lateinit var chatRepository: ChatRepository
    var isInitialized = false
        private set

    fun init(context: android.content.Context) {
        if (isInitialized) return
        preferenceManager = PreferenceManager(context.applicationContext)
        database = AppDatabase.getInstance(context.applicationContext)
        chatRepository = ChatRepository(database)
        val savedToken = preferenceManager.loadAuthToken()
        bridgeClient = TermuxBridgeClient(token = savedToken)
        agentEngine = AutonomousAgentEngine(
            bridgeClient = bridgeClient,
            preferenceManager = preferenceManager,
            chatRepository = chatRepository
        )
        bridgeClient.connect(savedToken)
        isInitialized = true
    }

    override fun onCleared() {
        super.onCleared()
        if (::bridgeClient.isInitialized) {
            bridgeClient.disconnect()
        }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val bridgeClient: TermuxBridgeClient get() = viewModel.bridgeClient
    private val agentEngine: AutonomousAgentEngine get() = viewModel.agentEngine
    private val preferenceManager: PreferenceManager get() = viewModel.preferenceManager
    private val database: AppDatabase get() = viewModel.database
    private val chatRepository: ChatRepository get() = viewModel.chatRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read preferences & database via ViewModel
        try {
            viewModel.init(this)
        } catch (error: IllegalStateException) {
            setContent {
                AutonomousAgentTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Secure storage unavailable", style = MaterialTheme.typography.titleLarge)
                            Text("Unlock your device and reopen AMC. Credentials cannot be loaded or saved until encrypted storage is available.")
                            Button(onClick = { recreate() }) { Text("Retry") }
                        }
                    }
                }
            }
            return
        }

        val savedToken = preferenceManager.loadAuthToken()

        // Request notification permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

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
                                    label = {
                                        Text(
                                            text = "Chat",
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            fontWeight = if (selectedTab == 0) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                                        )
                                    },
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
                                    label = {
                                        Text(
                                            text = "Terminal",
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            fontWeight = if (selectedTab == 1) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                                        )
                                    },
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
                                    label = {
                                        Text(
                                            text = "Setup",
                                            fontSize = 10.5.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            fontWeight = if (selectedTab == 2) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                                        )
                                    },
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
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                    label = {
                                        Text(
                                            text = "Settings",
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            fontWeight = if (selectedTab == 3) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                                        )
                                    },
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
                                preferenceManager = preferenceManager,
                                chatRepository = chatRepository,
                                bridgeClient = bridgeClient
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.isInitialized) bridgeClient.reconnectIfDisconnected(force = true)
    }

    override fun onResume() {
        super.onResume()
        // Automatically reconnect the moment the user switches back from Termux to AMC
        if (viewModel.isInitialized) bridgeClient.reconnectIfDisconnected(force = true)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

