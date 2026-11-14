package com.agent.mobile.agent

import android.util.Log
import com.agent.mobile.data.model.*
import com.agent.mobile.data.network.LlmClient
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.network.WebFetcher
import com.agent.mobile.data.network.WebSearcher
import com.agent.mobile.data.repository.ChatRepository
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import com.agent.mobile.phone.PhoneToolPolicy
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
 * One instance drives the interactive chat. Headless instances (each with their own bridge
 * connection) run scheduled tasks and the heartbeat, conversations from messaging channels such
 * as Telegram, and delegated sub-agents, see [runHeadless].
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
    private val headless: Boolean = false,
    private val webSearcher: WebSearcher = WebSearcher(),
    /** The user's persona and standing instructions (SOUL.md), injected into every system prompt. */
    val personaStore: TextFileStore? = null,
    /** 0 for a normal agent, 1 for a sub-agent. Sub-agents cannot delegate further. */
    private val subAgentDepth: Int = 0,
    /** Phone control (screen, notifications, intents, Shizuku). Null: those tools are not offered. */
    private val phone: PhoneController? = null
) {
    companion object {
        private const val TAG = "AgentEngine"

        /** Upper bound for unattended runs so a background job always finishes in time. */
        const val UNATTENDED_MAX_STEPS = 25
        const val UNATTENDED_TIMEOUT_MS = 9 * 60_000L

        /** Channel conversations may wait for approvals from the phone owner, so they get more time. */
        const val REMOTE_TIMEOUT_MS = 45 * 60_000L

        const val SUBAGENT_MAX_STEPS = 20
        const val SUBAGENT_TIMEOUT_MS = 15 * 60_000L
        const val MAX_SUBAGENT_DEPTH = 1

        private const val SKILLS_CACHE_MS = 10 * 60_000L
        private const val MAX_SEND_FILE_BYTES = 5 * 1024 * 1024

        /** Skills index shared by all engines (interactive, background, channel). */
        @Volatile
        private var skillsCache: Pair<Long, String>? = null

        fun invalidateSkillsCache() {
            skillsCache = null
        }

        /** Consecutive failed tool calls after which the agent is told to rethink its approach. */
        private const val REFLECTION_THRESHOLD = 3
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** Attempts per provider (first try + retries) for errors that may go away (429, 5xx, network). */
        const val MAX_ATTEMPTS_PER_PROVIDER = 3
        private const val MAX_RETRY_DELAY_MS = 30_000L
        /** A provider that asks to wait longer than this is not waited for (fallback or stop instead). */
        private const val MAX_RETRY_AFTER_MS = 60_000L

        /**
         * Wait before retry [attempt] (1-based): exponential with jitter, at least what the provider
         * asked for. Null when the provider wants us to wait too long.
         */
        internal fun retryDelayMs(attempt: Int, retryAfterMs: Long, baseMs: Long, random: java.util.Random = java.util.Random()): Long? {
            if (retryAfterMs > MAX_RETRY_AFTER_MS) return null
            val exp = (baseMs shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_DELAY_MS)
            val jittered = (exp * (0.75 + random.nextDouble() * 0.5)).toLong()
            return maxOf(jittered, retryAfterMs)
        }

        /** Interactive approvals that nobody answers are rejected after this (configurable in Settings). */
        const val DEFAULT_APPROVAL_TIMEOUT_MS = 10 * 60_000L

        /** Exit code of a tool failure that is not the agent's fault (display off, screen busy). */
        const val TRANSIENT_EXIT_CODE = 75

        private const val DEFAULT_COMMAND_TIMEOUT_MS = 90_000L
        private const val MAX_COMMAND_TIMEOUT_MS = 30 * 60_000L

        /** Only the most recent images are replayed to the model; older ones cost tokens for little value. */
        private const val MAX_IMAGES_IN_CONTEXT = 3
        private const val IMAGE_CHAR_WEIGHT = 4_000

        private val UNTRUSTED_OUTPUT_TOOLS = setOf(
            AgentTools.EXECUTE_COMMAND, AgentTools.READ_FILE, AgentTools.WEB_FETCH,
            AgentTools.WEB_SEARCH, AgentTools.SEARCH_HISTORY, AgentTools.DELEGATE_TASK
        ) + PhoneTools.UNTRUSTED_OUTPUT
        /** Start of a screen reading in a tool result ("Screen: App (pkg) · …"). */
        private val SCREEN_HEADER = Regex("(^|\\n)Screen: ")

        /** Tools that need the Termux bridge. */
        private val BRIDGE_TOOLS = setOf(AgentTools.EXECUTE_COMMAND, AgentTools.READ_FILE, AgentTools.WRITE_FILE, AgentTools.SEND_FILE)
        private const val BRIDGE_RECOVERY_TIMEOUT_MS = 25_000L

        private val LOOP_EXEMPT_TOOLS = setOf(AgentTools.UPDATE_PLAN, AgentTools.REMEMBER, AgentTools.LIST_SCHEDULED_TASKS) +
            PhoneTools.LOOP_EXEMPT
    }

    data class UnattendedResult(
        val sessionId: String?,
        val summary: String,
        val success: Boolean,
        val skippedApprovals: List<String>,
        /** Set when the run parked an action for the user's approval and stopped (see [ApprovalQueue]). */
        val parkedApprovalId: String? = null,
        /** True when the run ended because its step or time budget ran out, not because it finished. */
        val budgetExhausted: Boolean = false,
        /** True when the run stopped because the AI provider stayed unreachable (worth a later retry). */
        val providerFailed: Boolean = false
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

    /**
     * Decides approvals in headless REMOTE and SUBAGENT runs (Telegram buttons, or the parent run).
     * When null, actions that need approval are refused there.
     */
    @Volatile
    var approvalHandler: (suspend (ToolCall, SecurityAssessment) -> Boolean)? = null

    /**
     * Parks actions that need approval in UNATTENDED runs, so the user can answer later and the
     * task resumes. When null, such actions are skipped and reported (the old behaviour).
     */
    @Volatile
    var approvalQueue: ApprovalQueue? = null

    /**
     * Starts or reconnects the Termux bridge (through RUN_COMMAND). Called when a bridge tool is
     * about to run while the bridge is down, so a run heals itself instead of failing every command.
     */
    @Volatile
    var bridgeRecovery: (suspend () -> Boolean)? = null

    /** First retry delay after a provider error (doubles per retry). Lowered in tests. */
    @Volatile
    internal var retryBaseDelayMs: Long = 2_000L

    /** Receives files from `send_file` in channel conversations. Null: files are shown in the app. */
    @Volatile
    var attachmentSink: AttachmentSink? = null

    /** Called with a one-line description whenever a tool starts (progress for sub-agents and channels). */
    @Volatile
    var activityListener: ((String) -> Unit)? = null

    /** Name of the messaging channel used in REMOTE runs (shown to the model). */
    @Volatile
    var channelName: String = "Telegram"

    private val baseExcludedTools: Set<String> =
        if (subAgentDepth >= MAX_SUBAGENT_DEPTH) setOf(AgentTools.DELEGATE_TASK) else emptySet()

    /** Tools hidden from the model in the current run (base exclusions plus phone control rules for the mode). */
    @Volatile
    private var excludedTools: Set<String> = baseExcludedTools + PhoneTools.ALL_NAMES

    /** Job of the running headless loop, cancelled by the phone control Stop button. */
    @Volatile
    private var headlessLoopJob: Job? = null

    @Volatile
    private var stoppedByUser = false

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

    /** Re-reads model, security preset and step budget from preferences (used by long-lived headless engines). */
    fun reloadSettings() {
        val prefs = preferenceManager ?: return
        _modelConfig.value = prefs.loadModelConfig()
        _securityPreset.value = prefs.loadSecurityPreset()
        _maxSteps.value = prefs.loadMaxSteps()
        reloadSecurityRules()
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
                runAgentLoop(executionSessionId, RunMode.INTERACTIVE)
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
    suspend fun runUnattended(
        title: String,
        prompt: String,
        onSessionReady: (suspend (String) -> Unit)? = null
    ): UnattendedResult =
        runHeadless(title = title, prompt = prompt, mode = RunMode.UNATTENDED, onSessionReady = onSessionReady)

    /**
     * Continues the session of an earlier background run (after an approval was answered, after
     * its budget ran out, or after AMC was restarted). An approved call runs exactly once first,
     * and only while it still matches the approved fingerprint and is not blocked.
     */
    suspend fun resumeUnattended(
        title: String,
        resume: ResumeRequest,
        onSessionReady: (suspend (String) -> Unit)? = null
    ): UnattendedResult =
        runHeadless(title = title, prompt = resume.note, mode = RunMode.UNATTENDED, resume = resume, onSessionReady = onSessionReady)

    /**
     * Queues a message from a channel user while a REMOTE run is busy ("steering"); it is picked
     * up before the next step. Returns false when the engine is idle.
     */
    fun queueSteering(text: String): Boolean {
        if (!_isBusy.value || text.isBlank()) return false
        steeringQueue.add(ChatMessage(role = MessageRole.USER, text = text.trim()))
        _queuedInputs.value = steeringQueue.size
        return true
    }

    /**
     * Runs one turn without the app UI. Must be called on a headless engine instance.
     * - [RunMode.UNATTENDED]: scheduled tasks and heartbeat, always in a fresh session; actions
     *   that need approval are refused.
     * - [RunMode.REMOTE]: a message from a messaging channel; continues [sessionId] when it
     *   exists, approvals go through [approvalHandler].
     * - [RunMode.SUBAGENT]: a delegated sub-task, in memory; approvals go to the parent.
     * The summary is the agent's final reply of this turn.
     */
    suspend fun runHeadless(
        title: String,
        prompt: String,
        mode: RunMode,
        sessionId: String? = null,
        imageBase64: String? = null,
        imageMimeType: String? = null,
        timeoutMs: Long = when (mode) {
            RunMode.REMOTE -> REMOTE_TIMEOUT_MS
            RunMode.SUBAGENT -> SUBAGENT_TIMEOUT_MS
            else -> UNATTENDED_TIMEOUT_MS
        },
        resume: ResumeRequest? = null,
        onSessionReady: (suspend (String) -> Unit)? = null
    ): UnattendedResult = withContext(Dispatchers.Main) {
        check(headless) { "runHeadless requires a headless engine" }
        require(mode != RunMode.INTERACTIVE) { "Interactive runs use startTask" }
        require(resume == null || mode == RunMode.UNATTENDED) { "Only background runs are resumed" }
        val skipped = mutableListOf<String>()

        val continueId = resume?.sessionId ?: sessionId.takeIf { mode == RunMode.REMOTE }
        val existing = continueId?.let { chatRepository?.getSessionById(it) }
        if (existing != null) {
            sessionCollectJob?.cancel()
            _currentSession.value = existing
            _messages.value = chatRepository?.getMessagesForSessionSync(existing.id).orEmpty()
        } else if (resume != null) {
            return@withContext UnattendedResult(null, "The chat of this task no longer exists, so it cannot be continued.", false, skipped)
        } else {
            createNewSessionInternal(title)
        }
        val activeSessionId = _currentSession.value?.id
            ?: return@withContext UnattendedResult(null, "Could not create a session.", false, skipped)
        try {
            onSessionReady?.invoke(activeSessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Session callback failed: ${e.message}")
        }

        val state = LoopState().apply {
            runTitle = title
            chain = resume?.chain ?: 0
            resume?.grantKey?.let { grants += it }
        }
        val turnText = if (resume != null) runApprovedCall(activeSessionId, resume, state, skipped) else prompt.trim()

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = turnText,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )
        val turnStart = _messages.value.size
        _messages.value = _messages.value + userMessage
        _isBusy.value = true
        _plan.value = emptyList()
        _currentStep.value = 0
        var success = true
        stoppedByUser = false
        try {
            chatRepository?.saveMessage(activeSessionId, userMessage)
            val finished = withTimeoutOrNull(timeoutMs) {
                // A child job, so the phone control Stop button can end the run without cancelling the caller.
                coroutineScope {
                    val loop = launch { runAgentLoop(activeSessionId, mode, skipped, state) }
                    headlessLoopJob = loop
                    loop.join()
                }
                true
            }
            headlessLoopJob = null
            if (stoppedByUser) {
                success = false
                recoverAbortedToolCalls(activeSessionId, "Stopped by the user.")
                appendSystemMessage("🛑 Stopped by the user (Stop button of phone control).", activeSessionId)
            } else if (finished == null) {
                success = false
                state.budgetExhausted = true
                bridgeClient.interruptCurrent()
                recoverAbortedToolCalls(activeSessionId, "Time limit reached.")
                appendSystemMessage("⏱️ Time limit reached. The task was stopped.", activeSessionId)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                recoverAbortedToolCalls(activeSessionId, "Execution canceled.")
                appendSystemMessage("🛑 Execution canceled.", activeSessionId)
                drainSteeringQueue(activeSessionId)
                awaitPendingWrites()
                _isBusy.value = false
            }
            throw e
        } catch (e: Exception) {
            success = false
            Log.e(TAG, "Headless run failed: ${e.message}", e)
            appendSystemMessage("⚠️ Run failed: ${e.localizedMessage}", activeSessionId)
        } finally {
            withContext(NonCancellable) {
                drainSteeringQueue(activeSessionId)
                awaitPendingWrites()
            }
            _isBusy.value = false
        }

        val turn = _messages.value.drop(turnStart + 1)
        val finalReply = turn.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.text.isNotBlank() && it.status != MessageStatus.ERROR &&
                it.toolCalls.isEmpty() && it.toolCall == null
        }
        val lastSystem = turn.lastOrNull { it.role == MessageRole.SYSTEM }
        val lastThought = turn.lastOrNull { it.role == MessageRole.ASSISTANT && it.text.isNotBlank() }
        if (finalReply == null) success = false
        val summary = finalReply?.text?.trim()
            ?: listOfNotNull(lastThought?.text?.trim(), lastSystem?.text).joinToString("\n\n").ifBlank { null }
            ?: "The task finished without a summary."
        UnattendedResult(
            activeSessionId, summary, success, skipped,
            parkedApprovalId = state.parkedId,
            budgetExhausted = state.budgetExhausted && state.parkedId == null && !stoppedByUser,
            providerFailed = state.providerFailed && !stoppedByUser
        )
    }

    /**
     * Builds the first user turn of a resumed run. An approved call runs here, once, before the
     * model continues; its result becomes part of the turn. The provider never sees a tool call
     * it did not make, so this works for every provider.
     */
    private suspend fun runApprovedCall(
        sessionId: String,
        resume: ResumeRequest,
        state: LoopState,
        skipped: MutableList<String>
    ): String {
        val call = resume.approvedCall ?: return resume.note
        if (PhoneTools.isPhoneTool(call.name)) {
            return resume.note + " AMC restored that approval for this run. The screen may have changed: read it " +
                "first, then do the action again."
        }
        val description = AgentTools.describe(call)
        if (resume.approvedFingerprint == null || ApprovalFingerprint.of(call) != resume.approvedFingerprint) {
            appendSystemMessage("🛡️ The approved action no longer matches what was stored and was not run: `${description.take(160)}`", sessionId)
            return resume.note + " AMC did NOT run it: the stored action does not match what was approved."
        }
        val assessment = AgentTools.assess(call)
        excludedTools = excludedFor(RunMode.UNATTENDED)
        if (assessment.isBlocked || !AgentTools.isKnown(call.name) || call.name in excludedTools) {
            val why = if (assessment.isBlocked) assessment.reason else "the tool is not available in this run"
            val result = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stderr = why, exitCode = -1, isError = true)
            recordAudit(sessionId, description, assessment.level.name, why, result, 0, approved = false)
            appendSystemMessage("🛡️ Approved action not run: `${description.take(160)}` ($why)", sessionId)
            return resume.note + " AMC did NOT run it: $why."
        }
        val start = System.currentTimeMillis()
        val raw = try {
            dispatchTool(
                call.copy(riskLevel = assessment.level.name, riskReason = assessment.reason),
                "", mutableListOf(), RunMode.UNATTENDED, skipped, state
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(toolCallId = call.id, command = description, stderr = e.localizedMessage ?: e.toString(), exitCode = -1, isError = true)
        }
        recordAudit(sessionId, description, assessment.level.name, "Approved by the user (parked action)", raw, System.currentTimeMillis() - start, approved = true)
        val failed = raw.isError || raw.exitCode != 0
        appendSystemMessage(
            (if (failed) "⚠️ Approved action failed: " else "✅ Approved action done: ") + "`${description.take(160)}`",
            sessionId
        )
        val output = compactToolOutput(if (raw.stdout.isNotEmpty()) raw.stdout else raw.stderr).ifBlank { "(no output)" }
        val guarded = if (call.name in UNTRUSTED_OUTPUT_TOOLS) "[UNTRUSTED_OUTPUT_START]\n$output\n[UNTRUSTED_OUTPUT_END]" else output
        return "${resume.note} AMC ran it now (exit code ${raw.exitCode}${if (failed) ", failed" else ""}). Result:\n$guarded\n\nContinue the task."
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
        /** Identifies this run towards the phone controller (screen lease, element ids per turn). */
        val runId: String = UUID.randomUUID().toString()
        val history = mutableListOf<String>()
        var consecutiveErrors = 0
        var loopWarnings = 0
        var stopRequested = false
        /** Per-run approvals that cover later calls with the same key (e.g. reading one messaging app). */
        val grants = mutableSetOf<String>()
        /** Overlay + notification while the agent operates the phone. */
        var control: AutoCloseable? = null
        /** Grant approved for the current call only (REMOTE runs, where grants are not kept). */
        var callGrant: String? = null
        /** Title of the run (shown with parked approvals). */
        var runTitle: String = ""
        /** Runs of this task before this one (resumes and continuations); limits parking. */
        var chain: Int = 0
        /** Queue id of the action this run parked for the user's approval. */
        var parkedId: String? = null
        /** The step or time budget ran out before the task finished. */
        var budgetExhausted: Boolean = false
        /** The run stopped because no provider answered (after retries and the fallback). */
        var providerFailed: Boolean = false
    }

    /** Tools the model may not use in [mode]. */
    private fun excludedFor(mode: RunMode): Set<String> {
        val p = phone ?: return baseExcludedTools + PhoneTools.ALL_NAMES + localProfileExcluded()
        val phoneExcluded = try {
            p.excludedTools(mode)
        } catch (e: Exception) {
            Log.w(TAG, "Phone control unavailable: ${e.message}")
            PhoneTools.ALL_NAMES
        }
        // Sub-agents research; they never drive the screen (their approvals would bypass the REMOTE rules).
        val subAgentExcluded = if (mode == RunMode.SUBAGENT) PhoneTools.SCREEN_TOOLS else emptySet()
        // Screenshots only for models that can look at images.
        val visionExcluded = if (com.agent.mobile.phone.ScreenshotSupport.likelySupportsVision(_modelConfig.value)) emptySet()
        else setOf(PhoneTools.SCREEN_CAPTURE)
        return baseExcludedTools + phoneExcluded + subAgentExcluded + visionExcluded + localProfileExcluded()
    }

    /** On-device models get the compact tool profile (prefill cost, reliability of small models). */
    private fun localProfileExcluded(): Set<String> =
        if (_modelConfig.value.provider == ProviderType.LOCAL) {
            AgentTools.ALL.map { it.name }.filter { it !in AgentTools.LOCAL_PROFILE }.toSet()
        } else emptySet()

    /** Stop button of the phone control overlay or notification. */
    private fun onPhoneControlStop() {
        if (headless) {
            stoppedByUser = true
            bridgeClient.interruptCurrent()
            headlessLoopJob?.cancel()
        } else {
            emergencyStop()
        }
    }

    private suspend fun runAgentLoop(
        executionSessionId: String,
        mode: RunMode,
        skippedApprovals: MutableList<String> = mutableListOf(),
        state: LoopState = LoopState()
    ) {
        val stepLimit = when (mode) {
            RunMode.UNATTENDED -> minOf(_maxSteps.value, UNATTENDED_MAX_STEPS)
            RunMode.SUBAGENT -> minOf(_maxSteps.value, SUBAGENT_MAX_STEPS)
            else -> _maxSteps.value
        }
        val steerable = mode == RunMode.INTERACTIVE || mode == RunMode.REMOTE
        // Built once per run so the prompt prefix stays identical between steps (prompt caching).
        val systemPrompt = AgentPrompts.build(
            memory = memoryStore?.asPromptText().orEmpty(),
            mode = mode,
            maxSteps = stepLimit,
            persona = withContext(Dispatchers.IO) { personaStore?.read().orEmpty() },
            skills = loadSkillsIndex(),
            channel = channelName
        )
        excludedTools = excludedFor(mode)
        try {
            runLoopIterations(executionSessionId, systemPrompt, mode, state, skippedApprovals, stepLimit, steerable)
        } finally {
            state.control?.let { handle ->
                try {
                    handle.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not end phone control: ${e.message}")
                }
            }
            state.control = null
            try {
                phone?.endRun(state.runId)
            } catch (e: Exception) {
                Log.w(TAG, "Could not end the phone run: ${e.message}")
            }
        }
    }

    private suspend fun runLoopIterations(
        executionSessionId: String,
        systemPrompt: String,
        mode: RunMode,
        state: LoopState,
        skippedApprovals: MutableList<String>,
        stepLimit: Int,
        steerable: Boolean
    ) {
        var iterations = 0
        while (currentCoroutineContext().isActive) {
            if (steerable) drainSteeringQueue(executionSessionId)
            if (iterations >= stepLimit) {
                state.budgetExhausted = true
                appendSystemMessage(
                    if (mode == RunMode.UNATTENDED) {
                        "ℹ️ Step budget of this background run used up ($stepLimit steps)."
                    } else {
                        "ℹ️ Step limit of $stepLimit reached. Send \"continue\" to let the agent keep working " +
                            "or raise the limit in Settings → Autonomy."
                    },
                    executionSessionId
                )
                break
            }
            iterations++
            _currentStep.value = iterations
            if (iterations > 1) excludedTools = excludedFor(mode)

            val outcome = runSingleStep(executionSessionId, systemPrompt, mode, state, skippedApprovals)
            if (outcome == StepOutcome.STOP || state.stopRequested) break
            if (outcome == StepOutcome.FINISHED) {
                // The model answered without tools. Continue only if the user queued new input.
                if (steerable && steeringQueue.isNotEmpty()) continue
                break
            }
        }
    }

    /** Lists installed skills through the bridge (cached for a few minutes; empty when offline). */
    private suspend fun loadSkillsIndex(): String {
        val cached = skillsCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < SKILLS_CACHE_MS) return cached.second
        if (bridgeClient.connectionStatus.value !is ConnectionStatus.Connected) return cached?.second.orEmpty()
        return try {
            val res = withTimeoutOrNull(6_000L) { bridgeClient.executeCommand(SkillsIndex.LIST_COMMAND, 5_000L) }
                ?: return cached?.second.orEmpty()
            if (res.isError && res.stdout.isBlank()) return cached?.second.orEmpty()
            val text = SkillsIndex.format(SkillsIndex.parse(res.stdout))
            skillsCache = now to text
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not list skills: ${e.message}")
            cached?.second.orEmpty()
        }
    }

    private enum class StepOutcome { CONTINUE, FINISHED, STOP }

    private suspend fun runSingleStep(
        executionSessionId: String,
        systemPrompt: String,
        mode: RunMode,
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

        var attemptOnProvider = 0
        while (!streamCompleted && currentCoroutineContext().isActive) {
            attemptOnProvider++
            try {
                val contextMessages = prepareContextMessages(_messages.value.filter { it.id != assistantMsgId })
                llmClient.streamRequest(
                    config = activeConfig,
                    systemPrompt = systemPrompt,
                    messages = contextMessages,
                    excludedTools = excludedTools
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

            if (!streamCompleted && errorEvent != null && errorEvent!!.isRetryable) {
                // 1. Retry the same provider with backoff (429, 5xx, network), honouring Retry-After.
                val wait = if (attemptOnProvider < MAX_ATTEMPTS_PER_PROVIDER) {
                    retryDelayMs(attemptOnProvider, errorEvent!!.retryAfterMs, retryBaseDelayMs)
                } else null
                if (wait != null) {
                    appendSystemMessage(
                        "⏳ ${activeConfig.provider.displayName} did not answer (${errorEvent!!.message.take(120)}). " +
                            "Retrying in ${(wait + 999) / 1000} s…",
                        executionSessionId
                    )
                    delay(wait)
                    textBuilder.clear()
                    updateMessageText(assistantMsgId, "", MessageStatus.STREAMING)
                    errorEvent = null
                    continue
                }
                // 2. Then the fallback provider (once per step; the next step tries the primary again).
                val fallback = primaryConfig.fallbackProvider
                if (fallback != null && !isUsingFallback) {
                    isUsingFallback = true
                    attemptOnProvider = 0
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
            if (errorEvent?.isRetryable == true) state.providerFailed = true
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
        // Element ids in this turn's calls refer to the screen the model saw before the turn.
        if (tools.any { PhoneTools.isPhoneTool(it.name) }) {
            try {
                phone?.beginTurn(state.runId)
            } catch (e: Exception) {
                Log.w(TAG, "Phone turn start failed: ${e.message}")
            }
        }
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
                mode, state, skippedApprovals, pendingVisionObservations
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
        mode: RunMode,
        state: LoopState,
        skippedApprovals: MutableList<String>,
        pendingVision: MutableList<ChatMessage>
    ) {
        if (!AgentTools.isKnown(toolCall.name) || toolCall.name in excludedTools) {
            val safeCall = if (PhoneTools.isPhoneTool(toolCall.name)) PhoneTools.redacted(toolCall) else toolCall
            tools[index] = safeCall
            updateMessageToolCalls(assistantMsgId, tools.toList(), MessageStatus.EXECUTING_TOOL)
            val hint = if (PhoneTools.isPhoneTool(toolCall.name)) {
                "Phone control tool '${toolCall.name}' is not available in this run (switched off for this mode, or the " +
                    "feature is not set up). Tell the user if it is needed. Available tools: "
            } else "Unknown tool '${toolCall.name}'. Available tools: "
            appendToolMessage(
                sessionId, safeCall, toolCall.name, AgentTools.describe(safeCall, redact = true), "",
                hint + AgentTools.ALL.filter { it.name !in excludedTools }.joinToString { it.name } + ".",
                -1, isError = true
            )
            state.consecutiveErrors++
            return
        }

        // Risk first: it decides whether the call carries a secret that must not be shown or stored.
        val phoneContext = if (PhoneTools.isPhoneTool(toolCall.name)) {
            try {
                phone?.riskContext(toolCall, state.runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Phone risk context failed: ${e.message}")
                null
            }
        } else null
        val assessment = AgentTools.assess(toolCall, phoneContext)
        val description = AgentTools.describe(toolCall, redact = assessment.redact)
        // What is shown, stored and sent back to the model; the real arguments are only used to execute.
        val displayCall = if (assessment.redact) PhoneTools.redacted(toolCall) else toolCall

        // 1. Loop protection: warn once, then stop.
        if (toolCall.name !in LOOP_EXEMPT_TOOLS &&
            isRunawayOrLoopDetected(description, state.history, state.consecutiveErrors)
        ) {
            releasePhoneCall(toolCall)
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

        // 2. Risk assessment (computed above).
        val securedToolCall = displayCall.copy(riskLevel = assessment.level.name, riskReason = assessment.reason)
        val executableCall = toolCall.copy(riskLevel = assessment.level.name, riskReason = assessment.reason)
        tools[index] = securedToolCall
        updateMessageToolCalls(assistantMsgId, tools.toList(), MessageStatus.EXECUTING_TOOL)

        if (assessment.isBlocked) {
            releasePhoneCall(toolCall)
            appendSystemMessage("🛡️ Security block: `$description` was blocked.\nReason: ${assessment.reason}", sessionId)
            val blocked = appendToolMessage(sessionId, securedToolCall, toolCall.name, description, "", "Security block active: ${assessment.reason}", -1, isError = true)
            updateMessageToolResult(assistantMsgId, blocked, MessageStatus.ERROR)
            recordAudit(sessionId, description, RiskLevel.BLOCKED.name, assessment.reason, blocked, 0, approved = false)
            state.consecutiveErrors++
            return
        }

        // 3. Approval. In REMOTE runs every screen action asks, and per-run grants do not apply there.
        val remoteForced = PhoneToolPolicy.requiresApprovalInMode(toolCall.name, mode)
        val granted = !remoteForced && assessment.grantKey != null && assessment.grantKey in state.grants
        if (!granted && (remoteForced || CommandSecurityFilter.shouldRequireApproval(assessment, _securityPreset.value))) {
            // Background run: park the action for the user instead of skipping it.
            if (mode == RunMode.UNATTENDED &&
                tryParkApproval(sessionId, assistantMsgId, toolCall, securedToolCall, assessment, description, state)
            ) return
            val skippedBefore = skippedApprovals.size
            val approved = requestApproval(
                assistantMsgId, securedToolCall, assessment, description, mode, skippedApprovals,
                onScreen = state.control != null && PhoneTools.isPhoneTool(toolCall.name)
            )
            if (approved) {
                assessment.grantKey?.let { if (remoteForced) state.callGrant = it else state.grants += it }
                if (PhoneTools.isPhoneTool(toolCall.name)) phone?.noteApproved(toolCall.id)
            }
            if (!approved) {
                releasePhoneCall(toolCall)
                val notAsked = skippedApprovals.size > skippedBefore
                val reason = if (notAsked) {
                    "Not executed: this action requires the user's approval, and the user cannot be asked during this " +
                        "run. Skip it and mention it in your summary."
                } else {
                    "Execution rejected by the user (or not answered in time). Do not retry this action; choose another " +
                        "approach or ask the user."
                }
                val rejected = appendToolMessage(sessionId, securedToolCall, toolCall.name, description, "", reason, -1, isError = true)
                updateMessageToolResult(assistantMsgId, rejected, MessageStatus.ERROR)
                recordAudit(sessionId, description, assessment.level.name, if (notAsked) "Needs approval (user not present)" else "Rejected by the user", rejected, 0, approved = false)
                return
            }
        }

        // 4. Execute.
        updateMessageStatus(assistantMsgId, MessageStatus.EXECUTING_TOOL)
        try {
            activityListener?.invoke(description.take(200))
        } catch (e: Exception) {
            Log.w(TAG, "Activity listener failed: ${e.message}")
        }
        val start = System.currentTimeMillis()
        val raw = try {
            dispatchTool(executableCall, assistantMsgId, pendingVision, mode, skippedApprovals, state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(toolCallId = securedToolCall.id, command = description, stderr = e.localizedMessage ?: e.toString(), exitCode = -1, isError = true)
        }
        val duration = System.currentTimeMillis() - start
        val failed = raw.isError || raw.exitCode != 0
        val transient = failed && raw.exitCode == TRANSIENT_EXIT_CODE && PhoneTools.isPhoneTool(toolCall.name)
        when {
            // Display off or screen busy: waiting is right, repeating it is not a loop.
            transient -> state.history.removeLastOrNull()
            failed -> state.consecutiveErrors++
            else -> state.consecutiveErrors = 0
        }

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

    /**
     * Asks for approval of [call] in the way the current [mode] allows:
     * in the chat UI (interactive), through [approvalHandler] (channel conversations and
     * sub-agents) or not at all (background runs, recorded in [skipped]).
     */
    private suspend fun requestApproval(
        assistantMsgId: String,
        call: ToolCall,
        assessment: SecurityAssessment,
        description: String,
        mode: RunMode,
        skipped: MutableList<String>,
        onScreen: Boolean = false
    ): Boolean = when (mode) {
        RunMode.UNATTENDED -> {
            skipped += description
            false
        }
        RunMode.REMOTE, RunMode.SUBAGENT -> {
            val handler = approvalHandler
            if (handler == null) {
                skipped += description
                false
            } else {
                updateMessageStatus(assistantMsgId, MessageStatus.WAITING_FOR_APPROVAL)
                try {
                    handler(call, assessment)
                } finally {
                    updateMessageStatus(assistantMsgId, MessageStatus.EXECUTING_TOOL)
                }
            }
        }
        RunMode.INTERACTIVE -> requestInteractiveApproval(assistantMsgId, call, assessment, description, onScreen)
    }

    /**
     * Interactive approval. The chat card is always shown; while the agent operates the phone the
     * same question also appears as a panel directly above the app in front ([onScreen]), so the
     * user does not have to switch to AMC. The notification offers Approve / Reject as well. The
     * first answer wins; no answer within the timeout counts as a rejection.
     */
    private suspend fun requestInteractiveApproval(
        assistantMsgId: String,
        call: ToolCall,
        assessment: SecurityAssessment,
        description: String,
        onScreen: Boolean
    ): Boolean = coroutineScope {
        updateMessageStatus(assistantMsgId, MessageStatus.WAITING_FOR_APPROVAL)
        _pendingApproval.value = Pair(assistantMsgId, call)
        val deferred = CompletableDeferred<Boolean>()
        approvalContinuation = deferred
        ApprovalActions.register(call.id) { approved -> deferred.complete(approved) }
        platformNotifyApprovalNeeded(call.id, description)
        val panel = if (onScreen && phone != null) launch {
            val answer = try {
                phone.requestOnScreenApproval(
                    title = "AMC: ${description.take(120)}",
                    detail = assessment.reason
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "On-screen approval failed: ${e.message}")
                null
            }
            if (answer != null) deferred.complete(answer)
        } else null
        val timeoutMs = preferenceManager?.loadApprovalTimeoutMs() ?: DEFAULT_APPROVAL_TIMEOUT_MS
        try {
            val answer = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (answer == null) {
                appendSystemMessage(
                    "⏱️ No answer to the approval request within ${timeoutMs / 60_000} minutes: `${description.take(120)}` was not executed.",
                    _currentSession.value?.id
                )
            }
            answer ?: false
        } finally {
            panel?.cancel()
            ApprovalActions.unregister(call.id)
            try {
                platform?.cancelApprovalNotification(call.id)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel the approval notification: ${e.message}")
            }
            _pendingApproval.value = null
            approvalContinuation = null
            updateMessageStatus(assistantMsgId, MessageStatus.EXECUTING_TOOL)
        }
    }

    /**
     * Parks [toolCall] in the [approvalQueue] and ends this run; a new run continues after the
     * user's answer. Never parks calls that carry a secret, phone actions that cannot be granted
     * per app (sending, paying: the screen will have changed), or when the task already used its
     * run budget. Returns false when the call was not parked (it is then skipped as before).
     */
    private suspend fun tryParkApproval(
        sessionId: String,
        assistantMsgId: String,
        toolCall: ToolCall,
        securedToolCall: ToolCall,
        assessment: SecurityAssessment,
        description: String,
        state: LoopState
    ): Boolean {
        val queue = approvalQueue ?: return false
        if (assessment.redact || state.chain >= ResumeRequest.MAX_CHAIN - 1) return false
        if (PhoneTools.isPhoneTool(toolCall.name) && assessment.grantKey == null) return false
        val id = try {
            queue.park(
                ParkedApproval(
                    sessionId = sessionId,
                    runTitle = state.runTitle,
                    call = toolCall,
                    description = description,
                    riskLevel = assessment.level.name,
                    riskReason = assessment.reason,
                    fingerprint = ApprovalFingerprint.of(toolCall),
                    grantKey = assessment.grantKey,
                    chain = state.chain
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not park the approval: ${e.message}")
            null
        } ?: return false
        releasePhoneCall(toolCall)
        val paused = appendToolMessage(
            sessionId, securedToolCall, toolCall.name, description, "",
            "Paused: this action waits for the user's approval. The task continues automatically in a new run " +
                "after the user answers. Do nothing else now.",
            -1, isError = true
        )
        updateMessageToolResult(assistantMsgId, paused, MessageStatus.WAITING_FOR_APPROVAL)
        recordAudit(sessionId, description, assessment.level.name, "Waiting for the user's approval (parked)", paused, 0, approved = false)
        appendSystemMessage("⏸️ Waiting for your approval: `${description.take(160)}`. The task continues after you answer.", sessionId)
        state.parkedId = id
        state.stopRequested = true
        return true
    }

    private fun releasePhoneCall(call: ToolCall) {
        if (!PhoneTools.isPhoneTool(call.name)) return
        try {
            phone?.releaseCall(call.id)
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the phone call: ${e.message}")
        }
    }

    private fun platformNotifyApprovalNeeded(callId: String, description: String) {
        // Only useful when the user left the app while the agent works.
        val p = platform ?: return
        if (!headless && !p.isAppInForeground()) {
            try {
                p.notifyApprovalNeeded(callId, description.take(200))
            } catch (e: Exception) {
                Log.w(TAG, "Approval notification failed: ${e.message}")
            }
        }
    }

    /** Runs a tool and returns the raw (unguarded) result. */
    private suspend fun dispatchTool(
        call: ToolCall,
        assistantMsgId: String,
        pendingVision: MutableList<ChatMessage>,
        mode: RunMode,
        skippedApprovals: MutableList<String>,
        state: LoopState
    ): ToolResult {
        val a = call.arguments
        val description = AgentTools.describe(call)
        fun ok(text: String) = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stdout = text)
        fun fail(text: String) = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stderr = text, exitCode = 1, isError = true)

        if (PhoneTools.isPhoneTool(call.name)) return runPhoneTool(call, mode, state, pendingVision)
        if (call.name in BRIDGE_TOOLS) ensureBridge()

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
                // Appending happens on the bridge: a failed read can no longer overwrite the file.
                val written = bridgeClient.writeFile(path, content, timeoutMs = 20_000L, append = append)
                if (written) {
                    if (path.contains(".amc/skills")) invalidateSkillsCache()
                    // After an append only the bridge knows the whole file; the viewer loads it on demand.
                    registerArtifactForPath(path, if (append) null else content)
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
            AgentTools.WEB_SEARCH -> {
                val query = a["query"].orEmpty().trim()
                val max = a["max_results"]?.trim()?.toDoubleOrNull()?.toInt() ?: 8
                val res = webSearcher.search(query, max)
                if (res.ok) ok(WebSearcher.format(query, res.hits)) else fail(res.error)
            }
            AgentTools.SEARCH_HISTORY -> {
                val repo = chatRepository ?: return fail("Conversation history is not available in this run.")
                val query = a["query"].orEmpty().trim()
                val max = a["max_results"]?.trim()?.toDoubleOrNull()?.toInt() ?: 8
                val hits = repo.searchHistory(query, max.coerceIn(1, 20), excludeSessionId = _currentSession.value?.id)
                ok(formatHistoryHits(query, hits))
            }
            AgentTools.DELEGATE_TASK -> runSubAgent(call, assistantMsgId, mode, skippedApprovals)
            AgentTools.SEND_FILE -> sendFile(call)
            else -> fail("Unknown tool '${call.name}'.")
        }
    }

    /**
     * Runs a phone control tool. The first screen tool of a run shows the "AMC is in control"
     * overlay and the ongoing notification; both stay until the run ends or the user taps Stop.
     */
    private suspend fun runPhoneTool(
        call: ToolCall,
        mode: RunMode,
        state: LoopState,
        pendingVision: MutableList<ChatMessage>
    ): ToolResult {
        val description = AgentTools.describe(call, redact = call.name == PhoneTools.SCREEN_TYPE)
        fun result(text: String, ok: Boolean) = ToolResult(
            toolCallId = call.id, command = description, toolName = call.name,
            stdout = if (ok) text else "", stderr = if (ok) "" else text,
            exitCode = if (ok) 0 else 1, isError = !ok
        )
        val p = phone ?: return result("Phone control is not available in this run.", false)
        if (call.name in PhoneTools.SCREEN_TOOLS && state.control == null) {
            state.control = try {
                p.beginControl { onPhoneControlStop() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the phone control overlay: ${e.message}")
                null
            }
        }
        val grants = state.grants.toSet() + listOfNotNull(state.callGrant)
        state.callGrant = null
        // The controller moves node access to its own phone thread; nothing blocks the main thread.
        val r = p.execute(call, mode, grants, state.runId)
        val image = r.imageBase64
        if (image != null) {
            pendingVision += ChatMessage(
                role = MessageRole.USER,
                text = "📱 Screenshot of the phone screen (screen_capture):",
                imageBase64 = image,
                imageMimeType = r.imageMimeType,
                status = MessageStatus.COMPLETED
            )
            if (r.sendImageToUser) {
                deliverImageToUser("screenshot.jpg", image, "Screenshot")
            }
        }
        if (!r.ok && r.transient) {
            return ToolResult(
                toolCallId = call.id, command = description, toolName = call.name,
                stderr = r.text, exitCode = TRANSIENT_EXIT_CODE, isError = true
            )
        }
        return result(r.text, r.ok)
    }

    /** Brings the bridge back before a bridge tool runs, when it dropped during the run. */
    private suspend fun ensureBridge() {
        if (bridgeClient.connectionStatus.value is ConnectionStatus.Connected) return
        val recover = bridgeRecovery ?: return
        val ok = try {
            withTimeoutOrNull(BRIDGE_RECOVERY_TIMEOUT_MS) { recover() } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bridge recovery failed: ${e.message}")
            false
        }
        Log.i(TAG, if (ok) "Termux bridge recovered during the run" else "Termux bridge still unreachable")
    }

    /** Sends an image to the channel chat, or shows it in the app's artifact panel. */
    private suspend fun deliverImageToUser(fileName: String, base64: String, caption: String) {
        val sink = attachmentSink
        if (sink != null) {
            try {
                val bytes = java.util.Base64.getMimeDecoder().decode(base64)
                sink.sendFile(fileName, bytes, caption)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not deliver image: ${e.message}")
            }
        } else {
            appendArtifact(ArtifactItem(filename = fileName, path = fileName, type = ArtifactType.IMAGE, base64Data = base64))
        }
    }

    private fun formatHistoryHits(query: String, hits: List<ChatRepository.HistoryHit>): String {
        if (hits.isEmpty()) return "No earlier conversation mentions \"$query\"."
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return buildString {
            append("Matches for \"").append(query).append("\" in earlier conversations (newest first):\n")
            hits.forEach { h ->
                val who = if (h.role == MessageRole.USER) "user" else "you"
                val text = h.text.replace(Regex("\\s+"), " ").trim()
                val idx = text.indexOf(query, ignoreCase = true)
                val snippet = if (text.length <= 400) text else {
                    val from = (idx - 150).coerceAtLeast(0)
                    (if (from > 0) "…" else "") + text.substring(from, minOf(text.length, from + 400)) + "…"
                }
                append("\n- [").append(fmt.format(java.util.Date(h.timestamp))).append("] \"")
                append(h.sessionTitle).append("\" ").append(who).append(": ").append(snippet)
            }
        }
    }

    /**
     * delegate_task: runs a sub-agent with a fresh context on the same bridge connection. The
     * parent waits, so the connection is never used by both at once. Approvals of the sub-agent
     * are forwarded to this run; its progress is streamed into the parent's tool card.
     */
    private suspend fun runSubAgent(
        call: ToolCall,
        assistantMsgId: String,
        parentMode: RunMode,
        parentSkipped: MutableList<String>
    ): ToolResult {
        val description = AgentTools.describe(call)
        fun result(text: String, error: Boolean) = ToolResult(
            toolCallId = call.id, command = description, toolName = call.name,
            stdout = if (error) "" else text, stderr = if (error) text else "",
            exitCode = if (error) 1 else 0, isError = error
        )
        if (subAgentDepth >= MAX_SUBAGENT_DEPTH) {
            return result("Sub-agents cannot delegate further. Do this part yourself.", true)
        }
        val task = call.arguments["task"].orEmpty().trim()
        val context = call.arguments["context"].orEmpty().trim()
        val prompt = buildString {
            append(task)
            if (context.isNotEmpty()) append("\n\nContext from the main agent:\n").append(context)
        }

        val child = AutonomousAgentEngine(
            bridgeClient = bridgeClient,
            llmClient = llmClient,
            memoryStore = memoryStore,
            platform = platform,
            webFetcher = webFetcher,
            headless = true,
            webSearcher = webSearcher,
            personaStore = personaStore,
            subAgentDepth = subAgentDepth + 1,
            phone = phone
        )
        child.setModelConfig(_modelConfig.value)
        child.setSecurityPreset(_securityPreset.value)
        child.setMaxSteps(minOf(_maxSteps.value, SUBAGENT_MAX_STEPS))
        child.channelName = channelName
        child.attachmentSink = attachmentSink
        child.approvalHandler = { childCall, assessment ->
            val childDescription = "[sub-agent] " + AgentTools.describe(childCall)
            requestApproval(assistantMsgId, childCall, assessment, childDescription, parentMode, parentSkipped)
        }
        child.activityListener = { line ->
            updateStreamingOutput(assistantMsgId, "▸ $line\n")
            try {
                activityListener?.invoke("sub-agent: $line")
            } catch (e: Exception) {
                Log.w(TAG, "Activity listener failed: ${e.message}")
            }
        }
        updateStreamingOutput(assistantMsgId, "🤖 Sub-agent started: ${task.take(120)}\n")
        return try {
            val res = child.runHeadless(title = "Sub-agent", prompt = prompt, mode = RunMode.SUBAGENT)
            val m = child.metrics.value
            recordMetrics(m.promptTokens, m.completionTokens, m.estimatedCostUsd)
            val text = buildString {
                append("Sub-agent report (").append(child.currentStep.value).append(" steps")
                if (!res.success) append(", incomplete")
                append("):\n\n").append(res.summary)
            }
            updateStreamingOutput(assistantMsgId, "✅ Sub-agent finished\n")
            result(text, error = !res.success)
        } finally {
            child.close()
        }
    }

    /** send_file: uploads to the channel chat, or shows the file in the app's artifact panel. */
    private suspend fun sendFile(call: ToolCall): ToolResult {
        val description = AgentTools.describe(call)
        fun ok(text: String) = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stdout = text)
        fun fail(text: String) = ToolResult(toolCallId = call.id, command = description, toolName = call.name, stderr = text, exitCode = 1, isError = true)
        val path = call.arguments["path"].orEmpty().trim()
        val caption = call.arguments["caption"].orEmpty().trim()
        val base64 = bridgeClient.readFileBase64(path, timeoutMs = 30_000L)
        if (base64.isEmpty()) return fail("The file is empty or could not be read: $path")
        val bytes = try {
            java.util.Base64.getMimeDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            return fail("Could not decode the file contents.")
        }
        if (bytes.size > MAX_SEND_FILE_BYTES) return fail("The file is larger than 5 MB. Compress or split it first.")
        val name = path.substringAfterLast('/').ifBlank { "file" }
        val sink = attachmentSink
        if (sink != null) return ok(sink.sendFile(name, bytes, caption))

        val type = artifactTypeFor(path)
        return when (type) {
            null -> ok("The app can preview images, text, code, Markdown and HTML only. Tell the user the file is at $path.")
            ArtifactType.IMAGE -> {
                appendArtifact(ArtifactItem(filename = name, path = path, type = type, base64Data = base64))
                ok("The image is shown to the user in the Artifacts panel.")
            }
            else -> {
                appendArtifact(ArtifactItem(filename = name, path = path, type = type, content = String(bytes, Charsets.UTF_8)))
                ok("The file is shown to the user in the Artifacts panel.")
            }
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

        maskSupersededScreens(window)

        // Keep images only on the most recent messages.
        val imageIndices = window.indices.filter { window[it].imageBase64 != null }
        val stripImages = imageIndices.dropLast(MAX_IMAGES_IN_CONTEXT).toSet()
        return window.mapIndexed { i, m ->
            if (i in stripImages) m.copy(imageBase64 = null, imageMimeType = null, text = (m.text + "\n[earlier image omitted]").trim()) else m
        }
    }

    /**
     * Screen readings are large and only the latest one matters: every full reading before the
     * newest full one (and the deltas based on those) is cut to its headline. The newest full
     * reading and the deltas after it stay, so the prompt prefix up to it stays stable for
     * prompt caching until the next full reading arrives.
     */
    internal fun maskSupersededScreens(window: MutableList<ChatMessage>) {
        fun isScreen(m: ChatMessage) = m.role == MessageRole.TOOL && m.toolResult?.toolName in PhoneTools.SCREEN_TOOLS
        fun isFull(m: ChatMessage) = isScreen(m) && SCREEN_HEADER.containsMatchIn(m.text) && !m.text.contains("Δ since your last reading")
        val lastFull = window.indexOfLast { isFull(it) }
        if (lastFull <= 0) return
        for (i in 0 until lastFull) {
            val m = window[i]
            if (!isScreen(m)) continue
            val untrusted = m.text.startsWith("[UNTRUSTED_OUTPUT_START]")
            val body = m.text.removePrefix("[UNTRUSTED_OUTPUT_START]").removeSuffix("[UNTRUSTED_OUTPUT_END]").trim()
            val match = SCREEN_HEADER.find(body) ?: continue
            val headline = body.substring(0, match.range.first).trim()
            val core = listOf(headline, "[Earlier screen reading omitted; a newer one follows.]").filter { it.isNotEmpty() }.joinToString("\n")
            val masked = if (untrusted) "[UNTRUSTED_OUTPUT_START]\n$core\n[UNTRUSTED_OUTPUT_END]" else core
            val result = m.toolResult
            window[i] = m.copy(
                text = masked,
                toolResult = result?.copy(stdout = if (result.stdout.isEmpty()) "" else masked)
            )
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
