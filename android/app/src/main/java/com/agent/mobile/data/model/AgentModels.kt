package com.agent.mobile.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

@Serializable
enum class SecurityPreset(
    val title: String,
    val description: String
) {
    AUTONOMOUS(
        title = "Autonomous",
        description = "Recommended. The agent works on its own: reading, writing, scripts, installs and downloads run automatically. Sensitive actions (SMS, calls, camera, location, deleting files, killing processes) still ask for approval."
    ),
    DEFAULT(
        title = "Supervised",
        description = "Every action requires your manual approval before it runs."
    ),
    FULL_MACHINE(
        title = "Full machine",
        description = "All terminal commands require review. The agent can read or write to any file in the machine."
    ),
    TURBO(
        title = "Turbo mode",
        description = "Fully autonomous: nothing asks for approval. Only catastrophic commands stay blocked. Use with care."
    ),
    CUSTOM(
        title = "Custom",
        description = "Manually customize individual settings."
    )
}

@Serializable
enum class ProviderType(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String> = emptyList()
) {
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        suggestedModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite"
        )
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        suggestedModels = listOf(
            "gpt-4o-mini",
            "gpt-4.1-mini",
            "gpt-4.1",
            "gpt-4o",
            "gpt-5-mini",
            "gpt-5",
            "o4-mini"
        )
    ),
    CLAUDE(
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-opus-5-5",
        suggestedModels = listOf(
            "claude-opus-5-5",
            "claude-sonnet-5-5",
            "claude-haiku-5-5",
            "claude-fable-5-1",
            "claude-sonnet-4-6",
            "claude-haiku-4-5"
        )
    ),
    GROQ(
        displayName = "Groq (Ultra-Fast)",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        suggestedModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
        )
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openrouter/auto",
        suggestedModels = listOf(
            "openrouter/auto",
            "anthropic/claude-sonnet-4.5",
            "google/gemini-2.5-flash",
            "openai/gpt-4o-mini",
            "deepseek/deepseek-chat",
            "meta-llama/llama-3.3-70b-instruct"
        )
    ),
    LOCAL(
        displayName = "Local server (llama.cpp/Ollama)",
        defaultBaseUrl = "http://127.0.0.1:8080/v1",
        defaultModel = "default",
        suggestedModels = listOf(
            "default",
            "qwen2.5:1.5b",
            "qwen2.5:3b",
            "llama3.2:3b",
            "deepseek-r1:1.5b"
        )
    )
}

@Serializable
data class ModelConfig(
    val provider: ProviderType = ProviderType.GEMINI,
    val modelName: String = provider.defaultModel,
    val apiKey: String = "",
    val baseUrl: String = provider.defaultBaseUrl,
    val fallbackProvider: ProviderType? = null,
    val fallbackModelName: String = "",
    val fallbackApiKey: String = "",
    val fallbackBaseUrl: String = ""
)

@Serializable
data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val rawJson: String = "",
    val riskLevel: String = "LOW",
    val riskReason: String = "",
    val thoughtSignature: String? = null
)

@Serializable
data class ToolResult(
    val toolCallId: String,
    val command: String,
    /** Name of the tool that produced this result (Gemini needs it in functionResponse). */
    val toolName: String = "execute_command",
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val isError: Boolean = false
)

enum class MessageStatus {
    SENT,
    STREAMING,
    WAITING_FOR_APPROVAL,
    EXECUTING_TOOL,
    COMPLETED,
    ERROR
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val toolCall: ToolCall? = null,
    val toolCalls: List<ToolCall> = if (toolCall != null) listOf(toolCall) else emptyList(),
    val toolResult: ToolResult? = null,
    val streamingTerminalOutput: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETED,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE }

data class PlanStep(val text: String, val status: PlanStepStatus)

enum class ArtifactType {
    TEXT,
    CODE,
    MARKDOWN,
    HTML,
    IMAGE
}

data class ArtifactItem(
    val filename: String,
    val path: String,
    val type: ArtifactType,
    val content: String? = null,
    val base64Data: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class SessionMetrics(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val estimatedCostUsd: Double = 0.0
)

data class TermuxSystemInfo(
    val os: String = "Linux",
    val cwd: String = "~",
    val batteryPercentage: Int? = null,
    val isCharging: Boolean? = null,
    val hasTermuxApi: Boolean = false,
    val hasLlama: Boolean = false
)

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    data class Connected(val info: TermuxSystemInfo) : ConnectionStatus()
    data class AuthFailed(val error: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}
