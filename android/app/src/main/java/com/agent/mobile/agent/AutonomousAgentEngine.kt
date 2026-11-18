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
import com.agent.mobile.audit.AuditDecision
import com.agent.mobile.audit.AuditEntry
import com.agent.mobile.audit.AuditRedactor
import com.agent.mobile.unattended.ActionClassifier
import com.agent.mobile.unattended.InjectionGuard
import com.agent.mobile.unattended.SecurityFloor
import com.agent.mobile.unattended.UnattendedCategory
import com.agent.mobile.unattended.UnattendedPolicy
import com.agent.mobile.unattended.UnattendedSettings
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
    private val phone: PhoneController? = null,
    /** Audit log for engines without a chat repository (sub-agents write into the parent's log). */
    private val auditLogger: com.agent.mobile.audit.AuditLogger? = null
) {
    companion object {
        private const val TAG = "AgentEngine"

        /** Start of notes the app adds to the conversation as USER messages (not the user's own words). */
        const val AGENT_NOTE_PREFIX = "[AMC]"

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

        /** Reminders per unattended run to end with finish_task instead of plain text. */
        const val MAX_FINISH_NUDGES = 2

        /** Consecutive failed tool calls after which the agent is told to rethink its approach. */
        private const val REFLECTION_THRESHOLD = 3
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /**
         * Actions in a row whose exact result the agent already had earlier in the run (cycles such
         * as A → B → C → A → B → C that the repeat check does not see): hint first, then stop.
         */
        const val NO_PROGRESS_HINT = 4
        const val NO_PROGRESS_STOP = 8

        /** Attempts per provider (first try + retries) for errors that may go away (429, 5xx, network). */
        const val MAX_ATTEMPTS_PER_PROVIDER = 3
        /** How long a run stays on the fallback provider after the primary failed. */
        const val FALLBACK_STICKY_MS = 10 * 60_000L
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
        /** Shell commands whose output is downloaded (foreign) content. */
        private val GUARD_DOWNLOAD = Regex("""\b(curl|wget|lynx|w3m|aria2c|yt-dlp|git\s+clone)\b|https?://""", RegexOption.IGNORE_CASE)
        /** Texts of image messages the app adds as USER turns (screenshots, photos). */
        private val SYNTHETIC_IMAGE_PREFIXES = listOf("📱 Screenshot", "📸 Photo captured")

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
        val providerFailed: Boolean = false,
        /** Id of this run in the audit log and the run registry. */
        val runId: String = "",
        /** Set when an unattended limit (duration, phone actions, messages) ended the run. */
        val limitReached: String? = null,
        /** Actions the unattended rules refused, with the reason (for the run report). */
        val autoDenied: List<String> = emptyList(),
        /** True when the run used unattended mode (no approval was asked). */
        val unattended: Boolean = false,
        /** The user stopped the run (emergency brake). */
        val stoppedByUser: Boolean = false
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

    /**
     * Unattended settings to use instead of the stored ones (tests, and runs whose caller decided
     * already). Only applies while the preset is Turbo; null: read consent and settings from preferences.
     */
    @Volatile
    var unattendedOverride: UnattendedSettings? = null

    /** Sub-agents: the parent's run id (audit entries link to it) and its injection guard. */
    @Volatile
    internal var parentRunId: String? = null
    @Volatile
    internal var inheritedGuard: InjectionGuard? = null

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

    /** True while the current run is in unattended mode (offers finish_task and ask_user). */
    @Volatile
    private var unattendedRun = false

    /** The final reply finish_task produced in the current step (added after the tool results). */
    @Volatile
    private var pendingFinal: ChatMessage? = null

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

    /** [close] and wait (up to [timeoutMs]) until the engine's coroutines have finished; for tests. */
    internal fun closeAndAwait(timeoutMs: Long = 5_000L) {
        close()
        val job = scope.coroutineContext[Job] ?: return
        runBlocking { withTimeoutOrNull(timeoutMs) { job.join() } }
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

        val state = LoopState().apply { runTitle = userPrompt.trim().take(60); trigger = RunTrigger.USER }
        interactiveRunId = state.runId
        RunRegistry.register(
            RunRegistry.ActiveRun(state.runId, state.runTitle, RunMode.INTERACTIVE, RunTrigger.USER, executionSessionId, unattended = unattendedSettings() != null)
        ) { stopLocal() }
        activeJob = scope.launch {
            try {
                if (targetSession != null && (targetSession.title == "New chat" || targetSession.title.isBlank())) {
                    val generatedTitle = chatRepository?.generateConciseTitle(userPrompt) ?: "Chat"
                    _currentSession.value = targetSession.copy(title = generatedTitle)
                    chatRepository?.updateSessionTitle(executionSessionId, generatedTitle)
                }
                chatRepository?.saveMessage(executionSessionId, userMessage)
                runAgentLoop(executionSessionId, RunMode.INTERACTIVE, state = state)
                if (state.unattended != null) appendRunReport(executionSessionId, state, userPrompt)
            } catch (e: CancellationException) {
                recoverAbortedToolCalls(executionSessionId, "Execution canceled by the user.")
                appendSystemMessage("🛑 Execution canceled by the user.", executionSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error: ${e.message}", e)
                recoverAbortedToolCalls(executionSessionId, "Agent loop error: ${e.localizedMessage}")
                appendSystemMessage("⚠️ Agent loop error: ${e.localizedMessage}", executionSessionId)
            } finally {
                RunRegistry.unregister(state.runId)
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
        onSessionReady: (suspend (String) -> Unit)? = null,
        trigger: String = RunTrigger.SCHEDULE
    ): UnattendedResult =
        runHeadless(title = title, prompt = prompt, mode = RunMode.UNATTENDED, onSessionReady = onSessionReady, trigger = trigger)

    /**
     * Continues the session of an earlier background run (after an approval was answered, after
     * its budget ran out, or after AMC was restarted). An approved call runs exactly once first,
     * and only while it still matches the approved fingerprint and is not blocked.
     */
    suspend fun resumeUnattended(
        title: String,
        resume: ResumeRequest,
        onSessionReady: (suspend (String) -> Unit)? = null,
        trigger: String = RunTrigger.RESUME
    ): UnattendedResult =
        runHeadless(title = title, prompt = resume.note, mode = RunMode.UNATTENDED, resume = resume, onSessionReady = onSessionReady, trigger = trigger)

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
        onSessionReady: (suspend (String) -> Unit)? = null,
        /** What started the run (see [RunTrigger]); recorded in the audit log and the run report. */
        trigger: String = RunTrigger.defaultFor(mode)
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
            this.trigger = trigger
            this.mode = mode
            unattended = unattendedSettings()
            parentRunId?.let { runId = "$it/sub-${runId.take(8)}" }
        }
        RunRegistry.register(
            RunRegistry.ActiveRun(state.runId, title, mode, trigger, activeSessionId, unattended = state.unattended != null)
        ) { onPhoneControlStop(fromBrake = true) }
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
                // The cancelled loop already stopped its own command (by execution id); a bare
                // interrupt would also stop the user's commands on a shared connection (#33).
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
            RunRegistry.unregister(state.runId)
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
        if (state.limitReached != null) success = false
        if (state.finishStatus == "blocked") success = false
        val summary = finalReply?.text?.trim()
            ?: listOfNotNull(lastThought?.text?.trim(), lastSystem?.text).joinToString("\n\n").ifBlank { null }
            ?: "The task finished without a summary."
        UnattendedResult(
            activeSessionId, summary, success, skipped,
            parkedApprovalId = state.parkedId,
            budgetExhausted = state.budgetExhausted && state.parkedId == null && !stoppedByUser && state.limitReached == null,
            providerFailed = state.providerFailed && !stoppedByUser,
            runId = state.runId,
            limitReached = state.limitReached,
            autoDenied = state.autoDenied.toList(),
            unattended = state.unattended != null,
            stoppedByUser = stoppedByUser
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
            audit(state, sessionId, call.name, call, description, if (assessment.isBlocked) AuditDecision.BLOCKED else AuditDecision.NOT_RUN,
                if (assessment.isBlocked) "security:blocked" else "tool_unavailable", why, assessment.level.name, result, 0, assessment.redact)
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
        audit(state, sessionId, call.name, call, description, AuditDecision.APPROVED, "user:parked",
            "Approved by the user (parked action). ${assessment.reason}", assessment.level.name, raw, System.currentTimeMillis() - start, assessment.redact)
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
        /** Identifies this run towards the phone controller (screen lease, element ids per turn) and in the audit log. */
        var runId: String = UUID.randomUUID().toString()
        val history = mutableListOf<String>()
        /** Result hash per [history] entry (null until the action has run). */
        val resultHistory = mutableListOf<Int?>()
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
        /** Hashes of (action, result) pairs seen in this run, and how many repeats came in a row. */
        val seenResults = HashSet<Int>()
        var noProgress = 0
        /** A reply cut off at the output limit was already continued once in this run. */
        var continuedAfterCutOff = false
        /** Until then the steps of this run go to the fallback provider directly (the primary failed). */
        var fallbackUntil: Long = 0L
        /** What started the run (audit log, run report). */
        var trigger: String = RunTrigger.USER
        var mode: RunMode = RunMode.INTERACTIVE
        /** Non-null while the run is in unattended mode: decides instead of asking. */
        var unattended: UnattendedSettings? = null
        /** Prompt-injection guard of unattended runs (built when the loop starts). */
        var guard: InjectionGuard? = null
        val startedAt: Long = System.currentTimeMillis()
        var phoneActions = 0
        var messagesSent = 0
        /** Set when an unattended limit ended the run. */
        var limitReached: String? = null
        /** Actions the unattended rules refused ("what — why"), for the run report. */
        val autoDenied = mutableListOf<String>()
        /** Running summary of steps that no longer fit into the context (#4). */
        var contextSummary: ContextCompactor.Summary? = null
        /** finish_task was called: "done", "partial" or "blocked" (#5). */
        var finishStatus: String? = null
        /** Reminders to call finish_task after a text-only reply (at most two per run). */
        var finishNudges = 0
    }

    /** Unattended settings when unattended mode is on for this engine right now, else null. */
    private fun unattendedSettings(): UnattendedSettings? {
        if (_securityPreset.value != SecurityPreset.TURBO) return null
        unattendedOverride?.let { return it }
        val prefs = preferenceManager ?: return null
        return if (prefs.loadUnattendedConsentVersion() >= UnattendedPolicy.CONSENT_VERSION) prefs.loadUnattendedSettings() else null
    }

    /**
     * The injection guard of a run: trusted task text is the request the current work serves;
     * untrusted content already in the conversation (earlier slots, a trigger's notification)
     * counts as read.
     */
    private fun buildGuard(): InjectionGuard {
        val list = _messages.value.filter { it.role != MessageRole.SYSTEM }
        val root = taskRootIndex(list)
        val rootText = list.getOrNull(root)?.text.orEmpty()
        val trusted = rootText.substringBefore("[UNTRUSTED_OUTPUT_START]")
        val guard = InjectionGuard(trusted)
        if (rootText.contains("[UNTRUSTED_OUTPUT_START]")) guard.observeUntrusted("trigger", rootText.substringAfter("[UNTRUSTED_OUTPUT_START]"))
        for (m in list) {
            val result = m.toolResult ?: continue
            if (m.role == MessageRole.TOOL && isGuardSource(result.toolName, result.command)) guard.observeUntrusted(result.toolName, m.text)
        }
        return guard
    }

    /**
     * Results that count as foreign content for the injection guard: web pages and searches,
     * files, screens, notifications, the clipboard, sub-agent reports, and shell commands that
     * download something. Plain shell output of the user's own system does not.
     */
    private fun isGuardSource(toolName: String, description: String): Boolean = when (toolName) {
        AgentTools.EXECUTE_COMMAND -> GUARD_DOWNLOAD.containsMatchIn(description)
        AgentTools.SEARCH_HISTORY -> false
        else -> toolName in UNTRUSTED_OUTPUT_TOOLS
    }

    private fun fallbackConfigOf(primary: ModelConfig): ModelConfig {
        val fallback = primary.fallbackProvider ?: return primary
        return ModelConfig(
            provider = fallback,
            modelName = primary.fallbackModelName.ifEmpty { fallback.defaultModel },
            apiKey = primary.fallbackApiKey,
            baseUrl = primary.fallbackBaseUrl.ifEmpty { fallback.defaultBaseUrl }
        )
    }

    /** Tools the model may not use in [mode]. */
    private fun excludedFor(mode: RunMode): Set<String> {
        val modeOnly = if (unattendedRun) emptySet() else AgentTools.UNATTENDED_ONLY
        return excludedForPhone(mode) + modeOnly
    }

    private fun excludedForPhone(mode: RunMode): Set<String> {
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

    /**
     * Stop button of the phone control overlay or notification; also the emergency brake of
     * [RunRegistry] ([fromBrake]: another stop source already reaches every run).
     */
    private fun onPhoneControlStop(fromBrake: Boolean = false) {
        if (headless) {
            stoppedByUser = true
            headlessLoopJob?.cancel()
            if (!fromBrake) RunRegistry.stopAll(unattendedOnly = true)
        } else if (fromBrake) {
            stopLocal()
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
        state.mode = mode
        if (mode == RunMode.INTERACTIVE) state.unattended = unattendedSettings()
        if (state.unattended != null) state.guard = inheritedGuard?.fork() ?: buildGuard()
        unattendedRun = state.unattended != null
        state.contextSummary = ContextCompactor.latest(_messages.value)
        // Built once per run so the prompt prefix stays identical between steps (prompt caching).
        val systemPrompt = AgentPrompts.build(
            memory = memoryStore?.asPromptText().orEmpty(),
            mode = mode,
            maxSteps = stepLimit,
            persona = withContext(Dispatchers.IO) { personaStore?.read().orEmpty() },
            skills = loadSkillsIndex(),
            channel = channelName,
            unattended = state.unattended != null
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
            if (unattendedLimitHit(executionSessionId, state)) break

            val outcome = runSingleStep(executionSessionId, systemPrompt, mode, state, skippedApprovals)
            if (outcome == StepOutcome.STOP || state.stopRequested) break
            if (outcome == StepOutcome.FINISHED) {
                // The model answered without tools. Continue only if the user queued new input.
                if (steerable && steeringQueue.isNotEmpty()) continue
                break
            }
        }
    }

    /** Ends an unattended run cleanly when its duration limit is reached (checked before each step). */
    private suspend fun unattendedLimitHit(sessionId: String, state: LoopState): Boolean {
        val settings = state.unattended ?: return false
        val maxMin = settings.limits.maxDurationMinutes
        if (maxMin <= 0 || System.currentTimeMillis() - state.startedAt < maxMin * 60_000L) return false
        endForLimit(sessionId, state, "maximum duration of $maxMin minutes")
        return true
    }

    private suspend fun endForLimit(sessionId: String, state: LoopState, what: String) {
        state.limitReached = what
        state.stopRequested = true
        appendSystemMessage("⏹️ Unattended limit reached ($what). The run was ended; see the run report.", sessionId)
        audit(
            state, sessionId, "run_limit", null, "", AuditDecision.INFO, "limit", "Run ended: limit reached ($what).",
            riskLevel = "", result = null, durationMs = 0
        )
    }

    /** Unattended mode in the chat: the run ends with the same report background runs send. */
    private suspend fun appendRunReport(sessionId: String, state: LoopState, goal: String) {
        val logger = auditLogger ?: chatRepository?.auditLogger ?: return
        val events = try {
            logger.forRun(state.runId)
        } catch (e: Exception) {
            return
        }
        if (events.isEmpty()) return
        val lastReply = _messages.value.lastOrNull { isFinalAnswer(it) }?.text.orEmpty()
        val report = com.agent.mobile.audit.RunReport.build(
            state.runTitle, goal, lastReply.take(400), success = state.limitReached == null && lastReply.isNotBlank(),
            events = events, endReason = state.limitReached?.let { "limit: $it" }
        )
        appendSystemMessage("📋 Run report (unattended mode)\n" + report.toText(2_000), sessionId)
    }

    /**
     * Folds steps that fell out of the context window into the running summary (#4): once enough
     * new ones dropped, one model call without tools writes the summary (an extractive one when
     * that fails). The summary is stored in the session and appended to the pinned task.
     */
    private suspend fun maybeCompact(sessionId: String, state: LoopState, history: List<ChatMessage>) {
        val nonSystem = history.filter { it.role != MessageRole.SYSTEM }
        val root = taskRootIndex(nonSystem)
        if (root < 0) return
        val window = prepareContextMessages(history)
        val dropped = ContextCompactor.uncoveredDropped(nonSystem, root, window, state.contextSummary)
        if (dropped.size < ContextCompactor.MIN_NEW_DROPPED) return
        val previous = state.contextSummary
        val text = try {
            summarizeWithModel(ContextCompactor.transcript(previous, dropped))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Context summary failed: ${e.message}")
            null
        } ?: ContextCompactor.extractive(previous, dropped)
        val summary = ContextCompactor.Summary(text.take(ContextCompactor.MAX_SUMMARY_CHARS), dropped.last().id)
        state.contextSummary = summary
        appendSystemMessage(ContextCompactor.stored(summary), sessionId)
    }

    /** One model call without tools; null when it fails or returns nothing. */
    private suspend fun summarizeWithModel(transcript: String): String? = withTimeoutOrNull(90_000L) {
        var text: String? = null
        llmClient.streamRequest(
            config = _modelConfig.value,
            systemPrompt = ContextCompactor.SYSTEM_PROMPT,
            messages = listOf(ChatMessage(role = MessageRole.USER, text = transcript)),
            excludedTools = AgentTools.ALL.map { it.name }.toSet()
        ).collect { event ->
            when (event) {
                is LlmClient.LlmStreamEvent.Completed -> {
                    text = event.fullText
                    recordMetrics(event.promptTokens, event.completionTokens, event.estimatedCostUsd)
                }
                else -> Unit
            }
        }
        text?.trim()?.takeIf { it.isNotEmpty() }
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
        // The primary failed recently in this run: stay on the fallback instead of losing a minute
        // of retries on every step; afterwards the primary is tried again.
        var startedOnFallback = false
        if (primaryConfig.fallbackProvider != null && System.currentTimeMillis() < state.fallbackUntil) {
            activeConfig = fallbackConfigOf(primaryConfig)
            isUsingFallback = true
            startedOnFallback = true
        }

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
        var textCutOff = false
        var errorEvent: LlmClient.LlmStreamEvent.Error? = null
        val textBuilder = StringBuilder()

        var attemptOnProvider = 0
        while (!streamCompleted && currentCoroutineContext().isActive) {
            attemptOnProvider++
            try {
                val history = _messages.value.filter { it.id != assistantMsgId }
                if (attemptOnProvider == 1 && !isUsingFallback) maybeCompact(executionSessionId, state, history)
                val pinned = state.contextSummary?.let { ContextCompactor.pinnedBlock(it.text, _plan.value) }
                val contextMessages = prepareContextMessages(history, pinned = pinned)
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
                            textCutOff = event.truncated
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
                // 2. Then the fallback provider, kept for the next steps of this run for a while.
                val fallback = primaryConfig.fallbackProvider
                if (fallback != null && !isUsingFallback) {
                    isUsingFallback = true
                    attemptOnProvider = 0
                    activeConfig = fallbackConfigOf(primaryConfig)
                    val fallbackModel = activeConfig.modelName
                    state.fallbackUntil = System.currentTimeMillis() + FALLBACK_STICKY_MS
                    appendSystemMessage(
                        "⚠️ Primary provider ${primaryConfig.provider.displayName} failed (${errorEvent!!.message}). " +
                            "Switching to fallback: ${fallback.displayName} ($fallbackModel)...",
                        executionSessionId
                    )
                    textBuilder.clear()
                    errorEvent = null
                    continue
                }
                // 3. The step started on the kept fallback and that failed too: the primary may be back.
                if (startedOnFallback) {
                    startedOnFallback = false
                    isUsingFallback = false
                    state.fallbackUntil = 0L
                    attemptOnProvider = 0
                    activeConfig = primaryConfig
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
            if (state.unattended != null && state.finishStatus == null && state.finishNudges < MAX_FINISH_NUDGES) {
                // Unattended: a reply without tools is not the end yet (it may announce work or ask a
                // question nobody can answer). Ask once or twice for finish_task or the next action (#5).
                state.finishNudges++
                val nudge = ChatMessage(
                    role = MessageRole.USER,
                    text = "$AGENT_NOTE_PREFIX Unattended run: nobody reads replies until the run ends and nobody can answer " +
                        "questions. If work is left, continue with your tools now (decide open questions yourself and state your " +
                        "assumption). If the task is complete or cannot be completed, call finish_task with the summary and the status.",
                    status = MessageStatus.COMPLETED
                )
                _messages.value = _messages.value + nudge
                chatRepository?.saveMessage(executionSessionId, nudge)
                return StepOutcome.CONTINUE
            }
            if (textCutOff && !state.continuedAfterCutOff) {
                // The reply hit the output limit mid-way: let the model finish it once instead of ending here.
                state.continuedAfterCutOff = true
                val note = ChatMessage(
                    role = MessageRole.USER,
                    text = "$AGENT_NOTE_PREFIX Your previous reply was cut off at the output token limit. Continue exactly " +
                        "where it stopped; if you were about to act, call the tool now.",
                    status = MessageStatus.COMPLETED
                )
                _messages.value = _messages.value + note
                chatRepository?.saveMessage(executionSessionId, note)
                return StepOutcome.CONTINUE
            }
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
        pendingFinal?.let { final ->
            // finish_task: its summary closes the turn as a normal final reply.
            pendingFinal = null
            val msg = final.copy(timestamp = System.currentTimeMillis())
            _messages.value = _messages.value + msg
            chatRepository?.saveMessage(executionSessionId, msg)
        }

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
        val invalidArgs = toolCall.arguments[LlmClient.INVALID_ARGUMENTS_KEY]
        if (invalidArgs != null) {
            // Malformed or cut-off arguments: a fixable mistake of the model, not the end of the run.
            val safeCall = toolCall.copy(arguments = emptyMap(), rawJson = "")
            tools[index] = safeCall
            updateMessageToolCalls(assistantMsgId, tools.toList(), MessageStatus.EXECUTING_TOOL)
            appendToolMessage(sessionId, safeCall, toolCall.name, "${toolCall.name} (invalid arguments)", "", invalidArgs, -1, isError = true)
            audit(state, sessionId, toolCall.name, safeCall, "", AuditDecision.NOT_RUN, "invalid_arguments", invalidArgs.take(200), "", null, 0)
            state.consecutiveErrors++
            return
        }
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
            audit(state, sessionId, toolCall.name, safeCall, "", AuditDecision.NOT_RUN, "tool_unavailable", "Tool not available in this run.", "", null, 0, secret = true)
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
        var assessment = withScriptChecks(toolCall, AgentTools.assess(toolCall, phoneContext))
        // Unattended mode: the floor turns what would only ask into a block (nobody could answer).
        val unattended = state.unattended
        var blockRule = if (assessment.isBlocked) "security:blocked" else ""
        if (unattended != null) {
            SecurityFloor.check(toolCall, assessment)?.let { (floor, rule) ->
                assessment = floor
                blockRule = rule
            }
        }
        if (assessment.isBlocked && assessment.reason == CommandSecurityFilter.BRIDGE_SECRET_REASON) blockRule = "floor:bridge_token"
        val app = phoneContext?.targetPackage ?: phoneContext?.foregroundPackage
        val description = AgentTools.describe(toolCall, redact = assessment.redact)
        // What is shown, stored and sent back to the model; the real arguments are only used to execute.
        val displayCall = if (assessment.redact) PhoneTools.redacted(toolCall) else toolCall

        // 1. Loop protection: warn once, then stop.
        if (toolCall.name !in LOOP_EXEMPT_TOOLS &&
            isRunawayOrLoopDetected(description, state.history, state.consecutiveErrors, results = state.resultHistory)
        ) {
            releasePhoneCall(toolCall)
            if (state.loopWarnings == 0) {
                state.loopWarnings++
                // The warning is the rethink: the next, different action gets a fresh error budget.
                // Only reaching the threshold again without a success in between stops the run.
                state.consecutiveErrors = 0
                appendToolMessage(
                    sessionId, toolCall, toolCall.name, description, "",
                    "Loop protection: this action was NOT executed because you are repeating yourself or failing " +
                        "repeatedly. Stop, analyse the previous outputs, and either try a clearly different approach " +
                        "or report the problem to the user.",
                    -1, isError = true
                )
                audit(state, sessionId, toolCall.name, displayCall, description, AuditDecision.NOT_RUN, "loop_protection",
                    "Repeated action or persistent failures: not executed (warning).", assessment.level.name, null, 0, assessment.redact, app)
                return
            }
            appendSystemMessage("🛑 Loop protection: repeated actions or persistent failures (`$description`). Execution stopped.", sessionId)
            updateMessageStatus(assistantMsgId, MessageStatus.ERROR)
            appendToolMessage(sessionId, toolCall, toolCall.name, description, "", "Loop protection stopped execution.", -1, isError = true)
            audit(state, sessionId, toolCall.name, displayCall, description, AuditDecision.NOT_RUN, "loop_protection",
                "Loop protection stopped the run.", assessment.level.name, null, 0, assessment.redact, app)
            state.stopRequested = true
            return
        }
        state.history.add(description)
        state.resultHistory.add(null)

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
            audit(state, sessionId, toolCall.name, displayCall, description, AuditDecision.BLOCKED, blockRule, assessment.reason,
                RiskLevel.BLOCKED.name, blocked, 0, assessment.redact, app)
            state.consecutiveErrors++
            return
        }

        // 3. Approval. In REMOTE runs every screen action asks, and per-run grants do not apply there.
        val remoteForced = PhoneToolPolicy.requiresApprovalInMode(toolCall.name, mode)
        var decision = AuditDecision.ALLOWED
        var decisionRule = "preset:${_securityPreset.value.name}"
        var decisionReason = assessment.reason
        var category: UnattendedCategory? = null
        if (unattended != null) {
            // Unattended mode: never ask, never park. A fixed rule decides and the log says why.
            val guard = state.guard
            val kind = UnattendedPolicy.guardedKind(toolCall)
            val denial: Pair<String, String>? = if (kind != null && guard != null) {
                val verdict = guard.check(kind, UnattendedPolicy.contentOf(toolCall, assessment))
                if (verdict.allowed) null else "injection:${kind.name.lowercase()}" to verdict.reason
            } else null
            val needsUser = remoteForced || CommandSecurityFilter.shouldRequireApproval(assessment, SecurityPreset.AUTONOMOUS)
            val granted = assessment.grantKey != null && assessment.grantKey in state.grants
            var policyDenial = denial
            if (policyDenial == null && needsUser && !granted) {
                val cat = ActionClassifier.classify(toolCall, assessment)
                category = cat
                val d = UnattendedPolicy.decide(cat, unattended, guard, UnattendedPolicy.contentOf(toolCall, assessment))
                if (!d.allowed) policyDenial = d.rule to d.reason
                else {
                    decision = AuditDecision.AUTO_ALLOWED
                    decisionRule = d.rule
                    decisionReason = "${d.reason} ${assessment.reason}".trim()
                }
            } else if (policyDenial == null && needsUser && granted) {
                decisionRule = "grant:${assessment.grantKey}"
            }
            if (policyDenial != null) {
                autoDeny(sessionId, assistantMsgId, toolCall, securedToolCall, displayCall, description, assessment, state, policyDenial.first, policyDenial.second, app)
                return
            }
            // Per-run limits: stop cleanly before the action that would exceed them.
            val limits = unattended.limits
            val isPhoneAction = toolCall.name in PhoneTools.SCREEN_ACTIONS || toolCall.name == PhoneTools.SCREEN_BATCH
            val phoneSteps = if (toolCall.name == PhoneTools.SCREEN_BATCH) {
                com.agent.mobile.phone.ActionBatchParser.parse(toolCall.arguments["steps"]).steps.size.coerceAtLeast(1)
            } else 1
            val sends = category == UnattendedCategory.SEND_MESSAGES
            val over = when {
                isPhoneAction && limits.maxPhoneActions > 0 && state.phoneActions + phoneSteps > limits.maxPhoneActions ->
                    "maximum of ${limits.maxPhoneActions} phone actions"
                sends && limits.maxMessagesSent > 0 && state.messagesSent + 1 > limits.maxMessagesSent ->
                    "maximum of ${limits.maxMessagesSent} sent messages"
                else -> null
            }
            if (over != null) {
                releasePhoneCall(toolCall)
                val stopped = appendToolMessage(sessionId, securedToolCall, toolCall.name, description, "",
                    "Not executed: the unattended limit ($over) is reached. The run ends now.", -1, isError = true)
                updateMessageToolResult(assistantMsgId, stopped, MessageStatus.ERROR)
                audit(state, sessionId, toolCall.name, displayCall, description, AuditDecision.NOT_RUN, "limit",
                    "Unattended limit reached: $over.", assessment.level.name, stopped, 0, assessment.redact, app)
                endForLimit(sessionId, state, over)
                return
            }
            if (isPhoneAction) state.phoneActions += phoneSteps
            if (sends) state.messagesSent++
            if (decision == AuditDecision.AUTO_ALLOWED) {
                assessment.grantKey?.let { state.grants += it }
                if (PhoneTools.isPhoneTool(toolCall.name)) phone?.noteApproved(toolCall.id)
            }
        } else {
            val granted = !remoteForced && assessment.grantKey != null && assessment.grantKey in state.grants
            if (granted) decisionRule = "grant:${assessment.grantKey}"
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
                    decision = AuditDecision.APPROVED
                    decisionRule = if (mode == RunMode.INTERACTIVE) "user:app" else "user:${mode.name.lowercase()}"
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
                    audit(state, sessionId, toolCall.name, displayCall, description,
                        if (notAsked) AuditDecision.SKIPPED else AuditDecision.REJECTED,
                        if (notAsked) "no_one_to_ask" else "user", assessment.reason, assessment.level.name, rejected, 0, assessment.redact, app)
                    return
                }
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
            // Only a cancelled run ends here. A timeout inside a tool (TimeoutCancellationException
            // while this job is still active) is a failed tool call the model can react to.
            if (!currentCoroutineContext().isActive) throw e
            ToolResult(
                toolCallId = securedToolCall.id, command = description,
                stderr = "Timed out: ${e.message ?: "the tool did not answer in time"}", exitCode = -1, isError = true
            )
        } catch (e: Exception) {
            ToolResult(toolCallId = securedToolCall.id, command = description, stderr = e.localizedMessage ?: e.toString(), exitCode = -1, isError = true)
        }
        val duration = System.currentTimeMillis() - start
        val failed = raw.isError || raw.exitCode != 0
        val transient = failed && raw.exitCode == TRANSIENT_EXIT_CODE && PhoneTools.isPhoneTool(toolCall.name)
        when {
            // Display off or screen busy: waiting is right, repeating it is not a loop.
            transient -> {
                state.history.removeLastOrNull()
                state.resultHistory.removeLastOrNull()
            }
            failed -> state.consecutiveErrors++
            else -> state.consecutiveErrors = 0
        }
        if (!transient && state.resultHistory.isNotEmpty()) {
            // What this action returned: a repeat with a different result (polling, test-fix-test) is no loop.
            state.resultHistory[state.resultHistory.lastIndex] =
                (raw.exitCode.toString() + "\u0000" + raw.stdout + "\u0000" + raw.stderr).hashCode()
        }

        recordAudit(sessionId, description, assessment.level.name, assessment.reason, raw, duration, approved = true)
        audit(state, sessionId, toolCall.name, displayCall, description, decision, decisionRule, decisionReason,
            assessment.level.name, raw, duration, assessment.redact, app)
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
        // Foreign content read in an unattended run restricts what may follow (injection guard).
        if (isGuardSource(toolCall.name, description)) state.guard?.observeUntrusted(toolCall.name, observation)
        val toolMsg = ChatMessage(
            role = MessageRole.TOOL,
            text = if (untrusted) "[UNTRUSTED_OUTPUT_START]\n${compactToolOutput(observation)}\n[UNTRUSTED_OUTPUT_END]" else compactToolOutput(observation),
            toolResult = result,
            status = status
        )
        val progressHint = trackProgress(toolCall.name, description, observation, state)
        // A successful step with a new result ends the warned phase: a later loop gets its own warning.
        if (!failed && state.noProgress == 0) state.loopWarnings = 0
        // Providers read the tool result fields, so the hint goes into stderr (like the reflection hint).
        val finalToolMsg = if (progressHint != null) {
            toolMsg.copy(
                text = toolMsg.text + "\n\n" + progressHint,
                toolResult = toolMsg.toolResult?.let { it.copy(stderr = (it.stderr + "\n\n" + progressHint).trim()) }
            )
        } else toolMsg
        _messages.value = _messages.value + finalToolMsg
        chatRepository?.saveMessage(sessionId, finalToolMsg)
        if (state.noProgress >= NO_PROGRESS_STOP) {
            appendSystemMessage(
                "🛑 No progress: the last $NO_PROGRESS_STOP actions only repeated results the agent already had. Execution stopped.",
                sessionId
            )
            state.stopRequested = true
        }
    }

    /**
     * Unattended mode refused [toolCall]: it does not run, the log records why, and the model
     * gets a hint to continue without it. Never waits for anybody.
     */
    private suspend fun autoDeny(
        sessionId: String,
        assistantMsgId: String,
        toolCall: ToolCall,
        securedToolCall: ToolCall,
        displayCall: ToolCall,
        description: String,
        assessment: SecurityAssessment,
        state: LoopState,
        rule: String,
        reason: String,
        app: String?
    ) {
        releasePhoneCall(toolCall)
        val denied = appendToolMessage(sessionId, securedToolCall, toolCall.name, description, "", UnattendedPolicy.hint(reason), -1, isError = true)
        updateMessageToolResult(assistantMsgId, denied, MessageStatus.ERROR)
        appendSystemMessage("🚫 Unattended rule: `${description.take(160)}` was not run. $reason", sessionId)
        recordAudit(sessionId, description, assessment.level.name, "Auto-denied (unattended): $reason", denied, 0, approved = false)
        audit(state, sessionId, toolCall.name, displayCall, description, AuditDecision.AUTO_DENIED, rule, reason,
            assessment.level.name, denied, 0, assessment.redact, app)
        state.autoDenied += "${description.take(160)} — $reason"
    }

    /**
     * Counts actions in a row that brought nothing new (same action, same result as earlier in
     * the run). Returns a one-time hint for the model when the count reaches [NO_PROGRESS_HINT].
     */
    private fun trackProgress(toolName: String, description: String, observation: String, state: LoopState): String? {
        if (toolName in LOOP_EXEMPT_TOOLS) return null
        val key = (description + "\u0000" + observation).hashCode()
        if (state.seenResults.add(key)) {
            state.noProgress = 0
            return null
        }
        state.noProgress++
        return if (state.noProgress == NO_PROGRESS_HINT) {
            "[AMC] Your last $NO_PROGRESS_HINT actions only repeated results you already had: you are going in circles. " +
                "Stop, decide what is actually missing, and either try a clearly different approach or finish and " +
                "report what blocks you."
        } else null
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
        audit(state, sessionId, toolCall.name, securedToolCall, description, AuditDecision.PARKED, "user:parked",
            "Waiting for the user's approval. ${assessment.reason}", assessment.level.name, paused, 0, assessment.redact)
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
            AgentTools.DELEGATE_TASK -> runSubAgent(call, assistantMsgId, mode, skippedApprovals, state)
            AgentTools.SEND_FILE -> sendFile(call)
            AgentTools.FINISH_TASK -> {
                val status = a["status"].orEmpty().trim().lowercase().takeIf { it in setOf("done", "partial", "blocked") } ?: "done"
                state.finishStatus = status
                state.stopRequested = true
                // The summary becomes the run's final reply (notification, Telegram, report).
                val summary = a["summary"].orEmpty().trim()
                val reply = ChatMessage(role = MessageRole.ASSISTANT, text = summary, status = MessageStatus.COMPLETED)
                pendingFinal = reply
                ok("Run finished with status '$status'.")
            }
            AgentTools.ASK_USER -> ok(
                "Nobody can answer during this unattended run. Decide yourself with the most reasonable assumption and " +
                    "state it in your summary, or skip this part. The question is recorded for the user's report."
            )
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

    /**
     * A command that runs a script file is judged by the script's content too: otherwise writing
     * `termux-sms-send …` into run.sh and calling `bash run.sh` would skip the checks the same
     * command gets when typed directly. Unreadable scripts keep the command's own assessment.
     */
    private suspend fun withScriptChecks(call: ToolCall, base: SecurityAssessment): SecurityAssessment {
        if (call.name != AgentTools.EXECUTE_COMMAND || base.isBlocked) return base
        val scripts = CommandSecurityFilter.scriptFiles(call.arguments["command"].orEmpty())
        if (scripts.isEmpty() || bridgeClient.connectionStatus.value !is ConnectionStatus.Connected) return base
        var result = base
        for (path in scripts) {
            val content = try {
                withTimeoutOrNull(6_000L) { bridgeClient.readFile(path, timeoutMs = 5_000L) }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                null
            } catch (e: Exception) {
                null
            } ?: continue
            result = CommandSecurityFilter.withScriptContent(result, path, content)
            if (result.isBlocked) break
        }
        return result
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
        parentSkipped: MutableList<String>,
        parentState: LoopState
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
            phone = phone,
            auditLogger = auditLogger ?: chatRepository?.auditLogger
        )
        // Unattended parents: the child decides by the same rules and never asks anybody.
        child.unattendedOverride = parentState.unattended
        child.parentRunId = parentState.runId
        child.inheritedGuard = parentState.guard
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

    /**
     * Writes one entry of the tamper-evident audit log (every tool call, whatever the decision).
     * Arguments are redacted ([AuditRedactor]); a call that carries a secret keeps no values.
     */
    private suspend fun audit(
        state: LoopState?,
        sessionId: String?,
        toolName: String,
        call: ToolCall?,
        description: String,
        decision: AuditDecision,
        rule: String,
        reason: String,
        riskLevel: String,
        result: ToolResult?,
        durationMs: Long,
        secret: Boolean = false,
        app: String? = null
    ) {
        val logger = auditLogger ?: chatRepository?.auditLogger ?: return
        val args = when {
            call != null -> AuditRedactor.arguments(toolName, call.arguments, secret)
            description.isNotEmpty() -> org.json.JSONObject().put("description", AuditRedactor.scrub(description).take(300)).toString()
            else -> "{}"
        }
        val ran = decision == AuditDecision.ALLOWED || decision == AuditDecision.AUTO_ALLOWED || decision == AuditDecision.APPROVED
        val out = result?.let { r -> if (r.stdout.isNotEmpty()) r.stdout else r.stderr }.orEmpty()
        try {
            logger.log(
                AuditEntry(
                    runId = state?.runId ?: "unknown",
                    sessionId = sessionId,
                    trigger = state?.trigger ?: RunTrigger.USER,
                    mode = (state?.mode ?: RunMode.INTERACTIVE).name,
                    preset = if (state?.unattended != null) "UNATTENDED" else _securityPreset.value.name,
                    tool = toolName,
                    app = app,
                    arguments = args,
                    decision = decision,
                    rule = rule,
                    reason = AuditRedactor.scrub(reason),
                    riskLevel = riskLevel,
                    resultSummary = if (secret) "" else AuditRedactor.scrub(out.take(700)),
                    durationMs = durationMs,
                    exitCode = if (ran) result?.exitCode else null
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Audit log write failed: ${e.message}")
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
        maxCharBudget: Int = 120_000,
        /** Summary of dropped steps and the current plan, appended to the pinned task (#4). */
        pinned: String? = null
    ): List<ChatMessage> {
        val nonSystem = repairToolPairs(allMessages.filter { it.role != MessageRole.SYSTEM })
        if (nonSystem.isEmpty()) return emptyList()

        // The request the current work serves. Continuation notes, screenshots, steering messages and
        // "continue" after a step limit belong to it, so it must never fall out of the window.
        val root = taskRootIndex(nonSystem)
        val windowStart = (nonSystem.size - maxMessages).coerceAtLeast(0)
        var window: MutableList<ChatMessage>
        if (root in 0 until windowStart) {
            // Long run: keep the task and the most recent progress after it.
            val tail = nonSystem.subList(maxOf(root + 1, nonSystem.size - (maxMessages - 1)), nonSystem.size).toMutableList()
            dropLeadingOrphans(tail)
            window = (listOf(nonSystem[root]) + tail).toMutableList()
        } else {
            window = nonSystem.subList(windowStart, nonSystem.size).toMutableList()
            trimToUserStart(window)
            if (window.isEmpty()) {
                // No user message in the window at all: keep the latest one and the progress after it.
                val latestUserIdx = nonSystem.indexOfLast { it.role == MessageRole.USER }
                if (latestUserIdx < 0) return emptyList()
                val tail = nonSystem.subList(latestUserIdx + 1, nonSystem.size).takeLast(maxMessages - 1).toMutableList()
                dropLeadingOrphans(tail)
                window = (listOf(nonSystem[latestUserIdx]) + tail).toMutableList()
            }
        }

        fun weight(m: ChatMessage): Int =
            m.text.length + (m.toolResult?.stdout?.length ?: 0) + (m.toolResult?.stderr?.length ?: 0) +
                m.toolCalls.sumOf { tc -> tc.arguments.values.sumOf { it.length } } +
                (if (m.imageBase64 != null) IMAGE_CHAR_WEIGHT else 0)

        while (window.size > 2 && window.sumOf { weight(it) } > maxCharBudget) {
            // Only a new task (a real user request after a finished answer) ends an older exchange;
            // notes, screenshots and steering inside the current task do not.
            val nextUser = (1 until window.size).firstOrNull { isTaskStart(window, it) }
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

        // The record of earlier steps travels with the pinned task, so it survives every trim.
        if (pinned != null && root >= 0 && window.isNotEmpty() && window[0].id == nonSystem[root].id) {
            window[0] = window[0].copy(text = window[0].text + pinned)
        }

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
        // Prefer starting at a real user request; fall back to any user message.
        val firstReal = window.indexOfFirst { isRealUserMessage(it) }
        val firstUser = if (firstReal >= 0) firstReal else window.indexOfFirst { it.role == MessageRole.USER }
        if (firstUser < 0) {
            window.clear()
            return
        }
        repeat(firstUser) { window.removeAt(0) }
    }

    /** USER-role messages the app wrote itself: [AMC] notes, screenshots and photos, tool results. */
    internal fun isSyntheticUserMessage(m: ChatMessage): Boolean =
        m.role == MessageRole.USER && (
            m.toolResult != null ||
                m.text.trimStart().startsWith(AGENT_NOTE_PREFIX) ||
                (m.imageBase64 != null && SYNTHETIC_IMAGE_PREFIXES.any { m.text.startsWith(it) })
            )

    private fun isRealUserMessage(m: ChatMessage): Boolean = m.role == MessageRole.USER && !isSyntheticUserMessage(m)

    /** An assistant reply that ended a turn (text, no tool calls, not failed). */
    private fun isFinalAnswer(m: ChatMessage): Boolean =
        m.role == MessageRole.ASSISTANT && m.toolCalls.isEmpty() && m.toolCall == null &&
            m.text.isNotBlank() && m.status != MessageStatus.ERROR

    /**
     * True when [list][i] is a real user request that starts a new task: nothing came before it, or
     * the previous turn ended with a final answer. A message sent while the agent was still working
     * (steering, "continue" after the step limit, a retry after an error) continues the earlier task.
     */
    private fun isTaskStart(list: List<ChatMessage>, i: Int): Boolean {
        if (!isRealUserMessage(list[i])) return false
        var p = i - 1
        while (p >= 0 && isSyntheticUserMessage(list[p])) p--
        return p < 0 || isFinalAnswer(list[p])
    }

    /** Index of the request the latest work belongs to (see [isTaskStart]), or -1. */
    internal fun taskRootIndex(list: List<ChatMessage>): Int {
        var i = list.indexOfLast { isRealUserMessage(it) }
        while (i > 0 && !isTaskStart(list, i)) {
            val previous = (i - 1 downTo 0).firstOrNull { isRealUserMessage(list[it]) } ?: return i
            i = previous
        }
        return i
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
        maxConsecutiveErrors: Int = MAX_CONSECUTIVE_ERRORS,
        /**
         * Result hash per [history] entry (same size, null = unknown). When given, a repetition only
         * counts as a loop if it also returned the same result, so polling a status or alternating
         * test and fix while the output changes is allowed. Without it every repetition counts.
         */
        results: List<Int?>? = null
    ): Boolean {
        if (consecutiveErrors >= maxConsecutiveErrors) return true
        val res = results?.takeIf { it.size == history.size }
        fun sameResult(i: Int, j: Int): Boolean {
            if (res == null) return true
            val a = res[i] ?: return true
            val b = res[j] ?: return true
            return a == b
        }
        val n = history.size
        // Same action three times in a row (with the same result each time).
        if (n >= 2 && history.takeLast(2).all { it.equals(cmd, ignoreCase = true) } && sameResult(n - 1, n - 2)) return true
        // Ping-pong: A -> B -> A -> B (B returned the same both times).
        if (n >= 3) {
            val last = history.last()
            val secondLast = history[n - 2]
            val thirdLast = history[n - 3]
            if (cmd.equals(secondLast, ignoreCase = true) && last.equals(thirdLast, ignoreCase = true) &&
                !cmd.equals(last, ignoreCase = true) && sameResult(n - 1, n - 3)
            ) {
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
        // Cancelling the run below stops the agent's own command by its execution id. No bare
        // interrupt: the terminal tab shares this connection and its command must keep running (#33).
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

    /**
     * The chat's stop button: stops this engine's run and, as an emergency brake, every unattended
     * run of AMC (background tasks, Telegram, triggers).
     */
    fun emergencyStop() {
        stopLocal()
        RunRegistry.stopAll(exceptRunId = interactiveRunId, unattendedOnly = true)
    }

    private fun stopLocal() {
        _isStopping.value = true
        if (stopJob?.isActive == true) return
        stopJob = scope.launch { stopExecution(silent = false) }
    }

    @Volatile
    private var interactiveRunId: String? = null

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
