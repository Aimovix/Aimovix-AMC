package com.agent.mobile.agent

import com.agent.mobile.data.model.*
import com.agent.mobile.data.network.LlmClient
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AutonomousAgentEngine(
    private val bridgeClient: TermuxBridgeClient,
    private val llmClient: LlmClient = LlmClient()
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _executionMode = MutableStateFlow(ExecutionMode.AUTOPILOT)
    val executionMode: StateFlow<ExecutionMode> = _executionMode.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _modelConfig = MutableStateFlow(ModelConfig())
    val modelConfig: StateFlow<ModelConfig> = _modelConfig.asStateFlow()

    private val _pendingApproval = MutableStateFlow<Pair<String, ToolCall>?>(null)
    val pendingApproval: StateFlow<Pair<String, ToolCall>?> = _pendingApproval.asStateFlow()

    private var approvalContinuation: CompletableDeferred<Boolean>? = null

    fun setExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
    }

    fun setModelConfig(config: ModelConfig) {
        _modelConfig.value = config
    }

    fun clearHistory() {
        _messages.value = emptyList()
    }

    fun startTask(userPrompt: String) {
        if (_isBusy.value) return

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = userPrompt
        )
        _messages.value = _messages.value + userMessage
        _isBusy.value = true

        activeJob = scope.launch {
            try {
                runAgentLoop()
            } catch (e: CancellationException) {
                appendSystemMessage("🛑 Ausführung durch Nutzer abgebrochen.")
            } catch (e: Exception) {
                appendSystemMessage("⚠️ Fehler im Agenten-Loop: ${e.localizedMessage}")
            } finally {
                _isBusy.value = false
                _pendingApproval.value = null
            }
        }
    }

    private suspend fun runAgentLoop() {
        var iterations = 0
        val maxIterations = 15 // Protection against infinite loops

        while (iterations < maxIterations && currentCoroutineContext().isActive) {
            iterations++

            // Call LLM with current history and system prompt
            val response = llmClient.sendRequest(
                config = _modelConfig.value,
                systemPrompt = AgentPrompts.SYSTEM_PROMPT,
                messages = _messages.value
            )

            when (response) {
                is LlmClient.LlmResponse.Message -> {
                    // Final text response reached
                    val assistantMsg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = response.text,
                        status = MessageStatus.COMPLETED
                    )
                    _messages.value = _messages.value + assistantMsg
                    break
                }

                is LlmClient.LlmResponse.Action -> {
                    val cmd = response.toolCall.arguments["command"] ?: ""
                    val assessment = com.agent.mobile.security.CommandSecurityFilter.analyze(cmd)

                    val securedToolCall = response.toolCall.copy(
                        riskLevel = assessment.level.name,
                        riskReason = assessment.reason
                    )

                    // 1. Catastrophic Blacklist Check
                    if (assessment.isBlocked) {
                        val blockedMsg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            text = "🛡️ Sicherheits-Sperre: Der Befehl `$cmd` wurde blockiert.\nGrund: ${assessment.reason}",
                            status = MessageStatus.ERROR
                        )
                        _messages.value = _messages.value + blockedMsg

                        val toolRejectedMsg = ChatMessage(
                            role = MessageRole.TOOL,
                            text = "Ausführung aus Sicherheitsgründen blockiert: ${assessment.reason}",
                            toolResult = ToolResult(
                                toolCallId = securedToolCall.id,
                                command = cmd,
                                stderr = "Sicherheits-Blockade aktiv.",
                                isError = true
                            )
                        )
                        _messages.value = _messages.value + toolRejectedMsg
                        continue
                    }

                    val mustApprove = com.agent.mobile.security.CommandSecurityFilter.shouldRequireApproval(
                        assessment, _executionMode.value
                    )

                    val actionMessageId = UUID.randomUUID().toString()
                    val actionMsg = ChatMessage(
                        id = actionMessageId,
                        role = MessageRole.ASSISTANT,
                        text = response.thought,
                        toolCall = securedToolCall,
                        status = if (mustApprove) MessageStatus.WAITING_FOR_APPROVAL else MessageStatus.EXECUTING_TOOL
                    )
                    _messages.value = _messages.value + actionMsg

                    // 2. Approval check (Step-by-Step OR High-Risk in Autopilot)
                    if (mustApprove) {
                        _pendingApproval.value = Pair(actionMessageId, securedToolCall)
                        val deferred = CompletableDeferred<Boolean>()
                        approvalContinuation = deferred

                        val approved = deferred.await()
                        _pendingApproval.value = null
                        approvalContinuation = null

                        if (!approved) {
                            updateMessageStatus(actionMessageId, MessageStatus.ERROR)
                            val rejectedMsg = ChatMessage(
                                role = MessageRole.TOOL,
                                text = "Befehl wurde vom Nutzer abgelehnt.",
                                toolResult = ToolResult(
                                    toolCallId = securedToolCall.id,
                                    command = cmd,
                                    stderr = "Ausführung vom Nutzer verweigert.",
                                    isError = true
                                )
                            )
                            _messages.value = _messages.value + rejectedMsg
                            continue
                        }
                    }

                    // Execute command
                    updateMessageStatus(actionMessageId, MessageStatus.EXECUTING_TOOL)

                    val result = bridgeClient.executeCommand(cmd) { chunk ->
                        // Live update streaming terminal output in the action message
                        updateStreamingOutput(actionMessageId, chunk)
                    }

                    updateMessageStatus(actionMessageId, MessageStatus.COMPLETED)

                    // Add tool observation to context
                    val toolMsg = ChatMessage(
                        role = MessageRole.TOOL,
                        text = if (result.stdout.isNotEmpty()) result.stdout else result.stderr,
                        toolResult = result,
                        status = MessageStatus.COMPLETED
                    )
                    _messages.value = _messages.value + toolMsg
                }

                is LlmClient.LlmResponse.Error -> {
                    appendSystemMessage("⚠️ ${response.message}")
                    break
                }
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

    private fun updateStreamingOutput(messageId: String, newChunk: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(streamingTerminalOutput = msg.streamingTerminalOutput + newChunk)
            } else {
                msg
            }
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(status = status)
            } else {
                msg
            }
        }
    }

    private fun appendSystemMessage(text: String) {
        val sysMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            text = text,
            status = MessageStatus.COMPLETED
        )
        _messages.value = _messages.value + sysMsg
    }
}
