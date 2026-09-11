package com.agent.mobile.agent

import android.util.Log
import com.agent.mobile.data.model.*
import com.agent.mobile.data.network.LlmClient
import com.agent.mobile.data.network.LlmClient.LlmResponse
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AutonomousAgentEngine(
    val bridgeClient: TermuxBridgeClient,
    val llmClient: LlmClient = LlmClient(),
    val preferenceManager: PreferenceManager? = null,
    val chatRepository: ChatRepository? = null
) {
    companion object {
        private const val TAG = "AgentEngine"
        private const val MAX_LOOP_ITERATIONS = 15
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null
    private var sessionCollectJob: Job? = null

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _executionMode = MutableStateFlow(
        preferenceManager?.loadExecutionMode() ?: ExecutionMode.AUTOPILOT
    )
    val executionMode: StateFlow<ExecutionMode> = _executionMode.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _modelConfig = MutableStateFlow(
        preferenceManager?.loadModelConfig() ?: ModelConfig()
    )
    val modelConfig: StateFlow<ModelConfig> = _modelConfig.asStateFlow()

    private val _pendingApproval = MutableStateFlow<Pair<String, ToolCall>?>(null)
    val pendingApproval: StateFlow<Pair<String, ToolCall>?> = _pendingApproval.asStateFlow()

    private val _artifacts = MutableStateFlow<List<ArtifactItem>>(emptyList())
    val artifacts: StateFlow<List<ArtifactItem>> = _artifacts.asStateFlow()

    private val _metrics = MutableStateFlow(SessionMetrics())
    val metrics: StateFlow<SessionMetrics> = _metrics.asStateFlow()

    private var approvalContinuation: CompletableDeferred<Boolean>? = null

    init {
        // Apply custom security rules from preferences
        preferenceManager?.let { prefs ->
            CommandSecurityFilter.setCustomRules(
                whitelist = prefs.loadWhitelist(),
                blacklist = prefs.loadBlacklist(),
                strict = prefs.loadStrictMode()
            )
        }

        // Initialize session from repository
        scope.launch {
            initSession()
        }
    }

    private suspend fun initSession() {
        val repo = chatRepository ?: return
        val savedSessionId = preferenceManager?.loadActiveSessionId()

        val sessionToLoad = if (savedSessionId != null) {
            repo.getSessionById(savedSessionId)
        } else null

        if (sessionToLoad != null) {
            loadSession(sessionToLoad)
        } else {
            val newSession = repo.createNewSession(
                title = "Neuer Chat",
                provider = _modelConfig.value.provider.name,
                model = _modelConfig.value.modelName
            )
            loadSession(newSession)
        }
    }

    fun loadSession(session: ChatSession) {
        _currentSession.value = session
        preferenceManager?.saveActiveSessionId(session.id)
        _metrics.value = SessionMetrics(
            promptTokens = session.totalPromptTokens,
            completionTokens = session.totalCompletionTokens,
            estimatedCostUsd = session.estimatedCostUsd
        )

        sessionCollectJob?.cancel()
        sessionCollectJob = scope.launch {
            chatRepository?.getMessagesForSession(session.id)?.collect { dbMessages ->
                // Update messages only if engine is not actively streaming new tokens to avoid UI flicker
                if (!_isBusy.value) {
                    _messages.value = dbMessages
                }
            }
        }
    }

    fun createNewSession() {
        emergencyStop()
        scope.launch {
            val repo = chatRepository
            val config = _modelConfig.value
            val session = repo?.createNewSession(
                title = "Neuer Chat",
                provider = config.provider.name,
                model = config.modelName
            ) ?: ChatSession(
                id = UUID.randomUUID().toString(),
                title = "Neuer Chat",
                modelProvider = config.provider.name,
                modelName = config.modelName
            )
            _messages.value = emptyList()
            _artifacts.value = emptyList()
            loadSession(session)
        }
    }

    fun switchSession(sessionId: String) {
        if (_currentSession.value?.id == sessionId) return
        emergencyStop()
        scope.launch {
            val session = chatRepository?.getSessionById(sessionId)
            if (session != null) {
                _artifacts.value = emptyList()
                loadSession(session)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            chatRepository?.deleteSession(sessionId)
            if (_currentSession.value?.id == sessionId) {
                createNewSession()
            }
        }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
        preferenceManager?.saveExecutionMode(mode)
    }

    fun setModelConfig(config: ModelConfig) {
        _modelConfig.value = config
        preferenceManager?.saveModelConfig(config)
    }

    fun reloadSecurityRules() {
        preferenceManager?.let { prefs ->
            CommandSecurityFilter.setCustomRules(
                whitelist = prefs.loadWhitelist(),
                blacklist = prefs.loadBlacklist(),
                strict = prefs.loadStrictMode()
            )
        }
    }

    fun clearHistory() {
        emergencyStop()
        val current = _currentSession.value
        if (current != null && chatRepository != null) {
            scope.launch {
                chatRepository.deleteSession(current.id)
                createNewSession()
            }
        } else {
            _messages.value = emptyList()
        }
    }

    fun startTask(userPrompt: String, imageBase64: String? = null, imageMimeType: String? = null) {
        if (_isBusy.value || userPrompt.isBlank()) return

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = userPrompt.trim(),
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )

        _messages.value = _messages.value + userMessage
        _isBusy.value = true

        activeJob = scope.launch {
            try {
                // Auto-generate title on first message in session
                val session = _currentSession.value
                if (session != null && (session.title == "Neuer Chat" || session.title.isBlank())) {
                    val generatedTitle = chatRepository?.generateConciseTitle(userPrompt) ?: "Chat"
                    _currentSession.value = session.copy(title = generatedTitle)
                    chatRepository?.updateSessionTitle(session.id, generatedTitle)
                }

                // Persist user message to DB
                session?.let { s ->
                    chatRepository?.saveMessage(s.id, userMessage)
                }

                runAgentLoop()
            } catch (e: CancellationException) {
                appendSystemMessage("🛑 Ausführung durch Nutzer abgebrochen.")
            } catch (e: Exception) {
                Log.e(TAG, "Fehler im Agenten-Loop: ${e.message}", e)
                appendSystemMessage("⚠️ Fehler im Agenten-Loop: ${e.localizedMessage}")
            } finally {
                _isBusy.value = false
                _pendingApproval.value = null
            }
        }
    }

    private suspend fun runAgentLoop() {
        var iterations = 0
        val commandHistory = mutableListOf<String>()
        var consecutiveErrors = 0

        while (iterations < MAX_LOOP_ITERATIONS && currentCoroutineContext().isActive) {
            iterations++

            val primaryConfig = _modelConfig.value
            var activeConfig = primaryConfig
            var isUsingFallback = false

            val assistantMsgId = UUID.randomUUID().toString()
            var assistantMsg = ChatMessage(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                text = "",
                status = MessageStatus.STREAMING
            )
            _messages.value = _messages.value + assistantMsg

            var streamCompleted = false
            var actionDetected: LlmResponse.Action? = null
            var errorEvent: LlmClient.LlmStreamEvent.Error? = null
            val textBuilder = StringBuilder()

            // Stream execution loop with automatic fallback resilience
            var streamAttempt = 0
            while (streamAttempt < 2 && !streamCompleted && currentCoroutineContext().isActive) {
                streamAttempt++
                try {
                    llmClient.streamRequest(
                        config = activeConfig,
                        systemPrompt = AgentPrompts.SYSTEM_PROMPT,
                        messages = _messages.value.filter { it.id != assistantMsgId }
                    ).collect { event ->
                        when (event) {
                            is LlmClient.LlmStreamEvent.Token -> {
                                textBuilder.append(event.textChunk)
                                updateMessageText(assistantMsgId, textBuilder.toString(), MessageStatus.STREAMING)
                            }
                            is LlmClient.LlmStreamEvent.ToolCallDetected -> {
                                actionDetected = LlmResponse.Action(
                                    thought = event.thought.ifEmpty { textBuilder.toString() },
                                    toolCall = event.toolCall
                                )
                                streamCompleted = true
                            }
                            is LlmClient.LlmStreamEvent.Completed -> {
                                val full = event.fullText.ifEmpty { textBuilder.toString() }
                                updateMessageText(assistantMsgId, full, MessageStatus.COMPLETED)
                                recordMetrics(event.promptTokens, event.completionTokens, event.estimatedCostUsd)
                                streamCompleted = true
                            }
                            is LlmClient.LlmStreamEvent.Error -> {
                                errorEvent = event
                            }
                        }
                    }
                } catch (e: Exception) {
                    errorEvent = LlmClient.LlmStreamEvent.Error(e.localizedMessage ?: "Netzwerkfehler", 0, true)
                }

                // If error occurred and fallback provider is configured, failover seamlessly!
                if (!streamCompleted && errorEvent != null) {
                    val fallback = primaryConfig.fallbackProvider
                    if (fallback != null && !isUsingFallback) {
                        isUsingFallback = true
                        val fallbackModel = primaryConfig.fallbackModelName.ifEmpty { fallback.defaultModel }
                        val fallbackUrl = primaryConfig.fallbackBaseUrl.ifEmpty { fallback.defaultBaseUrl }
                        activeConfig = ModelConfig(
                            provider = fallback,
                            modelName = fallbackModel,
                            apiKey = primaryConfig.fallbackApiKey,
                            baseUrl = fallbackUrl
                        )
                        appendSystemMessage("⚠️ Primär-Provider ${primaryConfig.provider.displayName} fehlgeschlagen (${errorEvent.message}). Wechsle zu Fallback: ${fallback.displayName} ($fallbackModel)...")
                        textBuilder.clear()
                        errorEvent = null
                        continue
                    }
                }
                break
            }

            if (!streamCompleted && errorEvent != null) {
                updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
                appendSystemMessage("⚠️ KI-Schnittstelle Fehler: ${errorEvent.message}")
                break
            }

            // Persist the completed assistant text message
            val finalAssistantMsg = _messages.value.firstOrNull { it.id == assistantMsgId }
            if (finalAssistantMsg != null) {
                _currentSession.value?.let { s -> chatRepository?.saveMessage(s.id, finalAssistantMsg) }
            }

            // If a tool call was detected, execute and process tool observations
            if (actionDetected != null) {
                val action = actionDetected!!
                val cmd = action.toolCall.arguments["command"]?.trim() ?: ""

                // 1. RUNAWAY & LOOP DETECTION GUARDRAILS
                if (isRunawayOrLoopDetected(cmd, commandHistory, consecutiveErrors)) {
                    val runawayMsg = "🛑 Runaway/Loop-Schutz aktiv: Wiederholte Befehlsausführung oder persistente Fehler für `$cmd` erkannt. Ausführung gestoppt."
                    appendSystemMessage(runawayMsg)
                    updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
                    break
                }
                commandHistory.add(cmd)

                val assessment = CommandSecurityFilter.analyze(cmd)
                val securedToolCall = action.toolCall.copy(
                    riskLevel = assessment.level.name,
                    riskReason = assessment.reason
                )

                // 2. Blacklist Check
                if (assessment.isBlocked) {
                    val blockedMsg = "🛡️ Sicherheits-Sperre: Befehl `$cmd` wurde blockiert.\nGrund: ${assessment.reason}"
                    updateMessageText(assistantMsgId, "${action.thought}\n\n$blockedMsg", MessageStatus.ERROR)

                    val toolRejectedMsg = ChatMessage(
                        role = MessageRole.TOOL,
                        text = "Ausführung blockiert: ${assessment.reason}",
                        toolResult = ToolResult(
                            toolCallId = securedToolCall.id,
                            command = cmd,
                            stderr = "Sicherheits-Blockade aktiv: ${assessment.reason}",
                            isError = true
                        )
                    )
                    _messages.value = _messages.value + toolRejectedMsg
                    _currentSession.value?.let { s -> chatRepository?.saveMessage(s.id, toolRejectedMsg) }

                    // Record Audit Entity
                    chatRepository?.recordAudit(
                        CommandAuditEntity(
                            sessionId = _currentSession.value?.id,
                            command = cmd,
                            riskLevel = RiskLevel.BLOCKED.name,
                            riskReason = assessment.reason,
                            exitCode = -1,
                            stderr = "Sicherheits-Blockade aktiv",
                            wasApproved = false
                        )
                    )
                    consecutiveErrors++
                    continue
                }

                // 3. Approval Check (Step-by-step or High-Risk)
                val mustApprove = CommandSecurityFilter.shouldRequireApproval(assessment, _executionMode.value)
                updateMessageToolCall(assistantMsgId, securedToolCall, if (mustApprove) MessageStatus.WAITING_FOR_APPROVAL else MessageStatus.EXECUTING_TOOL)

                if (mustApprove) {
                    _pendingApproval.value = Pair(assistantMsgId, securedToolCall)
                    val deferred = CompletableDeferred<Boolean>()
                    approvalContinuation = deferred

                    val approved = deferred.await()
                    _pendingApproval.value = null
                    approvalContinuation = null

                    if (!approved) {
                        updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
                        val rejectedMsg = ChatMessage(
                            role = MessageRole.TOOL,
                            text = "Befehl wurde vom Nutzer verweigert.",
                            toolResult = ToolResult(
                                toolCallId = securedToolCall.id,
                                command = cmd,
                                stderr = "Ausführung vom Nutzer verweigert.",
                                isError = true
                            )
                        )
                        _messages.value = _messages.value + rejectedMsg
                        _currentSession.value?.let { s -> chatRepository?.saveMessage(s.id, rejectedMsg) }

                        chatRepository?.recordAudit(
                            CommandAuditEntity(
                                sessionId = _currentSession.value?.id,
                                command = cmd,
                                riskLevel = assessment.level.name,
                                riskReason = "Vom Nutzer verweigert",
                                exitCode = -1,
                                stderr = "Verweigert",
                                wasApproved = false
                            )
                        )
                        continue
                    }
                }

                // 4. Command Execution via Termux Bridge
                updateMessageStatus(assistantMsgId, MessageStatus.EXECUTING_TOOL)
                val startTime = System.currentTimeMillis()

                val result = bridgeClient.executeCommand(cmd) { chunk ->
                    updateStreamingOutput(assistantMsgId, chunk)
                }

                val duration = System.currentTimeMillis() - startTime
                updateMessageStatus(assistantMsgId, MessageStatus.COMPLETED)

                if (result.isError) consecutiveErrors++ else consecutiveErrors = 0

                // 5. Record Audit Entity in Room
                chatRepository?.recordAudit(
                    CommandAuditEntity(
                        sessionId = _currentSession.value?.id,
                        command = cmd,
                        riskLevel = assessment.level.name,
                        riskReason = assessment.reason,
                        executionDurationMs = duration,
                        exitCode = result.exitCode,
                        stdout = result.stdout.take(500),
                        stderr = result.stderr.take(500),
                        timestamp = System.currentTimeMillis(),
                        wasApproved = true
                    )
                )

                // 6. Termux-Vision-Loop Detection
                var visionObservation: ChatMessage? = null
                if (cmd.contains("termux-camera-photo") && result.exitCode == 0) {
                    val photoPath = extractPhotoPathFromCommand(cmd)
                    if (photoPath != null) {
                        try {
                            val base64Img = bridgeClient.readFileBase64(photoPath)
                            if (base64Img.isNotEmpty()) {
                                visionObservation = ChatMessage(
                                    role = MessageRole.USER,
                                    text = "📸 Aufgenommenes Foto aus Termux (`$photoPath`):",
                                    imageBase64 = base64Img,
                                    imageMimeType = "image/jpeg",
                                    status = MessageStatus.COMPLETED
                                )
                                appendArtifact(
                                    ArtifactItem(
                                        filename = photoPath.substringAfterLast('/'),
                                        path = photoPath,
                                        type = ArtifactType.IMAGE,
                                        base64Data = base64Img
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Termux-Vision: Foto konnte nicht gelesen werden: ${e.message}")
                        }
                    }
                }

                // 7. Generic Artifact Scanner
                detectAndRegisterArtifacts(cmd, result)

                // 8. Add Tool Observation to Context
                val rawOutput = if (result.stdout.isNotEmpty()) result.stdout else result.stderr
                val guardedResult = result.copy(
                    stdout = if (result.stdout.isNotEmpty()) "[UNTRUSTED_OUTPUT_START]\n${result.stdout}\n[UNTRUSTED_OUTPUT_END]" else "",
                    stderr = if (result.stderr.isNotEmpty()) "[UNTRUSTED_OUTPUT_START]\n${result.stderr}\n[UNTRUSTED_OUTPUT_END]" else ""
                )
                val toolMsg = ChatMessage(
                    role = MessageRole.TOOL,
                    text = "[UNTRUSTED_OUTPUT_START]\n$rawOutput\n[UNTRUSTED_OUTPUT_END]",
                    toolResult = guardedResult,
                    status = MessageStatus.COMPLETED
                )
                _messages.value = _messages.value + toolMsg
                _currentSession.value?.let { s -> chatRepository?.saveMessage(s.id, toolMsg) }

                if (visionObservation != null) {
                    _messages.value = _messages.value + visionObservation
                    _currentSession.value?.let { s -> chatRepository?.saveMessage(s.id, visionObservation) }
                }
            } else {
                // Normal message completion reached, end loop
                break
            }
        }

        if (iterations >= MAX_LOOP_ITERATIONS) {
            appendSystemMessage("ℹ️ Schrittlimit von $MAX_LOOP_ITERATIONS Schritten erreicht. Ausführung abgeschlossen.")
        }
    }

    internal fun isRunawayOrLoopDetected(
        cmd: String,
        history: List<String>,
        consecutiveErrors: Int
    ): Boolean {
        // 1. Persistent failure: 3 consecutive commands failed
        if (consecutiveErrors >= 3) return true

        // 2. Exact command repetition: same command executed 3 times consecutively
        if (history.size >= 2 && history.takeLast(2).all { it.equals(cmd, ignoreCase = true) }) {
            return true
        }

        // 3. Ping-pong alternating cycle: A -> B -> A -> B
        if (history.size >= 3) {
            val last = history.last()
            val secondLast = history[history.size - 2]
            val thirdLast = history[history.size - 3]
            if (cmd.equals(secondLast, ignoreCase = true) && last.equals(thirdLast, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    internal fun extractPhotoPathFromCommand(cmd: String): String? {
        val tokens = cmd.split("\\s+".toRegex())
        val photoIndex = tokens.indexOfFirst { it.contains("termux-camera-photo") }
        if (photoIndex == -1) return null

        // Find the last argument or token after flags
        for (i in tokens.size - 1 downTo photoIndex + 1) {
            val t = tokens[i]
            if (!t.startsWith("-") && t.isNotEmpty()) {
                return t
            }
        }
        return "photo.jpg"
    }

    private fun detectAndRegisterArtifacts(command: String, result: ToolResult) {
        val fileExtensions = listOf(".py", ".sh", ".js", ".json", ".md", ".html", ".txt", ".png", ".jpg", ".jpeg")
        val tokens = command.split("\\s+".toRegex())

        for (token in tokens) {
            val clean = token.trim('"', '\'', '>', '<', ';', '&')
            val ext = fileExtensions.firstOrNull { clean.endsWith(it, ignoreCase = true) }
            if (ext != null && clean.length > ext.length) {
                val type = when {
                    ext in listOf(".png", ".jpg", ".jpeg") -> ArtifactType.IMAGE
                    ext == ".md" -> ArtifactType.MARKDOWN
                    ext == ".html" -> ArtifactType.HTML
                    ext in listOf(".py", ".sh", ".js", ".json") -> ArtifactType.CODE
                    else -> ArtifactType.TEXT
                }
                appendArtifact(
                    ArtifactItem(
                        filename = clean.substringAfterLast('/'),
                        path = clean,
                        type = type,
                        content = if (result.exitCode == 0 && result.stdout.isNotEmpty() && type != ArtifactType.IMAGE) result.stdout.take(4000) else null
                    )
                )
            }
        }
    }

    private fun appendArtifact(item: ArtifactItem) {
        if (_artifacts.value.none { it.path == item.path }) {
            _artifacts.value = _artifacts.value + item
        }
    }

    private fun recordMetrics(promptTokens: Int, completionTokens: Int, costUsd: Double) {
        _metrics.value = _metrics.value.copy(
            promptTokens = _metrics.value.promptTokens + promptTokens,
            completionTokens = _metrics.value.completionTokens + completionTokens,
            estimatedCostUsd = _metrics.value.estimatedCostUsd + costUsd
        )
        _currentSession.value?.let { s ->
            scope.launch {
                chatRepository?.addMetrics(s.id, promptTokens, completionTokens, costUsd)
            }
        }
    }

    fun approvePendingAction() {
        approvalContinuation?.complete(true)
    }

    fun rejectPendingAction() {
        approvalContinuation?.complete(false)
    }

    fun emergencyStop() {
        activeJob?.cancel()
        approvalContinuation?.cancel()
        bridgeClient.interruptCurrent()
        _isBusy.value = false
        _pendingApproval.value = null
        appendSystemMessage("🛑 Not-Aus aktiviert: Befehlsausführung gestoppt.")
    }

    private fun updateMessageText(messageId: String, text: String, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(text = text, status = status)
            } else msg
        }
    }

    private fun updateMessageToolCall(messageId: String, toolCall: ToolCall, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(toolCall = toolCall, status = status)
            } else msg
        }
    }

    private fun updateStreamingOutput(messageId: String, newChunk: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(streamingTerminalOutput = msg.streamingTerminalOutput + newChunk)
            } else msg
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(status = status)
            } else msg
        }
    }

    private fun appendSystemMessage(text: String) {
        val sysMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            text = text,
            status = MessageStatus.COMPLETED
        )
        _messages.value = _messages.value + sysMsg
        _currentSession.value?.let { s ->
            scope.launch { chatRepository?.saveMessage(s.id, sysMsg) }
        }
    }
}
