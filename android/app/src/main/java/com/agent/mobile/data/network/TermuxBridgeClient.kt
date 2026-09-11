package com.agent.mobile.data.network

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
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
    companion object {
        private const val TAG = "TermuxBridgeClient"
        private const val RECONNECT_DELAY_MS = 1500L

        fun isTermuxInstalled(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.termux", 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun isIgnoringBatteryOptimizations(context: Context, packageName: String = "com.termux"): Boolean {
            return try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(packageName) ?: false
            } catch (e: Exception) {
                false
            }
        }

        fun getTermuxBatterySettingsIntent(): Intent {
            return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:com.termux")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun getIgnoreBatteryOptimizationListIntent(): Intent {
            return Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun getTermuxNotificationSettingsIntent(): Intent {
            return Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, "com.termux")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        fun getDeveloperOptionsIntent(): Intent {
            return Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS) // 0 disables ping timeout aborts on localhost
        .retryOnConnectionFailure(true)
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

    // Map for waiting file reads
    private val pendingFileReads = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // Auto-reconnect worker state
    private var isAutoReconnectEnabled = true
    private var reconnectJob: Job? = null
    private var isManualDisconnect = false

    fun setToken(newToken: String) {
        this.token = newToken
        if (_connectionStatus.value !is ConnectionStatus.Connected) {
            connect(newToken)
        }
    }

    fun connect(authToken: String = token) {
        this.token = authToken
        this.isManualDisconnect = false
        this.isAutoReconnectEnabled = true

        if (_connectionStatus.value is ConnectionStatus.Connected) {
            return
        }

        // Cancel existing reconnect job to avoid racing
        reconnectJob?.cancel()

        scope.launch {
            doConnect(authToken)
        }
    }

    fun forceReconnect(authToken: String = token) {
        this.token = authToken
        this.isManualDisconnect = false
        this.isAutoReconnectEnabled = true
        reconnectJob?.cancel()
        scope.launch {
            try {
                webSocket?.cancel()
            } catch (e: Exception) {
                // ignore
            }
            webSocket = null
            doConnect(authToken)
        }
    }

    fun reconnectIfDisconnected(force: Boolean = false) {
        val current = _connectionStatus.value
        if (current is ConnectionStatus.Connected) {
            if (force) {
                // Check if existing socket is responsive; if not, force reconnect
                val ws = webSocket
                if (ws == null) {
                    forceReconnect()
                } else {
                    val pingOk = ws.send(JSONObject().apply {
                        put("action", "ping")
                        put("timestamp", System.currentTimeMillis())
                    }.toString())
                    if (!pingOk) {
                        forceReconnect()
                    }
                }
            }
            return
        }
        if (current is ConnectionStatus.Connecting && !force) return

        this.isManualDisconnect = false
        this.isAutoReconnectEnabled = true
        reconnectJob?.cancel()

        scope.launch {
            doConnect(token)
        }
    }

    suspend fun triggerBoost(): ToolResult {
        return executeCommand("amc boost || amc restart")
    }

    private fun doConnect(authToken: String) {
        // Clean up previous socket
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null

        _connectionStatus.value = ConnectionStatus.Connecting

        // Try primary IP (127.0.0.1)
        val url = "ws://$host:$port"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Verbindung geöffnet zu $url")
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
                Log.w(TAG, "WebSocket geschlossen ($code): $reason")
                failAllPendingExecutions("Verbindung zu Termux wurde geschlossen ($reason)")
                _connectionStatus.value = ConnectionStatus.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket Fehler: ${t.message}")
                failAllPendingExecutions("Verbindungsfehler: ${t.localizedMessage}")
                _connectionStatus.value = ConnectionStatus.Error(t.localizedMessage ?: "Verbindungsfehler zu Termux")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isAutoReconnectEnabled || isManualDisconnect) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (isAutoReconnectEnabled && !isManualDisconnect && _connectionStatus.value !is ConnectionStatus.Connected) {
                Log.d(TAG, "Auto-Reconnect Versuch zu Termux...")
                doConnect(token)
            }
        }
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
                    // Once connected, cancel any scheduled reconnects
                    reconnectJob?.cancel()
                    Log.i(TAG, "✅ Termux Bridge erfolgreich authentifiziert und verbunden.")
                }
                "auth_fail" -> {
                    val error = json.optString("error", "Authentifizierung fehlgeschlagen: Token ungültig")
                    _connectionStatus.value = ConnectionStatus.AuthFailed(error)
                    // Do NOT auto-reconnect continuously on bad auth token until token changes
                    reconnectJob?.cancel()
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
                "file_content" -> {
                    val path = json.optString("path")
                    val content = json.optString("content")
                    val deferred = pendingFileReads.remove(path)
                    deferred?.complete(content)
                }
                "file_base64" -> {
                    val path = json.optString("path")
                    val b64 = json.optString("data")
                    val deferred = pendingFileReads.remove(path)
                    deferred?.complete(b64)
                }
                "error" -> {
                    val execId = json.optString("execution_id")
                    val err = json.optString("error")
                    val path = json.optString("path")
                    if (path.isNotEmpty()) {
                        pendingFileReads.remove(path)?.completeExceptionally(Exception(err))
                    }
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
            Log.e(TAG, "Fehler beim Parsen der WebSocket-Nachricht: ${e.message}", e)
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
                stderr = "Keine aktive Verbindung zu Termux (ws://$host:$port). Bitte 'amc start' in Termux ausführen.",
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

    suspend fun readFile(path: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<String>()
        pendingFileReads[path] = deferred
        val msg = JSONObject().apply {
            put("action", "read_file")
            put("path", path)
        }
        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            pendingFileReads.remove(path)
            throw IllegalStateException("Keine Verbindung zu Termux")
        }
        ws.send(msg.toString())
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingFileReads.remove(path)
        }
    }

    suspend fun readFileBase64(path: String, timeoutMs: Long = 15_000L): String = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<String>()
        pendingFileReads[path] = deferred
        val msg = JSONObject().apply {
            put("action", "read_file_base64")
            put("path", path)
        }
        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            pendingFileReads.remove(path)
            throw IllegalStateException("Keine Verbindung zu Termux")
        }
        ws.send(msg.toString())
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingFileReads.remove(path)
        }
    }

    suspend fun getCrontab(): String {
        val res = executeCommand("crontab -l")
        return if (res.exitCode == 0) res.stdout else ""
    }

    suspend fun setCrontab(crontabContent: String): ToolResult {
        val escaped = crontabContent.replace("'", "'\\''")
        return executeCommand("echo '$escaped' | crontab -")
    }

    fun disconnect() {
        isManualDisconnect = true
        isAutoReconnectEnabled = false
        reconnectJob?.cancel()
        failAllPendingExecutions("Client getrennt")
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
}
