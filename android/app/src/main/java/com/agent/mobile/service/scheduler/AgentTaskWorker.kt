package com.agent.mobile.service.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agent.mobile.AgentRuntime
import com.agent.mobile.agent.AgentPrompts
import com.agent.mobile.agent.ApprovalFingerprint
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.agent.ResumeRequest
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.data.storage.db.entity.PendingApprovalEntity
import com.agent.mobile.service.AgentNotifier
import kotlinx.coroutines.CancellationException

/**
 * Runs the autonomous agent in the background without the UI: scheduled agent tasks, the
 * periodic heartbeat, and the runs that continue a task (after a parked approval was answered,
 * after a background slot ran out of budget, or after AMC was restarted mid-run). Each task has
 * its own chat session (visible in the session drawer), its own bridge connection and a hard
 * time limit per run.
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
        const val KEY_APPROVAL_ID = "agent_task_approval_id"
        const val KEY_SESSION_ID = "agent_task_session_id"
        const val KEY_CHAIN = "agent_task_chain"
        const val KEY_ORIGIN_KIND = "agent_task_origin_kind"
        const val KIND_TASK = "task"
        const val KIND_HEARTBEAT = "heartbeat"
        const val KIND_RESUME = "resume"
        const val KIND_CONTINUE = "continue"

        /** A RUNNING checkpoint older than this is not continued after a restart (stale task). */
        private const val RESTART_WINDOW_MS = 6L * 60 * 60_000

        /** Wait before a run that stopped because the AI provider was unreachable tries again. */
        private const val PROVIDER_RETRY_DELAY_MIN = 10L
    }

    /** What this run does: a fresh task, or the continuation of an existing session. */
    private data class Plan(
        val title: String,
        val originKind: String,
        val prompt: String = "",
        val resume: ResumeRequest? = null
    )

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

        val runs = runtime.database.agentQueueDao()
        val runKey = id.toString()
        val plan = planRun(kind, runKey) ?: return Result.success()

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
        engine.approvalQueue = runtime.approvalQueue
        engine.bridgeRecovery = { runtime.bridgeStarter.ensureConnected(bridge, token, 20_000L) }
        val chain = plan.resume?.chain ?: 0
        val checkpoint: suspend (String) -> Unit = { sessionId ->
            runs.upsertRun(
                AgentRunEntity(
                    id = runKey, sessionId = sessionId, title = plan.title, kind = plan.originKind,
                    status = AgentRunEntity.STATUS_RUNNING, chain = chain
                )
            )
        }
        return try {
            if (!runtime.bridgeStarter.ensureConnected(bridge, token, 20_000L)) {
                Log.w(TAG, "Termux bridge not reachable for '${plan.title}' (attempt $runAttemptCount)")
                // The agent can still work without Termux (web, memory, notifications); continue.
            }
            val result = if (plan.resume != null) {
                engine.resumeUnattended(plan.title, plan.resume, checkpoint)
            } else {
                engine.runUnattended(plan.title, plan.prompt, checkpoint)
            }
            val now = System.currentTimeMillis()
            // Out of budget, or the AI provider stayed unreachable: continue the same session later.
            val canContinue = (result.budgetExhausted || result.providerFailed) &&
                result.sessionId != null && chain + 1 < ResumeRequest.MAX_CHAIN
            val status = when {
                result.parkedApprovalId != null -> AgentRunEntity.STATUS_PARKED
                canContinue -> AgentRunEntity.STATUS_CONTINUED
                result.success -> AgentRunEntity.STATUS_DONE
                else -> AgentRunEntity.STATUS_FAILED
            }
            runs.setRunStatus(runKey, status, now)
            runs.pruneRuns(now - 7L * 24 * 60 * 60_000)

            when {
                // The user was asked already (notification / Telegram); the task resumes after the answer.
                result.parkedApprovalId != null -> Unit
                canContinue -> runtime.scheduler.enqueueContinuation(
                    result.sessionId!!, plan.title, chain + 1, plan.originKind,
                    delayMinutes = if (result.providerFailed) PROVIDER_RETRY_DELAY_MIN else 0
                )
                else -> report(runtime, plan, result)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background agent run failed: ${e.message}", e)
            if (runAttemptCount < 2) Result.retry() else {
                runs.setRunStatus(runKey, AgentRunEntity.STATUS_FAILED, System.currentTimeMillis())
                AgentNotifier.notify(appContext, "⚠️ ${plan.title}", e.localizedMessage ?: "Background run failed")
                Result.failure()
            }
        } finally {
            bridge.disconnect()
            engine.close()
        }
    }

    /**
     * Decides what to run. A RUNNING checkpoint with this work id means AMC died during this very
     * run (WorkManager starts interrupted work again): continue that session instead of starting
     * the task from scratch. Returns null when there is nothing to do.
     */
    private suspend fun planRun(kind: String, runKey: String): Plan? {
        val runtime = AgentRuntime.get(appContext)
        val prefs = runtime.preferenceManager
        val runs = runtime.database.agentQueueDao()

        val interrupted = runs.getRun(runKey)?.takeIf {
            it.status == AgentRunEntity.STATUS_RUNNING && it.sessionId.isNotBlank() &&
                System.currentTimeMillis() - it.updatedAt < RESTART_WINDOW_MS
        }
        if (interrupted != null) {
            if (interrupted.chain + 1 >= ResumeRequest.MAX_CHAIN) return null
            return Plan(
                title = interrupted.title,
                originKind = interrupted.kind,
                resume = ResumeRequest(interrupted.sessionId, ResumeRequest.RESTART_NOTE, chain = interrupted.chain + 1)
            )
        }

        return when (kind) {
            KIND_HEARTBEAT -> {
                if (!prefs.loadHeartbeatEnabled()) return null
                Plan("💓 Heartbeat", KIND_HEARTBEAT, AgentPrompts.heartbeatPrompt(prefs.loadHeartbeatChecklist()))
            }
            KIND_RESUME -> {
                val approval = runs.getApproval(inputData.getString(KEY_APPROVAL_ID).orEmpty()) ?: return null
                Plan(approval.runTitle.ifBlank { "Background task" }, KIND_RESUME, resume = resumeFor(approval))
            }
            KIND_CONTINUE -> {
                val sessionId = inputData.getString(KEY_SESSION_ID) ?: return null
                Plan(
                    title = inputData.getString(KEY_TITLE) ?: "Background task",
                    originKind = inputData.getString(KEY_ORIGIN_KIND) ?: KIND_TASK,
                    resume = ResumeRequest(sessionId, ResumeRequest.CONTINUE_NOTE, chain = inputData.getInt(KEY_CHAIN, 1))
                )
            }
            else -> {
                val prompt = inputData.getString(KEY_PROMPT).orEmpty()
                if (prompt.isBlank()) return null
                Plan("⏰ " + (inputData.getString(KEY_TITLE) ?: "Scheduled task"), KIND_TASK, prompt)
            }
        }
    }

    private fun resumeFor(approval: PendingApprovalEntity): ResumeRequest? {
        val chain = approval.chain + 1
        return when (approval.status) {
            PendingApprovalEntity.STATUS_APPROVED -> {
                val args = ApprovalFingerprint.decodeArguments(approval.argumentsJson) ?: emptyMap()
                ResumeRequest(
                    sessionId = approval.sessionId,
                    note = ResumeRequest.approvedNote(approval.description),
                    approvedCall = ToolCall(name = approval.toolName, arguments = args),
                    approvedFingerprint = approval.fingerprint,
                    grantKey = approval.grantKey,
                    chain = chain
                )
            }
            PendingApprovalEntity.STATUS_REJECTED ->
                ResumeRequest(approval.sessionId, ResumeRequest.rejectedNote(approval.description), chain = chain)
            else -> null
        }
    }

    private suspend fun report(runtime: AgentRuntime, plan: Plan, result: AutonomousAgentEngine.UnattendedResult) {
        val isQuietHeartbeat = plan.originKind == KIND_HEARTBEAT && plan.resume == null && result.success &&
            result.summary.trim().trimEnd('.').equals(AgentPrompts.HEARTBEAT_OK, ignoreCase = true)
        if (isQuietHeartbeat) {
            // Nothing to report: don't clutter the session list.
            result.sessionId?.let { runtime.chatRepository.deleteSession(it) }
            return
        }
        val body = buildString {
            append(result.summary.take(1_500))
            if (result.skippedApprovals.isNotEmpty()) {
                append("\n\nNeeds your approval: ")
                append(result.skippedApprovals.take(3).joinToString("; ") { it.take(80) })
            }
        }
        val title = if (result.success) plan.title else "⚠️ ${plan.title}"
        AgentNotifier.notify(appContext, title, body)
    }
}
