package com.agent.mobile.service.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agent.mobile.AgentRuntime
import com.agent.mobile.audit.DailySummary

/** Sends the daily summary of all agent runs (notification, and Telegram when delivery is on). */
class DailyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = try {
            AgentRuntime.get(applicationContext)
        } catch (e: IllegalStateException) {
            return Result.retry()
        }
        if (!runtime.preferenceManager.loadDailyReport()) return Result.success()
        val since = System.currentTimeMillis() - 24L * 60 * 60_000
        val text = DailySummary.build(runtime.chatRepository.auditLogger.since(since)) ?: return Result.success()
        runtime.platform.notifyUser("AMC daily summary", text)
        return Result.success()
    }
}
