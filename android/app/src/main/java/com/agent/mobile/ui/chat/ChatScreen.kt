package com.agent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.model.ExecutionMode
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.chat.components.MessageBubble
import com.agent.mobile.ui.theme.DarkBackground
import com.agent.mobile.ui.theme.DarkCard
import com.agent.mobile.ui.theme.GreenPrimary
import com.agent.mobile.ui.theme.RedEmergency
import com.agent.mobile.ui.theme.YellowWarning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    agentEngine: AutonomousAgentEngine,
    bridgeClient: TermuxBridgeClient,
    onNavigateSetup: () -> Unit,
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by agentEngine.messages.collectAsState()
    val isBusy by agentEngine.isBusy.collectAsState()
    val executionMode by agentEngine.executionMode.collectAsState()
    val modelConfig by agentEngine.modelConfig.collectAsState()
    val connectionStatus by bridgeClient.connectionStatus.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AMC",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Status Dot
                            val (dotColor, statusLabel) = when (connectionStatus) {
                                is ConnectionStatus.Connected -> Pair(GreenPrimary, "Verbunden")
                                is ConnectionStatus.Connecting -> Pair(YellowWarning, "Verbindet...")
                                is ConnectionStatus.AuthFailed -> Pair(RedEmergency, "Auth-Fehler")
                                else -> Pair(Color.Gray, "Getrennt")
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AI Mobile Center • $statusLabel",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                            )
                        }
                    }
                },
                actions = {
                    // Autopilot toggle chip
                    FilterChip(
                        selected = executionMode == ExecutionMode.AUTOPILOT,
                        onClick = {
                            agentEngine.setExecutionMode(
                                if (executionMode == ExecutionMode.AUTOPILOT) ExecutionMode.STEP_BY_STEP else ExecutionMode.AUTOPILOT
                            )
                        },
                        label = {
                            Text(
                                text = if (executionMode == ExecutionMode.AUTOPILOT) "Autopilot" else "Freigabe",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (executionMode == ExecutionMode.AUTOPILOT) Icons.Default.Bolt else Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GreenPrimary.copy(alpha = 0.2f),
                            selectedLabelColor = GreenPrimary,
                            selectedLeadingIconColor = GreenPrimary
                        )
                    )

                    IconButton(onClick = { agentEngine.clearHistory() }) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Verlauf leeren", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        floatingActionButton = {
            if (isBusy) {
                ExtendedFloatingActionButton(
                    onClick = { agentEngine.emergencyStop() },
                    containerColor = RedEmergency,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.Stop, contentDescription = "Not-Aus") },
                    text = { Text("NOT-AUS", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Unconnected Banner
            if (connectionStatus !is ConnectionStatus.Connected) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = YellowWarning.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = YellowWarning, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Termux Bridge nicht verbunden",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, color = YellowWarning)
                            )
                        }
                        TextButton(onClick = onNavigateSetup) {
                            Text("Einrichten", color = GreenPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = GreenPrimary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "AMC – AI Mobile Center bereit",
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Gib einen Befehl ein wie z. B.:\n'Prüfe Akku und erstelle test.txt'\n'Nimm ein Foto mit der Kamera auf'\n'Sende eine SMS'",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray, fontSize = 13.sp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onApproveTool = { agentEngine.approvePendingAction() },
                        onRejectTool = { agentEngine.rejectPendingAction() }
                    )
                }
            }

            // Quick Action Hardware Toolbar
            com.agent.mobile.ui.chat.components.QuickActionToolbar(
                onActionSelected = { prompt ->
                    if (!isBusy) {
                        agentEngine.startTask(prompt)
                    }
                }
            )

            // Input Bar
            Surface(
                color = DarkCard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Auftrag an Agenten eingeben...", fontSize = 14.sp, color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        maxLines = 4
                    )

                    // Speech-to-Text Microphone
                    com.agent.mobile.ui.chat.components.VoiceInputButton(
                        onSpeechResult = { spokenText ->
                            inputText = spokenText
                        }
                    )

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !isBusy) {
                                val textToSend = inputText.trim()
                                inputText = ""
                                agentEngine.startTask(textToSend)
                            }
                        },
                        enabled = inputText.isNotBlank() && !isBusy
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Senden",
                            tint = if (inputText.isNotBlank() && !isBusy) GreenPrimary else Color.DarkGray
                        )
                    }
                }
            }
        }
    }
}
