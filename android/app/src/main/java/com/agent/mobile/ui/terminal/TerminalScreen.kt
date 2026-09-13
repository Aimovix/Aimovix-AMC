package com.agent.mobile.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TerminalViewModel : ViewModel() {
    private val _terminalHistory = MutableStateFlow("Welcome to the AMC terminal.\nTarget: Termux localhost:8765 (pair in Setup first)\n$ ")
    val terminalHistory: StateFlow<String> = _terminalHistory.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var activeJob: Job? = null

    fun appendOutput(chunk: String) {
        _terminalHistory.value = (_terminalHistory.value + chunk).takeLast(100_000)
    }

    fun clear() {
        _terminalHistory.value = "$ "
    }

    fun interrupt(bridgeClient: TermuxBridgeClient) {
        bridgeClient.interruptCurrent()
        appendOutput("^C\n$ ")
        activeJob?.cancel()
        _isRunning.value = false
    }

    fun runCommand(cmd: String, bridgeClient: TermuxBridgeClient) {
        if (cmd.isBlank() || _isRunning.value) return
        val toExec = cmd.trim()
        appendOutput("$toExec\n")
        _isRunning.value = true
        activeJob = viewModelScope.launch {
            try {
                val result = bridgeClient.executeCommand(toExec) { chunk ->
                    appendOutput(chunk)
                }
                if (result.isError && result.stderr.isNotEmpty()) {
                    appendOutput(result.stderr + "\n")
                }
                appendOutput("$ ")
            } catch (e: CancellationException) {
                appendOutput("^C\n$ ")
            } catch (e: Exception) {
                appendOutput("Error: ${e.message}\n$ ")
            } finally {
                _isRunning.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    bridgeClient: TermuxBridgeClient,
    viewModel: TerminalViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val terminalHistory by viewModel.terminalHistory.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    var inputCmd by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    // Auto-scroll on new output
    LaunchedEffect(terminalHistory) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = AccentPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Termux Console",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { TermuxBridgeClient.openTermuxApp(context) }) {
                            Icon(Icons.Default.Terminal, contentDescription = "Open Termux App", tint = AccentPrimary)
                        }
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Default.ClearAll, contentDescription = "Clear", tint = TextMuted)
                        }
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
        ) {
            // Main Terminal Window
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TerminalBg)
                    .padding(10.dp)
            ) {
                Text(
                    text = terminalHistory,
                    color = TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 15.5.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                )
            }

            // Quick Keys Toolbar for mobile terminal users
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCard)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("TAB", "ESC", "CTRL-C", "|", "/", "-", "_", "~", "$", "clear").forEach { key ->
                    Surface(
                        onClick = {
                            when (key) {
                                "CTRL-C" -> viewModel.interrupt(bridgeClient)
                                "clear" -> viewModel.clear()
                                "TAB" -> inputCmd += "  "
                                else -> inputCmd += key
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (key == "CTRL-C") RedEmergency.copy(alpha = 0.15f) else DarkCardElevated,
                        border = BorderStroke(
                            1.dp,
                            if (key == "CTRL-C") RedEmergency.copy(alpha = 0.4f) else BorderSubtle
                        )
                    ) {
                        Text(
                            text = key,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = if (key == "CTRL-C") RedEmergency else TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

            // Input Bar
            Surface(
                color = DarkBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$ ",
                        color = AccentPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    TextField(
                        value = inputCmd,
                        onValueChange = { inputCmd = it },
                        placeholder = { Text("Enter a command...", fontSize = 12.5.sp, color = TextMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = TextWhite),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )

                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = AccentPrimary
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (inputCmd.isNotBlank()) {
                                    val toExec = inputCmd.trim()
                                    inputCmd = ""
                                    viewModel.runCommand(toExec, bridgeClient)
                                }
                            },
                            enabled = inputCmd.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                                contentDescription = "Run",
                                tint = if (inputCmd.isNotBlank()) AccentPrimary else TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

