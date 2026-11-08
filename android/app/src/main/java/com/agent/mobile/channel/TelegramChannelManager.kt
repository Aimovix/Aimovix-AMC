package com.agent.mobile.channel

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.agent.mobile.AgentRuntime
import com.agent.mobile.channel.telegram.PairedUser
import com.agent.mobile.channel.telegram.TelegramBotApi
import com.agent.mobile.channel.telegram.TelegramGateway
import com.agent.mobile.channel.telegram.TelegramStateStore
import com.agent.mobile.service.AgentNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Telegram gateway for the whole process. [ChannelGatewayService] starts and stops it;
 * the settings screen reads [status], creates pairing codes and removes paired accounts.
 */
class TelegramChannelManager(private val runtime: AgentRuntime) {

    private val prefs = runtime.preferenceManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val stateStore = TelegramStateStore(load = { prefs.loadTelegramState() }, save = { prefs.saveTelegramState(it) })

    private val _status = MutableStateFlow<TelegramGateway.Status>(TelegramGateway.Status.Stopped)
    val status: StateFlow<TelegramGateway.Status> = _status.asStateFlow()

    private val _state = MutableStateFlow(stateStore.get())
    /** Paired users and pairing code, refreshed whenever the gateway or the settings change them. */
    val state: StateFlow<com.agent.mobile.channel.telegram.TelegramState> = _state.asStateFlow()

    private var gateway: TelegramGateway? = null
    private var statusJob: Job? = null
    private var activeTurns = 0
    private val wakeLock: PowerManager.WakeLock? = try {
        (runtime.appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AMC:TelegramTurn")
            ?.apply { setReferenceCounted(false) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun start() {
        if (gateway != null) return
        val token = prefs.loadTelegramBotToken()
        if (token.isBlank()) {
            _status.value = TelegramGateway.Status.Error("No bot token configured.", fatal = true)
            return
        }
        val api = TelegramBotApi(token)
        val g = TelegramGateway(
            api = api,
            store = stateStore,
            agentFactory = { EngineChannelAgent(runtime, "Telegram") },
            scope = scope,
            onPaired = ::onPaired,
            onTurnActive = ::onTurnActive
        )
        gateway = g
        statusJob = scope.launch { g.status.collect { _status.value = it; refreshState() } }
        g.start()
    }

    @Synchronized
    fun stop() {
        val g = gateway ?: return
        gateway = null
        statusJob?.cancel()
        statusJob = null
        _status.value = TelegramGateway.Status.Stopped
        scope.launch { g.stop() }
        releaseWakeLock()
    }

    /** Applies a new bot token while the channel is running. */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    /** Creates a fresh one-time pairing code (valid 15 minutes). */
    fun createPairingCode(): String {
        val code = TelegramStateStore.newPairingCode()
        stateStore.update {
            it.copy(
                pairingCode = code,
                pairingExpiresAt = System.currentTimeMillis() + TelegramStateStore.PAIRING_TTL_MS,
                pairingAttempts = 0
            )
        }
        refreshState()
        return code
    }

    fun unpair(userId: Long) {
        stateStore.update { st -> st.copy(paired = st.paired.filterNot { it.id == userId }) }
        refreshState()
    }

    /** Forgets paired accounts, sessions and offset (used when the bot token changes). */
    fun resetState() {
        stateStore.update { com.agent.mobile.channel.telegram.TelegramState() }
        refreshState()
    }

    fun refreshState() {
        _state.value = stateStore.get()
    }

    /** Checks a bot token with Telegram's getMe. */
    suspend fun verifyToken(token: String): Result<TelegramBotApi.BotInfo> =
        runCatching { TelegramBotApi(token.trim()).getMe() }

    private fun onPaired(user: PairedUser) {
        refreshState()
        AgentNotifier.notify(
            runtime.appContext,
            "Telegram account paired",
            "${user.name} (id ${user.id}) can now control AMC from Telegram. Remove it in Settings → Channels if this wasn't you."
        )
    }

    @Synchronized
    private fun onTurnActive(active: Boolean) {
        activeTurns = (activeTurns + if (active) 1 else -1).coerceAtLeast(0)
        try {
            if (activeTurns > 0) {
                if (wakeLock?.isHeld == false) wakeLock.acquire(60 * 60_000L)
            } else {
                releaseWakeLock()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock error: ${e.message}")
        }
        refreshState()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock release failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TelegramChannel"
    }
}
