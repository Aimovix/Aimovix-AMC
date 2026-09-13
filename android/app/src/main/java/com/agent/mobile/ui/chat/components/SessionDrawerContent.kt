package com.agent.mobile.ui.chat.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SessionDrawerContent(
    agentEngine: AutonomousAgentEngine,
    chatRepository: ChatRepository?,
    onCloseDrawer: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val allSessions by (chatRepository?.allSessions ?: remember { kotlinx.coroutines.flow.flowOf(emptyList()) }).collectAsState(initial = emptyList())
    val currentSession by agentEngine.currentSession.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var sessionToExport by remember { mutableStateOf<ChatSession?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportFormat by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val content = pendingExportContent ?: run {
                    val sId = pendingExportSessionId
                    val fmt = pendingExportFormat
                    if (sId != null && fmt != null && chatRepository != null) {
                        val session = chatRepository.getSessionById(sId)
                            ?: ChatSession(id = sId, title = "Chat", createdAt = System.currentTimeMillis())
                        val messages = chatRepository.getMessagesForSessionSync(sId)
                        if (fmt == "json") chatRepository.exportToJson(session, messages)
                        else chatRepository.exportToMarkdown(session, messages)
                    } else null
                }
                if (content != null) {
                    val success = chatRepository?.saveExportToUri(context, uri, content) ?: false
                    if (success) {
                        Toast.makeText(context, "Export saved successfully.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save export.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Failed to save export.", Toast.LENGTH_SHORT).show()
                }
                pendingExportContent = null
                pendingExportSessionId = null
                pendingExportFormat = null
            }
        } else {
            pendingExportContent = null
            pendingExportSessionId = null
            pendingExportFormat = null
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ENGLISH) }

    ModalDrawerSheet(
        drawerContainerColor = DarkBackground,
        drawerContentColor = TextWhite,
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .widthIn(max = 340.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chat sessions",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        fontSize = 16.sp
                    )
                )
                IconButton(onClick = onCloseDrawer) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // New Chat Button
            Button(
                onClick = {
                    agentEngine.createNewSession()
                    onCloseDrawer()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New chat", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { q ->
                    searchQuery = q
                    searchJob?.cancel()
                    if (q.isNotBlank()) {
                        isSearching = true
                        searchJob = coroutineScope.launch {
                            kotlinx.coroutines.delay(300L)
                            searchResults = chatRepository?.searchMessages(q) ?: emptyList()
                            isSearching = false
                        }
                    } else {
                        searchResults = emptyList()
                        isSearching = false
                    }
                },
                placeholder = { Text("Search history and terminal...", fontSize = 12.sp, color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchJob?.cancel()
                            searchQuery = ""
                            searchResults = emptyList()
                            isSearching = false
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPrimary,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface
                )
            )

            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

            // Search Results or Sessions List
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "Search results (${searchResults.size}):",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                if (searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text("No results found", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults) { result ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkCard,
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = result.role.name + " • " + dateFormat.format(Date(result.timestamp)),
                                        style = MaterialTheme.typography.labelSmall.copy(color = AccentPrimary, fontSize = 10.sp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = result.text.ifEmpty { result.streamingTerminalOutput }.take(150),
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextWhite, fontSize = 12.sp),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "All chats (${allSessions.size}):",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allSessions, key = { it.id }) { session ->
                        val isSelected = currentSession?.id == session.id

                        Surface(
                            onClick = {
                                agentEngine.switchSession(session.id)
                                onCloseDrawer()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) DarkCardElevated else DarkCard,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentPrimary else BorderSubtle
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) TextWhite else TextSecondary
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = dateFormat.format(Date(session.updatedAt)),
                                            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 10.sp)
                                        )
                                        if (session.estimatedCostUsd > 0.0) {
                                            Text(
                                                text = " • \$${String.format(Locale.US, "%.4f", session.estimatedCostUsd)}",
                                                style = MaterialTheme.typography.labelSmall.copy(color = AccentPrimary, fontSize = 10.sp)
                                            )
                                        }
                                    }
                                }

                                Row {
                                    // Export Button
                                    IconButton(
                                        onClick = {
                                            sessionToExport = session
                                            showExportDialog = true
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.FileDownload, contentDescription = "Export", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }

                                    // Delete Button
                                    IconButton(
                                        onClick = { agentEngine.deleteSession(session.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Export Format Selection Dialog
    if (showExportDialog && sessionToExport != null) {
        val session = sessionToExport!!
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export chat", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose the export format for '${session.title}':",
                        color = TextSecondary,
                        fontSize = 12.5.sp
                    )

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val messages = chatRepository?.getMessagesForSessionSync(session.id) ?: emptyList()
                                val md = chatRepository?.exportToMarkdown(session, messages) ?: ""
                                pendingExportContent = md
                                pendingExportSessionId = session.id
                                pendingExportFormat = "md"
                                exportLauncher.launch("chat_${session.id.take(8)}.md")
                                showExportDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground)
                    ) {
                        Text("Markdown (.md)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val messages = chatRepository?.getMessagesForSessionSync(session.id) ?: emptyList()
                                val json = chatRepository?.exportToJson(session, messages) ?: ""
                                pendingExportContent = json
                                pendingExportSessionId = session.id
                                pendingExportFormat = "json"
                                exportLauncher.launch("chat_${session.id.take(8)}.json")
                                showExportDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated, contentColor = TextWhite)
                    ) {
                        Text("JSON (.json)", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel", color = TextMuted, fontSize = 12.sp)
                }
            },
            containerColor = DarkCard
        )
    }
}
