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
        val title = inputData.getString(KEY_TITLE) ?: "Hintergrund-Workflow"
        val command = inputData.getString(KEY_COMMAND) ?: "termux-battery-status"
        Log.i(TAG, "Starte Hintergrund-Automation: $title mit Befehl: $command")

        sendNotification(title, "Führe Befehl aus: $command...")

        return try {
            val assessment = CommandSecurityFilter.analyze(command)
            if (assessment.isBlocked) {
                sendNotification("🛡️ Sicherheits-Sperre ($title)", "Befehl `$command` wurde aus Sicherheitsgründen blockiert.")
                return Result.failure()
            }

            val prefs = PreferenceManager(appContext)
            val token = prefs.loadAuthToken()
            val bridge = TermuxBridgeClient(token = token)
            bridge.connect(token)

            val startTime = System.currentTimeMillis()
            val result = bridge.executeCommand(command, timeoutMs = 60_000L)
            val duration = System.currentTimeMillis() - startTime
            bridge.disconnect()

            val db = AppDatabase.getInstance(appContext)
            db.commandAuditDao().insertAudit(
                CommandAuditEntity(
                    command = command,
                    riskLevel = assessment.level.name,
                    riskReason = "Geplanter Hintergrund-Workflow: $title",
                    executionDurationMs = duration,
                    exitCode = result.exitCode,
                    stdout = result.stdout.take(500),
                    stderr = result.stderr.take(500),
                    timestamp = System.currentTimeMillis(),
                    wasApproved = true
                )
            )

            val summary = if (result.exitCode == 0) {
                "Erfolgreich abgeschlossen (Code 0)\n${result.stdout.take(120)}"
            } else {
                "Fehler bei Ausführung (Code ${result.exitCode})\n${result.stderr.take(120)}"
            }

            sendNotification("✅ $title", summary)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Fehler bei Hintergrund-Workflow: ${e.message}", e)
            sendNotification("⚠️ Fehler ($title)", e.localizedMessage ?: "Unbekannter Fehler")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun sendNotification(title: String, message: String) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Automations-Benachrichtigungen",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ergebnisse zeit- und ereignisgesteuerter Workflows"
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

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
