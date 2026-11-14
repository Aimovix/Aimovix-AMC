package com.agent.mobile.service.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agent.mobile.AgentRuntime
import com.agent.mobile.data.network.TermuxBridgeClient
import com.agent.mobile.local.LocalModelCatalog
import com.agent.mobile.local.LocalModelSetup
import com.agent.mobile.local.LocalSetupStore
import com.agent.mobile.local.SetupPhase
import com.agent.mobile.local.SetupProgress
import com.agent.mobile.local.TermuxLlmBridge
import com.agent.mobile.service.AgentNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sets up the local model in the background: the card only starts it and shows its progress.
 * Runs as a foreground worker with a progress notification (Cancel button), keeps going when the
 * user leaves the app, and is restarted by WorkManager after the process died or the phone
 * rebooted; [LocalModelSetup] then continues from the saved phase and the partial download.
 */
class LocalModelSetupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "LocalModelSetupWorker"
        const val UNIQUE_NAME = "amc_local_setup"
        private const val CHANNEL_ID = "amc_local_setup"
        private const val NOTIFICATION_ID = 4_100
        private const val MAX_ATTEMPTS = 6

        private const val KEY_PHASE = "phase"
        private const val KEY_MESSAGE = "message"
        private const val KEY_MODEL = "model"
        private const val KEY_BYTES = "bytes"
        private const val KEY_TOTAL = "total"
        private const val KEY_SPEED = "speed"
        private const val KEY_ETA = "eta"

        private val _activated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /** Emits when a setup finished and saved the local model as the active one. */
        val activated: SharedFlow<Unit> = _activated.asSharedFlow()

        /** Starts the setup, or does nothing when one is already running. */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocalModelSetupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        fun store(context: Context) =
            LocalSetupStore(context.getSharedPreferences(LocalSetupStore.PREFS_NAME, Context.MODE_PRIVATE))

        /** Continues a setup that stopped half-way (e.g. after a reboot), unless it is running already. */
        fun resumeIfUnfinished(context: Context) {
            val state = store(context).load()
            if (state.active) start(context)
        }

        fun toData(p: SetupProgress): Data = workDataOf(
            KEY_PHASE to p.phase.name, KEY_MESSAGE to p.message, KEY_MODEL to p.modelTitle,
            KEY_BYTES to p.bytes, KEY_TOTAL to p.total, KEY_SPEED to p.bytesPerSecond, KEY_ETA to p.etaSeconds
        )

        fun fromData(data: Data): SetupProgress? {
            val phase = data.getString(KEY_PHASE)?.let { runCatching { SetupPhase.valueOf(it) }.getOrNull() } ?: return null
            return SetupProgress(
                phase = phase,
                message = data.getString(KEY_MESSAGE).orEmpty(),
                modelTitle = data.getString(KEY_MODEL).orEmpty(),
                bytes = data.getLong(KEY_BYTES, 0),
                total = data.getLong(KEY_TOTAL, 0),
                bytesPerSecond = data.getLong(KEY_SPEED, 0),
                etaSeconds = data.getLong(KEY_ETA, -1)
            )
        }

        /** "1.2 / 2.7 GB · 4.1 MB/s · 6 min left" */
        fun detailLine(p: SetupProgress): String = buildString {
            if (p.total > 0) append("%.1f / %.1f GB".format(p.bytes / GB, p.total / GB))
            if (p.bytesPerSecond > 0) append(" · %.1f MB/s".format(p.bytesPerSecond / MB))
            if (p.etaSeconds >= 0) {
                append(" · ")
                append(if (p.etaSeconds < 60) "<1 min left" else "${(p.etaSeconds + 59) / 60} min left")
            }
        }

        private const val GB = 1024.0 * 1024 * 1024
        private const val MB = 1024.0 * 1024
    }

    private var lastNotifyAt = 0L

    override suspend fun doWork(): Result {
        val runtime = try {
            AgentRuntime.get(appContext)
        } catch (e: IllegalStateException) {
            return Result.retry()
        }
        val prefs = runtime.preferenceManager
        val store = store(appContext)
        try {
            setForeground(foregroundInfo(SetupProgress(SetupPhase.CHECK, "Preparing…")))
        } catch (e: Exception) {
            // Android may refuse a foreground start from the background; the work still runs.
            Log.w(TAG, "No foreground notification: ${e.message}")
        }

        val token = prefs.loadAuthToken()
        val client = TermuxBridgeClient(token = token).also { it.attachContext(appContext) }
        val setup = LocalModelSetup(appContext, TermuxLlmBridge(client), store)
        return try {
            // Starts the bridge in Termux when it is down (instead of telling the user to run 'amc start').
            runtime.bridgeStarter.ensureConnected(client, token, 25_000L)
            val outcome = setup.run { progress ->
                setProgressAsync(toData(progress))
                notifyProgress(progress)
            }
            when (outcome) {
                is LocalModelSetup.Outcome.Ready -> {
                    val config = LocalModelCatalog.localConfig(prefs.loadModelConfig(), LocalModelSetup.PORT)
                    prefs.saveModelConfig(config)
                    _activated.tryEmit(Unit)
                    AgentNotifier.notify(
                        appContext, "Local AI active",
                        "${outcome.model.title} runs on this phone (%.1f tokens/s). Cloud fallback: %s."
                            .format(outcome.tokensPerSecond, config.fallbackProvider?.displayName ?: "none")
                    )
                    Result.success()
                }
                is LocalModelSetup.Outcome.Failed -> {
                    if (outcome.error.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
                        Result.retry()
                    } else {
                        AgentNotifier.notify(appContext, "Local AI setup stopped", outcome.reason)
                        Result.success()
                    }
                }
            }
        } catch (e: CancellationException) {
            // Cancel button (card or notification): stop the download in Termux, keep the partial file.
            withContext(NonCancellable) { setup.cancel() }
            throw e
        } finally {
            client.disconnect()
            cancelNotification()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Local AI setup", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress while AMC downloads and tests the local model"
                    }
                )
            }
        }
    }

    private fun foregroundInfo(p: SetupProgress): ForegroundInfo {
        ensureChannel()
        val fraction = p.fraction
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Setting up local AI")
            .setContentText(listOf(p.message, detailLine(p)).filter { it.isNotBlank() }.joinToString(" · ").take(200))
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(appContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyProgress(p: SetupProgress) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 1_000L && p.phase == SetupPhase.DOWNLOAD) return
        lastNotifyAt = now
        try {
            setForegroundAsync(foregroundInfo(p))
        } catch (e: Exception) {
            Log.w(TAG, "Progress notification failed: ${e.message}")
        }
    }

    private fun cancelNotification() {
        try {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the notification: ${e.message}")
        }
    }
}
