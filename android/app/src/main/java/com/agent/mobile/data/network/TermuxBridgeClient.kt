package com.agent.mobile.data.network

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.data.model.TermuxSystemInfo
import com.agent.mobile.data.model.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        fun openTermuxApp(context: Context): Boolean {
            return try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Termux app: ${e.message}", e)
                false
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

    // Direct per-execution chunk listeners
    private val executionChunkCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    internal class HeadTailBuffer(
        val maxHead: Int = 100 * 1024,
        val maxTail: Int = 150 * 1024
    ) {
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private var totalOmitted = 0

        @Synchronized
        fun append(chunk: String) {
            val remainingHead = maxHead - head.length
            if (remainingHead > 0) {
                val toHead = chunk.take(remainingHead)
                head.append(toHead)
                val remainingChunk = chunk.substring(toHead.length)
                if (remainingChunk.isNotEmpty()) {
                    appendToTail(remainingChunk)
                }
            } else {
                appendToTail(chunk)
            }
        }

        private fun appendToTail(chunk: String) {
            tail.append(chunk)
            if (tail.length > maxTail) {
                val overflow = tail.length - maxTail
                totalOmitted += overflow
                tail.delete(0, overflow)
            }
        }

        @Synchronized
        fun build(): String {
            return if (totalOmitted > 0) {
                "${head.toString()}\n\n[... $totalOmitted characters omitted ...]\n\n${tail.toString()}"
            } else {
                "${head.toString()}${tail.toString()}"
            }
        }
    }

    private class ExecutionBuffers(
        val stdout: HeadTailBuffer = HeadTailBuffer(),
        val stderr: HeadTailBuffer = HeadTailBuffer()
    )

    // Map for waiting command execution completions
    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()
    private val executionBuffers = ConcurrentHashMap<String, ExecutionBuffers>()
    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Long>>()

    // Map for waiting file reads (keyed by request_id and path)
    private val pendingFileReads = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // Connection generation to prevent stale callbacks
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // Mutex to strictly serialize connection attempts
    private val connectionMutex = Mutex()

    // Auto-reconnect worker and heartbeat state
    private var isAutoReconnectEnabled = true
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
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
                    scope.launch {
                        val pingId = UUID.randomUUID().toString()
                        val deferred = CompletableDeferred<Long>()
                        pendingPings[pingId] = deferred
                        val sent = ws.send(JSONObject().apply {
                            put("action", "ping")
                            put("ping_id", pingId)
                            put("timestamp", System.currentTimeMillis())
                        }.toString())

                        if (!sent) {
                            pendingPings.remove(pingId)
                            forceReconnect()
                        } else {
                            try {
                                withTimeout(2500L) { deferred.await() }
                                requestSysInfo()
                            } catch (e: Exception) {
                                pendingPings.remove(pingId)
                                forceReconnect()
                            }
                        }
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
        return executeCommand("nohup amc boost >/dev/null 2>&1 &")
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive && _connectionStatus.value is ConnectionStatus.Connected) {
                delay(15_000L)
                if (!isActive || _connectionStatus.value !is ConnectionStatus.Connected) break

                val ws = webSocket
                if (ws == null) {
                    handleConnectionLost("WebSocket instance is null")
                    break
                }

                val pingId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<Long>()
                pendingPings[pingId] = deferred
                val sent = ws.send(JSONObject().apply {
                    put("action", "ping")
                    put("ping_id", pingId)
                    put("timestamp", System.currentTimeMillis())
                }.toString())

                if (!sent) {
                    pendingPings.remove(pingId)
                    handleConnectionLost("Failed to send ping heartbeat")
                    break
                }

                try {
                    withTimeout(5_000L) { deferred.await() }
                } catch (e: Exception) {
                    pendingPings.remove(pingId)
                    Log.w(TAG, "Heartbeat ping timeout after 5s: ${e.message}")
                    handleConnectionLost("Heartbeat ping timeout")
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleConnectionLost(reason: String) {
        stopHeartbeat()
        failAllPendingExecutions("Connection lost: $reason")
        if (_connectionStatus.value !is ConnectionStatus.AuthFailed && !isManualDisconnect) {
            _connectionStatus.value = ConnectionStatus.Disconnected
            scheduleReconnect()
        }
    }

    private suspend fun doConnect(authToken: String) = connectionMutex.withLock {
        if (_connectionStatus.value is ConnectionStatus.Connected && webSocket != null) {
            return@withLock
        }
        stopHeartbeat()
        val currentGen = connectionGeneration.incrementAndGet()
        failAllPendingExecutions("Reconnecting to Termux bridge...")

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
                if (connectionGeneration.get() != currentGen) return
                Log.d(TAG, "WebSocket connection opened to $url (gen $currentGen)")
                // Send auth handshake immediately
                val authMsg = JSONObject().apply {
                    put("action", "auth")
                    put("token", authToken)
                }
                ws.send(authMsg.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (connectionGeneration.get() != currentGen) return
                handleIncomingMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (connectionGeneration.get() != currentGen) return
                Log.w(TAG, "WebSocket closed ($code): $reason")
                stopHeartbeat()
                failAllPendingExecutions("Connection to Termux closed ($reason)")
                if (_connectionStatus.value !is ConnectionStatus.AuthFailed) {
                    _connectionStatus.value = ConnectionStatus.Disconnected
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (connectionGeneration.get() != currentGen) return
                Log.w(TAG, "WebSocket error: ${t.message}")
                stopHeartbeat()
                failAllPendingExecutions("Connection error: ${t.localizedMessage}")
                if (_connectionStatus.value !is ConnectionStatus.AuthFailed) {
                    _connectionStatus.value = ConnectionStatus.Error(t.localizedMessage ?: "Connection to Termux failed")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isAutoReconnectEnabled || isManualDisconnect) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (isAutoReconnectEnabled && !isManualDisconnect && _connectionStatus.value !is ConnectionStatus.Connected) {
                Log.d(TAG, "Attempting to reconnect to Termux...")
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
        executionBuffers.clear()
        executionChunkCallbacks.clear()
        pendingPings.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pendingPings.clear()
        pendingFileReads.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pendingFileReads.clear()
    }

    private fun parseSystemInfo(json: JSONObject): TermuxSystemInfo {
        val sysJson = json.optJSONObject("system") ?: json.optJSONObject("data") ?: json
        val batteryJson = sysJson.optJSONObject("battery")
        val isCharging = if (batteryJson != null && batteryJson.has("plugged")) {
            batteryJson.optString("plugged") != "UNPLUGGED"
        } else {
            false
        }
        return TermuxSystemInfo(
            os = sysJson.optString("os", "Linux"),
            cwd = json.optString("cwd", sysJson.optString("cwd", "~")),
            batteryPercentage = if (batteryJson != null && batteryJson.has("percentage")) batteryJson.optInt("percentage") else null,
            isCharging = isCharging,
            hasTermuxApi = sysJson.optBoolean("has_termux_api", false),
            hasLlama = sysJson.optBoolean("has_llama", false)
        )
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "auth_ok" -> {
                    val info = parseSystemInfo(json)
                    _connectionStatus.value = ConnectionStatus.Connected(info)
                    // Once connected, cancel any scheduled reconnects and start heartbeat
                    reconnectJob?.cancel()
                    startHeartbeat()
                    Log.i(TAG, "✅ Termux bridge authenticated and connected.")
                }
                "sys_info" -> {
                    val info = parseSystemInfo(json)
                    _connectionStatus.value = ConnectionStatus.Connected(info)
                }
                "pong" -> {
                    val pingId = json.optString("ping_id")
                    if (pingId.isNotEmpty()) {
                        pendingPings.remove(pingId)?.complete(System.currentTimeMillis())
                    }
                }
                "auth_fail" -> {
                    stopHeartbeat()
                    val error = json.optString("error", "Authentication failed: invalid token")
                    isAutoReconnectEnabled = false
                    _connectionStatus.value = ConnectionStatus.AuthFailed(error)
                    failAllPendingExecutions(error)
                    reconnectJob?.cancel()
                }
                "stdout" -> {
                    val execId = json.optString("execution_id")
                    val data = json.optString("data")
                    val buffers = executionBuffers.getOrPut(execId) { ExecutionBuffers() }
                    buffers.stdout.append(data)
                    executionChunkCallbacks[execId]?.invoke(data)
                    terminalOutputEvents.tryEmit(Pair(execId, data))
                }
                "stderr" -> {
                    val execId = json.optString("execution_id")
                    val data = json.optString("data")
                    val buffers = executionBuffers.getOrPut(execId) { ExecutionBuffers() }
                    buffers.stderr.append(data)
                    executionChunkCallbacks[execId]?.invoke(data)
                    terminalOutputEvents.tryEmit(Pair(execId, data))
                }
                "completed" -> {
                    val execId = json.optString("execution_id")
                    val exitCode = json.optInt("exit_code", 0)
                    val buffers = executionBuffers[execId]
                    val out = buffers?.stdout?.build() ?: ""
                    val err = buffers?.stderr?.build() ?: ""
                    val deferred = pendingExecutions[execId]
                    if (deferred != null) {
                        deferred.complete(
                            ToolResult(
                                toolCallId = execId,
                                command = "",
                                stdout = out,
                                stderr = err,
                                exitCode = exitCode,
                                isError = exitCode != 0
                            )
                        )
                        pendingExecutions.remove(execId)
                        executionBuffers.remove(execId)
                        executionChunkCallbacks.remove(execId)
                    }
                }
                "file_content" -> {
                    val reqId = json.optString("request_id")
                    val path = json.optString("path")
                    val content = json.optString("content")
                    val deferred = if (reqId.isNotEmpty()) pendingFileReads.remove(reqId) else pendingFileReads.remove(path)
                    deferred?.complete(content)
                }
                "file_base64" -> {
                    val reqId = json.optString("request_id")
                    val path = json.optString("path")
                    val b64 = json.optString("data")
                    val deferred = if (reqId.isNotEmpty()) pendingFileReads.remove(reqId) else pendingFileReads.remove(path)
                    deferred?.complete(b64)
                }
                "error" -> {
                    val execId = json.optString("execution_id")
                    val err = json.optString("error")
                    val reqId = json.optString("request_id")
                    val path = json.optString("path")
                    if (reqId.isNotEmpty()) {
                        pendingFileReads.remove(reqId)?.completeExceptionally(Exception(err))
                    } else if (path.isNotEmpty()) {
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
                        executionBuffers.remove(execId)
                        executionChunkCallbacks.remove(execId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WebSocket message: ${e.message}", e)
        }
    }

    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = 90_000L,
        onChunk: ((String) -> Unit)? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val assessment = CommandSecurityFilter.analyze(command)
        if (assessment.isBlocked) {
            return@withContext ToolResult(
                toolCallId = UUID.randomUUID().toString(), command = command,
                stderr = assessment.reason, exitCode = -1, isError = true
            )
        }
        val execId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ToolResult>()
        pendingExecutions[execId] = deferred
        executionBuffers[execId] = ExecutionBuffers()
        if (onChunk != null) {
            executionChunkCallbacks[execId] = onChunk
        }

        val msg = JSONObject().apply {
            put("action", "execute")
            put("command", command)
            put("execution_id", execId)
            put("timeout_ms", timeoutMs.coerceIn(1L, 3_600_000L))
        }

        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            executionChunkCallbacks.remove(execId)
            pendingExecutions.remove(execId)
            executionBuffers.remove(execId)
            return@withContext ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "No active connection to Termux (ws://$host:$port). Run 'amc start' in Termux.",
                exitCode = -1,
                isError = true
            )
        }

        val sent = ws.send(msg.toString())
        if (!sent) {
            executionChunkCallbacks.remove(execId)
            pendingExecutions.remove(execId)
            executionBuffers.remove(execId)
            return@withContext ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "Could not send command (socket closed).",
                exitCode = -1,
                isError = true
            )
        }

        val result = try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            interruptCurrent(execId)
            ToolResult(
                toolCallId = execId,
                command = command,
                stderr = "Timeout: command did not respond within ${timeoutMs / 1000}s.",
                exitCode = 124,
                isError = true
            )
        } catch (e: CancellationException) {
            interruptCurrent(execId)
            throw e
        } finally {
            executionChunkCallbacks.remove(execId)
            pendingExecutions.remove(execId)
            executionBuffers.remove(execId)
        }

        result.copy(command = command)
    }

    fun interruptCurrent(executionId: String? = null) {
        val msg = JSONObject().apply {
            put("action", "interrupt")
            executionId?.let { put("execution_id", it) }
        }
        webSocket?.send(msg.toString())
    }

    suspend fun readFile(path: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingFileReads[reqId] = deferred
        val msg = JSONObject().apply {
            put("action", "read_file")
            put("request_id", reqId)
            put("path", path)
        }
        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            pendingFileReads.remove(reqId)
            throw IllegalStateException("No connection to Termux")
        }
        ws.send(msg.toString())
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingFileReads.remove(reqId)
        }
    }

    suspend fun readFileBase64(path: String, timeoutMs: Long = 15_000L): String = withContext(Dispatchers.IO) {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingFileReads[reqId] = deferred
        val msg = JSONObject().apply {
            put("action", "read_file_base64")
            put("request_id", reqId)
            put("path", path)
        }
        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            pendingFileReads.remove(reqId)
            throw IllegalStateException("No connection to Termux")
        }
        ws.send(msg.toString())
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingFileReads.remove(reqId)
        }
    }

    suspend fun awaitConnected(timeoutMs: Long = 10_000L): Boolean {
        if (_connectionStatus.value is ConnectionStatus.Connected) return true
        val status = withTimeoutOrNull(timeoutMs) {
            connectionStatus.first { it is ConnectionStatus.Connected || it is ConnectionStatus.AuthFailed }
        }
        return status is ConnectionStatus.Connected
    }

    suspend fun getCrontab(): String {
        val res = executeCommand("crontab -l")
        if (res.exitCode == 0) return res.stdout
        if (res.exitCode == 1 && (res.stderr.contains("no crontab", ignoreCase = true) || res.stdout.contains("no crontab", ignoreCase = true))) {
            return ""
        }
        throw java.io.IOException(if (res.stderr.isNotEmpty()) res.stderr else "Failed to read crontab (exit ${res.exitCode})")
    }

    suspend fun setCrontab(crontabContent: String): ToolResult {
        val escaped = crontabContent.replace("'", "'\\''")
        return executeCommand("echo '$escaped' | crontab -")
    }

    fun requestSysInfo() {
        val ws = webSocket
        if (ws != null && _connectionStatus.value is ConnectionStatus.Connected) {
            ws.send(JSONObject().apply { put("action", "sys_info") }.toString())
        }
    }

    suspend fun checkCronStatus(): String {
        val res = executeCommand("command -v crond >/dev/null && (pgrep -x crond >/dev/null) && echo 'RUNNING' || (command -v crond >/dev/null && echo 'STOPPED' || echo 'NOT_INSTALLED')")
        return res.stdout.trim().ifEmpty { "UNKNOWN" }
    }

    fun disconnect() {
        isManualDisconnect = true
        isAutoReconnectEnabled = false
        stopHeartbeat()
        reconnectJob?.cancel()
        failAllPendingExecutions("Client disconnected")
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
}
