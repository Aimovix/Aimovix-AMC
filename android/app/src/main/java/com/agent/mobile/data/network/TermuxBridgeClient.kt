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
    private val token: String = ""
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // Live terminal streaming events
    val terminalOutputEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 128)

    // Map for waiting command execution completions
    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()
    private val executionOutputs = ConcurrentHashMap<String, StringBuilder>()

    fun connect(authToken: String = token) {
        if (_connectionStatus.value is ConnectionStatus.Connected || _connectionStatus.value is ConnectionStatus.Connecting) {
            return
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
                _connectionStatus.value = ConnectionStatus.Disconnected
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionStatus.value = ConnectionStatus.Error(t.localizedMessage ?: "Verbindungsfehler")
            }
        })
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

        val sent = webSocket?.send(msg.toString()) ?: false
        if (!sent) {
            listenerJob?.cancel()
            pendingExecutions.remove(execId)
            return@withContext ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "Keine Verbindung zu Termux",
                exitCode = -1,
                isError = true
            )
        }

        val result = try {
            deferred.await()
        } finally {
            listenerJob?.cancel()
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
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
}
