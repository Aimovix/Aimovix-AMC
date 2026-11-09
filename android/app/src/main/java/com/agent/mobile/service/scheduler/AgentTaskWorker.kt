package com.agent.mobile.service.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agent.mobile.AgentRuntime
import com.agent.mobile.agent.AgentPrompts
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.service.AgentNotifier
import kotlinx.coroutines.CancellationException

/**
 * Runs the autonomous agent in the background without the UI: scheduled agent tasks and the
 * periodic heartbeat. Each run gets its own chat session (visible in the session drawer), its
 * own bridge connection and a hard time limit.
 */
class AgentTaskWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AgentTaskWorker"
        const val KEY_KIND = "agent_task_kind"
        const val KEY_TITLE = "agent_task_title"
        const val KEY_PROMPT = "agent_task_prompt"
        const val KIND_TASK = "task"
        const val KIND_HEARTBEAT = "heartbeat"
    }

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: KIND_TASK
        val runtime = try {
            AgentRuntime.get(appContext)
        } catch (e: IllegalStateException) {
            // Encrypted storage is locked (Direct Boot). Try again later.
            Log.w(TAG, "Secure storage unavailable: ${e.message}")
            return Result.retry()
        }
        val prefs = runtime.preferenceManager
        // Never act in the background before the user has accepted the terms of use.
        if (prefs.loadAcceptedLegalVersion() < com.agent.mobile.ui.legal.LegalTexts.VERSION) return Result.success()

        val title: String
        val prompt: String
        if (kind == KIND_HEARTBEAT) {
            if (!prefs.loadHeartbeatEnabled()) return Result.success()
            title = "💓 Heartbeat"
            prompt = AgentPrompts.heartbeatPrompt(prefs.loadHeartbeatChecklist())
        } else {
            title = "⏰ " + (inputData.getString(KEY_TITLE) ?: "Scheduled task")
            prompt = inputData.getString(KEY_PROMPT).orEmpty()
            if (prompt.isBlank()) return Result.failure()
        }

        val token = prefs.loadAuthToken()
        val bridge = TermuxBridgeClient(token = token)
        bridge.attachContext(appContext)
        val engine = AutonomousAgentEngine(
            bridgeClient = bridge,
            preferenceManager = prefs,
            chatRepository = runtime.chatRepository,
            memoryStore = runtime.memoryStore,
            platform = runtime.platform,
            headless = true,
            personaStore = runtime.personaStore,
            phone = runtime.phoneController
        )
        return try {
            if (!runtime.bridgeStarter.ensureConnected(bridge, token, 20_000L)) {
                Log.w(TAG, "Termux bridge not reachable for '$title' (attempt $runAttemptCount)")
                // The agent can still work without Termux (web, memory, notifications); continue.
            }
            val result = engine.runUnattended(title, prompt)

            val isQuietHeartbeat = kind == KIND_HEARTBEAT && result.success &&
                result.summary.trim().trimEnd('.').equals(AgentPrompts.HEARTBEAT_OK, ignoreCase = true)
            if (isQuietHeartbeat) {
                // Nothing to report: don't clutter the session list.
                result.sessionId?.let { runtime.chatRepository.deleteSession(it) }
            } else {
                val body = buildString {
                    append(result.summary.take(1_500))
                    if (result.skippedApprovals.isNotEmpty()) {
                        append("\n\nNeeds your approval: ")
                        append(result.skippedApprovals.take(3).joinToString("; ") { it.take(80) })
                    }
                }
                AgentNotifier.notify(appContext, if (result.success) title else "⚠️ $title", body)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background agent run failed: ${e.message}", e)
            if (runAttemptCount < 2) Result.retry() else {
                AgentNotifier.notify(appContext, "⚠️ $title", e.localizedMessage ?: "Background run failed")
                Result.failure()
            }
        } finally {
            bridge.disconnect()
            engine.close()
        }
    }
}
