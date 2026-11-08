package com.agent.mobile.ui.chat.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
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
import java.util.Date
import java.util.Locale

/**
 * The chat list. Swipe a chat to the left to delete it, or use its bin icon. Deleting can be undone
 * for a few seconds; the chat is removed for good only after that, or when the drawer closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // Deletion is reversible for a few seconds: the chat disappears and an Undo bar appears. It is
    // final when the bar times out, when another deletion replaces it, or when the drawer closes.
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val visibleSessions = allSessions.filter { it.id != pendingDelete?.id }

    fun requestDelete(session: ChatSession) {
        pendingDelete?.let { agentEngine.deleteSession(it.id) }
        pendingDelete = session
    }

    LaunchedEffect(pendingDelete?.id) {
        val target = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Chat deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long
        )
        if (pendingDelete?.id == target.id) {
            pendingDelete = null
            if (result != SnackbarResult.ActionPerformed) agentEngine.deleteSession(target.id)
        }
    }
    DisposableEffect(Unit) {
        onDispose { pendingDelete?.let { agentEngine.deleteSession(it.id) } }
    }

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
                        Toast.makeText(context, "Export saved.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Could not save the export.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Could not save the export.", Toast.LENGTH_SHORT).show()
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    IconButton(onClick = onCloseDrawer) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        agentEngine.createNewSession()
                        onCloseDrawer()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New chat", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.height(12.dp))

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
                    placeholder = { Text("Search chats", style = MaterialTheme.typography.bodyMedium, color = TextMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchJob?.cancel()
                                searchQuery = ""
                                searchResults = emptyList()
                                isSearching = false
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
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

                if (searchQuery.isNotBlank()) {
                    Text(
                        text = "Results (${searchResults.size})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Text("No matches", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(searchResults) { result ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = DarkCard,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = result.role.name.lowercase().replaceFirstChar { it.uppercase() } + " · " + dateFormat.format(Date(result.timestamp)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AccentPrimary
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = result.text.ifEmpty { result.streamingTerminalOutput }.take(150),
                                            style = MaterialTheme.typography.bodySmall.copy(color = TextWhite),
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
                        text = "All chats (${visibleSessions.size})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleSessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                selected = currentSession?.id == session.id,
                                dateFormat = dateFormat,
                                onOpen = {
                                    agentEngine.switchSession(session.id)
                                    onCloseDrawer()
                                },
                                onExport = {
                                    sessionToExport = session
                                    showExportDialog = true
                                },
                                onDelete = { requestDelete(session) }
                            )
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DarkCardElevated,
                    contentColor = TextWhite,
                    actionContentColor = AccentPrimary
                )
            }
        }
    }

    // Export Format Selection Dialog
    if (showExportDialog && sessionToExport != null) {
        val session = sessionToExport!!
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export chat", color = TextWhite, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose the format for '${session.title}':",
                        style = MaterialTheme.typography.bodySmall
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
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = DarkBackground)
                    ) {
                        Text("Markdown (.md)", style = MaterialTheme.typography.labelLarge)
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
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated, contentColor = TextWhite)
                    ) {
                        Text("JSON (.json)", style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel", color = TextMuted, style = MaterialTheme.typography.labelLarge)
                }
            },
            containerColor = DarkCard
        )
    }
}

/** One chat in the list: tap to open, swipe left or use the bin to delete (undoable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    dateFormat: SimpleDateFormat,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(RedEmergency.copy(alpha = 0.16f))
                    .padding(end = 18.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete", style = MaterialTheme.typography.labelLarge, color = RedText)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.Delete, contentDescription = null, tint = RedText, modifier = Modifier.size(18.dp))
                }
            }
        }
    ) {
        Surface(
            onClick = onOpen,
            shape = MaterialTheme.shapes.medium,
            color = if (selected) DarkCardElevated else DarkCard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = TextWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dateFormat.format(Date(session.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        if (session.estimatedCostUsd > 0.0) {
                            Text(
                                text = " · \$${String.format(Locale.US, "%.4f", session.estimatedCostUsd)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
                IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Export chat", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete chat", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
