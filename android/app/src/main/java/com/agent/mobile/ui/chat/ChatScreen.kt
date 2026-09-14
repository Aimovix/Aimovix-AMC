package com.agent.mobile.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.*
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.chat.components.ArtifactViewerDialog
import com.agent.mobile.ui.chat.components.MessageBubble
import com.agent.mobile.ui.chat.components.QuickActionToolbar
import com.agent.mobile.ui.chat.components.SessionDrawerContent
import com.agent.mobile.ui.chat.components.VoiceInputButton
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

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
    val currentSession by agentEngine.currentSession.collectAsState()
    val artifacts by agentEngine.artifacts.collectAsState()
    val metrics by agentEngine.metrics.collectAsState()
    val pendingApproval by agentEngine.pendingApproval.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showModelPickerDialog by remember { mutableStateOf(false) }
    var viewingArtifactPath by remember { mutableStateOf<String?>(null) }
    val viewingArtifact = artifacts.firstOrNull { it.path == viewingArtifactPath }

    // Multimodal image attachment states
    var pendingImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingImageBase64 by remember { mutableStateOf<String?>(null) }
    var pendingImageMimeType by remember { mutableStateOf<String?>("image/jpeg") }
    var showAttachMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // Activity result launchers for camera and gallery
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val res = processImageUri(context, uri)
                if (res != null) {
                    pendingImageBase64 = res.first
                    pendingImageMimeType = res.second
                    pendingImageBitmap = res.third
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) {
            coroutineScope.launch {
                val (b64, scaled) = withContext(Dispatchers.IO) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    Pair(Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP), bitmap)
                }
                pendingImageBitmap = scaled
                pendingImageBase64 = b64
                pendingImageMimeType = "image/jpeg"
            }
        }
    }

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawerContent(
                agentEngine = agentEngine,
                chatRepository = agentEngine.chatRepository,
                onCloseDrawer = { coroutineScope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = DarkBackground,
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu and chats", tint = TextWhite)
                            }
                        },
                        title = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = currentSession?.title?.take(18) ?: "AMC",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Quick Model Selector Pill
                                    Surface(
                                        onClick = { showModelPickerDialog = true },
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkCard,
                                        border = BorderStroke(1.dp, BorderSubtle),
                                        modifier = Modifier.widthIn(max = 120.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = modelConfig.modelName.ifEmpty { modelConfig.provider.defaultModel }.take(14),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = TextSecondary,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 9.5.sp
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = "Switch model",
                                                tint = TextMuted,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }

                                // Status row: Termux connection & metrics
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    val (dotColor, statusLabel) = when (connectionStatus) {
                                        is ConnectionStatus.Connected -> Pair(StatusOnline, "Connected")
                                        is ConnectionStatus.Connecting -> Pair(YellowWarning, "Connecting...")
                                        is ConnectionStatus.AuthFailed -> Pair(RedEmergency, "Authentication error")
                                        else -> Pair(TextMuted, "Disconnected")
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val costSuffix = if (metrics.estimatedCostUsd > 0.0) {
                                        " • \$${String.format(Locale.US, "%.4f", metrics.estimatedCostUsd)}"
                                    } else ""
                                    Text(
                                        text = "Termux • $statusLabel$costSuffix",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.5.sp,
                                            color = TextMuted
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = if (executionMode == ExecutionMode.AUTOPILOT) Icons.Default.Bolt else Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (executionMode == ExecutionMode.AUTOPILOT) AccentPrimary else TextMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (executionMode == ExecutionMode.AUTOPILOT) "Autopilot" else "Approval",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (executionMode == ExecutionMode.AUTOPILOT) AccentPrimary else TextSecondary
                                    )
                                }
                            }

                            // New Chat button
                            IconButton(onClick = { agentEngine.createNewSession() }) {
                                Icon(
                                    imageVector = Icons.Default.AddComment,
                                    contentDescription = "New chat",
                                    tint = TextSecondary
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
                        icon = { Icon(Icons.Default.Stop, contentDescription = "Emergency stop") },
                        text = { Text("STOP", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Termux status banner when not connected
                if (connectionStatus !is ConnectionStatus.Connected) {
                    val bannerColor = when (connectionStatus) {
                        is ConnectionStatus.AuthFailed -> RedEmergency
                        is ConnectionStatus.Error -> RedEmergency
                        else -> YellowWarning
                    }
                    val bannerText = when (connectionStatus) {
                        is ConnectionStatus.Connecting -> "Connecting to Termux (ws://127.0.0.1:8765)..."
                        is ConnectionStatus.AuthFailed -> "Authentication error: token does not match"
                        is ConnectionStatus.Error -> "Termux unavailable (run 'amc start' in Termux)"
                        else -> "Termux bridge is not running"
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = DarkCard,
                        border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.3f))
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
                                    if (connectionStatus is ConnectionStatus.Error || connectionStatus is ConnectionStatus.AuthFailed) Icons.Default.ErrorOutline else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = bannerColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = bannerText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, color = TextSecondary),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (connectionStatus is ConnectionStatus.Connecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentPrimary)
                                } else {
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                bridgeClient.forceReconnect()
                                                if (bridgeClient.connectionStatus.value is ConnectionStatus.Connected) {
                                                    bridgeClient.triggerBoost()
                                                    android.widget.Toast.makeText(context, "🚀 Termux boost requested.", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    if (!TermuxBridgeClient.isIgnoringBatteryOptimizations(context, "com.termux")) {
                                                        try {
                                                            context.startActivity(TermuxBridgeClient.getTermuxBatterySettingsIntent())
                                                        } catch (e: Exception) {
                                                            // ignore
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text("Boost", color = TerminalGreen, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(
                                        onClick = { bridgeClient.reconnectIfDisconnected(force = true) },
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text("Connect", color = AccentPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Discovered Artifacts Strip
                if (artifacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Artifacts:", style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 10.sp))
                        artifacts.forEach { item ->
                            val icon = when (item.type) {
                                ArtifactType.IMAGE -> Icons.Default.Image
                                ArtifactType.HTML -> Icons.Default.Html
                                ArtifactType.CODE -> Icons.Default.Code
                                ArtifactType.MARKDOWN -> Icons.Default.Description
                                else -> Icons.AutoMirrored.Filled.Article
                            }
                            AssistChip(
                                onClick = {
                                    viewingArtifactPath = item.path
                                    coroutineScope.launch {
                                        agentEngine.loadArtifactContent(item)
                                    }
                                },
                                label = { Text(item.filename, fontSize = 11.sp, maxLines = 1) },
                                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentPrimary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = DarkCardElevated,
                                    labelColor = TextWhite
                                ),
                                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = BorderSubtle)
                            )
                        }
                    }
                }

                // Chat Messages List
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
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = AccentPrimary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "AMC Autonomous Mobile Agent",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "Multimodal AI agent with a direct Termux shell bridge\nand automatic provider fallback.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextMuted,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    )
                                }
                            }
                        }
                    }

                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            pendingApproval = pendingApproval,
                            onApproveTool = { toolId -> agentEngine.approvePendingAction(toolId) },
                            onRejectTool = { toolId -> agentEngine.rejectPendingAction(toolId) }
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

                // Modern Floating Input Container with Vision Attachment
                Surface(
                    color = DarkCard,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Pending Image Preview Chip
                        if (pendingImageBitmap != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        bitmap = pendingImageBitmap!!.asImageBitmap(),
                                        contentDescription = "Preview",
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Image attached (vision ready)",
                                        style = MaterialTheme.typography.labelSmall.copy(color = AccentPrimary, fontWeight = FontWeight.Bold)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        pendingImageBitmap = null
                                        pendingImageBase64 = null
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Vision Attachment Button
                            Box {
                                IconButton(
                                    onClick = { showAttachMenu = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddPhotoAlternate,
                                        contentDescription = "Attach image",
                                        tint = if (pendingImageBitmap != null) AccentPrimary else TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false },
                                    modifier = Modifier.background(DarkCard)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Take a photo", color = TextWhite) },
                                        leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = AccentPrimary) },
                                        onClick = {
                                            showAttachMenu = false
                                            cameraLauncher.launch(null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Choose from gallery", color = TextWhite) },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, tint = AccentPrimary) },
                                        onClick = {
                                            showAttachMenu = false
                                            galleryLauncher.launch("image/*")
                                        }
                                    )
                                }
                            }

                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = {
                                    Text(
                                        if (pendingImageBitmap != null) "Ask about the image..." else "Enter a command or task...",
                                        fontSize = 12.5.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = TextWhite),
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
                                        if ((inputText.isNotBlank() || pendingImageBase64 != null) && !isBusy) {
                                            val prompt = inputText.trim().ifEmpty { "Analyze the attached image." }
                                            val imgB64 = pendingImageBase64
                                            val mime = pendingImageMimeType

                                            if (prompt.startsWith("/boost", ignoreCase = true) || prompt.startsWith("amc boost", ignoreCase = true)) {
                                                inputText = ""
                                                pendingImageBitmap = null
                                                pendingImageBase64 = null
                                                focusManager.clearFocus()
                                                coroutineScope.launch {
                                                    bridgeClient.forceReconnect()
                                                    val connected = bridgeClient.awaitConnected(3000)
                                                    val isIgnoringBattery = TermuxBridgeClient.isIgnoringBatteryOptimizations(context, "com.termux")
                                                    if (connected) {
                                                        bridgeClient.triggerBoost()
                                                        android.widget.Toast.makeText(context, "🚀 Termux background boost requested.", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        android.widget.Toast.makeText(context, "Connecting to Termux... Leave Termux running in the background.", android.widget.Toast.LENGTH_LONG).show()
                                                        if (!isIgnoringBattery) {
                                                            try {
                                                                context.startActivity(TermuxBridgeClient.getTermuxBatterySettingsIntent())
                                                            } catch (e: Exception) {
                                                                // ignore
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                val accepted = agentEngine.startTask(prompt, imgB64, mime)
                                                if (accepted) {
                                                    inputText = ""
                                                    pendingImageBitmap = null
                                                    pendingImageBase64 = null
                                                    focusManager.clearFocus()
                                                }
                                            }
                                        }
                                    }
                                )
                            )

                            // Speech-to-Text Microphone
                            VoiceInputButton(
                                modifier = Modifier.size(36.dp),
                                onSpeechResult = { spokenText ->
                                    inputText = spokenText
                                }
                            )

                            // Send Button
                            val canSend = (inputText.isNotBlank() || pendingImageBase64 != null) && !isBusy
                            Surface(
                                onClick = {
                                    if (canSend) {
                                        val prompt = inputText.trim().ifEmpty { "Analyze the attached image." }
                                        val imgB64 = pendingImageBase64
                                        val mime = pendingImageMimeType

                                        if (prompt.startsWith("/boost", ignoreCase = true) || prompt.startsWith("amc boost", ignoreCase = true)) {
                                            inputText = ""
                                            pendingImageBitmap = null
                                            pendingImageBase64 = null
                                            focusManager.clearFocus()
                                            coroutineScope.launch {
                                                bridgeClient.forceReconnect()
                                                val connected = bridgeClient.awaitConnected(3000)
                                                val isIgnoringBattery = TermuxBridgeClient.isIgnoringBatteryOptimizations(context, "com.termux")
                                                if (connected) {
                                                    bridgeClient.triggerBoost()
                                                    android.widget.Toast.makeText(context, "🚀 Termux background boost requested.", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "Connecting to Termux... Leave Termux running in the background.", android.widget.Toast.LENGTH_LONG).show()
                                                    if (!isIgnoringBattery) {
                                                        try {
                                                            context.startActivity(TermuxBridgeClient.getTermuxBatterySettingsIntent())
                                                        } catch (e: Exception) {
                                                            // ignore
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            val accepted = agentEngine.startTask(prompt, imgB64, mime)
                                            if (accepted) {
                                                inputText = ""
                                                pendingImageBitmap = null
                                                pendingImageBase64 = null
                                                focusManager.clearFocus()
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (canSend) AccentPrimary else DarkCardElevated,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = if (canSend) Color.Black else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Artifact Viewer Dialog
    if (viewingArtifact != null) {
        ArtifactViewerDialog(
            artifact = viewingArtifact,
            onDismiss = { viewingArtifactPath = null },
            onExecuteInTermux = { cmd ->
                agentEngine.startTask(cmd)
            },
            onRetryLoad = {
                coroutineScope.launch {
                    agentEngine.loadArtifactContent(viewingArtifact)
                }
            }
        )
    }

    // Quick Model Picker Dialog
    if (showModelPickerDialog) {
        QuickModelPickerDialog(
            currentConfig = modelConfig,
            preferenceManager = agentEngine.preferenceManager,
            onDismiss = { showModelPickerDialog = false },
            onModelSelected = { newConfig ->
                agentEngine.setModelConfig(newConfig)
                showModelPickerDialog = false
            }
        )
    }
}

private fun bitmapToBase64(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

private suspend fun processImageUri(context: Context, uri: Uri): Triple<String, String, Bitmap?>? = withContext(Dispatchers.IO) {
    try {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        val maxDim = 1280
        var inSampleSize = 1
        if (boundsOptions.outHeight > maxDim || boundsOptions.outWidth > maxDim) {
            val halfHeight = boundsOptions.outHeight / 2
            val halfWidth = boundsOptions.outWidth / 2
            while ((halfHeight / inSampleSize) >= maxDim || (halfWidth / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return@withContext null

        val byteStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, byteStream)
        val b64 = Base64.encodeToString(byteStream.toByteArray(), Base64.NO_WRAP)
        Triple(b64, "image/jpeg", bitmap)
    } catch (t: Throwable) {
        Log.e("ChatScreen", "Error processing image: ${t.message}", t)
        null
    }
}

@Composable
private fun QuickModelPickerDialog(
    currentConfig: ModelConfig,
    preferenceManager: PreferenceManager? = null,
    onDismiss: () -> Unit,
    onModelSelected: (ModelConfig) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(currentConfig.provider) }
    var selectedModel by remember { mutableStateOf(currentConfig.modelName) }

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
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select model",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

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
                        ProviderType.entries.forEach { provider ->
                            val isSelected = selectedProvider == provider
                            Surface(
                                onClick = {
                                    selectedProvider = provider
                                    selectedModel = provider.defaultModel
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

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Suggested models (${selectedProvider.displayName})",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedProvider.suggestedModels.forEach { model ->
                            val isSelected = selectedModel == model
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedModel = model },
                                label = { Text(model, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                shape = RoundedCornerShape(6.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                                    selectedLabelColor = AccentPrimary
                                )
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val newConfig = if (selectedProvider == currentConfig.provider) {
                            currentConfig.copy(
                                modelName = selectedModel
                            )
                        } else {
                            val profile = preferenceManager?.loadProviderProfile(selectedProvider)
                            ModelConfig(
                                provider = selectedProvider,
                                modelName = selectedModel,
                                apiKey = profile?.apiKey ?: "",
                                baseUrl = profile?.baseUrl ?: selectedProvider.defaultBaseUrl,
                                fallbackProvider = currentConfig.fallbackProvider,
                                fallbackModelName = currentConfig.fallbackModelName,
                                fallbackApiKey = currentConfig.fallbackApiKey,
                                fallbackBaseUrl = currentConfig.fallbackBaseUrl
                            )
                        }
                        onModelSelected(newConfig)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground)
                ) {
                    Text("Apply", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
