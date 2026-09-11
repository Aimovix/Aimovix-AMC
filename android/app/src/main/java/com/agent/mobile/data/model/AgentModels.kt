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
enum class ExecutionMode {
    AUTOPILOT,
    STEP_BY_STEP
}

@Serializable
enum class ProviderType(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    LOCAL("Lokaler Server (llama.cpp/Ollama)", "http://127.0.0.1:8080/v1", "default"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    CLAUDE("Anthropic Claude", "https://api.anthropic.com/v1", "claude-3-5-sonnet-20241022"),
    GROQ("Groq (Ultra-Fast)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-3.5-haiku")
}

@Serializable
data class ModelConfig(
    val provider: ProviderType = ProviderType.GEMINI,
    val modelName: String = provider.defaultModel,
    val apiKey: String = "",
    val baseUrl: String = provider.defaultBaseUrl
)

@Serializable
data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val rawJson: String = "",
    val riskLevel: String = "LOW",
    val riskReason: String = ""
)

@Serializable
data class ToolResult(
    val toolCallId: String,
    val command: String,
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
    val toolResult: ToolResult? = null,
    val streamingTerminalOutput: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETED
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
