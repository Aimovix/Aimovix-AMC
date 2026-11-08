package com.agent.mobile.agent

import android.util.Log
import com.agent.mobile.data.model.*
import com.agent.mobile.data.network.LlmClient
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.network.WebFetcher
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.security.SecurityAssessment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The autonomous agent loop: model → tool calls → observations → model … until the task is done.
 *
 * One instance drives the interactive chat. Background runs (scheduled tasks, heartbeat) use a
 * separate headless instance with its own bridge connection, see [runUnattended].
 */
class AutonomousAgentEngine(
    val bridgeClient: TermuxBridgeClient,
    val llmClient: LlmClient = LlmClient(),
    val preferenceManager: PreferenceManager? = null,
    val chatRepository: ChatRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    val memoryStore: MemoryStore? = null,
    private val platform: AgentPlatform? = null,
    private val webFetcher: WebFetcher = WebFetcher(),
    private val headless: Boolean = false
) {
    companion object {
        private const val TAG = "AgentEngine"

        /** Upper bound for unattended runs so a background job always finishes in time. */
        const val UNATTENDED_MAX_STEPS = 25
        const val UNATTENDED_TIMEOUT_MS = 9 * 60_000L

        /** Consecutive failed tool calls after which the agent is told to rethink its approach. */
        private const val REFLECTION_THRESHOLD = 3
        private const val MAX_CONSECUTIVE_ERRORS = 5

        private const val DEFAULT_COMMAND_TIMEOUT_MS = 90_000L
        private const val MAX_COMMAND_TIMEOUT_MS = 30 * 60_000L

        /** Only the most recent images are replayed to the model; older ones cost tokens for little value. */
        private const val MAX_IMAGES_IN_CONTEXT = 3
        private const val IMAGE_CHAR_WEIGHT = 4_000

        private val UNTRUSTED_OUTPUT_TOOLS = setOf(AgentTools.EXECUTE_COMMAND, AgentTools.READ_FILE, AgentTools.WEB_FETCH)
        private val LOOP_EXEMPT_TOOLS = setOf(AgentTools.UPDATE_PLAN, AgentTools.REMEMBER, AgentTools.LIST_SCHEDULED_TASKS)
    }

    data class UnattendedResult(
        val sessionId: String?,
        val summary: String,
        val success: Boolean,
        val skippedApprovals: List<String>
    )

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

    private val _securityPreset = MutableStateFlow(
        preferenceManager?.loadSecurityPreset() ?: SecurityPreset.AUTONOMOUS
    )
    val securityPreset: StateFlow<SecurityPreset> = _securityPreset.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _modelConfig = MutableStateFlow(
        preferenceManager?.loadModelConfig() ?: ModelConfig()
    )
    val modelConfig: StateFlow<ModelConfig> = _modelConfig.asStateFlow()

    private val _maxSteps = MutableStateFlow(
        preferenceManager?.loadMaxSteps() ?: PreferenceManager.DEFAULT_MAX_STEPS
    )
    val maxSteps: StateFlow<Int> = _maxSteps.asStateFlow()

    private val _pendingApproval = MutableStateFlow<Pair<String, ToolCall>?>(null)
    val pendingApproval: StateFlow<Pair<String, ToolCall>?> = _pendingApproval.asStateFlow()

    private val _artifacts = MutableStateFlow<List<ArtifactItem>>(emptyList())
    val artifacts: StateFlow<List<ArtifactItem>> = _artifacts.asStateFlow()

    private val _metrics = MutableStateFlow(SessionMetrics())
    val metrics: StateFlow<SessionMetrics> = _metrics.asStateFlow()

    /** The agent's current plan (from the update_plan tool). */
    private val _plan = MutableStateFlow<List<PlanStep>>(emptyList())
    val plan: StateFlow<List<PlanStep>> = _plan.asStateFlow()

    /** Number of user messages typed while the agent was busy, waiting to be picked up. */
    private val _queuedInputs = MutableStateFlow(0)
    val queuedInputs: StateFlow<Int> = _queuedInputs.asStateFlow()

    /** Step counter of the running task (for the UI progress label). */
    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val steeringQueue = ConcurrentLinkedQueue<ChatMessage>()
    private val pendingWrites = java.util.Collections.synchronizedList(mutableListOf<Job>())
    private var approvalContinuation: CompletableDeferred<Boolean>? = null

    init {
        preferenceManager?.let { prefs ->
            CommandSecurityFilter.setCustomRules(
                whitelist = prefs.loadWhitelist(),
                blacklist = prefs.loadBlacklist(),
                strict = prefs.loadStrictMode()
            )
        }
        if (headless) {
            _isInitialized.value = true
        } else {
            scope.launch { initSession() }
        }
    }

    fun close() {
        sessionCollectJob?.cancel()
        activeJob?.cancel()
        stopJob?.cancel()
        scope.cancel()
    }

    // -------------------------------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------------------------------

    private suspend fun initSession() {
        try {
            val repo = chatRepository
            if (repo == null) {
                _isInitialized.value = true
                return
            }
            val savedSessionId = preferenceManager?.loadActiveSessionId()
            val sessionToLoad = if (savedSessionId != null) repo.getSessionById(savedSessionId) else null
            if (sessionToLoad != null) loadSession(sessionToLoad) else createNewSessionInternal()
        } finally {
            _isInitialized.value = true
        }
    }

    fun loadSession(session: ChatSession) {
        _currentSession.value = session
        if (!headless) preferenceManager?.saveActiveSessionId(session.id)
        _metrics.value = SessionMetrics(
            promptTokens = session.totalPromptTokens,
            completionTokens = session.totalCompletionTokens,
            estimatedCostUsd = session.estimatedCostUsd
        )

        sessionCollectJob?.cancel()
        sessionCollectJob = scope.launch {
            chatRepository?.getMessagesForSession(session.id)?.collect { dbMessages ->
                // Only adopt DB state while idle; during a run the in-memory list is the source of truth.
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

    private suspend fun createNewSessionInternal(title: String = "New chat") {
        val config = _modelConfig.value
        val session = chatRepository?.createNewSession(
            title = title,
            provider = config.provider.name,
            model = config.modelName
        ) ?: ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            modelProvider = config.provider.name,
            modelName = config.modelName
        )
        _messages.value = emptyList()
        _artifacts.value = emptyList()
        _plan.value = emptyList()
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
                _plan.value = emptyList()
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
                _plan.value = emptyList()
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------------------------

    fun setSecurityPreset(preset: SecurityPreset) {
        _securityPreset.value = preset
        preferenceManager?.saveSecurityPreset(preset)
    }

    fun setModelConfig(config: ModelConfig) {
        _modelConfig.value = config
        preferenceManager?.saveModelConfig(config)
    }

    fun setMaxSteps(steps: Int) {
        val clamped = steps.coerceIn(PreferenceManager.MIN_MAX_STEPS, PreferenceManager.MAX_MAX_STEPS)
        _maxSteps.value = clamped
        preferenceManager?.saveMaxSteps(clamped)
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

    // -------------------------------------------------------------------------------------------
    // Running tasks
    // -------------------------------------------------------------------------------------------

    /**
     * Starts a task from the user. While the agent is already working, the message is queued and
     * picked up before the next step ("steering"), so the user can add instructions mid-run.
     */
    fun startTask(userPrompt: String, imageBase64: String? = null, imageMimeType: String? = null): Boolean {
        if (!_isInitialized.value || _isStopping.value || userPrompt.isBlank()) return false

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = userPrompt.trim(),
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )

        if (_isBusy.value) {
            steeringQueue.add(userMessage)
            _queuedInputs.value = steeringQueue.size
            return true
        }

        val targetSession = _currentSession.value
        val executionSessionId = targetSession?.id ?: UUID.randomUUID().toString()

        _messages.value = _messages.value + userMessage
        _isBusy.value = true
        _plan.value = emptyList()
        _currentStep.value = 0

        activeJob = scope.launch {
            try {
                if (targetSession != null && (targetSession.title == "New chat" || targetSession.title.isBlank())) {
                    val generatedTitle = chatRepository?.generateConciseTitle(userPrompt) ?: "Chat"
                    _currentSession.value = targetSession.copy(title = generatedTitle)
                    chatRepository?.updateSessionTitle(executionSessionId, generatedTitle)
                }
                chatRepository?.saveMessage(executionSessionId, userMessage)
                runAgentLoop(executionSessionId, unattended = false)
            } catch (e: CancellationException) {
                recoverAbortedToolCalls(executionSessionId, "Execution canceled by the user.")
                appendSystemMessage("🛑 Execution canceled by the user.", executionSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error: ${e.message}", e)
                recoverAbortedToolCalls(executionSessionId, "Agent loop error: ${e.localizedMessage}")
                appendSystemMessage("⚠️ Agent loop error: ${e.localizedMessage}", executionSessionId)
            } finally {
                withContext(NonCancellable) {
                    // Messages typed after the run ended (or after a stop) are kept, not lost.
                    drainSteeringQueue(executionSessionId)
                    _isBusy.value = false
                    _pendingApproval.value = null
                    try {
                        val sessionId = _currentSession.value?.id ?: executionSessionId
                        chatRepository?.getMessagesForSession(sessionId)?.firstOrNull()?.let { dbMessages ->
                            _messages.value = dbMessages
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-sync messages from Room DB: ${e.message}")
                    }
                }
            }
        }
        return true
    }

    /**
     * Runs one task without a user watching (scheduled task or heartbeat) in a fresh session.
     * Must be called on a headless engine instance. Actions that need approval are refused.
     */
    suspend fun runUnattended(title: String, prompt: String): UnattendedResult = withContext(Dispatchers.Main) {
        check(headless) { "runUnattended requires a headless engine" }
        val skipped = mutableListOf<String>()
        createNewSessionInternal(title)
        val sessionId = _currentSession.value?.id
            ?: return@withContext UnattendedResult(null, "Could not create a session.", false, skipped)

        val userMessage = ChatMessage(role = MessageRole.USER, text = prompt.trim())
        _messages.value = listOf(userMessage)
        _isBusy.value = true
        var success = true
        try {
            chatRepository?.saveMessage(sessionId, userMessage)
            val finished = withTimeoutOrNull(UNATTENDED_TIMEOUT_MS) {
                runAgentLoop(sessionId, unattended = true, skippedApprovals = skipped)
                true
            }
            if (finished == null) {
                success = false
                bridgeClient.interruptCurrent()
                recoverAbortedToolCalls(sessionId, "Background time limit reached.")
                appendSystemMessage("⏱️ Background time limit reached. The task was stopped.", sessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            success = false
            Log.e(TAG, "Unattended run failed: ${e.message}", e)
            appendSystemMessage("⚠️ Background run failed: ${e.localizedMessage}", sessionId)
        } finally {
            withContext(NonCancellable) { awaitPendingWrites() }
            _isBusy.value = false
        }

        val lastAssistant = _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.text.isNotBlank() }
        val lastError = _messages.value.lastOrNull { it.role == MessageRole.SYSTEM && it.text.startsWith("⚠️") }
        if (lastAssistant == null || lastAssistant.status == MessageStatus.ERROR) success = false
        val summary = lastAssistant?.text?.trim()
            ?: lastError?.text
            ?: "The task finished without a summary."
        UnattendedResult(sessionId, summary, success, skipped)
    }

    private fun drainSteeringQueue(sessionId: String): Boolean {
        var drained = false
        while (true) {
            val queued = steeringQueue.poll() ?: break
            // Re-stamp so DB ordering places it after the tool results that preceded it.
            val msg = queued.copy(timestamp = System.currentTimeMillis())
            _messages.value = _messages.value + msg
            persistAsync { chatRepository?.saveMessage(sessionId, msg) }
            drained = true
        }
        _queuedInputs.value = steeringQueue.size
        return drained
    }

    private class LoopState {
        val history = mutableListOf<String>()
        var consecutiveErrors = 0
        var loopWarnings = 0
        var stopRequested = false
    }

    private suspend fun runAgentLoop(
        executionSessionId: String,
        unattended: Boolean,
        skippedApprovals: MutableList<String> = mutableListOf()
    ) {
        val stepLimit = if (unattended) minOf(_maxSteps.value, UNATTENDED_MAX_STEPS) else _maxSteps.value
        // Built once per run so the prompt prefix stays identical between steps (prompt caching).
        val systemPrompt = AgentPrompts.build(
            memory = memoryStore?.asPromptText().orEmpty(),
            unattended = unattended,
            maxSteps = stepLimit
        )
        val state = LoopState()
        var iterations = 0

        while (currentCoroutineContext().isActive) {
            if (!unattended) drainSteeringQueue(executionSessionId)
            if (iterations >= stepLimit) {
                appendSystemMessage(
                    "ℹ️ Step limit of $stepLimit reached. Send \"continue\" to let the agent keep working " +
                        "or raise the limit in Settings → Autonomy.",
                    executionSessionId
                )
                break
            }
            iterations++
            _currentStep.value = iterations

            val outcome = runSingleStep(executionSessionId, systemPrompt, unattended, state, skippedApprovals)
            if (outcome == StepOutcome.STOP || state.stopRequested) break
            if (outcome == StepOutcome.FINISHED) {
                // The model answered without tools. Continue only if the user queued new input.
                if (!unattended && steeringQueue.isNotEmpty()) continue
                break
            }
        }
    }

    private enum class StepOutcome { CONTINUE, FINISHED, STOP }

    private suspend fun runSingleStep(
        executionSessionId: String,
        systemPrompt: String,
        unattended: Boolean,
        state: LoopState,
        skippedApprovals: MutableList<String>
    ): StepOutcome {
        val primaryConfig = _modelConfig.value
        var activeConfig = primaryConfig
        var isUsingFallback = false

        val assistantMsgId = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            text = "",
            status = MessageStatus.STREAMING
        )

        var streamCompleted = false
        var detectedTools: List<ToolCall> = emptyList()
        var detectedThought = ""
        var errorEvent: LlmClient.LlmStreamEvent.Error? = null
        val textBuilder = StringBuilder()

        var streamAttempt = 0
        while (streamAttempt < 2 && !streamCompleted && currentCoroutineContext().isActive) {
            streamAttempt++
            try {
                val contextMessages = prepareContextMessages(_messages.value.filter { it.id != assistantMsgId })
                llmClient.streamRequest(
                    config = activeConfig,
                    systemPrompt = systemPrompt,
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
                        is LlmClient.LlmStreamEvent.Error -> errorEvent = event
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorEvent = LlmClient.LlmStreamEvent.Error(e.localizedMessage ?: "Network error", 0, true)
            }

            if (!streamCompleted && errorEvent != null) {
                val fallback = primaryConfig.fallbackProvider
                if (fallback != null && !isUsingFallback && errorEvent!!.isRetryable) {
                    isUsingFallback = true
                    val fallbackModel = primaryConfig.fallbackModelName.ifEmpty { fallback.defaultModel }
                    activeConfig = ModelConfig(
                        provider = fallback,
                        modelName = fallbackModel,
                        apiKey = primaryConfig.fallbackApiKey,
                        baseUrl = primaryConfig.fallbackBaseUrl.ifEmpty { fallback.defaultBaseUrl }
                    )
                    appendSystemMessage(
                        "⚠️ Primary provider ${primaryConfig.provider.displayName} failed (${errorEvent!!.message}). " +
                            "Switching to fallback: ${fallback.displayName} ($fallbackModel)...",
                        executionSessionId
                    )
                    textBuilder.clear()
                    errorEvent = null
                    continue
                }
            }
            break
        }

        if (!streamCompleted) {
            updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
            _messages.value.firstOrNull { it.id == assistantMsgId }?.let { chatRepository?.saveMessage(executionSessionId, it) }
            if (errorEvent != null) {
                appendSystemMessage("⚠️ AI API error: ${errorEvent!!.message}", executionSessionId)
            }
            return StepOutcome.STOP
        }

        if (detectedTools.isEmpty()) {
            _messages.value.firstOrNull { it.id == assistantMsgId }?.let { chatRepository?.saveMessage(executionSessionId, it) }
            return StepOutcome.FINISHED
        }

        if (detectedThought.isNotEmpty()) {
            updateMessageText(assistantMsgId, detectedThought, MessageStatus.EXECUTING_TOOL)
        }
        val tools = detectedTools.toMutableList()
        updateMessageToolCalls(assistantMsgId, tools, MessageStatus.EXECUTING_TOOL)
        _messages.value.firstOrNull { it.id == assistantMsgId }?.let { chatRepository?.saveMessage(executionSessionId, it) }

        val pendingVisionObservations = mutableListOf<ChatMessage>()
        for ((index, toolCall) in tools.withIndex()) {
            if (!currentCoroutineContext().isActive) break
            if (state.stopRequested) {
                // A previous call in this batch ended the run; answer the remaining calls so the
                // conversation stays valid for every provider.
                appendToolMessage(executionSessionId, toolCall, toolCall.name, AgentTools.describe(toolCall), "", "Skipped: execution was stopped.", -1, isError = true)
                continue
            }
            executeToolCall(
                executionSessionId, assistantMsgId, toolCall, index, tools,
                unattended, state, skippedApprovals, pendingVisionObservations
            )
        }

        for (vision in pendingVisionObservations) {
            _messages.value = _messages.value + vision
            chatRepository?.saveMessage(executionSessionId, vision)
        }
        _messages.value.firstOrNull { it.id == assistantMsgId }?.let { chatRepository?.saveMessage(executionSessionId, it) }

        return if (state.stopRequested) StepOutcome.STOP else StepOutcome.CONTINUE
    }

    private suspend fun executeToolCall(
        sessionId: String,
        assistantMsgId: String,
        toolCall: ToolCall,
        index: Int,
        tools: MutableList<ToolCall>,
        unattended: Boolean,
        state: LoopState,
        skippedApprovals: MutableList<String>,
        pendingVision: MutableList<ChatMessage>
    ) {
        val description = AgentTools.describe(toolCall)

        if (!AgentTools.isKnown(toolCall.name)) {
            appendToolMessage(
                sessionId, toolCall, toolCall.name, description, "",
                "Unknown tool '${toolCall.name}'. Available tools: ${AgentTools.ALL.joinToString { it.name }}.",
                -1, isError = true
            )
            state.consecutiveErrors++
            return
        }

        // 1. Loop protection: warn once, then stop.
        if (toolCall.name !in LOOP_EXEMPT_TOOLS &&
            isRunawayOrLoopDetected(description, state.history, state.consecutiveErrors)
        ) {
            if (state.loopWarnings == 0) {
                state.loopWarnings++
                appendToolMessage(
                    sessionId, toolCall, toolCall.name, description, "",
                    "Loop protection: this action was NOT executed because you are repeating yourself or failing " +
                        "repeatedly. Stop, analyse the previous outputs, and either try a clearly different approach " +
                        "or report the problem to the user.",
                    -1, isError = true
                )
                return
            }
            appendSystemMessage("🛑 Loop protection: repeated actions or persistent failures (`$description`). Execution stopped.", sessionId)
            updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
            appendToolMessage(sessionId, toolCall, toolCall.name, description, "", "Loop protection stopped execution.", -1, isError = true)
            state.stopRequested = true
            return
        }
        state.history.add(description)

        // 2. Risk assessment.
        val assessment = AgentTools.assess(toolCall)
        val securedToolCall = toolCall.copy(riskLevel = assessment.level.name, riskReason = assessment.reason)
        tools[index] = securedToolCall
        updateMessageToolCalls(assistantMsgId, tools.toList(), MessageStatus.EXECUTING_TOOL)

        if (assessment.isBlocked) {
            appendSystemMessage("🛡️ Security block: `$description` was blocked.\nReason: ${assessment.reason}", sessionId)
            val blocked = appendToolMessage(sessionId, securedToolCall, toolCall.name, description, "", "Security block active: ${assessment.reason}", -1, isError = true)
            updateMessageToolResult(assistantMsgId, blocked, MessageStatus.ERROR)
            recordAudit(sessionId, description, RiskLevel.BLOCKED.name, assessment.reason, blocked, 0, approved = false)
            state.consecutiveErrors++
            return
        }

        // 3. Approval.
        if (CommandSecurityFilter.shouldRequireApproval(assessment, _securityPreset.value)) {
            val approved = if (unattended) {
                skippedApprovals += description
                false
            } else {
                updateMessageToolCalls(assistantMsgId, tools.toList(), MessageStatus.WAITING_FOR_APPROVAL)
                _pendingApproval.value = Pair(assistantMsgId, securedToolCall)
                platformNotifyApprovalNeeded(description)
                val deferred = CompletableDeferred<Boolean>()
                approvalContinuation = deferred
                try {
                    deferred.await()
                } finally {
                    _pendingApproval.value = null
                    approvalContinuation = null
                }
            }
            if (!approved) {
                val reason = if (unattended) {
                    "Not executed: this action requires the user's approval, and the user is not present during a " +
                        "background run. Skip it and mention it in your summary."
                } else {
                    "Execution rejected by the user. Do not retry this action; choose another approach or ask the user."
                }
                val rejected = appendToolMessage(sessionId, securedToolCall, toolCall.name, description, "", reason, -1, isError = true)
                updateMessageToolResult(assistantMsgId, rejected, MessageStatus.ERROR)
                recordAudit(sessionId, description, assessment.level.name, if (unattended) "Needs approval (background run)" else "Rejected by the user", rejected, 0, approved = false)
                return
            }
        }

        // 4. Execute.
        updateMessageStatus(assistantMsgId, MessageStatus.EXECUTING_TOOL)
        val start = System.currentTimeMillis()
        val raw = try {
            dispatchTool(securedToolCall, assistantMsgId, pendingVision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(toolCallId = securedToolCall.id, command = description, stderr = e.localizedMessage ?: e.toString(), exitCode = -1, isError = true)
        }
        val duration = System.currentTimeMillis() - start
        val failed = raw.isError || raw.exitCode != 0
        if (failed) state.consecutiveErrors++ else state.consecutiveErrors = 0

        recordAudit(sessionId, description, assessment.level.name, assessment.reason, raw, duration, approved = true)
        if (toolCall.name == AgentTools.EXECUTE_COMMAND) {
            detectAndRegisterArtifacts(description, raw)
        }

        // 5. Observation for the model.
        val untrusted = toolCall.name in UNTRUSTED_OUTPUT_TOOLS
        fun guard(text: String): String =
            if (text.isEmpty()) "" else if (untrusted) "[UNTRUSTED_OUTPUT_START]\n${compactToolOutput(text)}\n[UNTRUSTED_OUTPUT_END]" else compactToolOutput(text)

        var stderr = raw.stderr
        if (failed && state.consecutiveErrors >= REFLECTION_THRESHOLD) {
            stderr = guard(stderr) + "\n\n[AMC] ${state.consecutiveErrors} consecutive failures. Step back: read the errors, " +
                "find the root cause, and try a clearly different approach instead of small variations."
        } else {
            stderr = guard(stderr)
        }
        val result = raw.copy(
            toolCallId = securedToolCall.id,
            toolName = toolCall.name,
            command = description,
            stdout = guard(raw.stdout),
            stderr = stderr
        )
        val status = if (failed) MessageStatus.ERROR else MessageStatus.COMPLETED
        updateMessageToolResult(assistantMsgId, result, status)
        val observation = if (raw.stdout.isNotEmpty()) raw.stdout else raw.stderr
        val toolMsg = ChatMessage(
            role = MessageRole.TOOL,
            text = if (untrusted) "[UNTRUSTED_OUTPUT_START]\n${compactToolOutput(observation)}\n[UNTRUSTED_OUTPUT_END]" else compactToolOutput(observation),
            toolResult = result,
            status = status
        )
        _messages.value = _messages.value + toolMsg
        chatRepository?.saveMessage(sessionId, toolMsg)
    }

    private fun platformNotifyApprovalNeeded(description: String) {
        // Only useful when the user left the app while the agent works.
        val p = platform ?: return
        if (!headless && !p.isAppInForeground()) {
            try {
                p.notifyUser("AMC needs your approval", description.take(200))
            } catch (e: Exception) {
                Log.w(TAG, "Approval notification failed: ${e.message}")
            }
        }
    }

    /** Runs a tool and returns the raw (unguarded) result. */
    private suspend fun dispatchTool(
        call: ToolCall,
        assistantMsgId: String,
        pendingVision: MutableList<ChatMessage>
    ): ToolResult {
        val a = call.arguments
        val description = AgentTools.describe(call)
        fun ok(text: String) = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stdout = text)
        fun fail(text: String) = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stderr = text, exitCode = 1, isError = true)

        return when (call.name) {
            AgentTools.EXECUTE_COMMAND -> {
                val cmd = a["command"]?.trim().orEmpty()
                val timeoutMs = a["timeout_seconds"]?.trim()?.toLongOrNull()
                    ?.let { (it * 1000).coerceIn(5_000L, MAX_COMMAND_TIMEOUT_MS) }
                    ?: DEFAULT_COMMAND_TIMEOUT_MS
                val result = bridgeClient.executeCommand(cmd, timeoutMs) { chunk ->
                    updateStreamingOutput(assistantMsgId, chunk)
                }
                if (cmd.contains("termux-camera-photo") && result.exitCode == 0) {
                    captureVisionObservation(cmd, pendingVision)
                }
                result.copy(toolName = call.name)
            }
            AgentTools.READ_FILE -> {
                val path = a["path"].orEmpty()
                val text = bridgeClient.readFile(path, timeoutMs = 20_000L)
                ok(text.ifEmpty { "(empty file)" })
            }
            AgentTools.WRITE_FILE -> {
                val path = a["path"].orEmpty()
                val content = a["content"].orEmpty()
                val append = a["mode"].equals("append", ignoreCase = true)
                val finalContent = if (append) {
                    val existing = try { bridgeClient.readFile(path, timeoutMs = 20_000L) } catch (e: Exception) { "" }
                    existing + content
                } else content
                val written = bridgeClient.writeFile(path, finalContent, timeoutMs = 20_000L)
                if (written) {
                    registerArtifactForPath(path, finalContent)
                    ok("${if (append) "Appended" else "Wrote"} ${content.length} characters to $path.")
                } else fail("Could not write $path.")
            }
            AgentTools.WEB_FETCH -> {
                val res = webFetcher.fetch(a["url"].orEmpty())
                if (res.ok) ok(res.text) else fail(res.text.ifEmpty { "HTTP ${res.statusCode}" })
            }
            AgentTools.REMEMBER -> {
                val store = memoryStore ?: return fail("Long-term memory is not available.")
                ok(store.remember(a["note"].orEmpty()))
            }
            AgentTools.FORGET -> {
                val store = memoryStore ?: return fail("Long-term memory is not available.")
                val removed = store.forget(a["text"].orEmpty())
                ok(if (removed == 0) "No matching memory entries." else "Removed $removed memory entr${if (removed == 1) "y" else "ies"}.")
            }
            AgentTools.UPDATE_PLAN -> {
                val steps = parsePlan(a["plan"].orEmpty())
                _plan.value = steps
                val done = steps.count { it.status == PlanStepStatus.DONE }
                ok("Plan updated: $done/${steps.size} steps done.")
            }
            AgentTools.NOTIFY_USER -> {
                val p = platform ?: return fail("Notifications are not available.")
                p.notifyUser(a["title"].orEmpty().ifBlank { "AMC" }, a["message"].orEmpty())
                ok("Notification shown.")
            }
            AgentTools.SCHEDULE_TASK -> {
                val p = platform ?: return fail("Scheduling is not available.")
                val delay = a["delay_minutes"]?.trim()?.toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0L
                val repeat = a["repeat_every_minutes"]?.trim()?.toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0L
                val id = p.scheduleAgentTask(
                    title = a["title"].orEmpty().ifBlank { "Scheduled task" },
                    prompt = a["prompt"].orEmpty(),
                    delayMinutes = delay,
                    repeatEveryMinutes = if (repeat in 1 until 15) 15 else repeat
                )
                val repeatText = if (repeat > 0) ", repeating every ${maxOf(repeat, 15)} minutes" else ""
                ok("Scheduled (id: $id). First run in about $delay minutes$repeatText. Android may delay background runs slightly.")
            }
            AgentTools.LIST_SCHEDULED_TASKS -> {
                val p = platform ?: return fail("Scheduling is not available.")
                ok(p.listAgentTasks().ifBlank { "No scheduled tasks." })
            }
            AgentTools.CANCEL_SCHEDULED_TASK -> {
                val p = platform ?: return fail("Scheduling is not available.")
                val id = a["task_id"].orEmpty().trim()
                if (p.cancelAgentTask(id)) ok("Cancelled task $id.") else fail("No scheduled task with id '$id'.")
            }
            else -> fail("Unknown tool '${call.name}'.")
        }
    }

    private suspend fun captureVisionObservation(cmd: String, pendingVision: MutableList<ChatMessage>) {
        val photoPath = extractPhotoPathFromCommand(cmd) ?: return
        try {
            val base64Img = bridgeClient.readFileBase64(photoPath)
            if (base64Img.isNotEmpty()) {
                pendingVision += ChatMessage(
                    role = MessageRole.USER,
                    text = "📸 Photo captured in Termux (`$photoPath`):",
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
            Log.w(TAG, "Termux vision: could not read photo: ${e.message}")
        }
    }

    private fun appendToolMessage(
        sessionId: String,
        call: ToolCall,
        toolName: String,
        description: String,
        stdout: String,
        stderr: String,
        exitCode: Int,
        isError: Boolean
    ): ToolResult {
        val result = ToolResult(
            toolCallId = call.id,
            command = description,
            toolName = toolName,
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            isError = isError
        )
        val msg = ChatMessage(
            role = MessageRole.TOOL,
            text = stderr.ifEmpty { stdout },
            toolResult = result,
            status = if (isError) MessageStatus.ERROR else MessageStatus.COMPLETED
        )
        _messages.value = _messages.value + msg
        persistAsync { chatRepository?.saveMessage(sessionId, msg) }
        return result
    }

    private suspend fun recordAudit(
        sessionId: String,
        description: String,
        riskLevel: String,
        riskReason: String,
        result: ToolResult,
        durationMs: Long,
        approved: Boolean
    ) {
        try {
            chatRepository?.recordAudit(
                CommandAuditEntity(
                    sessionId = sessionId,
                    command = description,
                    riskLevel = riskLevel,
                    riskReason = riskReason,
                    executionDurationMs = durationMs,
                    exitCode = result.exitCode,
                    stdout = result.stdout.take(500),
                    stderr = result.stderr.take(500),
                    timestamp = System.currentTimeMillis(),
                    wasApproved = approved
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Audit write failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Context window
    // -------------------------------------------------------------------------------------------

    /**
     * Selects the messages sent to the model. Guarantees, for every provider:
     * - the window starts with a USER message;
     * - every assistant tool call is followed by exactly one matching TOOL result (missing ones,
     *   e.g. after the app was killed mid-command, are synthesised) and no TOOL result is orphaned;
     * - only the latest few images are replayed;
     * - the total size stays within [maxCharBudget], dropping the oldest exchanges first.
     */
    internal fun prepareContextMessages(
        allMessages: List<ChatMessage>,
        maxMessages: Int = 40,
        maxCharBudget: Int = 120_000
    ): List<ChatMessage> {
        val nonSystem = repairToolPairs(allMessages.filter { it.role != MessageRole.SYSTEM })
        if (nonSystem.isEmpty()) return emptyList()

        var window = nonSystem.takeLast(maxMessages).toMutableList()
        trimToUserStart(window)
        if (window.isEmpty()) {
            // The latest user request is older than the window (long autonomous run): keep it and
            // the most recent progress after it.
            val latestUserIdx = nonSystem.indexOfLast { it.role == MessageRole.USER }
            if (latestUserIdx < 0) return emptyList()
            val tail = nonSystem.subList(latestUserIdx + 1, nonSystem.size).takeLast(maxMessages - 1).toMutableList()
            dropLeadingOrphans(tail)
            window = (listOf(nonSystem[latestUserIdx]) + tail).toMutableList()
        }

        fun weight(m: ChatMessage): Int =
            m.text.length + (m.toolResult?.stdout?.length ?: 0) + (m.toolResult?.stderr?.length ?: 0) +
                m.toolCalls.sumOf { tc -> tc.arguments.values.sumOf { it.length } } +
                (if (m.imageBase64 != null) IMAGE_CHAR_WEIGHT else 0)

        while (window.size > 2 && window.sumOf { weight(it) } > maxCharBudget) {
            val nextUser = (1 until window.size).firstOrNull { window[it].role == MessageRole.USER && window[it].toolResult == null }
            if (nextUser != null && nextUser < window.size - 1) {
                // Drop the whole oldest exchange.
                repeat(nextUser) { window.removeAt(0) }
            } else {
                // Only one user turn: drop the oldest step after it.
                window.removeAt(1)
                val rest = window.subList(1, window.size)
                dropLeadingOrphans(rest)
            }
        }

        // Keep images only on the most recent messages.
        val imageIndices = window.indices.filter { window[it].imageBase64 != null }
        val stripImages = imageIndices.dropLast(MAX_IMAGES_IN_CONTEXT).toSet()
        return window.mapIndexed { i, m ->
            if (i in stripImages) m.copy(imageBase64 = null, imageMimeType = null, text = (m.text + "\n[earlier image omitted]").trim()) else m
        }
    }

    private fun trimToUserStart(window: MutableList<ChatMessage>) {
        while (window.isNotEmpty() && window.first().role != MessageRole.USER) window.removeAt(0)
    }

    /** Removes TOOL results at the start of [list] whose assistant tool call is gone. */
    private fun dropLeadingOrphans(list: MutableList<ChatMessage>) {
        while (list.isNotEmpty() && list.first().role == MessageRole.TOOL) list.removeAt(0)
    }

    /** Makes tool call/result pairing consistent (see [prepareContextMessages]). */
    private fun repairToolPairs(messages: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                MessageRole.ASSISTANT -> {
                    out += m
                    val calls = m.toolCalls.ifEmpty { listOfNotNull(m.toolCall) }
                    if (calls.isNotEmpty()) {
                        val ids = calls.map { it.id }.toSet()
                        val answered = mutableSetOf<String>()
                        var j = i + 1
                        while (j < messages.size && messages[j].role == MessageRole.TOOL) {
                            val t = messages[j]
                            val id = t.toolResult?.toolCallId
                            if (id != null && id in ids && answered.add(id)) out += t
                            j++
                        }
                        for (call in calls) {
                            if (call.id !in answered) {
                                out += ChatMessage(
                                    role = MessageRole.TOOL,
                                    text = "Result unavailable: execution was interrupted.",
                                    toolResult = ToolResult(
                                        toolCallId = call.id,
                                        command = AgentTools.describe(call),
                                        toolName = call.name,
                                        stderr = "Result unavailable: execution was interrupted.",
                                        exitCode = -1,
                                        isError = true
                                    ),
                                    status = MessageStatus.ERROR,
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        i = j
                        continue
                    }
                }
                MessageRole.TOOL -> {
                    // A TOOL message not directly after its assistant turn is an orphan: skip it.
                }
                else -> out += m
            }
            i++
        }
        return out
    }

    internal fun compactToolOutput(
        text: String,
        maxChars: Int = 6000,
        headChars: Int = 3000,
        tailChars: Int = 2500
    ): String {
        if (text.length <= maxChars) return text
        val omitted = text.length - (headChars + tailChars)
        if (omitted <= 0) return text.take(maxChars)
        return "${text.take(headChars)}\n\n... [Output truncated: $omitted characters omitted to conserve context] ...\n\n${text.takeLast(tailChars)}"
    }

    internal fun isRunawayOrLoopDetected(
        cmd: String,
        history: List<String>,
        consecutiveErrors: Int,
        maxConsecutiveErrors: Int = MAX_CONSECUTIVE_ERRORS
    ): Boolean {
        if (consecutiveErrors >= maxConsecutiveErrors) return true
        // Same action three times in a row.
        if (history.size >= 2 && history.takeLast(2).all { it.equals(cmd, ignoreCase = true) }) return true
        // Ping-pong: A -> B -> A -> B
        if (history.size >= 3) {
            val last = history.last()
            val secondLast = history[history.size - 2]
            val thirdLast = history[history.size - 3]
            if (cmd.equals(secondLast, ignoreCase = true) && last.equals(thirdLast, ignoreCase = true) && !cmd.equals(last, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    internal fun parsePlan(text: String): List<PlanStep> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val m = Regex("""^(?:[-*]\s*)?\[([ xX>~\-/])]\s*(.*)$""").find(line)
            if (m != null) {
                val status = when (m.groupValues[1]) {
                    "x", "X" -> PlanStepStatus.DONE
                    ">", "~", "-", "/" -> PlanStepStatus.IN_PROGRESS
                    else -> PlanStepStatus.PENDING
                }
                PlanStep(m.groupValues[2].trim(), status)
            } else {
                PlanStep(line.removePrefix("-").removePrefix("*").trim(), PlanStepStatus.PENDING)
            }
        }.filter { it.text.isNotEmpty() }.take(30)

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

    // -------------------------------------------------------------------------------------------
    // Artifacts
    // -------------------------------------------------------------------------------------------

    private fun artifactTypeFor(path: String): ArtifactType? {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> ArtifactType.IMAGE
            lower.endsWith(".md") -> ArtifactType.MARKDOWN
            lower.endsWith(".html") -> ArtifactType.HTML
            listOf(".py", ".sh", ".js", ".json", ".kt").any { lower.endsWith(it) } -> ArtifactType.CODE
            lower.endsWith(".txt") || lower.endsWith(".csv") -> ArtifactType.TEXT
            else -> null
        }
    }

    private fun registerArtifactForPath(path: String, content: String?) {
        val type = artifactTypeFor(path) ?: return
        appendArtifact(
            ArtifactItem(
                filename = path.substringAfterLast('/'),
                path = path,
                type = type,
                content = if (type == ArtifactType.IMAGE) null else content
            )
        )
    }

    private fun detectAndRegisterArtifacts(command: String, result: ToolResult) {
        if (result.isError) return
        val tokenMatcher = java.util.regex.Pattern.compile(""""([^"]*)"|'([^']*)'|(\S+)""").matcher(command)
        while (tokenMatcher.find()) {
            val token = tokenMatcher.group(1) ?: tokenMatcher.group(2) ?: tokenMatcher.group(3) ?: continue
            val clean = token.trim('"', '\'', '>', '<', ';', '&', ' ')
            if (clean.contains('\n') || clean.length < 3) continue
            val type = artifactTypeFor(clean) ?: continue
            if (clean.substringAfterLast('/').substringBeforeLast('.').isEmpty()) continue
            appendArtifact(ArtifactItem(filename = clean.substringAfterLast('/'), path = clean, type = type, content = null))
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
        _artifacts.value = if (existingIndex >= 0) {
            _artifacts.value.mapIndexed { idx, existing -> if (idx == existingIndex) item else existing }
        } else {
            _artifacts.value + item
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
            scope.launch { chatRepository?.addMetrics(targetId, promptTokens, completionTokens, costUsd) }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Approvals & stopping
    // -------------------------------------------------------------------------------------------

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
        stopJob = scope.launch { stopExecution(silent = false) }
    }

    private fun recoverAbortedToolCalls(executionSessionId: String, reason: String) {
        val currentMessages = _messages.value
        val lastAssistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        val toolCalls = lastAssistant.toolCalls.ifEmpty { listOfNotNull(lastAssistant.toolCall) }
        if (toolCalls.isEmpty()) return

        for (toolCall in toolCalls) {
            val alreadyAnswered = currentMessages.any { it.role == MessageRole.TOOL && it.toolResult?.toolCallId == toolCall.id }
            if (!alreadyAnswered) {
                val abortedMsg = ChatMessage(
                    role = MessageRole.TOOL,
                    text = reason,
                    toolResult = ToolResult(
                        toolCallId = toolCall.id,
                        command = AgentTools.describe(toolCall),
                        toolName = toolCall.name,
                        stderr = reason,
                        isError = true,
                        exitCode = -1
                    ),
                    status = MessageStatus.ERROR
                )
                _messages.value = _messages.value + abortedMsg
                persistAsync { chatRepository?.saveMessage(executionSessionId, abortedMsg) }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Message state helpers
    // -------------------------------------------------------------------------------------------

    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == messageId) transform(it) else it }
    }

    private fun updateMessageToolCalls(messageId: String, toolCalls: List<ToolCall>, status: MessageStatus) =
        updateMessage(messageId) { it.copy(toolCalls = toolCalls, toolCall = toolCalls.firstOrNull(), status = status) }

    private fun updateMessageText(messageId: String, text: String, status: MessageStatus) =
        updateMessage(messageId) { it.copy(text = text, status = status) }

    private fun updateMessageToolResult(messageId: String, toolResult: ToolResult, status: MessageStatus) =
        updateMessage(messageId) { it.copy(toolResult = toolResult, status = status) }

    private fun updateStreamingOutput(messageId: String, newChunk: String) =
        updateMessage(messageId) { it.copy(streamingTerminalOutput = (it.streamingTerminalOutput + newChunk).takeLast(50_000)) }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) =
        updateMessage(messageId) { it.copy(status = status) }

    /** Fire-and-forget DB write that survives cancellation and never crashes the app. */
    private fun persistAsync(block: suspend () -> Unit) {
        val job = scope.launch(NonCancellable) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Persisting message failed: ${e.message}")
            }
        }
        pendingWrites += job
        job.invokeOnCompletion { pendingWrites.remove(job) }
    }

    /** Waits until every queued DB write has finished. */
    suspend fun awaitPendingWrites() {
        val jobs = synchronized(pendingWrites) { pendingWrites.toList() }
        jobs.joinAll()
    }

    private fun appendSystemMessage(text: String, sessionId: String? = null) {
        val sysMsg = ChatMessage(role = MessageRole.SYSTEM, text = text, status = MessageStatus.COMPLETED)
        _messages.value = _messages.value + sysMsg
        val targetId = sessionId ?: _currentSession.value?.id
        if (targetId != null) {
            persistAsync { chatRepository?.saveMessage(targetId, sysMsg) }
        }
    }
}
