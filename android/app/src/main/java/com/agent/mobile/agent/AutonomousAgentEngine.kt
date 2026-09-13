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
    val chatRepository: ChatRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    companion object {
        private const val TAG = "AgentEngine"
        private const val MAX_LOOP_ITERATIONS = 15
    }

    private var activeJob: Job? = null
    private var sessionCollectJob: Job? = null
    private var stopJob: Job? = null

    private val _isStopping = MutableStateFlow(false)
    val isStopping: StateFlow<Boolean> = _isStopping.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

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

    fun close() {
        sessionCollectJob?.cancel()
        activeJob?.cancel()
        stopJob?.cancel()
        scope.cancel()
    }

    private suspend fun initSession() {
        try {
            val repo = chatRepository
            if (repo == null) {
                _isInitialized.value = true
                return
            }
            val savedSessionId = preferenceManager?.loadActiveSessionId()

            val sessionToLoad = if (savedSessionId != null) {
                repo.getSessionById(savedSessionId)
            } else null

            if (sessionToLoad != null) {
                loadSession(sessionToLoad)
            } else {
                createNewSessionInternal()
            }
        } finally {
            _isInitialized.value = true
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
        scope.launch {
            sessionCollectJob?.cancel()
            stopExecution(silent = true)
            createNewSessionInternal()
        }
    }

    private suspend fun createNewSessionInternal() {
        val repo = chatRepository
        val config = _modelConfig.value
        val session = repo?.createNewSession(
            title = "New chat",
            provider = config.provider.name,
            model = config.modelName
        ) ?: ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            modelProvider = config.provider.name,
            modelName = config.modelName
        )
        _messages.value = emptyList()
        _artifacts.value = emptyList()
        loadSession(session)
    }

    fun switchSession(sessionId: String) {
        if (_currentSession.value?.id == sessionId) return
        scope.launch {
            sessionCollectJob?.cancel()
            stopExecution(silent = true)
            val session = chatRepository?.getSessionById(sessionId)
            if (session != null) {
                _artifacts.value = emptyList()
                _messages.value = emptyList()
                loadSession(session)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            if (_currentSession.value?.id == sessionId) {
                sessionCollectJob?.cancel()
                stopExecution(silent = true)
                chatRepository?.deleteSession(sessionId)
                createNewSessionInternal()
            } else {
                chatRepository?.deleteSession(sessionId)
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
        val current = _currentSession.value
        scope.launch {
            sessionCollectJob?.cancel()
            stopExecution(silent = true)
            if (current != null && chatRepository != null) {
                chatRepository.deleteSession(current.id)
                createNewSessionInternal()
            } else {
                _messages.value = emptyList()
            }
        }
    }

    fun startTask(userPrompt: String, imageBase64: String? = null, imageMimeType: String? = null): Boolean {
        if (!_isInitialized.value || _isBusy.value || _isStopping.value || userPrompt.isBlank()) return false

        val targetSession = _currentSession.value
        val executionSessionId = targetSession?.id ?: UUID.randomUUID().toString()

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
                if (targetSession != null && (targetSession.title == "New chat" || targetSession.title.isBlank())) {
                    val generatedTitle = chatRepository?.generateConciseTitle(userPrompt) ?: "Chat"
                    _currentSession.value = targetSession.copy(title = generatedTitle)
                    chatRepository?.updateSessionTitle(executionSessionId, generatedTitle)
                }

                // Persist user message to DB
                chatRepository?.saveMessage(executionSessionId, userMessage)

                runAgentLoop(executionSessionId)
            } catch (e: CancellationException) {
                recoverAbortedToolCalls(executionSessionId, "Execution canceled by the user.")
                appendSystemMessage("🛑 Execution canceled by the user.", executionSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error: ${e.message}", e)
                recoverAbortedToolCalls(executionSessionId, "Agent loop error: ${e.localizedMessage}")
                appendSystemMessage("⚠️ Agent loop error: ${e.localizedMessage}", executionSessionId)
            } finally {
                _isBusy.value = false
                _pendingApproval.value = null
            }
        }
        return true
    }

    internal fun prepareContextMessages(
        allMessages: List<ChatMessage>,
        maxMessages: Int = 14,
        maxCharBudget: Int = 80_000
    ): List<ChatMessage> {
        val nonSystem = allMessages.filter { it.role != MessageRole.SYSTEM }
        if (nonSystem.isEmpty()) return emptyList()

        val latestUserMsg = nonSystem.lastOrNull { it.role == MessageRole.USER }
        var window = nonSystem.takeLast(maxMessages).toMutableList()

        // Ensure we do not start the window with an orphaned TOOL result
        while (window.isNotEmpty() && window.first().role == MessageRole.TOOL) {
            window.removeAt(0)
        }

        if (latestUserMsg != null && !window.contains(latestUserMsg)) {
            window.add(0, latestUserMsg)
        }

        fun totalChars(msgs: List<ChatMessage>): Int =
            msgs.sumOf { it.text.length + (it.toolResult?.stdout?.length ?: 0) + (it.toolResult?.stderr?.length ?: 0) }

        while (window.size > 2 && totalChars(window) > maxCharBudget) {
            val dropIdx = if (window[0] == latestUserMsg && window.size > 1) 1 else 0
            window.removeAt(dropIdx)
            while (window.isNotEmpty() && window.first().role == MessageRole.TOOL) {
                window.removeAt(0)
            }
            if (latestUserMsg != null && !window.contains(latestUserMsg)) {
                window.add(0, latestUserMsg)
            }
        }

        return window
    }

    private suspend fun runAgentLoop(executionSessionId: String) {
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
            var detectedTools: List<ToolCall> = emptyList()
            var detectedThought: String = ""
            var errorEvent: LlmClient.LlmStreamEvent.Error? = null
            val textBuilder = StringBuilder()

            // Stream execution loop with automatic fallback resilience
            var streamAttempt = 0
            while (streamAttempt < 2 && !streamCompleted && currentCoroutineContext().isActive) {
                streamAttempt++
                try {
                    val contextMessages = prepareContextMessages(_messages.value.filter { it.id != assistantMsgId })
                    llmClient.streamRequest(
                        config = activeConfig,
                        systemPrompt = AgentPrompts.SYSTEM_PROMPT,
                        messages = contextMessages
                    ).collect { event ->
                        when (event) {
                            is LlmClient.LlmStreamEvent.Token -> {
                                textBuilder.append(event.textChunk)
                                updateMessageText(assistantMsgId, textBuilder.toString(), MessageStatus.STREAMING)
                            }
                            is LlmClient.LlmStreamEvent.ToolCallDetected -> {
                                detectedTools = event.toolCalls.ifEmpty { listOf(event.toolCall) }
                                detectedThought = event.thought.ifEmpty { textBuilder.toString() }
                                recordMetrics(event.promptTokens, event.completionTokens, event.estimatedCostUsd, executionSessionId)
                                streamCompleted = true
                            }
                            is LlmClient.LlmStreamEvent.Completed -> {
                                val full = event.fullText.ifEmpty { textBuilder.toString() }
                                updateMessageText(assistantMsgId, full, MessageStatus.COMPLETED)
                                recordMetrics(event.promptTokens, event.completionTokens, event.estimatedCostUsd, executionSessionId)
                                streamCompleted = true
                            }
                            is LlmClient.LlmStreamEvent.Error -> {
                                errorEvent = event
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorEvent = LlmClient.LlmStreamEvent.Error(e.localizedMessage ?: "Network error", 0, true)
                }

                // If retryable error occurred and fallback provider is configured, failover seamlessly!
                if (!streamCompleted && errorEvent != null) {
                    val fallback = primaryConfig.fallbackProvider
                    if (fallback != null && !isUsingFallback && errorEvent!!.isRetryable) {
                        isUsingFallback = true
                        val fallbackModel = primaryConfig.fallbackModelName.ifEmpty { fallback.defaultModel }
                        val fallbackUrl = primaryConfig.fallbackBaseUrl.ifEmpty { fallback.defaultBaseUrl }
                        activeConfig = ModelConfig(
                            provider = fallback,
                            modelName = fallbackModel,
                            apiKey = primaryConfig.fallbackApiKey,
                            baseUrl = fallbackUrl
                        )
                        appendSystemMessage("⚠️ Primary provider ${primaryConfig.provider.displayName} failed (${errorEvent!!.message}). Switching to fallback: ${fallback.displayName} ($fallbackModel)...", executionSessionId)
                        textBuilder.clear()
                        errorEvent = null
                        continue
                    }
                }
                break
            }

            if (!streamCompleted && errorEvent != null) {
                updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
                val failedAssistantMsg = _messages.value.firstOrNull { it.id == assistantMsgId }
                if (failedAssistantMsg != null) {
                    chatRepository?.saveMessage(executionSessionId, failedAssistantMsg)
                }
                appendSystemMessage("⚠️ AI API error: ${errorEvent!!.message}", executionSessionId)
                break
            }

            // If tool calls were detected, execute and process tool observations
            if (detectedTools.isNotEmpty()) {
                if (detectedThought.isNotEmpty()) {
                    updateMessageText(assistantMsgId, detectedThought, MessageStatus.EXECUTING_TOOL)
                }
                updateMessageToolCalls(assistantMsgId, detectedTools, MessageStatus.EXECUTING_TOOL)
                val preMsg = _messages.value.firstOrNull { it.id == assistantMsgId }
                if (preMsg != null) {
                    chatRepository?.saveMessage(executionSessionId, preMsg)
                }

                val pendingVisionObservations = mutableListOf<ChatMessage>()
                for (toolCall in detectedTools) {
                    if (!currentCoroutineContext().isActive) break
                    val cmd = toolCall.arguments["command"]?.trim() ?: ""

                    // 1. RUNAWAY & LOOP DETECTION GUARDRAILS
                    if (isRunawayOrLoopDetected(cmd, commandHistory, consecutiveErrors)) {
                        val runawayMsg = "🛑 Loop protection: repeated execution or persistent failures detected for `$cmd` . Execution stopped."
                        appendSystemMessage(runawayMsg, executionSessionId)
                        updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
                        recoverAbortedToolCalls(executionSessionId, "Loop protection stopped execution.")
                        val stoppedMsg = _messages.value.firstOrNull { it.id == assistantMsgId }
                        if (stoppedMsg != null) {
                            chatRepository?.saveMessage(executionSessionId, stoppedMsg)
                        }
                        return
                    }
                    commandHistory.add(cmd)

                    val assessment = CommandSecurityFilter.analyze(cmd)
                    val securedToolCall = toolCall.copy(
                        riskLevel = assessment.level.name,
                        riskReason = assessment.reason
                    )
                    val updatedTools = detectedTools.map { if (it.id == securedToolCall.id) securedToolCall else it }
                    updateMessageToolCalls(assistantMsgId, updatedTools, MessageStatus.EXECUTING_TOOL)

                    // 2. Blacklist Check
                    if (assessment.isBlocked) {
                        val blockedMsg = "🛡️ Security block: command `$cmd` was blocked.\nReason: ${assessment.reason}"
                        appendSystemMessage(blockedMsg, executionSessionId)

                        val blockedResult = ToolResult(
                            toolCallId = securedToolCall.id,
                            command = cmd,
                            stderr = "Security block active: ${assessment.reason}",
                            isError = true,
                            exitCode = -1
                        )
                        updateMessageToolResult(assistantMsgId, blockedResult, MessageStatus.ERROR)

                        val toolRejectedMsg = ChatMessage(
                            role = MessageRole.TOOL,
                            text = "Execution blocked: ${assessment.reason}",
                            toolResult = blockedResult,
                            status = MessageStatus.ERROR
                        )
                        _messages.value = _messages.value + toolRejectedMsg
                        chatRepository?.saveMessage(executionSessionId, toolRejectedMsg)

                        // Record Audit Entity
                        chatRepository?.recordAudit(
                            CommandAuditEntity(
                                sessionId = executionSessionId,
                                command = cmd,
                                riskLevel = RiskLevel.BLOCKED.name,
                                riskReason = assessment.reason,
                                exitCode = -1,
                                stderr = "Security block active",
                                wasApproved = false
                            )
                        )
                        consecutiveErrors++
                        continue
                    }

                    // 3. Approval Check (Step-by-step or High-Risk)
                    val mustApprove = CommandSecurityFilter.shouldRequireApproval(assessment, _executionMode.value)
                    if (mustApprove) {
                        updateMessageToolCalls(assistantMsgId, updatedTools, MessageStatus.WAITING_FOR_APPROVAL)
                        _pendingApproval.value = Pair(assistantMsgId, securedToolCall)
                        val deferred = CompletableDeferred<Boolean>()
                        approvalContinuation = deferred

                        val approved = deferred.await()
                        _pendingApproval.value = null
                        approvalContinuation = null

                        if (!approved) {
                            val rejectedResult = ToolResult(
                                toolCallId = securedToolCall.id,
                                command = cmd,
                                stderr = "Execution rejected by the user.",
                                isError = true,
                                exitCode = -1
                            )
                            updateMessageToolResult(assistantMsgId, rejectedResult, MessageStatus.ERROR)

                            val rejectedMsg = ChatMessage(
                                role = MessageRole.TOOL,
                                text = "Command rejected by the user.",
                                toolResult = rejectedResult,
                                status = MessageStatus.ERROR
                            )
                            _messages.value = _messages.value + rejectedMsg
                            chatRepository?.saveMessage(executionSessionId, rejectedMsg)

                            chatRepository?.recordAudit(
                                CommandAuditEntity(
                                    sessionId = executionSessionId,
                                    command = cmd,
                                    riskLevel = assessment.level.name,
                                    riskReason = "Rejected by the user",
                                    exitCode = -1,
                                    stderr = "Rejected",
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

                    if (result.isError || result.exitCode != 0) consecutiveErrors++ else consecutiveErrors = 0

                    // 5. Record Audit Entity in Room
                    chatRepository?.recordAudit(
                        CommandAuditEntity(
                            sessionId = executionSessionId,
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
                    if (cmd.contains("termux-camera-photo") && result.exitCode == 0) {
                        val photoPath = extractPhotoPathFromCommand(cmd)
                        if (photoPath != null) {
                            try {
                                val base64Img = bridgeClient.readFileBase64(photoPath)
                                if (base64Img.isNotEmpty()) {
                                    pendingVisionObservations.add(
                                        ChatMessage(
                                            role = MessageRole.USER,
                                            text = "📸 Photo captured in Termux (`$photoPath`):",
                                            imageBase64 = base64Img,
                                            imageMimeType = "image/jpeg",
                                            status = MessageStatus.COMPLETED
                                        )
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
                                Log.w(TAG, "Termux vision: could not read photo: ${e.message}")
                            }
                        }
                    }

                    // 7. Generic Artifact Scanner
                    detectAndRegisterArtifacts(cmd, result)

                    // 8. Add Tool Observation to Context & Bind to Assistant Card
                    val rawOutput = if (result.stdout.isNotEmpty()) result.stdout else result.stderr
                    val compactedOutput = compactToolOutput(rawOutput)
                    val guardedResult = result.copy(
                        toolCallId = securedToolCall.id,
                        command = cmd,
                        stdout = if (result.stdout.isNotEmpty()) "[UNTRUSTED_OUTPUT_START]\n${compactToolOutput(result.stdout)}\n[UNTRUSTED_OUTPUT_END]" else "",
                        stderr = if (result.stderr.isNotEmpty()) "[UNTRUSTED_OUTPUT_START]\n${compactToolOutput(result.stderr)}\n[UNTRUSTED_OUTPUT_END]" else ""
                    )

                    val execStatus = if (result.isError || result.exitCode != 0) MessageStatus.ERROR else MessageStatus.COMPLETED
                    updateMessageToolResult(assistantMsgId, guardedResult, execStatus)

                    val toolMsg = ChatMessage(
                        role = MessageRole.TOOL,
                        text = "[UNTRUSTED_OUTPUT_START]\n$compactedOutput\n[UNTRUSTED_OUTPUT_END]",
                        toolResult = guardedResult,
                        status = execStatus
                    )
                    _messages.value = _messages.value + toolMsg
                    chatRepository?.saveMessage(executionSessionId, toolMsg)
                }

                for (visionObservation in pendingVisionObservations) {
                    _messages.value = _messages.value + visionObservation
                    chatRepository?.saveMessage(executionSessionId, visionObservation)
                }

                val completedAssistant = _messages.value.firstOrNull { it.id == assistantMsgId }
                if (completedAssistant != null) {
                    chatRepository?.saveMessage(executionSessionId, completedAssistant)
                }
            } else {
                // Persist the completed assistant text message when no tool call
                val finalAssistantMsg = _messages.value.firstOrNull { it.id == assistantMsgId }
                if (finalAssistantMsg != null) {
                    chatRepository?.saveMessage(executionSessionId, finalAssistantMsg)
                }
                // Normal message completion reached, end loop
                break
            }
        }

        if (iterations >= MAX_LOOP_ITERATIONS) {
            appendSystemMessage("ℹ️ Step limit of $MAX_LOOP_ITERATIONS reached. Execution ended.", executionSessionId)
        }
    }

    internal fun compactToolOutput(
        text: String,
        maxChars: Int = 4000,
        headChars: Int = 2000,
        tailChars: Int = 1500
    ): String {
        if (text.length <= maxChars) {
            return text
        }
        val omitted = text.length - (headChars + tailChars)
        if (omitted <= 0) {
            return text.take(maxChars)
        }
        val head = text.take(headChars)
        val tail = text.takeLast(tailChars)
        return "$head\n\n... [Output truncated: $omitted characters omitted to conserve context] ...\n\n$tail"
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
        val regex = Regex("""termux-camera-photo(?:\s+-[a-zA-Z0-9]+(?:\s+\S+)?)*\s+(?:["']([^"']+)["']|(\S+))""")
        val match = regex.find(cmd)
        if (match != null) {
            return match.groups[1]?.value ?: match.groups[2]?.value
        }

        val tokens = mutableListOf<String>()
        val tokenMatcher = java.util.regex.Pattern.compile(""""([^"]*)"|'([^']*)'|(\S+)""").matcher(cmd)
        while (tokenMatcher.find()) {
            tokens.add(tokenMatcher.group(1) ?: tokenMatcher.group(2) ?: tokenMatcher.group(3))
        }
        val photoIndex = tokens.indexOfFirst { it.contains("termux-camera-photo") }
        if (photoIndex == -1) return null

        var i = photoIndex + 1
        var candidate: String? = null
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.startsWith("-")) {
                if (t in listOf("-c", "--camera") && i + 1 < tokens.size) {
                    i += 2
                    continue
                }
            } else {
                candidate = t
            }
            i++
        }
        return candidate ?: "photo.jpg"
    }

    private fun detectAndRegisterArtifacts(command: String, result: ToolResult) {
        val fileExtensions = listOf(".py", ".sh", ".js", ".json", ".md", ".html", ".txt", ".png", ".jpg", ".jpeg")
        val tokens = mutableListOf<String>()
        val tokenMatcher = java.util.regex.Pattern.compile(""""([^"]*)"|'([^']*)'|(\S+)""").matcher(command)
        while (tokenMatcher.find()) {
            val token = tokenMatcher.group(1) ?: tokenMatcher.group(2) ?: tokenMatcher.group(3)
            if (token != null) tokens.add(token)
        }

        for (token in tokens) {
            val clean = token.trim('"', '\'', '>', '<', ';', '&', ' ')
            val ext = fileExtensions.firstOrNull { clean.endsWith(it, ignoreCase = true) }
            if (ext != null && clean.length > ext.length && !clean.contains('\n')) {
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
                        content = null
                    )
                )
            }
        }
    }

    suspend fun loadArtifactContent(artifact: ArtifactItem): String = withContext(Dispatchers.IO) {
        if (artifact.content != null && artifact.type != ArtifactType.IMAGE) return@withContext artifact.content
        if (artifact.base64Data != null && artifact.type == ArtifactType.IMAGE) return@withContext artifact.base64Data
        try {
            if (artifact.type == ArtifactType.IMAGE) {
                val base64 = bridgeClient.readFileBase64(artifact.path)
                val updated = artifact.copy(base64Data = base64)
                _artifacts.value = _artifacts.value.map { if (it.path == artifact.path) updated else it }
                base64
            } else {
                val text = bridgeClient.readFile(artifact.path)
                val updated = artifact.copy(content = text)
                _artifacts.value = _artifacts.value.map { if (it.path == artifact.path) updated else it }
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load artifact content: ${e.message}")
            artifact.content ?: artifact.base64Data ?: ""
        }
    }

    private fun appendArtifact(item: ArtifactItem) {
        val existingIndex = _artifacts.value.indexOfFirst { it.path == item.path }
        if (existingIndex >= 0) {
            _artifacts.value = _artifacts.value.mapIndexed { idx, existing ->
                if (idx == existingIndex) item else existing
            }
        } else {
            _artifacts.value = _artifacts.value + item
        }
    }

    private fun recordMetrics(promptTokens: Int, completionTokens: Int, costUsd: Double, sessionId: String? = null) {
        _metrics.value = _metrics.value.copy(
            promptTokens = _metrics.value.promptTokens + promptTokens,
            completionTokens = _metrics.value.completionTokens + completionTokens,
            estimatedCostUsd = _metrics.value.estimatedCostUsd + costUsd
        )
        val targetId = sessionId ?: _currentSession.value?.id
        if (targetId != null) {
            scope.launch {
                chatRepository?.addMetrics(targetId, promptTokens, completionTokens, costUsd)
            }
        }
    }

    fun approvePendingAction(toolCallId: String? = null) {
        val pending = _pendingApproval.value
        if (toolCallId != null && pending != null && pending.second.id != toolCallId) {
            Log.w(TAG, "Mismatched tool approval ID: expected ${pending.second.id} but received $toolCallId")
            return
        }
        approvalContinuation?.complete(true)
    }

    fun rejectPendingAction(toolCallId: String? = null) {
        val pending = _pendingApproval.value
        if (toolCallId != null && pending != null && pending.second.id != toolCallId) {
            Log.w(TAG, "Mismatched tool reject ID: expected ${pending.second.id} but received $toolCallId")
            return
        }
        approvalContinuation?.complete(false)
    }

    suspend fun stopExecution(silent: Boolean = false) {
        _isStopping.value = true
        approvalContinuation?.cancel()
        bridgeClient.interruptCurrent()
        _pendingApproval.value = null
        if (!silent) {
            appendSystemMessage("🛑 Emergency stop requested. Waiting for the bridge to stop execution.", _currentSession.value?.id)
        }
        val jobToCancel = activeJob
        activeJob = null
        try {
            jobToCancel?.cancelAndJoin()
        } finally {
            _isBusy.value = false
            _isStopping.value = false
            stopJob = null
        }
    }

    fun emergencyStop() {
        _isStopping.value = true
        if (stopJob?.isActive == true) return
        stopJob = scope.launch {
            stopExecution(silent = false)
        }
    }

    private fun recoverAbortedToolCalls(executionSessionId: String, reason: String) {
        val currentMessages = _messages.value
        val lastAssistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        val toolCalls = lastAssistant.toolCalls.ifEmpty { listOfNotNull(lastAssistant.toolCall) }
        if (toolCalls.isEmpty()) return

        for (toolCall in toolCalls) {
            val alreadyAnswered = currentMessages.any { it.role == MessageRole.TOOL && it.toolResult?.toolCallId == toolCall.id }
            if (!alreadyAnswered) {
                val abortedResult = ToolResult(
                    toolCallId = toolCall.id,
                    command = toolCall.arguments["command"] ?: "",
                    stderr = reason,
                    isError = true,
                    exitCode = -1
                )
                val abortedMsg = ChatMessage(
                    role = MessageRole.TOOL,
                    text = reason,
                    toolResult = abortedResult,
                    status = MessageStatus.ERROR
                )
                _messages.value = _messages.value + abortedMsg
                scope.launch(NonCancellable) {
                    chatRepository?.saveMessage(executionSessionId, abortedMsg)
                }
            }
        }
    }

    private fun updateMessageToolCalls(messageId: String, toolCalls: List<ToolCall>, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(toolCalls = toolCalls, toolCall = toolCalls.firstOrNull(), status = status)
            } else msg
        }
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

    private fun updateMessageToolResult(messageId: String, toolResult: ToolResult, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(toolResult = toolResult, status = status)
            } else msg
        }
    }

    private fun updateStreamingOutput(messageId: String, newChunk: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                val updatedOutput = (msg.streamingTerminalOutput + newChunk).takeLast(50_000)
                msg.copy(streamingTerminalOutput = updatedOutput)
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

    private fun appendSystemMessage(text: String, sessionId: String? = null) {
        val sysMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            text = text,
            status = MessageStatus.COMPLETED
        )
        _messages.value = _messages.value + sysMsg
        val targetId = sessionId ?: _currentSession.value?.id
        if (targetId != null) {
            scope.launch { chatRepository?.saveMessage(targetId, sysMsg) }
        }
    }
}
