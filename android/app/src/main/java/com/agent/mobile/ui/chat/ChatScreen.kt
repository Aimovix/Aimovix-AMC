package com.agent.mobile.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.model.ExecutionMode
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.chat.components.MessageBubble
import com.agent.mobile.ui.chat.components.QuickActionToolbar
import com.agent.mobile.ui.chat.components.VoiceInputButton
import com.agent.mobile.ui.theme.*

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
    var showModelPickerDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

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
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "AMC",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))

                                // Quick Model Selector Pill
                                Surface(
                                    onClick = { showModelPickerDialog = true },
                                    shape = RoundedCornerShape(6.dp),
                                    color = DarkCard,
                                    border = BorderStroke(1.dp, BorderSubtle)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = modelConfig.modelName.ifEmpty { modelConfig.provider.defaultModel },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = TextSecondary,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Modell wechseln",
                                            tint = TextMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }

                            // Connection Status row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                val (dotColor, statusLabel) = when (connectionStatus) {
                                    is ConnectionStatus.Connected -> Pair(StatusOnline, "Verbunden")
                                    is ConnectionStatus.Connecting -> Pair(YellowWarning, "Verbindet...")
                                    is ConnectionStatus.AuthFailed -> Pair(RedEmergency, "Auth-Fehler")
                                    else -> Pair(TextMuted, "Getrennt")
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Termux • $statusLabel",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        // Execution mode toggle chip
                        Surface(
                            onClick = {
                                agentEngine.setExecutionMode(
                                    if (executionMode == ExecutionMode.AUTOPILOT) ExecutionMode.STEP_BY_STEP else ExecutionMode.AUTOPILOT
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (executionMode == ExecutionMode.AUTOPILOT) AccentPrimary.copy(alpha = 0.12f) else DarkCard,
                            border = BorderStroke(
                                1.dp,
                                if (executionMode == ExecutionMode.AUTOPILOT) AccentPrimary.copy(alpha = 0.4f) else BorderSubtle
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (executionMode == ExecutionMode.AUTOPILOT) Icons.Default.Bolt else Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = if (executionMode == ExecutionMode.AUTOPILOT) AccentPrimary else TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (executionMode == ExecutionMode.AUTOPILOT) "Autopilot" else "Freigabe",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (executionMode == ExecutionMode.AUTOPILOT) AccentPrimary else TextSecondary
                                )
                            }
                        }

                        IconButton(onClick = { agentEngine.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Verlauf leeren",
                                tint = TextMuted
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
        },
        floatingActionButton = {
            if (isBusy) {
                ExtendedFloatingActionButton(
                    onClick = { agentEngine.emergencyStop() },
                    containerColor = RedEmergency,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, YellowWarning.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = YellowWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Termux Bridge nicht verbunden",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = TextSecondary)
                            )
                        }
                        TextButton(
                            onClick = onNavigateSetup,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Einrichten", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp, bottom = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = DarkCard,
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = AccentPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "AMC Autonomous Terminal",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Führe beliebige Befehle aus oder steuere Android-Sensoren via Termux.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextMuted,
                                    fontSize = 12.sp
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Suggested prompt shortcuts
                            val samplePrompts = listOf(
                                "Prüfe meinen Akkustand und Speicherplatz",
                                "Erstelle eine Datei test.txt mit aktuellem Datum",
                                "Zeige mir alle aktiven Netzwerkverbindungen"
                            )

                            samplePrompts.forEach { prompt ->
                                Surface(
                                    onClick = { inputText = prompt },
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkCard,
                                    border = BorderStroke(1.dp, BorderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SubdirectoryArrowRight,
                                            contentDescription = null,
                                            tint = AccentPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = prompt,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = TextSecondary,
                                                fontSize = 12.sp
                                            )
                                        )
                                    }
                                }
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
            QuickActionToolbar(
                onActionSelected = { prompt ->
                    if (!isBusy) {
                        agentEngine.startTask(prompt)
                    }
                }
            )

            // Modern Floating Input Container
            Surface(
                color = DarkCard,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                "Befehl oder Aufgabe eingeben...",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isBusy) {
                                    val textToSend = inputText.trim()
                                    inputText = ""
                                    focusManager.clearFocus()
                                    agentEngine.startTask(textToSend)
                                }
                            }
                        )
                    )

                    // Speech-to-Text Microphone
                    VoiceInputButton(
                        onSpeechResult = { spokenText ->
                            inputText = spokenText
                        }
                    )

                    // Send Button
                    val canSend = inputText.isNotBlank() && !isBusy
                    Surface(
                        onClick = {
                            if (canSend) {
                                val textToSend = inputText.trim()
                                inputText = ""
                                focusManager.clearFocus()
                                agentEngine.startTask(textToSend)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (canSend) AccentPrimary else DarkCardElevated,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Senden",
                                tint = if (canSend) Color.Black else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Quick Model Picker Dialog
    if (showModelPickerDialog) {
        QuickModelPickerDialog(
            currentConfig = modelConfig,
            onDismiss = { showModelPickerDialog = false },
            onModelSelected = { newConfig ->
                agentEngine.setModelConfig(newConfig)
                showModelPickerDialog = false
            }
        )
    }
}

@Composable
private fun QuickModelPickerDialog(
    currentConfig: ModelConfig,
    onDismiss: () -> Unit,
    onModelSelected: (ModelConfig) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(currentConfig.provider) }
    var selectedModel by remember { mutableStateOf(currentConfig.modelName) }
    var customModelInput by remember { mutableStateOf(currentConfig.modelName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Modell auswählen",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen", tint = TextMuted)
                    }
                }

                // Provider Switcher Row
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Provider",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProviderType.values().forEach { provider ->
                            val isSelected = selectedProvider == provider
                            Surface(
                                onClick = {
                                    selectedProvider = provider
                                    selectedModel = provider.defaultModel
                                    customModelInput = provider.defaultModel
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) AccentPrimary.copy(alpha = 0.15f) else DarkSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) AccentPrimary else BorderSubtle
                                )
                            ) {
                                Text(
                                    text = provider.displayName,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isSelected) AccentPrimary else TextSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }

                // Suggested Models Chips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Empfohlene Modelle (${selectedProvider.displayName})",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedProvider.suggestedModels.forEach { model ->
                            val isChosen = customModelInput.trim().equals(model, ignoreCase = true)
                            FilterChip(
                                selected = isChosen,
                                onClick = {
                                    selectedModel = model
                                    customModelInput = model
                                },
                                label = {
                                    Text(
                                        text = model,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentPrimary.copy(alpha = 0.15f),
                                    selectedLabelColor = AccentPrimary,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isChosen,
                                    borderColor = if (isChosen) AccentPrimary else BorderSubtle,
                                    selectedBorderColor = AccentPrimary,
                                    borderWidth = 1.dp
                                )
                            )
                        }
                    }
                }

                // Custom Model Name Input Field (Free text entry)
                OutlinedTextField(
                    value = customModelInput,
                    onValueChange = { customModelInput = it },
                    label = { Text("Modellname frei eingeben", fontSize = 12.sp) },
                    placeholder = { Text("z. B. gemini-2.0-flash, gpt-4o...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextWhite
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface
                    )
                )

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Abbrechen", color = TextMuted, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val chosenModel = customModelInput.trim().ifEmpty { selectedProvider.defaultModel }
                            val newConfig = currentConfig.copy(
                                provider = selectedProvider,
                                modelName = chosenModel,
                                baseUrl = if (selectedProvider != currentConfig.provider) selectedProvider.defaultBaseUrl else currentConfig.baseUrl
                            )
                            onModelSelected(newConfig)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Übernehmen", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

