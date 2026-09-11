package com.agent.mobile.data.network

import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.model.TermuxSystemInfo
import com.agent.mobile.data.model.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TermuxBridgeClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 8765,
    private var token: String = ""
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // Live terminal streaming events (ID to Output chunk)
    val terminalOutputEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 256)

    // Map for waiting command execution completions
    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()
    private val executionOutputs = ConcurrentHashMap<String, StringBuilder>()

    fun setToken(newToken: String) {
        this.token = newToken
    }

    fun connect(authToken: String = token) {
        this.token = authToken
        if (_connectionStatus.value is ConnectionStatus.Connected) {
            return
        }

        // Close any hanging previous socket
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // ignore
        }

        _connectionStatus.value = ConnectionStatus.Connecting
        val url = "ws://$host:$port"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // Send auth handshake immediately
                val authMsg = JSONObject().apply {
                    put("action", "auth")
                    put("token", authToken)
                }
                ws.send(authMsg.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                failAllPendingExecutions("Verbindung zu Termux wurde geschlossen ($reason)")
                _connectionStatus.value = ConnectionStatus.Disconnected
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                failAllPendingExecutions("Verbindungsfehler: ${t.localizedMessage}")
                _connectionStatus.value = ConnectionStatus.Error(t.localizedMessage ?: "Verbindungsfehler zu Termux")
            }
        })
    }

    private fun failAllPendingExecutions(reason: String) {
        pendingExecutions.forEach { (id, deferred) ->
            deferred.complete(
                ToolResult(
                    toolCallId = id,
                    command = "",
                    stderr = reason,
                    exitCode = -1,
                    isError = true
                )
            )
        }
        pendingExecutions.clear()
        executionOutputs.clear()
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "auth_ok" -> {
                    val sysJson = json.optJSONObject("system")
                    val batteryJson = sysJson?.optJSONObject("battery")
                    val info = TermuxSystemInfo(
                        os = sysJson?.optString("os", "Linux") ?: "Linux",
                        cwd = json.optString("cwd", "~"),
                        batteryPercentage = batteryJson?.optInt("percentage"),
                        isCharging = batteryJson?.optString("plugged") != "UNPLUGGED",
                        hasTermuxApi = sysJson?.optBoolean("has_termux_api", false) ?: false,
                        hasLlama = sysJson?.optBoolean("has_llama", false) ?: false
                    )
                    _connectionStatus.value = ConnectionStatus.Connected(info)
                }
                "auth_fail" -> {
                    val error = json.optString("error", "Authentifizierung fehlgeschlagen")
                    _connectionStatus.value = ConnectionStatus.AuthFailed(error)
                }
                "stdout", "stderr" -> {
                    val execId = json.optString("execution_id")
                    val data = json.optString("data")
                    val buffer = executionOutputs.getOrPut(execId) { StringBuilder() }
                    buffer.append(data)
                    scope.launch {
                        terminalOutputEvents.emit(Pair(execId, data))
                    }
                }
                "completed" -> {
                    val execId = json.optString("execution_id")
                    val exitCode = json.optInt("exit_code", 0)
                    val out = executionOutputs[execId]?.toString() ?: ""
                    val deferred = pendingExecutions[execId]
                    if (deferred != null) {
                        deferred.complete(
                            ToolResult(
                                toolCallId = execId,
                                command = "",
                                stdout = out,
                                exitCode = exitCode,
                                isError = exitCode != 0
                            )
                        )
                        pendingExecutions.remove(execId)
                        executionOutputs.remove(execId)
                    }
                }
                "error" -> {
                    val execId = json.optString("execution_id")
                    val err = json.optString("error")
                    val deferred = pendingExecutions[execId]
                    if (deferred != null) {
                        deferred.complete(
                            ToolResult(
                                toolCallId = execId,
                                command = "",
                                stderr = err,
                                exitCode = 1,
                                isError = true
                            )
                        )
                        pendingExecutions.remove(execId)
                        executionOutputs.remove(execId)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = 90_000L,
        onChunk: ((String) -> Unit)? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val execId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ToolResult>()
        pendingExecutions[execId] = deferred
        executionOutputs[execId] = StringBuilder()

        val msg = JSONObject().apply {
            put("action", "execute")
            put("command", command)
            put("execution_id", execId)
        }

        val listenerJob = onChunk?.let { callback ->
            scope.launch {
                terminalOutputEvents.collect { (id, chunk) ->
                    if (id == execId) {
                        callback(chunk)
                    }
                }
            }
        }

        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            listenerJob?.cancel()
            pendingExecutions.remove(execId)
            return@withContext ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "Keine aktive Verbindung zu Termux (ws://127.0.0.1:8765). Bitte zuerst in Termux starten.",
                exitCode = -1,
                isError = true
            )
        }

        val sent = ws.send(msg.toString())
        if (!sent) {
            listenerJob?.cancel()
            pendingExecutions.remove(execId)
            return@withContext ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "Befehl konnte nicht gesendet werden (Socket geschlossen).",
                exitCode = -1,
                isError = true
            )
        }

        val result = try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            interruptCurrent()
            ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "Zeitüberschreitung: Befehl hat nach ${timeoutMs / 1000}s nicht geantwortet.",
                exitCode = 124,
                isError = true
            )
        } finally {
            listenerJob?.cancel()
            pendingExecutions.remove(execId)
            executionOutputs.remove(execId)
        }

        result.copy(command = command)
    }

    fun interruptCurrent() {
        val msg = JSONObject().apply {
            put("action", "interrupt")
        }
        webSocket?.send(msg.toString())
    }

    fun disconnect() {
        failAllPendingExecutions("Client getrennt")
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
}
