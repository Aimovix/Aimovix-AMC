package com.agent.mobile.data.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
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
import java.util.concurrent.ThreadLocalRandom

class TermuxBridgeClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 8765,
    private var token: String = "",
    private var context: Context? = null
) {
    companion object {
        private const val TAG = "TermuxBridgeClient"
        const val INITIAL_BACKOFF_MS = 1000L
        const val BACKOFF_MULTIPLIER = 2.0
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_JITTER_MS = 1000L
        const val PING_TIMEOUT_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        /** Time the bridge has to answer the auth handshake after the socket opened. */
        const val AUTH_TIMEOUT_MS = 8_000L

        /** Wait between retries when an old daemon is still busy with another command. */
        private const val BUSY_RETRY_MS = 500L
        /** Characters per write_file frame: even 4-byte UTF-8 plus JSON escaping stays far below 10 MiB. */
        const val WRITE_CHUNK_CHARS = 256 * 1024
        const val MAX_WRITE_BYTES = 64L * 1024 * 1024
        const val WRITE_APPEND_CAPABILITY = "write_append"
        val LLM_ACTIONS = setOf("llm_status", "llm_install", "llm_start", "llm_stop")

        /** Splits [content] into transfer chunks without cutting a surrogate pair. */
        fun chunkForTransfer(content: String, chunkChars: Int = WRITE_CHUNK_CHARS): List<String> {
            if (content.length <= chunkChars) return listOf(content)
            val out = mutableListOf<String>()
            var start = 0
            while (start < content.length) {
                var end = minOf(start + chunkChars, content.length)
                if (end < content.length && Character.isHighSurrogate(content[end - 1])) end--
                out += content.substring(start, end)
                start = end
            }
            return out
        }

        @Volatile
        private var singletonInstance: TermuxBridgeClient? = null

        fun getInstance(context: Context, token: String = ""): TermuxBridgeClient {
            val existing = singletonInstance
            if (existing != null) {
                if (token.isNotEmpty() && existing.token.isEmpty()) {
                    existing.setToken(token)
                }
                return existing
            }
            return synchronized(this) {
                singletonInstance ?: TermuxBridgeClient(
                    token = token,
                    context = context.applicationContext
                ).also { singletonInstance = it }
            }
        }

        internal fun resetInstanceForTesting() {
            synchronized(this) {
                singletonInstance?.disconnect()
                singletonInstance = null
            }
        }

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

    /**
     * True only while a socket is being opened or authenticated. Callers set the status to
     * Connecting before calling doConnect, so the status alone can't tell whether an attempt is
     * really running; relying on it left the client stuck in "Connecting" forever after a
     * failed attempt (e.g. when the bridge was started after the app, or after a wrong token).
     */
    @Volatile
    private var connectAttemptInFlight = false
    private var authWatchdog: Job? = null
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
    private val pendingFileWrites = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingLlmActions = ConcurrentHashMap<String, CompletableDeferred<LlmActionResult>>()

    /** Features the connected daemon announced in auth_ok (empty for daemons before v1.7.1). */
    @Volatile
    var serverCapabilities: Set<String> = emptySet()
        private set

    // Connection generation to prevent stale callbacks
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0)

    // Mutex to strictly serialize connection attempts
    private val connectionMutex = Mutex()

    // Auto-reconnect worker, exponential backoff, and heartbeat state
    private var isAutoReconnectEnabled = true
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var isManualDisconnect = false
    private var reconnectAttempts = 0

    private val powerManager: PowerManager?
        get() = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var pingWakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        if (context != null) {
            registerNetworkCallback()
        }
    }

    fun attachContext(ctx: Context) {
        if (this.context == null) {
            this.context = ctx.applicationContext
            registerNetworkCallback()
        }
    }

    private fun acquirePingWakeLock(timeoutMs: Long = PING_TIMEOUT_MS + 2000L) {
        try {
            if (pingWakeLock == null) {
                pingWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AMC:BridgeClient:Ping")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (pingWakeLock?.isHeld == false) {
                pingWakeLock?.acquire(timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire ping wake lock: ${e.message}")
        }
    }

    private fun releasePingWakeLock() {
        try {
            if (pingWakeLock?.isHeld == true) {
                pingWakeLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun registerNetworkCallback() {
        val ctx = context ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network interface became available")
                if (isAutoReconnectEnabled && !isManualDisconnect &&
                    _connectionStatus.value !is ConnectionStatus.Connected &&
                    _connectionStatus.value !is ConnectionStatus.Connecting
                ) {
                    Log.i(TAG, "Network available, triggering immediate reconnect")
                    reconnectIfDisconnected(force = false)
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network interface lost")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val ctx = context ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    fun getReconnectAttempts(): Int = reconnectAttempts

    fun calculateBackoffDelay(attempt: Int): Long {
        val base = (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt.coerceAtLeast(0).toDouble())).toLong()
        val capped = minOf(base, MAX_BACKOFF_MS)
        val jitter = if (MAX_JITTER_MS > 0) ThreadLocalRandom.current().nextLong(0, MAX_JITTER_MS + 1) else 0L
        return capped + jitter
    }

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
        this.reconnectAttempts = 0

        if (_connectionStatus.value is ConnectionStatus.Connected) {
            return
        }

        _connectionStatus.value = ConnectionStatus.Connecting
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
        this.reconnectAttempts = 0
        reconnectJob?.cancel()
        scope.launch {
            try {
                webSocket?.cancel()
            } catch (e: Exception) {
                // ignore
            }
            webSocket = null
            doConnect(authToken, force = true)
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
                        acquirePingWakeLock()
                        val sent = ws.send(JSONObject().apply {
                            put("action", "ping")
                            put("ping_id", pingId)
                            put("timestamp", System.currentTimeMillis())
                        }.toString())

                        if (!sent) {
                            releasePingWakeLock()
                            pendingPings.remove(pingId)
                            forceReconnect()
                        } else {
                            try {
                                withTimeout(PING_TIMEOUT_MS) { deferred.await() }
                                requestSysInfo()
                            } catch (e: Exception) {
                                pendingPings.remove(pingId)
                                forceReconnect()
                            } finally {
                                releasePingWakeLock()
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
        _connectionStatus.value = ConnectionStatus.Connecting
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
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive || _connectionStatus.value !is ConnectionStatus.Connected) break

                val ws = webSocket
                if (ws == null) {
                    handleConnectionLost("WebSocket instance is null")
                    break
                }

                val pingId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<Long>()
                pendingPings[pingId] = deferred
                acquirePingWakeLock()
                val sent = ws.send(JSONObject().apply {
                    put("action", "ping")
                    put("ping_id", pingId)
                    put("timestamp", System.currentTimeMillis())
                }.toString())

                if (!sent) {
                    releasePingWakeLock()
                    pendingPings.remove(pingId)
                    handleConnectionLost("Failed to send ping heartbeat")
                    break
                }

                try {
                    withTimeout(PING_TIMEOUT_MS) { deferred.await() }
                } catch (e: Exception) {
                    pendingPings.remove(pingId)
                    Log.w(TAG, "Heartbeat ping timeout after ${PING_TIMEOUT_MS / 1000}s: ${e.message}")
                    handleConnectionLost("Heartbeat ping timeout")
                    break
                } finally {
                    releasePingWakeLock()
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

    private suspend fun doConnect(authToken: String, force: Boolean = false) = connectionMutex.withLock {
        val currentStatus = _connectionStatus.value
        if (!force && webSocket != null && (currentStatus is ConnectionStatus.Connected || connectAttemptInFlight)) {
            Log.d(TAG, "Connection attempt already in progress or connected ($currentStatus), skipping doConnect")
            return@withLock
        }
        stopHeartbeat()
        authWatchdog?.cancel()
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
        connectAttemptInFlight = true

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
                // A bridge that accepts the socket but never answers would otherwise keep us in
                // "Connecting" forever.
                authWatchdog?.cancel()
                authWatchdog = scope.launch {
                    delay(AUTH_TIMEOUT_MS)
                    if (connectionGeneration.get() == currentGen && connectAttemptInFlight &&
                        _connectionStatus.value !is ConnectionStatus.Connected
                    ) {
                        Log.w(TAG, "Bridge did not answer the auth handshake in time")
                        connectionGeneration.incrementAndGet()
                        connectAttemptInFlight = false
                        if (webSocket === ws) webSocket = null
                        ws.cancel()
                        failAllPendingExecutions("The bridge did not answer the login")
                        _connectionStatus.value = ConnectionStatus.Error(
                            "The bridge accepted the connection but did not answer the login. Restart it with 'amc restart'."
                        )
                        scheduleReconnect()
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (connectionGeneration.get() != currentGen) return
                handleIncomingMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (connectionGeneration.get() != currentGen) return
                Log.w(TAG, "WebSocket closed ($code): $reason")
                connectAttemptInFlight = false
                authWatchdog?.cancel()
                if (webSocket === ws) webSocket = null
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
                connectAttemptInFlight = false
                authWatchdog?.cancel()
                if (webSocket === ws) webSocket = null
                stopHeartbeat()
                failAllPendingExecutions("Connection error: ${t.localizedMessage}")
                if (_connectionStatus.value !is ConnectionStatus.AuthFailed) {
                    _connectionStatus.value = ConnectionStatus.Error(describeConnectFailure(t))
                    scheduleReconnect()
                }
            }
        })
    }

    private fun describeConnectFailure(t: Throwable): String = when {
        t is java.net.ConnectException || t.message.orEmpty().contains("Failed to connect", ignoreCase = true) ->
            "The AMC bridge is not running in Termux (nothing listens on $host:$port). Start it with 'amc start'."
        t is java.net.SocketTimeoutException -> "Timed out connecting to the AMC bridge."
        else -> t.localizedMessage ?: "Connection to Termux failed"
    }

    private fun scheduleReconnect() {
        if (!isAutoReconnectEnabled || isManualDisconnect) return

        reconnectJob?.cancel()
        val attempt = reconnectAttempts++
        val delayMs = calculateBackoffDelay(attempt)
        Log.d(TAG, "Scheduling reconnect attempt #$attempt in ${delayMs}ms")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isAutoReconnectEnabled && !isManualDisconnect && _connectionStatus.value !is ConnectionStatus.Connected) {
                Log.d(TAG, "Attempting to reconnect to Termux (attempt #$attempt)...")
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
        pendingLlmActions.values.forEach { it.complete(LlmActionResult(false, reason, emptyMap())) }
        pendingLlmActions.clear()
        pendingPings.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pendingPings.clear()
        pendingFileReads.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pendingFileReads.clear()
        pendingFileWrites.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pendingFileWrites.clear()
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
                    connectAttemptInFlight = false
                    authWatchdog?.cancel()
                    reconnectAttempts = 0
                    val info = parseSystemInfo(json)
                    serverCapabilities = json.optJSONArray("capabilities")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toSet()
                    } ?: emptySet()
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
                    connectAttemptInFlight = false
                    authWatchdog?.cancel()
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
                "interrupted" -> {
                    val execId = json.optString("execution_id")
                    Log.i(TAG, "Process interrupted by bridge daemon: execution_id=$execId")
                    terminalOutputEvents.tryEmit(Pair(execId, "\n[Execution interrupted]\n"))
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
                "file_written" -> {
                    val reqId = json.optString("request_id")
                    val path = json.optString("path")
                    val success = json.optBoolean("success", true)
                    val deferred = if (reqId.isNotEmpty()) pendingFileWrites.remove(reqId) else pendingFileWrites.remove(path)
                    deferred?.complete(success)
                }
                "llm_result" -> {
                    val reqId = json.optString("request_id")
                    val status = json.optJSONObject("status")
                    val map = status?.keys()?.asSequence()?.associateWith { status.optString(it) } ?: emptyMap()
                    pendingLlmActions.remove(reqId)?.complete(
                        LlmActionResult(
                            ok = json.optBoolean("ok", false),
                            output = json.optString("output"),
                            status = map
                        )
                    )
                }
                "error" -> {
                    val execId = json.optString("execution_id")
                    val err = json.optString("error")
                    val reqId = json.optString("request_id")
                    val path = json.optString("path")
                    if (reqId.isNotEmpty()) {
                        pendingFileReads.remove(reqId)?.completeExceptionally(Exception(err))
                        pendingFileWrites.remove(reqId)?.completeExceptionally(Exception(err))
                    } else if (path.isNotEmpty()) {
                        pendingFileReads.remove(path)?.completeExceptionally(Exception(err))
                        pendingFileWrites.remove(path)?.completeExceptionally(Exception(err))
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
    ): ToolResult {
        // Daemons before v1.7.1 run one command per connection and reject a second one at once.
        // Wait for the running one instead of failing (the agent and the terminal share a client).
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val result = executeOnce(command, remaining.coerceAtLeast(1_000L), onChunk)
            if (!isBusyRejection(result) || remaining <= BUSY_RETRY_MS) return result
            delay(BUSY_RETRY_MS)
        }
    }

    private fun isBusyRejection(result: ToolResult): Boolean =
        result.isError && result.stdout.isEmpty() && result.stderr.contains("already running on this connection")

    private suspend fun executeOnce(
        command: String,
        timeoutMs: Long,
        onChunk: ((String) -> Unit)?
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

        val execWakeLock = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AMC:BridgeClient:Exec:$execId")?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs.coerceAtLeast(1000L) + 5000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire exec wake lock: ${e.message}")
            null
        }

        val msg = JSONObject().apply {
            put("action", "execute")
            put("command", command)
            put("execution_id", execId)
            put("timeout_ms", timeoutMs.coerceIn(1L, 3_600_000L))
        }

        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            try {
                if (execWakeLock?.isHeld == true) execWakeLock.release()
            } catch (e: Exception) {
                // ignore
            }
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
            try {
                if (execWakeLock?.isHeld == true) execWakeLock.release()
            } catch (e: Exception) {
                // ignore
            }
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
            try {
                if (execWakeLock?.isHeld == true) {
                    execWakeLock.release()
                }
            } catch (e: Exception) {
                // ignore
            }
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

    /**
     * Writes [content] to [path]. With [append] the daemon appends on its side (a failed read can
     * never truncate the file). Large contents are sent in chunks below the WebSocket frame limit;
     * the first chunk writes or appends, the rest append.
     */
    suspend fun writeFile(path: String, content: String, timeoutMs: Long = 10_000L, append: Boolean = false): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        require(bytes <= MAX_WRITE_BYTES) { "Content is too large for write_file (${bytes / (1024 * 1024)} MiB, limit ${MAX_WRITE_BYTES / (1024 * 1024)} MiB)." }
        val chunks = chunkForTransfer(content)
        val canAppend = WRITE_APPEND_CAPABILITY in serverCapabilities
        if ((append || chunks.size > 1) && !canAppend) {
            if (chunks.size > 1) throw java.io.IOException("The Termux bridge is too old for large files. Update it with 'amc update'.")
            return appendWithoutServerSupport(path, content, timeoutMs)
        }
        for ((index, chunk) in chunks.withIndex()) {
            val mode = if (index == 0 && !append) "write" else "append"
            if (!sendWrite(path, chunk, if (canAppend) mode else null, timeoutMs)) return false
        }
        return true
    }

    /** Old daemons: append by reading first. Only a missing file counts as empty; any other error aborts. */
    private suspend fun appendWithoutServerSupport(path: String, content: String, timeoutMs: Long): Boolean {
        val existing = try {
            readFile(path, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("No such file", ignoreCase = true) == true) "" else
                throw java.io.IOException("Could not read $path before appending (nothing was written): ${e.message}")
        }
        return sendWrite(path, existing + content, null, timeoutMs)
    }

    private suspend fun sendWrite(path: String, content: String, mode: String?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingFileWrites[reqId] = deferred
        val msg = JSONObject().apply {
            put("action", "write_file")
            put("request_id", reqId)
            put("path", path)
            put("content", content)
            mode?.let { put("mode", it) }
        }
        val ws = webSocket
        if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
            pendingFileWrites.remove(reqId)
            throw IllegalStateException("No connection to Termux")
        }
        val sent = ws.send(msg.toString())
        if (!sent) {
            pendingFileWrites.remove(reqId)
            throw java.io.IOException("Failed to send write_file message (socket closed)")
        }
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingFileWrites.remove(reqId)
        }
    }

    /** Runs a local model action (status, install, start, stop) through the bridge. */
    suspend fun llmAction(action: String, params: Map<String, String> = emptyMap(), timeoutMs: Long = 120_000L): LlmActionResult =
        withContext(Dispatchers.IO) {
            require(action in LLM_ACTIONS) { "Unknown llm action $action" }
            val ws = webSocket
            if (ws == null || _connectionStatus.value !is ConnectionStatus.Connected) {
                return@withContext LlmActionResult(false, "No connection to Termux.", emptyMap())
            }
            val reqId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<LlmActionResult>()
            pendingLlmActions[reqId] = deferred
            val msg = JSONObject().apply {
                put("action", action)
                put("request_id", reqId)
                params.forEach { (k, v) -> put(k, v) }
            }
            if (!ws.send(msg.toString())) {
                pendingLlmActions.remove(reqId)
                return@withContext LlmActionResult(false, "Could not send $action (socket closed).", emptyMap())
            }
            try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
                    ?: LlmActionResult(false, "$action did not answer within ${timeoutMs / 1000} s.", emptyMap())
            } finally {
                pendingLlmActions.remove(reqId)
            }
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
        if (!ws.send(msg.toString())) {
            pendingFileReads.remove(reqId)
            throw java.io.IOException("Failed to send read_file message (socket closed)")
        }
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
        if (!ws.send(msg.toString())) {
            pendingFileReads.remove(reqId)
            throw java.io.IOException("Failed to send read_file_base64 message (socket closed)")
        }
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
        connectAttemptInFlight = false
        authWatchdog?.cancel()
        isManualDisconnect = true
        isAutoReconnectEnabled = false
        stopHeartbeat()
        reconnectJob?.cancel()
        releasePingWakeLock()
        unregisterNetworkCallback()
        failAllPendingExecutions("Client disconnected")
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
}

/** Answer of an llm_* bridge action. [status] holds the script's "status.KEY=VALUE" lines. */
data class LlmActionResult(val ok: Boolean, val output: String, val status: Map<String, String>)
