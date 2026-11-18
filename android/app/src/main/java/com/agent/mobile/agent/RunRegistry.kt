package com.agent.mobile.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Every agent run in this process (chat, background worker, Telegram, sub-agent) registers here
 * while it works. The emergency brake (phone control Stop, the Stop action of the AMC
 * notification, /stop in Telegram, the chat's stop button) reaches every unattended run through
 * [stopAll], also runs nobody watches.
 */
object RunRegistry {

    data class ActiveRun(
        val runId: String,
        val title: String,
        val mode: RunMode,
        val trigger: String,
        val sessionId: String?,
        val unattended: Boolean,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val stops = ConcurrentHashMap<String, () -> Unit>()
    private val _active = MutableStateFlow<List<ActiveRun>>(emptyList())
    val active: StateFlow<List<ActiveRun>> = _active.asStateFlow()

    fun register(run: ActiveRun, stop: () -> Unit) {
        stops[run.runId] = stop
        _active.update { list -> list.filterNot { it.runId == run.runId } + run }
    }

    fun unregister(runId: String) {
        stops.remove(runId)
        _active.update { list -> list.filterNot { it.runId == runId } }
    }

    /** Stops one run. Returns false when it is not running. */
    fun stop(runId: String): Boolean {
        val stop = stops[runId] ?: return false
        runCatching { stop() }
        return true
    }

    /**
     * Stops every registered run except [exceptRunId] (the caller stops itself). With
     * [unattendedOnly] only runs in unattended mode are stopped. Returns how many were asked to stop.
     */
    fun stopAll(exceptRunId: String? = null, unattendedOnly: Boolean = false): Int {
        val targets = _active.value.filter { it.runId != exceptRunId && (!unattendedOnly || it.unattended) }
        targets.forEach { stop(it.runId) }
        return targets.size
    }
}
