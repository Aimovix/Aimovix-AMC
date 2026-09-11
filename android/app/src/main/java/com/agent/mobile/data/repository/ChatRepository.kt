package com.agent.mobile.data.repository

import android.content.Context
import android.os.Environment
import com.agent.mobile.data.model.*
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.entity.ChatMessageEntity
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ChatRepository(
    private val database: AppDatabase
) {
    val allSessions: Flow<List<ChatSession>> = database.chatSessionDao().getAllSessions()
    val allAudits: Flow<List<CommandAuditEntity>> = database.commandAuditDao().getAllAudits()

    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> {
        return database.chatMessageDao().getMessagesForSession(sessionId).map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun getMessagesForSessionSync(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        database.chatMessageDao().getMessagesForSessionSync(sessionId).map { it.toDomain() }
    }

    suspend fun getSessionById(sessionId: String): ChatSession? = withContext(Dispatchers.IO) {
        database.chatSessionDao().getSessionById(sessionId)
    }

    suspend fun createNewSession(
        title: String = "Neuer Chat",
        provider: String = "GEMINI",
        model: String = "gemini-2.0-flash"
    ): ChatSession = withContext(Dispatchers.IO) {
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            modelProvider = provider,
            modelName = model
        )
        database.chatSessionDao().insertSession(session)
        session
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        database.chatSessionDao().updateTitle(sessionId, title, System.currentTimeMillis())
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        database.chatSessionDao().deleteSessionById(sessionId)
    }

    suspend fun saveMessage(sessionId: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        database.chatMessageDao().insertMessage(message.toEntity(sessionId))
        database.chatSessionDao().updateTitle(sessionId = sessionId, title = "", updatedAt = System.currentTimeMillis())
    }

    suspend fun saveMessages(sessionId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        database.chatMessageDao().insertMessages(messages.map { it.toEntity(sessionId) })
    }

    suspend fun addMetrics(
        sessionId: String,
        promptTokens: Int,
        completionTokens: Int,
        cost: Double
    ) = withContext(Dispatchers.IO) {
        database.chatSessionDao().addMetrics(sessionId, promptTokens, completionTokens, cost, System.currentTimeMillis())
    }

    suspend fun searchMessages(query: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        database.chatMessageDao().searchMessages(query).map { it.toDomain() }
    }

    suspend fun recordAudit(audit: CommandAuditEntity) = withContext(Dispatchers.IO) {
        database.commandAuditDao().insertAudit(audit)
    }

    suspend fun clearAudits() = withContext(Dispatchers.IO) {
        database.commandAuditDao().clearAudits()
    }

    fun generateConciseTitle(userPrompt: String): String {
        val clean = userPrompt.trim()
            .replace(Regex("^[#!?/><*+\\s]+"), "")
            .lines().firstOrNull()?.trim() ?: "Neuer Chat"

        return if (clean.length <= 35) {
            clean.ifEmpty { "Neuer Chat" }
        } else {
            val cut = clean.substring(0, 32)
            val lastSpace = cut.lastIndexOf(' ')
            if (lastSpace in 15..32) {
                "${cut.substring(0, lastSpace)}..."
            } else {
                "$cut..."
            }
        }
    }

    fun exportToMarkdown(session: ChatSession, messages: List<ChatMessage>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("# ${session.title}\n\n")
        sb.append("- **Sitzungs-ID:** `${session.id}`\n")
        sb.append("- **Erstellt:** ${dateFormat.format(Date(session.createdAt))}\n")
        sb.append("- **Modell:** ${session.modelProvider} (${session.modelName})\n")
        sb.append("- **Tokens:** Prompt: ${session.totalPromptTokens} | Completion: ${session.totalCompletionTokens}\n")
        sb.append("- **Geschätzte Kosten:** \$${String.format(Locale.US, "%.5f", session.estimatedCostUsd)}\n\n")
        sb.append("---\n\n")

        for (m in messages) {
            val time = dateFormat.format(Date(m.timestamp))
            when (m.role) {
                MessageRole.USER -> {
                    sb.append("### 👤 Nutzer ($time)\n\n${m.text}\n\n")
                }
                MessageRole.ASSISTANT -> {
                    sb.append("### 🤖 AMC Agent ($time)\n\n")
                    if (m.text.isNotEmpty()) {
                        sb.append("${m.text}\n\n")
                    }
                    if (m.toolCall != null) {
                        sb.append("**Ausgeführter Befehl:**\n```bash\n${m.toolCall.arguments["command"] ?: ""}\n```\n\n")
                    }
                }
                MessageRole.TOOL -> {
                    sb.append("### 💻 Terminal-Ausgabe ($time)\n\n")
                    val out = m.toolResult?.stdout ?: m.text
                    sb.append("```bash\n$out\n```\n\n")
                }
                MessageRole.SYSTEM -> {
                    sb.append("### ℹ️ System ($time)\n\n${m.text}\n\n")
                }
            }
        }
        return sb.toString()
    }

    fun exportToJson(session: ChatSession, messages: List<ChatMessage>): String {
        val root = JSONObject()
        root.put("id", session.id)
        root.put("title", session.title)
        root.put("created_at", session.createdAt)
        root.put("updated_at", session.updatedAt)
        root.put("provider", session.modelProvider)
        root.put("model", session.modelName)
        root.put("prompt_tokens", session.totalPromptTokens)
        root.put("completion_tokens", session.totalCompletionTokens)
        root.put("cost_usd", session.estimatedCostUsd)

        val msgsArr = JSONArray()
        for (m in messages) {
            val mObj = JSONObject()
            mObj.put("id", m.id)
            mObj.put("role", m.role.name)
            mObj.put("text", m.text)
            mObj.put("timestamp", m.timestamp)
            mObj.put("status", m.status.name)
            if (m.toolCall != null) {
                mObj.put("tool_call", JSONObject().apply {
                    put("id", m.toolCall.id)
                    put("name", m.toolCall.name)
                    put("command", m.toolCall.arguments["command"] ?: "")
                    put("risk_level", m.toolCall.riskLevel)
                    put("risk_reason", m.toolCall.riskReason)
                })
            }
            if (m.toolResult != null) {
                mObj.put("tool_result", JSONObject().apply {
                    put("stdout", m.toolResult.stdout)
                    put("stderr", m.toolResult.stderr)
                    put("exit_code", m.toolResult.exitCode)
                    put("is_error", m.toolResult.isError)
                })
            }
            if (m.streamingTerminalOutput.isNotEmpty()) {
                mObj.put("streaming_output", m.streamingTerminalOutput)
            }
            msgsArr.put(mObj)
        }
        root.put("messages", msgsArr)
        return root.toString(2)
    }

    suspend fun saveExportFile(
        context: Context,
        filename: String,
        content: String
    ): File = withContext(Dispatchers.IO) {
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        if (!docsDir.exists()) docsDir.mkdirs()
        val file = File(docsDir, filename)
        file.writeText(content, Charsets.UTF_8)
        file
    }

    private fun ChatMessage.toEntity(sessionId: String): ChatMessageEntity {
        val toolCallJson = toolCall?.let { tc ->
            JSONObject().apply {
                put("id", tc.id)
                put("name", tc.name)
                put("arguments", JSONObject(tc.arguments))
                put("rawJson", tc.rawJson)
                put("riskLevel", tc.riskLevel)
                put("riskReason", tc.riskReason)
            }.toString()
        }

        val toolResultJson = toolResult?.let { tr ->
            JSONObject().apply {
                put("toolCallId", tr.toolCallId)
                put("command", tr.command)
                put("stdout", tr.stdout)
                put("stderr", tr.stderr)
                put("exitCode", tr.exitCode)
                put("isError", tr.isError)
            }.toString()
        }

        return ChatMessageEntity(
            id = id,
            sessionId = sessionId,
            role = role.name,
            text = text,
            toolCallJson = toolCallJson,
            toolResultJson = toolResultJson,
            streamingTerminalOutput = streamingTerminalOutput,
            timestamp = timestamp,
            status = status.name,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )
    }

    private fun ChatMessageEntity.toDomain(): ChatMessage {
        val parsedRole = try {
            MessageRole.valueOf(role)
        } catch (e: Exception) {
            MessageRole.ASSISTANT
        }

        val parsedStatus = try {
            MessageStatus.valueOf(status)
        } catch (e: Exception) {
            MessageStatus.COMPLETED
        }

        val parsedToolCall = toolCallJson?.let { jsonStr ->
            try {
                val obj = JSONObject(jsonStr)
                val argsMap = mutableMapOf<String, String>()
                val argsObj = obj.optJSONObject("arguments")
                argsObj?.keys()?.forEach { k ->
                    argsMap[k] = argsObj.optString(k, "")
                }
                ToolCall(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.optString("name", "execute_command"),
                    arguments = argsMap,
                    rawJson = obj.optString("rawJson", ""),
                    riskLevel = obj.optString("riskLevel", "LOW"),
                    riskReason = obj.optString("riskReason", "")
                )
            } catch (e: Exception) {
                null
            }
        }

        val parsedToolResult = toolResultJson?.let { jsonStr ->
            try {
                val obj = JSONObject(jsonStr)
                ToolResult(
                    toolCallId = obj.optString("toolCallId", ""),
                    command = obj.optString("command", ""),
                    stdout = obj.optString("stdout", ""),
                    stderr = obj.optString("stderr", ""),
                    exitCode = obj.optInt("exitCode", 0),
                    isError = obj.optBoolean("isError", false)
                )
            } catch (e: Exception) {
                null
            }
        }

        return ChatMessage(
            id = id,
            role = parsedRole,
            text = text,
            toolCall = parsedToolCall,
            toolResult = parsedToolResult,
            streamingTerminalOutput = streamingTerminalOutput,
            timestamp = timestamp,
            status = parsedStatus,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )
    }
}
