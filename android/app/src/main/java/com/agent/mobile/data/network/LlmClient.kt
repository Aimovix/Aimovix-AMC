package com.agent.mobile.data.network

import android.util.Log
import com.agent.mobile.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.UnknownHostException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "LlmClient"
    }

    sealed class LlmResponse {
        data class Message(
            val text: String,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val costUsd: Double = 0.0
        ) : LlmResponse()

        data class Action(
            val thought: String,
            val toolCall: ToolCall,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val costUsd: Double = 0.0
        ) : LlmResponse()

        data class Error(
            val message: String,
            val statusCode: Int = 0,
            val isRetryable: Boolean = true
        ) : LlmResponse()
    }

    sealed class LlmStreamEvent {
        data class Token(val textChunk: String) : LlmStreamEvent()
        data class ToolCallDetected(
            val thought: String,
            val toolCall: ToolCall,
            val toolCalls: List<ToolCall> = listOf(toolCall),
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val estimatedCostUsd: Double = 0.0
        ) : LlmStreamEvent()
        data class Completed(
            val fullText: String,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val estimatedCostUsd: Double = 0.0
        ) : LlmStreamEvent()
        data class Error(
            val message: String,
            val statusCode: Int = 0,
            val isRetryable: Boolean = true
        ) : LlmStreamEvent()
    }

    /**
     * Streams tokens in real time via Server-Sent Events (SSE).
     */
    fun streamRequest(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): Flow<LlmStreamEvent> = flow {
        val providerMessages = messages.filter { it.role != MessageRole.SYSTEM }
        try {
            when (config.provider) {
                ProviderType.GEMINI -> streamGemini(config, systemPrompt, providerMessages) { emit(it) }
                ProviderType.CLAUDE -> streamClaude(config, systemPrompt, providerMessages) { emit(it) }
                ProviderType.OPENAI,
                ProviderType.GROQ,
                ProviderType.OPENROUTER,
                ProviderType.LOCAL -> streamOpenAiCompatible(config, systemPrompt, providerMessages) { emit(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            emit(LlmStreamEvent.Error(
                "Cannot resolve the AI provider address (DNS). Check Wi-Fi/mobile data, " +
                    "VPN or Private DNS, and the provider URL. " +
                    "Termux Connected only confirms the local bridge connection.", 0, true
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Exception during SSE streaming: ${e.message}", e)
            emit(LlmStreamEvent.Error(e.localizedMessage ?: "Connection error during SSE streaming", 0, true))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming fallback invocation.
     */
    suspend fun sendRequest(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): LlmResponse = withContext(Dispatchers.IO) {
        var lastAction: LlmResponse.Action? = null
        var lastMessage: LlmResponse.Message? = null
        var errorOccurred: LlmResponse.Error? = null
        val textAccumulator = StringBuilder()

        try {
            streamRequest(config, systemPrompt, messages).collect { event ->
                when (event) {
                    is LlmStreamEvent.Token -> {
                        textAccumulator.append(event.textChunk)
                    }
                    is LlmStreamEvent.ToolCallDetected -> {
                        lastAction = LlmResponse.Action(
                            thought = event.thought,
                            toolCall = event.toolCall,
                            promptTokens = event.promptTokens,
                            completionTokens = event.completionTokens,
                            costUsd = event.estimatedCostUsd
                        )
                    }
                    is LlmStreamEvent.Completed -> {
                        lastMessage = LlmResponse.Message(
                            text = event.fullText.ifEmpty { textAccumulator.toString() },
                            promptTokens = event.promptTokens,
                            completionTokens = event.completionTokens,
                            costUsd = event.estimatedCostUsd
                        )
                    }
                    is LlmStreamEvent.Error -> {
                        errorOccurred = LlmResponse.Error(
                            message = event.message,
                            statusCode = event.statusCode,
                            isRetryable = event.isRetryable
                        )
                    }
                }
            }

            if (errorOccurred != null) return@withContext errorOccurred!!
            if (lastAction != null) return@withContext lastAction!!
            if (lastMessage != null) return@withContext lastMessage!!

            val fallbackText = textAccumulator.toString()
            val parsedTool = extractToolCallFromText(fallbackText)
            if (parsedTool != null) return@withContext parsedTool

            LlmResponse.Message(fallbackText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LlmResponse.Error(e.localizedMessage ?: "Communication with the LLM failed")
        }
    }


    private suspend inline fun <T> executeCancellableCall(
        request: Request,
        crossinline block: suspend (Response) -> T
    ): T {
        val call = client.newCall(request)
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }

        return try {
            response.use { resp ->
                block(resp)
            }
        } catch (t: Throwable) {
            call.cancel()
            throw t
        }
    }

    private class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    /**
     * 1. OpenAI-Compatible SSE Streaming (OpenAI, Groq, OpenRouter, Local llama-server)
     */
    private suspend fun streamOpenAiCompatible(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        emit: suspend (LlmStreamEvent) -> Unit
    ) {
        val root = JSONObject()
        root.put("model", config.modelName)
        root.put("temperature", 0.3)
        root.put("stream", true)
        root.put("parallel_tool_calls", false)
        root.put("stream_options", JSONObject().apply { put("include_usage", true) })

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
                    if (m.imageBase64 != null) {
                        val contentArr = JSONArray()
                        if (m.text.isNotEmpty()) {
                            contentArr.put(JSONObject().apply {
                                put("type", "text")
                                put("text", m.text)
                            })
                        }
                        val mime = m.imageMimeType ?: "image/jpeg"
                        contentArr.put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:$mime;base64,${m.imageBase64}")
                            })
                        })
                        mObj.put("content", contentArr)
                    } else {
                        mObj.put("content", m.text)
                    }
                }
                MessageRole.ASSISTANT -> {
                    mObj.put("role", "assistant")
                    val calls = if (m.toolCalls.isNotEmpty()) m.toolCalls else if (m.toolCall != null) listOf(m.toolCall) else emptyList()
                    if (calls.isNotEmpty()) {
                        mObj.put("content", if (m.text.isNotEmpty()) m.text else null)
                        val toolCallsArr = JSONArray()
                        for (tc in calls) {
                            toolCallsArr.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", JSONObject(tc.arguments).toString())
                                })
                            })
                        }
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
        root.put("tools", getStandardToolDefinitions())

        val trimmedBase = config.baseUrl.trimEnd('/')
        val url = if (trimmedBase.endsWith("/chat/completions")) {
            trimmedBase
        } else {
            "$trimmedBase/chat/completions"
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

        executeCancellableCall(reqBuilder.build()) { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                val isRetryable = response.code == 429 || response.code >= 500
                emit(LlmStreamEvent.Error("OpenAI API error (${response.code}): $errBody", response.code, isRetryable))
                return@executeCancellableCall
            }

            val body = response.body ?: return@executeCancellableCall
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

            val accumulatedText = StringBuilder()
            val toolCallsMap = sortedMapOf<Int, ToolCallAccumulator>()
            var promptTokens = 0
            var completionTokens = 0
            var streamDone = false

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.isEmpty() || l.startsWith(":") || l == "event: ping") continue

                if (l.startsWith("data:")) {
                    val data = l.substring(5).trim()
                    if (data == "[DONE]") {
                        streamDone = true
                        break
                    }

                    try {
                        val json = JSONObject(data)

                        if (json.has("error")) {
                            val errObj = json.getJSONObject("error")
                            val errMsg = errObj.optString("message", "OpenAI stream error")
                            val isRetryable = errObj.optString("type") == "rate_limit_error"
                            emit(LlmStreamEvent.Error("OpenAI stream error: $errMsg", 0, isRetryable))
                            return@executeCancellableCall
                        }

                        if (json.has("usage") && !json.isNull("usage")) {
                            val u = json.getJSONObject("usage")
                            promptTokens = u.optInt("prompt_tokens", promptTokens)
                            completionTokens = u.optInt("completion_tokens", completionTokens)
                        }

                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue

                        // Text streaming chunk
                        if (delta.has("content") && !delta.isNull("content")) {
                            val chunk = delta.getString("content")
                            if (chunk.isNotEmpty()) {
                                accumulatedText.append(chunk)
                                emit(LlmStreamEvent.Token(chunk))
                            }
                        }

                        // Tool call streaming chunk
                        val toolCalls = delta.optJSONArray("tool_calls")
                        if (toolCalls != null && toolCalls.length() > 0) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", i)
                                val acc = toolCallsMap.getOrPut(idx) { ToolCallAccumulator() }
                                if (tc.has("id")) acc.id += tc.getString("id")
                                val fn = tc.optJSONObject("function")
                                if (fn != null) {
                                    if (fn.has("name")) acc.name += fn.getString("name")
                                    if (fn.has("arguments")) acc.arguments.append(fn.getString("arguments"))
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // ignore chunk decode issue and continue
                    }
                }
            }

            if (!streamDone) {
                emit(LlmStreamEvent.Error("OpenAI stream closed prematurely without [DONE] termination", 0, true))
                return@executeCancellableCall
            }

            val fullText = accumulatedText.toString()
            if (promptTokens == 0) {
                promptTokens = estimateTokens(systemPrompt) + messages.sumOf { estimateTokens(it.text) + if (it.imageBase64 != null) 1000 else 0 }
            }
            if (completionTokens == 0) completionTokens = estimateTokens(fullText)
            val cost = calculateEstimatedCost(config.provider, config.modelName, promptTokens, completionTokens)

            if (toolCallsMap.isNotEmpty()) {
                val detectedTools = mutableListOf<ToolCall>()
                var hasInvalidArgs = false

                for ((_, acc) in toolCallsMap) {
                    if (acc.name.isNotEmpty()) {
                        val parsed = parseArgumentsJson(acc.arguments.toString())
                        if (parsed != null) {
                            val idStr = acc.id.ifEmpty { UUID.randomUUID().toString() }
                            detectedTools.add(ToolCall(id = idStr, name = acc.name, arguments = parsed, rawJson = acc.arguments.toString()))
                        } else {
                            hasInvalidArgs = true
                        }
                    }
                }

                if (detectedTools.isNotEmpty()) {
                    val primaryTool = detectedTools.first()
                    emit(
                        LlmStreamEvent.ToolCallDetected(
                            thought = fullText,
                            toolCall = primaryTool,
                            toolCalls = detectedTools,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            estimatedCostUsd = cost
                        )
                    )
                    return@executeCancellableCall
                } else if (hasInvalidArgs) {
                    emit(LlmStreamEvent.Error("Received malformed JSON arguments for tool call from model", 0, false))
                    return@executeCancellableCall
                }
            }

            // Fallback markdown tool block parsing
            val parsedBlock = extractToolCallFromText(fullText)
            if (parsedBlock != null) {
                emit(
                    LlmStreamEvent.ToolCallDetected(
                        thought = parsedBlock.thought,
                        toolCall = parsedBlock.toolCall,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        estimatedCostUsd = cost
                    )
                )
                return@executeCancellableCall
            }

            if (!streamDone && fullText.isEmpty() && toolCallsMap.isEmpty()) {
                emit(LlmStreamEvent.Error("OpenAI stream ended prematurely without [DONE]", 0, true))
                return@executeCancellableCall
            }

            emit(LlmStreamEvent.Completed(fullText = fullText, promptTokens = promptTokens, completionTokens = completionTokens, estimatedCostUsd = cost))
        }
    }

    /**
     * 2. Google Gemini SSE Streaming (streamGenerateContent?alt=sse)
     */
    private suspend fun streamGemini(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        emit: suspend (LlmStreamEvent) -> Unit
    ) {
        val root = JSONObject()
        root.put("system_instruction", JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        })

        val contents = JSONArray()
        for (m in messages) {
            val role = if (m.role == MessageRole.ASSISTANT) "model" else "user"
            val partsArr = JSONArray()

            if (m.imageBase64 != null) {
                partsArr.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", m.imageMimeType ?: "image/jpeg")
                        put("data", m.imageBase64)
                    })
                })
            }

            val calls = if (m.toolCalls.isNotEmpty()) m.toolCalls else if (m.toolCall != null) listOf(m.toolCall) else emptyList()
            when {
                calls.isNotEmpty() -> {
                    for (tc in calls) {
                        partsArr.put(JSONObject().apply {
                            put("functionCall", JSONObject().apply {
                                put("name", tc.name)
                                put("args", JSONObject(tc.arguments))
                            })
                            if (tc.thoughtSignature != null) {
                                put("thoughtSignature", tc.thoughtSignature)
                            }
                        })
                    }
                }
                m.toolResult != null -> {
                    partsArr.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", "execute_command")
                            if (m.toolResult.toolCallId.isNotEmpty()) {
                                put("id", m.toolResult.toolCallId)
                            }
                            put("response", JSONObject().apply {
                                put("stdout", m.toolResult.stdout)
                                put("stderr", m.toolResult.stderr)
                                put("exit_code", m.toolResult.exitCode)
                            })
                        })
                    })
                }
                else -> {
                    if (m.text.isNotEmpty()) {
                        partsArr.put(JSONObject().put("text", m.text))
                    }
                }
            }

            if (partsArr.length() > 0) {
                if (m.toolResult != null && contents.length() > 0 && contents.getJSONObject(contents.length() - 1).getString("role") == "user") {
                    val prevObj = contents.getJSONObject(contents.length() - 1)
                    val prevParts = prevObj.getJSONArray("parts")
                    if (prevParts.length() > 0 && prevParts.getJSONObject(0).has("functionResponse")) {
                        for (pIdx in 0 until partsArr.length()) {
                            prevParts.put(partsArr.getJSONObject(pIdx))
                        }
                    } else {
                        contents.put(JSONObject().apply {
                            put("role", role)
                            put("parts", partsArr)
                        })
                    }
                } else {
                    contents.put(JSONObject().apply {
                        put("role", role)
                        put("parts", partsArr)
                    })
                }
            }
        }
        root.put("contents", contents)

        val funcDecls = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "execute_command")
                put("description", "Runs a Bash command or Termux:API command on the phone.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The shell command to execute")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })
        }
        root.put("tools", JSONArray().put(JSONObject().put("function_declarations", funcDecls)))

        val cleanKey = config.apiKey.removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (cleanKey.isEmpty()) {
            emit(LlmStreamEvent.Error(
                "No API key configured for Google Gemini. Please enter your Gemini API key in Settings (get a free key at aistudio.google.com/apikey).",
                401, false
            ))
            return
        }

        val isOAuthToken = cleanKey.startsWith("ya29.")
        val url = if (isOAuthToken) {
            "${config.baseUrl.trimEnd('/')}/models/${config.modelName}:streamGenerateContent?alt=sse"
        } else {
            "${config.baseUrl.trimEnd('/')}/models/${config.modelName}:streamGenerateContent?alt=sse&key=$cleanKey"
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody("application/json".toMediaType()))

        if (isOAuthToken) {
            reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
        } else {
            reqBuilder.addHeader("x-goog-api-key", cleanKey)
        }

        val req = reqBuilder.build()

        executeCancellableCall(req) { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                val isRetryable = response.code == 429 || response.code >= 500
                val displayError = when {
                    response.code == 401 && errBody.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED") ->
                        "Invalid Google credential type. Please use a Gemini API key from Google AI Studio (starts with 'AIzaSy...'), not a Google Cloud OAuth client or access token. Get a free key at aistudio.google.com/apikey."
                    response.code == 400 && errBody.contains("API_KEY_INVALID") ->
                        "Invalid Gemini API key. Please verify your API key in Settings (aistudio.google.com/apikey)."
                    else ->
                        "Gemini API error (${response.code}): $errBody"
                }
                emit(LlmStreamEvent.Error(displayError, response.code, isRetryable))
                return@executeCancellableCall
            }

            val body = response.body ?: return@executeCancellableCall
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

            val accumulatedText = StringBuilder()
            val detectedTools = mutableListOf<ToolCall>()
            var promptTokens = 0
            var completionTokens = 0

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.isEmpty() || !l.startsWith("data:")) continue

                val data = l.substring(5).trim()
                try {
                    val json = JSONObject(data)
                    if (json.has("error")) {
                        val errObj = json.getJSONObject("error")
                        val msg = errObj.optString("message", "Gemini stream error")
                        emit(LlmStreamEvent.Error("Gemini stream error: $msg", errObj.optInt("code", 0), false))
                        return@executeCancellableCall
                    }

                    if (json.has("promptFeedback")) {
                        val fb = json.getJSONObject("promptFeedback")
                        if (fb.has("blockReason")) {
                            val br = fb.optString("blockReason")
                            emit(LlmStreamEvent.Error("Gemini prompt blocked: $br", 0, false))
                            return@executeCancellableCall
                        }
                    }

                    val candidates = json.optJSONArray("candidates")

                    if (json.has("usageMetadata")) {
                        val meta = json.getJSONObject("usageMetadata")
                        promptTokens = meta.optInt("promptTokenCount", promptTokens)
                        completionTokens = meta.optInt("candidatesTokenCount", completionTokens)
                    }

                    if (candidates != null && candidates.length() > 0) {
                        val c = candidates.getJSONObject(0)
                        val finishReason = c.optString("finishReason", "")
                        if (finishReason == "SAFETY" || finishReason == "RECITATION" || finishReason == "BLOCKLIST" || finishReason == "PROHIBITED_CONTENT") {
                            emit(LlmStreamEvent.Error("Gemini response blocked by content filter ($finishReason)", 0, false))
                            return@executeCancellableCall
                        }

                        val content = c.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")

                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val p = parts.getJSONObject(i)
                                if (p.has("text")) {
                                    val chunk = p.getString("text")
                                    accumulatedText.append(chunk)
                                    emit(LlmStreamEvent.Token(chunk))
                                }
                                if (p.has("functionCall")) {
                                    val fc = p.getJSONObject("functionCall")
                                    val name = fc.getString("name")
                                    val id = if (fc.has("id")) fc.getString("id")
                                        else if (p.has("id")) p.getString("id")
                                        else UUID.randomUUID().toString()
                                    val argsObj = fc.optJSONObject("args")
                                    val argsMap = mutableMapOf<String, String>()
                                    argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.optString(k, "") }
                                    val signature = if (p.has("thoughtSignature")) p.getString("thoughtSignature")
                                        else if (fc.has("thoughtSignature")) fc.getString("thoughtSignature")
                                        else null
                                    detectedTools.add(
                                        ToolCall(
                                            id = id,
                                            name = name,
                                            arguments = argsMap,
                                            thoughtSignature = signature
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ignore single parse error in stream
                }
            }

            val fullText = accumulatedText.toString()
            if (promptTokens == 0) {
                promptTokens = estimateTokens(systemPrompt) + messages.sumOf { estimateTokens(it.text) + if (it.imageBase64 != null) 1000 else 0 }
            }
            if (completionTokens == 0) completionTokens = estimateTokens(fullText)
            val cost = calculateEstimatedCost(config.provider, config.modelName, promptTokens, completionTokens)

            if (detectedTools.isEmpty() && fullText.isEmpty()) {
                emit(LlmStreamEvent.Error("Gemini stream completed with empty response", 0, true))
                return@executeCancellableCall
            }

            if (detectedTools.isNotEmpty()) {
                emit(
                    LlmStreamEvent.ToolCallDetected(
                        thought = fullText,
                        toolCall = detectedTools.first(),
                        toolCalls = detectedTools,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        estimatedCostUsd = cost
                    )
                )
                return@executeCancellableCall
            }

            val fallbackParsed = extractToolCallFromText(fullText)
            if (fallbackParsed != null) {
                emit(
                    LlmStreamEvent.ToolCallDetected(
                        thought = fallbackParsed.thought,
                        toolCall = fallbackParsed.toolCall,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        estimatedCostUsd = cost
                    )
                )
                return@executeCancellableCall
            }

            emit(LlmStreamEvent.Completed(fullText = fullText, promptTokens = promptTokens, completionTokens = completionTokens, estimatedCostUsd = cost))
        }
    }

    /**
     * 3. Anthropic Claude SSE Streaming (/messages with stream: true)
     */
    private suspend fun streamClaude(
        config: ModelConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        emit: suspend (LlmStreamEvent) -> Unit
    ) {
        val root = JSONObject()
        root.put("model", config.modelName)
        root.put("max_tokens", 4096)
        root.put("system", systemPrompt)
        root.put("stream", true)

        val toolsArr = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "execute_command")
                put("description", "Executes a shell or Termux:API command on the phone and streams terminal output.")
                put("input_schema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "The exact Bash command to execute (e.g. 'termux-battery-status', 'ls -la')")
                        })
                    })
                    put("required", JSONArray().put("command"))
                })
            })
        }
        root.put("tools", toolsArr)

        val msgs = JSONArray()
        for (m in messages) {
            when (m.role) {
                MessageRole.USER -> {
                    val contentArr = JSONArray()
                    if (m.text.isNotEmpty()) {
                        contentArr.put(JSONObject().apply {
                            put("type", "text")
                            put("text", m.text)
                        })
                    }
                    if (m.imageBase64 != null) {
                        contentArr.put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", m.imageMimeType ?: "image/jpeg")
                                put("data", m.imageBase64)
                            })
                        })
                    }
                    if (msgs.length() > 0 && msgs.getJSONObject(msgs.length() - 1).getString("role") == "user") {
                        val prevContent = msgs.getJSONObject(msgs.length() - 1).getJSONArray("content")
                        for (idx in 0 until contentArr.length()) {
                            prevContent.put(contentArr.get(idx))
                        }
                    } else {
                        msgs.put(JSONObject().apply {
                            put("role", "user")
                            put("content", contentArr)
                        })
                    }
                }
                MessageRole.ASSISTANT -> {
                    val contentArr = JSONArray()
                    if (m.text.isNotEmpty()) {
                        contentArr.put(JSONObject().apply {
                            put("type", "text")
                            put("text", m.text)
                        })
                    }
                    val calls = if (m.toolCalls.isNotEmpty()) m.toolCalls else if (m.toolCall != null) listOf(m.toolCall) else emptyList()
                    if (calls.isNotEmpty()) {
                        for (tc in calls) {
                            contentArr.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", JSONObject(tc.arguments))
                            })
                        }
                    }
                    if (contentArr.length() == 0) {
                        contentArr.put(JSONObject().apply {
                            put("type", "text")
                            put("text", "...")
                        })
                    }
                    if (msgs.length() > 0 && msgs.getJSONObject(msgs.length() - 1).getString("role") == "assistant") {
                        val prevContent = msgs.getJSONObject(msgs.length() - 1).getJSONArray("content")
                        for (idx in 0 until contentArr.length()) {
                            prevContent.put(contentArr.get(idx))
                        }
                    } else {
                        msgs.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", contentArr)
                        })
                    }
                }
                MessageRole.TOOL -> {
                    val toolResultBlock = JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", m.toolResult?.toolCallId ?: "default")
                        val resContent = JSONObject().apply {
                            put("command", m.toolResult?.command ?: "")
                            put("stdout", m.toolResult?.stdout ?: "")
                            put("stderr", m.toolResult?.stderr ?: "")
                            put("exit_code", m.toolResult?.exitCode ?: 0)
                        }
                        put("content", resContent.toString())
                        if (m.toolResult?.isError == true) {
                            put("is_error", true)
                        }
                    }
                    if (msgs.length() > 0 && msgs.getJSONObject(msgs.length() - 1).getString("role") == "user") {
                        val prevContent = msgs.getJSONObject(msgs.length() - 1).getJSONArray("content")
                        prevContent.put(toolResultBlock)
                    } else {
                        msgs.put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().put(toolResultBlock))
                        })
                    }
                }
                else -> {}
            }
        }
        root.put("messages", msgs)

        val req = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(root.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeCancellableCall(req) { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                val isRetryable = response.code == 429 || response.code >= 500
                emit(LlmStreamEvent.Error("Claude API error (${response.code}): $errBody", response.code, isRetryable))
                return@executeCancellableCall
            }

            val body = response.body ?: return@executeCancellableCall
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

            val accumulatedText = StringBuilder()
            var promptTokens = 0
            var completionTokens = 0
            val toolCallsMap = mutableMapOf<Int, ToolCallAccumulator>()
            var currentBlockIndex = -1
            var streamDone = false

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue

                val data = l.substring(5).trim()
                try {
                    val json = JSONObject(data)
                    val type = json.optString("type")

                    when (type) {
                        "error" -> {
                            val err = json.optJSONObject("error")
                            val errMsg = err?.optString("message", "Claude stream error") ?: "Claude stream error"
                            val isRetryable = err?.optString("type") == "rate_limit_error"
                            emit(LlmStreamEvent.Error(errMsg, 0, isRetryable))
                            return@executeCancellableCall
                        }
                        "message_start" -> {
                            val msgObj = json.optJSONObject("message")
                            val usage = msgObj?.optJSONObject("usage")
                            promptTokens = usage?.optInt("input_tokens", promptTokens) ?: promptTokens
                        }
                        "content_block_start" -> {
                            val idx = json.optInt("index", currentBlockIndex + 1)
                            currentBlockIndex = idx
                            val block = json.optJSONObject("content_block")
                            if (block != null && block.optString("type") == "tool_use") {
                                val id = block.optString("id", UUID.randomUUID().toString())
                                val name = block.optString("name", "execute_command")
                                toolCallsMap[idx] = ToolCallAccumulator(id = id, name = name)
                            }
                        }
                        "content_block_delta" -> {
                            val idx = json.optInt("index", currentBlockIndex)
                            val delta = json.optJSONObject("delta")
                            if (delta != null) {
                                val deltaType = delta.optString("type")
                                if (deltaType == "text_delta") {
                                    val chunk = delta.optString("text", "")
                                    if (chunk.isNotEmpty()) {
                                        accumulatedText.append(chunk)
                                        emit(LlmStreamEvent.Token(chunk))
                                    }
                                } else if (deltaType == "input_json_delta") {
                                    val partial = delta.optString("partial_json", "")
                                    toolCallsMap[idx]?.arguments?.append(partial)
                                }
                            }
                        }
                        "message_delta" -> {
                            val usage = json.optJSONObject("usage")
                            completionTokens = usage?.optInt("output_tokens", completionTokens) ?: completionTokens
                        }
                        "message_stop" -> {
                            streamDone = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ignore single chunk decode issue
                }
            }

            if (!streamDone) {
                emit(LlmStreamEvent.Error("Claude stream closed prematurely without message_stop", 0, true))
                return@executeCancellableCall
            }

            val fullText = accumulatedText.toString()
            if (promptTokens == 0) {
                promptTokens = estimateTokens(systemPrompt) + messages.sumOf { estimateTokens(it.text) + if (it.imageBase64 != null) 1000 else 0 }
            }
            if (completionTokens == 0) completionTokens = estimateTokens(fullText)
            val cost = calculateEstimatedCost(config.provider, config.modelName, promptTokens, completionTokens)

            if (toolCallsMap.isNotEmpty()) {
                val detectedTools = mutableListOf<ToolCall>()
                var hasInvalidArgs = false

                for ((_, acc) in toolCallsMap) {
                    if (acc.name.isNotEmpty()) {
                        val parsed = parseArgumentsJson(acc.arguments.toString())
                        if (parsed != null) {
                            val idStr = acc.id.ifEmpty { UUID.randomUUID().toString() }
                            detectedTools.add(ToolCall(id = idStr, name = acc.name, arguments = parsed, rawJson = acc.arguments.toString()))
                        } else {
                            hasInvalidArgs = true
                        }
                    }
                }

                if (detectedTools.isNotEmpty()) {
                    emit(
                        LlmStreamEvent.ToolCallDetected(
                            thought = fullText,
                            toolCall = detectedTools.first(),
                            toolCalls = detectedTools,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            estimatedCostUsd = cost
                        )
                    )
                    return@executeCancellableCall
                } else if (hasInvalidArgs) {
                    emit(LlmStreamEvent.Error("Received malformed JSON arguments for tool call from Claude", 0, false))
                    return@executeCancellableCall
                }
            }

            val parsedTool = extractToolCallFromText(fullText)
            if (parsedTool != null) {
                emit(
                    LlmStreamEvent.ToolCallDetected(
                        thought = parsedTool.thought,
                        toolCall = parsedTool.toolCall,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        estimatedCostUsd = cost
                    )
                )
                return@executeCancellableCall
            }

            emit(LlmStreamEvent.Completed(fullText = fullText, promptTokens = promptTokens, completionTokens = completionTokens, estimatedCostUsd = cost))
        }
    }

    private fun parseArgumentsJson(rawJson: String): Map<String, String>? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = JSONObject(trimmed)
            val argsMap = mutableMapOf<String, String>()
            obj.keys().forEach { k -> argsMap[k] = obj.optString(k, "") }
            val cmd = argsMap["command"]
            if (cmd != null && cmd.trim().isEmpty()) {
                return null
            }
            argsMap
        } catch (e: Exception) {
            null
        }
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    fun calculateEstimatedCost(
        provider: ProviderType,
        model: String,
        promptTokens: Int,
        completionTokens: Int
    ): Double {
        val (promptRatePerM, completionRatePerM) = when (provider) {
            ProviderType.GEMINI -> Pair(0.10, 0.40)
            ProviderType.OPENAI -> {
                if (model.contains("4o-mini", ignoreCase = true)) Pair(0.15, 0.60)
                else if (model.contains("o3", ignoreCase = true) || model.contains("o1", ignoreCase = true)) Pair(1.10, 4.40)
                else Pair(2.50, 10.00)
            }
            ProviderType.CLAUDE -> {
                if (model.contains("haiku", ignoreCase = true)) Pair(0.80, 4.00)
                else Pair(3.00, 15.00)
            }
            ProviderType.OPENROUTER -> Pair(0.50, 1.50)
            ProviderType.GROQ, ProviderType.LOCAL -> Pair(0.0, 0.0)
        }
        return (promptTokens * promptRatePerM + completionTokens * completionRatePerM) / 1_000_000.0
    }

    fun extractToolCallFromText(content: String): LlmResponse.Action? {
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
                // Ignore parse errors
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
                    put("description", "Executes a shell or Termux:API command on the phone and streams terminal output.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply {
                                put("type", "string")
                                put("description", "The exact Bash command to execute (e.g. 'termux-battery-status', 'ls -la', 'python script.py', 'termux-camera-photo photo.jpg')")
                            })
                        })
                        put("required", JSONArray().put("command"))
                    })
                })
            })
        }
    }
}
