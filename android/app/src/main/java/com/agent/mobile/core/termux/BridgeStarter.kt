package com.agent.mobile.core.termux

import android.content.Context
import android.util.Log
import com.agent.mobile.data.model.ConnectionStatus
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.delay

/**
 * Brings the Termux bridge up when it is not reachable: starts it through RUN_COMMAND (when
 * AMC may do that) and reconnects right away instead of waiting for the reconnect backoff.
 * Used by the app, background tasks and the Telegram channel.
 */
class BridgeStarter(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val appContext = context.applicationContext

    @Volatile
    private var lastStartAt = 0L

    /** True when AMC can start the bridge itself. */
    fun canAutoStart(): Boolean = TermuxRunCommand.availability(appContext) == TermuxRunCommand.Availability.READY

    /**
     * Starts the bridge if it seems down (at most every [MIN_INTERVAL_MS]) and reconnects.
     * Returns true when a start request was sent to Termux.
     */
    fun kick(bridge: TermuxBridgeClient): Boolean {
        val status = bridge.connectionStatus.value
        if (status is ConnectionStatus.Connected || status is ConnectionStatus.AuthFailed) return false
        val now = clock()
        if (now - lastStartAt < MIN_INTERVAL_MS || !canAutoStart()) return false
        lastStartAt = now
        val sent = TermuxRunCommand.start(appContext, TermuxRunCommand.START_BRIDGE_SCRIPT, "AMC: start bridge")
        if (sent) Log.i(TAG, "Asked Termux to start the AMC bridge")
        return sent
    }

    /**
     * Makes sure [bridge] is connected, starting the bridge in Termux if needed.
     * Returns whether the bridge is connected within [timeoutMs].
     */
    suspend fun ensureConnected(bridge: TermuxBridgeClient, token: String, timeoutMs: Long = 15_000L): Boolean {
        if (bridge.connectionStatus.value is ConnectionStatus.Connected) return true
        if (bridge.connectionStatus.value is ConnectionStatus.Disconnected) bridge.connect(token)
        // Give an already running bridge a moment before starting anything.
        if (bridge.awaitConnected(2_000L)) return true
        if (kick(bridge)) {
            delay(1_500L)
            bridge.reconnectIfDisconnected(force = true)
        }
        return bridge.awaitConnected((timeoutMs - 2_000L).coerceAtLeast(1_000L))
    }

    companion object {
        private const val TAG = "BridgeStarter"
        const val MIN_INTERVAL_MS = 30_000L
    }
}
