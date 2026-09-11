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
            .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        return request.id.toString()
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
}
