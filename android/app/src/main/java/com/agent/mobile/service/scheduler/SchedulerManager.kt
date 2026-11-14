package com.agent.mobile.service.scheduler

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

data class ScheduledWorkflowItem(
    val id: String,
    val title: String,
    val command: String,
    val intervalHours: Long = 0,
    val requiresWifi: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val isPeriodic: Boolean = false
)

class SchedulerManager(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleOneTimeWorkflow(
        title: String,
        command: String,
        delayMinutes: Long = 0,
        requiresWifi: Boolean = false,
        requiresCharging: Boolean = false,
        requiresBatteryNotLow: Boolean = true,
        tag: String = "amc_workflow"
    ): String {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(requiresBatteryNotLow)
            .setRequiresCharging(requiresCharging)
            .setRequiredNetworkType(if (requiresWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .build()

        val data = workDataOf(
            AgentWorkflowWorker.KEY_TITLE to title,
            AgentWorkflowWorker.KEY_COMMAND to command
        )

        val request = OneTimeWorkRequestBuilder<AgentWorkflowWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .addTag(tag)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        workManager.enqueue(request)
        return request.id.toString()
    }

    fun schedulePeriodicWorkflow(
        title: String,
        command: String,
        repeatIntervalHours: Long = 6,
        requiresWifi: Boolean = false,
        requiresCharging: Boolean = false,
        requiresBatteryNotLow: Boolean = true,
        uniqueWorkName: String = "amc_periodic_$title"
    ): String {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(requiresBatteryNotLow)
            .setRequiresCharging(requiresCharging)
            .setRequiredNetworkType(if (requiresWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .build()

        val data = workDataOf(
            AgentWorkflowWorker.KEY_TITLE to title,
            AgentWorkflowWorker.KEY_COMMAND to command
        )

        val request = PeriodicWorkRequestBuilder<AgentWorkflowWorker>(
            repeatIntervalHours.coerceAtLeast(1),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(data)
            .addTag("amc_workflow")
            .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        return request.id.toString()
    }

    fun getActiveWorkflowsFlow(): kotlinx.coroutines.flow.Flow<List<WorkInfo>> {
        return workManager.getWorkInfosByTagFlow("amc_workflow")
    }

    fun cancelWorkflowById(workId: String) {
        try {
            workManager.cancelWorkById(java.util.UUID.fromString(workId))
        } catch (e: Exception) {
            // ignore
        }
    }

    fun cancelAllWorkflowsByTag(tag: String) {
        workManager.cancelAllWorkByTag(tag)
    }

    // --- Autonomous agent tasks ---------------------------------------------------------------

    /**
     * Schedules an unattended agent run. One-time when [repeatEveryMinutes] is 0, otherwise
     * periodic (WorkManager enforces a minimum of 15 minutes). Returns the WorkManager id.
     */
    fun scheduleAgentTask(title: String, prompt: String, delayMinutes: Long, repeatEveryMinutes: Long): String {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()
        val data = workDataOf(
            AgentTaskWorker.KEY_KIND to AgentTaskWorker.KIND_TASK,
            AgentTaskWorker.KEY_TITLE to title.take(80),
            AgentTaskWorker.KEY_PROMPT to prompt.take(8_000)
        )
        val titleTag = TITLE_TAG_PREFIX + title.take(60)
        return if (repeatEveryMinutes > 0) {
            val request = PeriodicWorkRequestBuilder<AgentTaskWorker>(repeatEveryMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(data)
                .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                .addTag(AGENT_TASK_TAG)
                .addTag(titleTag)
                .addTag("$REPEAT_TAG_PREFIX${repeatEveryMinutes.coerceAtLeast(15)}")
                .build()
            workManager.enqueue(request)
            request.id.toString()
        } else {
            val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
                .addTag(AGENT_TASK_TAG)
                .addTag(titleTag)
                .build()
            workManager.enqueue(request)
            request.id.toString()
        }
    }

    /** Continues a background task after its parked approval was answered. */
    fun enqueueResume(approvalId: String) {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    AgentTaskWorker.KEY_KIND to AgentTaskWorker.KIND_RESUME,
                    AgentTaskWorker.KEY_APPROVAL_ID to approvalId
                )
            )
            .addTag(AGENT_TASK_TAG)
            .addTag(TITLE_TAG_PREFIX + "Resume after approval")
            .build()
        workManager.enqueueUniqueWork("amc_resume_$approvalId", ExistingWorkPolicy.KEEP, request)
    }

    /** Continues a background task whose step or time budget ran out, in the same chat session. */
    fun enqueueContinuation(sessionId: String, title: String, chain: Int, originKind: String, delayMinutes: Long = 0) {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(delayMinutes.coerceAtLeast(0), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    AgentTaskWorker.KEY_KIND to AgentTaskWorker.KIND_CONTINUE,
                    AgentTaskWorker.KEY_SESSION_ID to sessionId,
                    AgentTaskWorker.KEY_TITLE to title.take(80),
                    AgentTaskWorker.KEY_CHAIN to chain,
                    AgentTaskWorker.KEY_ORIGIN_KIND to originKind
                )
            )
            .addTag(AGENT_TASK_TAG)
            .addTag(TITLE_TAG_PREFIX + title.take(60))
            .build()
        workManager.enqueueUniqueWork("amc_continue_${sessionId}_$chain", ExistingWorkPolicy.KEEP, request)
    }

    /** Lists pending/running agent tasks, one per line. Blocking; call off the main thread. */
    fun describeAgentTasks(): String {
        val infos = workManager.getWorkInfosByTag(AGENT_TASK_TAG).get()
            .filter { !it.state.isFinished }
        if (infos.isEmpty()) return ""
        return infos.joinToString("\n") { info ->
            val title = info.tags.firstOrNull { it.startsWith(TITLE_TAG_PREFIX) }?.removePrefix(TITLE_TAG_PREFIX) ?: "Task"
            val repeat = info.tags.firstOrNull { it.startsWith(REPEAT_TAG_PREFIX) }?.removePrefix(REPEAT_TAG_PREFIX)
            val next = runCatching { info.nextScheduleTimeMillis }.getOrNull()?.takeIf { it > 0 && it < Long.MAX_VALUE }
            buildString {
                append("- id=").append(info.id).append(" \"").append(title).append("\" state=").append(info.state.name)
                if (repeat != null) append(" every ").append(repeat).append(" min")
                if (next != null) append(" next=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(next)))
            }
        }
    }

    /** Cancels an agent task by id. Blocking; call off the main thread. */
    fun cancelAgentTask(taskId: String): Boolean {
        val uuid = try { java.util.UUID.fromString(taskId.trim()) } catch (e: Exception) { return false }
        val info = workManager.getWorkInfoById(uuid).get() ?: return false
        if (AGENT_TASK_TAG !in info.tags || info.state.isFinished) return false
        workManager.cancelWorkById(uuid).result.get()
        return true
    }

    fun getAgentTasksFlow(): kotlinx.coroutines.flow.Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagFlow(AGENT_TASK_TAG)

    /** Enables or updates the periodic heartbeat. */
    fun scheduleHeartbeat(intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<AgentTaskWorker>(intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(workDataOf(AgentTaskWorker.KEY_KIND to AgentTaskWorker.KIND_HEARTBEAT))
            .setInitialDelay(intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .addTag(HEARTBEAT_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(HEARTBEAT_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Runs the heartbeat once, right now. */
    fun runHeartbeatNow() {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(workDataOf(AgentTaskWorker.KEY_KIND to AgentTaskWorker.KIND_HEARTBEAT))
            .addTag(HEARTBEAT_TAG)
            .build()
        workManager.enqueue(request)
    }

    fun cancelHeartbeat() {
        workManager.cancelUniqueWork(HEARTBEAT_WORK_NAME)
    }

    companion object {
        const val AGENT_TASK_TAG = "amc_agent_task"
        const val HEARTBEAT_TAG = "amc_heartbeat"
        const val HEARTBEAT_WORK_NAME = "amc_heartbeat"
        private const val TITLE_TAG_PREFIX = "amc_title:"
        private const val REPEAT_TAG_PREFIX = "amc_repeat:"
    }
}
