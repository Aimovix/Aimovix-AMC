package com.agent.mobile.service.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agent.mobile.AgentRuntime
import com.agent.mobile.agent.InteractiveRunListener
import com.agent.mobile.agent.ResumeRequest
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Keeps a chat run going when the app goes away. While a run is active in the app, it is stored
 * as RUNNING and a watchdog job is scheduled in WorkManager (which survives a killed process):
 * - the run ends normally or the user presses Stop: the record is closed and the watchdog cancelled;
 * - the app is closed mid-run (swiped away, activity destroyed): the task continues right away as a
 *   background run in the same conversation;
 * - the process is killed without warning: the watchdog finds the RUNNING record without a live
 *   run in its process and continues the task the same way.
 * The background run reports its result as a notification (and on Telegram when connected).
 */
class InteractiveRunGuard(private val context: Context) : InteractiveRunListener {

    override suspend fun onRunStarted(sessionId: String, title: String) {
        alive += sessionId
        try {
            AgentRuntime.get(context).database.agentQueueDao().upsertRun(
                AgentRunEntity(id = runId(sessionId), sessionId = sessionId, title = title.ifBlank { "Chat task" }, kind = KIND, status = AgentRunEntity.STATUS_RUNNING)
            )
            scheduleWatchdog(context, sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the chat run: ${e.message}")
        }
    }

    override suspend fun onRunEnded(sessionId: String, interrupted: Boolean) {
        alive -= sessionId
        try {
            val dao = AgentRuntime.get(context).database.agentQueueDao()
            val run = dao.getRun(runId(sessionId))
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(watchdogName(sessionId))
            if (interrupted && run != null && run.status == AgentRunEntity.STATUS_RUNNING) {
                continueInBackground(context, run)
            } else {
                dao.setRunStatus(runId(sessionId), AgentRunEntity.STATUS_DONE, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not close the chat run: ${e.message}")
        }
    }

    /** Checks a chat run some minutes after it started (or after the last check). */
    class Watchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.success()
            val dao = AgentRuntime.get(applicationContext).database.agentQueueDao()
            val run = dao.getRun(runId(sessionId)) ?: return Result.success()
            if (run.status != AgentRunEntity.STATUS_RUNNING) return Result.success()
            if (sessionId in alive) {
                // Still working in this process: look again later (appended, so this worker is not cancelled).
                scheduleWatchdog(applicationContext, sessionId, ExistingWorkPolicy.APPEND_OR_REPLACE)
            } else {
                continueInBackground(applicationContext, run)
            }
            return Result.success()
        }
    }

    companion object {
        private const val TAG = "InteractiveRunGuard"
        const val KIND = "interactive"
        private const val KEY_SESSION_ID = "session_id"
        private const val WATCHDOG_MINUTES = 3L

        /** Sessions with a chat run in this process (empty in a new process after a kill). */
        private val alive: MutableSet<String> = Collections.synchronizedSet(HashSet())

        fun runId(sessionId: String) = "interactive:$sessionId"
        private fun watchdogName(sessionId: String) = "amc_chat_watchdog_$sessionId"

        private fun scheduleWatchdog(context: Context, sessionId: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val request = OneTimeWorkRequestBuilder<Watchdog>()
                .setInitialDelay(WATCHDOG_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(watchdogName(sessionId), policy, request)
        }

        private suspend fun continueInBackground(context: Context, run: AgentRunEntity) {
            val runtime = AgentRuntime.get(context)
            runtime.database.agentQueueDao().setRunStatus(run.id, AgentRunEntity.STATUS_CONTINUED, System.currentTimeMillis())
            runtime.scheduler.enqueueContinuation(
                run.sessionId, run.title, chain = 1, originKind = AgentTaskWorker.KIND_TASK, note = ResumeRequest.APP_CLOSED_NOTE
            )
            Log.i(TAG, "Chat run in ${run.sessionId} continues in the background")
        }
    }
}
