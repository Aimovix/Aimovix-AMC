package com.agent.mobile.data.network

import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.data.model.ModelConfig
import com.agent.mobile.data.model.ProviderType
import com.agent.mobile.data.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LlmClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    sealed class LlmResponse {
        data class Message(val text: String) : LlmResponse()
        data class Action(val thought: String, val toolCall: ToolCall) : LlmResponse()
        data class Error(val message: String) : LlmResponse()
    }

    suspend fun sendRequest(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): LlmResponse = withContext(Dispatchers.IO) {
        try {
            when (config.provider) {
                ProviderType.GEMINI -> callGemini(config, systemPrompt, messages)
                ProviderType.CLAUDE -> callClaude(config, systemPrompt, messages)
                ProviderType.OPENAI,
                ProviderType.GROQ,
                ProviderType.OPENROUTER,
                ProviderType.LOCAL -> callOpenAiCompatible(config, systemPrompt, messages)
            }
        } catch (e: Exception) {
            LlmResponse.Error(e.localizedMessage ?: "Netzwerkfehler beim Aufruf des LLMs")
        }
    }

    /**
     * OpenAI-compatible format (used by OpenAI, Groq, OpenRouter, and Local llama-server)
     */
    private fun callOpenAiCompatible(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): LlmResponse {
        val root = JSONObject()
        root.put("model", config.modelName)
        root.put("temperature", 0.3)

        val msgs = JSONArray()
        msgs.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        for (m in messages) {
            val mObj = JSONObject()
            when (m.role) {
                MessageRole.USER -> {
                    mObj.put("role", "user")
                    mObj.put("content", m.text)
                }
                MessageRole.ASSISTANT -> {
                    mObj.put("role", "assistant")
                    if (m.toolCall != null) {
                        mObj.put("content", if (m.text.isNotEmpty()) m.text else null)
                        val toolCallsArr = JSONArray()
                        toolCallsArr.put(JSONObject().apply {
                            put("id", m.toolCall.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", m.toolCall.name)
                                put("arguments", JSONObject(m.toolCall.arguments).toString())
                            })
                        })
                        mObj.put("tool_calls", toolCallsArr)
                    } else {
                        mObj.put("content", m.text)
                    }
                }
                MessageRole.TOOL -> {
                    mObj.put("role", "tool")
                    mObj.put("tool_call_id", m.toolResult?.toolCallId ?: "default")
                    val content = JSONObject().apply {
                        put("command", m.toolResult?.command ?: "")
                        put("stdout", m.toolResult?.stdout ?: "")
                        put("stderr", m.toolResult?.stderr ?: "")
                        put("exit_code", m.toolResult?.exitCode ?: 0)
                    }
                    mObj.put("content", content.toString())
                }
                else -> {}
            }
            msgs.put(mObj)
        }
        root.put("messages", msgs)

        // Add tool definitions
        root.put("tools", getStandardToolDefinitions())

        val url = if (config.baseUrl.endsWith("/chat/completions")) {
            config.baseUrl
        } else {
            "${config.baseUrl.trimEnd('/')}/chat/completions"
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody("application/json".toMediaType()))

        if (config.apiKey.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        if (config.provider == ProviderType.OPENROUTER) {
            reqBuilder.addHeader("HTTP-Referer", "https://github.com/Aimovix/Aimovix-AMC")
            reqBuilder.addHeader("X-Title", "AMC - AI Mobile Center")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return LlmResponse.Error("API Fehler (${response.code}): $body")
            }

            val resJson = JSONObject(body)
            val choices = resJson.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return LlmResponse.Error("Leere Antwort von der KI-Schnittstelle")
            }

            val firstMessage = choices.getJSONObject(0).getJSONObject("message")
            val content = firstMessage.optString("content", "")

            // Check native tool calls
            val toolCalls = firstMessage.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val firstCall = toolCalls.getJSONObject(0)
                val fn = firstCall.getJSONObject("function")
                val fnName = fn.getString("name")
                val fnArgs = fn.getString("arguments")
                val argsMap = mutableMapOf<String, String>()
                try {
                    val argsObj = JSONObject(fnArgs)
                    argsObj.keys().forEach { k ->
                        argsMap[k] = argsObj.optString(k, "")
                    }
                } catch (e: Exception) {
                    argsMap["command"] = fnArgs
                }

                return LlmResponse.Action(
                    thought = content,
                    toolCall = ToolCall(
                        id = firstCall.optString("id", UUID.randomUUID().toString()),
                        name = fnName,
                        arguments = argsMap,
                        rawJson = fnArgs
                    )
                )
            }

            // Fallback for smaller local models that print markdown code blocks for tools
            val parsedBlock = extractToolCallFromText(content)
            if (parsedBlock != null) {
                return parsedBlock
            }

            return LlmResponse.Message(content)
        }
    }

    /**
     * Google Gemini API (v1beta generateContent)
     */
    private fun callGemini(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): LlmResponse {
        val root = JSONObject()

        // System Instruction
        root.put("system_instruction", JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        })

        // Contents
        val contents = JSONArray()
        for (m in messages) {
            val part = JSONObject()
            // In Gemini API, only ASSISTANT is "model"; USER and TOOL observations must be "user"!
            val role = if (m.role == MessageRole.ASSISTANT) "model" else "user"

            when {
                m.toolCall != null -> {
                    part.put("functionCall", JSONObject().apply {
                        put("name", m.toolCall.name)
                        put("args", JSONObject(m.toolCall.arguments))
                    })
                }
                m.toolResult != null -> {
                    part.put("functionResponse", JSONObject().apply {
                        put("name", "execute_command")
                        put("response", JSONObject().apply {
                            put("stdout", m.toolResult.stdout)
                            put("stderr", m.toolResult.stderr)
                            put("exit_code", m.toolResult.exitCode)
                        })
                    })
                }
                else -> {
                    part.put("text", m.text)
                }
            }
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(part))
            })
        }
        root.put("contents", contents)

        // Gemini Tools definition
        val funcDecls = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "execute_command")
                put("description", "Führt einen Bash-Befehl oder ein Termux:API Kommando auf dem Smartphone aus.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Der auszuführende Shell-Befehl")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })
        }
        root.put("tools", JSONArray().put(JSONObject().put("function_declarations", funcDecls)))

        val url = "${config.baseUrl}/models/${config.modelName}:generateContent?key=${config.apiKey}"
        val req = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return LlmResponse.Error("Gemini Fehler (${response.code}): $body")
            }

            val resJson = JSONObject(body)
            val candidates = resJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return LlmResponse.Error("Keine Antwort von Gemini generiert")
            }

            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            var textAcc = ""

            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("text")) {
                    textAcc += p.getString("text")
                }
                if (p.has("functionCall")) {
                    val fc = p.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val argsObj = fc.optJSONObject("args")
                    val argsMap = mutableMapOf<String, String>()
                    argsObj?.keys()?.forEach { k ->
                        argsMap[k] = argsObj.optString(k, "")
                    }
                    return LlmResponse.Action(
                        thought = textAcc,
                        toolCall = ToolCall(name = name, arguments = argsMap)
                    )
                }
            }

            val fallbackAction = extractToolCallFromText(textAcc)
            if (fallbackAction != null) {
                return fallbackAction
            }

            return LlmResponse.Message(textAcc)
        }
    }

    /**
     * Anthropic Claude API (v1/messages)
     */
    private fun callClaude(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): LlmResponse {
        val root = JSONObject()
        root.put("model", config.modelName)
        root.put("max_tokens", 2048)
        root.put("system", systemPrompt)

        val msgs = JSONArray()
        for (m in messages) {
            val role = if (m.role == MessageRole.USER) "user" else "assistant"
            msgs.put(JSONObject().apply {
                put("role", role)
                put("content", m.text)
            })
        }
        root.put("messages", msgs)

        val req = Request.Builder()
            .url("${config.baseUrl}/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(root.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return LlmResponse.Error("Claude Fehler (${response.code}): $body")
            }

            val resJson = JSONObject(body)
            val contentArr = resJson.optJSONArray("content")
            var reply = ""
            if (contentArr != null) {
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.getJSONObject(i)
                    if (item.optString("type") == "text") {
                        reply += item.optString("text")
                    }
                }
            }

            val parsed = extractToolCallFromText(reply)
            return parsed ?: LlmResponse.Message(reply)
        }
    }

    private fun extractToolCallFromText(content: String): LlmResponse.Action? {
        // Robust regex searching for {"tool": "...", "args": {...}} or {"tool": "...", "command": "..."}
        val p1 = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```")
        val m1 = p1.matcher(content)
        while (m1.find()) {
            val jsonCandidate = m1.group(1) ?: continue
            try {
                val obj = JSONObject(jsonCandidate)
                if (obj.has("tool")) {
                    val toolName = obj.getString("tool")
                    val argsMap = mutableMapOf<String, String>()
                    if (obj.has("args")) {
                        val argsObj = obj.getJSONObject("args")
                        argsObj.keys().forEach { k -> argsMap[k] = argsObj.optString(k) }
                    } else if (obj.has("command")) {
                        argsMap["command"] = obj.getString("command")
                    }
                    val thought = content.substring(0, m1.start()).trim()
                    return LlmResponse.Action(
                        thought = thought,
                        toolCall = ToolCall(name = toolName, arguments = argsMap)
                    )
                }
            } catch (e: Exception) {
                // ignore parsing error and continue
            }
        }
        return null
    }

    private fun getStandardToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "execute_command")
                    put("description", "Führt einen Shell- oder Termux:API-Befehl direkt auf dem Smartphone aus und streamt das Terminal-Ergebnis.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply {
                                put("type", "string")
                                put("description", "Der genaue auszuführende Bash-Befehl (z. B. 'termux-battery-status', 'ls -la', 'python script.py', 'termux-sms-send -n 12345 Hello')")
                            })
                        })
                        put("required", JSONArray().put("command"))
                    })
                })
            })
        }
    }
}
