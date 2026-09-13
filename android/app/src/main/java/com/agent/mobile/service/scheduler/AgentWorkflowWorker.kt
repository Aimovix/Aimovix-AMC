package com.agent.mobile.service.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.data.storage.PreferenceManager
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.data.model.ExecutionMode
import kotlinx.coroutines.CancellationException

class AgentWorkflowWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AgentWorkflowWorker"
        const val KEY_TITLE = "key_workflow_title"
        const val KEY_COMMAND = "key_workflow_command"
        const val KEY_PROMPT = "key_workflow_prompt"
        const val CHANNEL_ID = "amc_automation_channel"
        const val NOTIFICATION_ID = 2002
    }

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: "Background workflow"
        val command = inputData.getString(KEY_COMMAND) ?: "termux-battery-status"
        Log.i(TAG, "Starting background automation: $title with command: $command")

        sendNotification(title, "Running command: $command...")

        return try {
            val prefs = PreferenceManager(appContext)
            CommandSecurityFilter.setCustomRules(prefs.loadWhitelist(), prefs.loadBlacklist(), prefs.loadStrictMode())
            val assessment = CommandSecurityFilter.analyze(command)
            if (assessment.isBlocked) {
                sendNotification("🛡️ Security block ($title)", "Command `$command` was blocked by security rules.")
                return Result.failure()
            }

            val requiresApproval = if (assessment.level == RiskLevel.LOW) {
                false
            } else {
                CommandSecurityFilter.shouldRequireApproval(assessment, prefs.loadExecutionMode())
            }

            if (requiresApproval) {
                sendNotification("Approval required ($title)", "Command `$command` requires interactive approval and cannot run unattended in background.")
                return Result.failure()
            }
            val token = prefs.loadAuthToken()
            val bridge = TermuxBridgeClient(token = token)
            bridge.connect(token)

            val startTime = System.currentTimeMillis()
            val result = try {
                val connected = bridge.awaitConnected(15_000L)
                if (!connected) {
                    Log.w(TAG, "Termux bridge not connected after 15s for workflow: $title (attempt $runAttemptCount)")
                    sendNotification("⚠️ Offline ($title)", "Termux bridge is not reachable.")
                    return if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
                bridge.executeCommand(command, timeoutMs = 60_000L)
            } finally {
                bridge.disconnect()
            }
            val duration = System.currentTimeMillis() - startTime
            val db = AppDatabase.getInstance(appContext)
            db.commandAuditDao().insertAudit(
                CommandAuditEntity(
                    command = command,
                    riskLevel = assessment.level.name,
                    riskReason = "Scheduled background workflow: $title",
                    executionDurationMs = duration,
                    exitCode = result.exitCode,
                    stdout = result.stdout.take(500),
                    stderr = result.stderr.take(500),
                    timestamp = System.currentTimeMillis(),
                    wasApproved = true
                )
            )

            val summary = if (result.exitCode == 0) {
                "Completed successfully (Code 0)\n${result.stdout.take(120)}"
            } else {
                "Execution failed (Code ${result.exitCode})\n${result.stderr.take(120)}"
            }

            sendNotification("✅ $title", summary)
            if (result.exitCode == 0) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background workflow failed: ${e.message}", e)
            sendNotification("⚠️ Error ($title)", e.localizedMessage ?: "Unknown error")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun sendNotification(title: String, message: String) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Automation notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Results of scheduled and event-triggered workflows"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
                return
            }
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException sending notification: ${e.message}")
        }
    }
}
