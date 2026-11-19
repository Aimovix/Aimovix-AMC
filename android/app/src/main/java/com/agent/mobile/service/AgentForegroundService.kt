package com.agent.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.agent.mobile.MainActivity

import android.content.pm.ServiceInfo
import android.util.Log

import android.os.PowerManager
import com.agent.mobile.data.network.TermuxBridgeClient
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AgentForegroundService : Service {

    constructor() : super()

    companion object {
        private const val TAG = "AgentForegroundService"
        const val CHANNEL_ID = "agent_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.agent.mobile.ACTION_STOP"
        const val ACTION_UPDATE = "com.agent.mobile.ACTION_UPDATE"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"

        fun start(context: Context) {
            try {
                val intent = Intent(context, AgentForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start foreground service: ${e.message}", e)
            }
        }

        fun updateNotification(context: Context, title: String, message: String) {
            try {
                val intent = Intent(context, AgentForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MESSAGE, message)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not update notification: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AgentForegroundService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop foreground service: ${e.message}", e)
            }
        }
    }

    private var serviceWakeLock: PowerManager.WakeLock? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var runsJob: kotlinx.coroutines.Job? = null

    /** True while the CPU is kept awake for an agent run (for tests and the notification). */
    internal val holdsWakeLock: Boolean get() = serviceWakeLock?.isHeld == true

    /**
     * Keeps the CPU awake only while an agent run works (#20): idle, AMC holds no wake lock. The
     * notification names the running task and offers the emergency brake.
     */
    private fun followRuns() {
        if (runsJob?.isActive == true) return
        runsJob = serviceScope.launch {
            com.agent.mobile.agent.RunRegistry.active.collect { runs ->
                if (runs.isEmpty()) {
                    releaseWakeLock()
                    updateOngoing("AMC – AI Mobile Center", "Ready. No agent is working.")
                } else {
                    acquireWakeLock()
                    val first = runs.first().title.ifBlank { "Agent task" }
                    updateOngoing("AMC is working", if (runs.size == 1) first else "$first (+${runs.size - 1} more)")
                }
            }
        }
    }

    private fun updateOngoing(title: String, message: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(title, message))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update the notification: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        if (serviceWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            serviceWakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AMC:AgentForegroundService::Lock"
            )?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (serviceWakeLock?.isHeld == false) {
                serviceWakeLock?.acquire(2 * 60 * 60 * 1000L) // re-acquired while runs continue
                Log.d(TAG, "Foreground service WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire service wake lock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
                Log.d(TAG, "Foreground service WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release service wake lock: ${e.message}", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        followRuns()
        try {
            TermuxBridgeClient.getInstance(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize bridge client in service: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }

        followRuns()

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "AMC – AI Mobile Center"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Autonomous agent ready"

        val notification = buildNotification(title, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String = "AMC – AI Mobile Center", statusText: String): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Emergency brake: stops every agent run (chat, background tasks, Telegram, triggers).
        val stopAll = PendingIntent.getBroadcast(
            this, 1, Intent(this, StopAllRunsReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Stop all agents", stopAll)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent background service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains the Termux connection in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "Foreground service timed out (startId: $startId, fgsType: $fgsType). Stopping cleanly.")
        // Chat runs are checkpointed: if the process is frozen or killed now, they continue in the
        // background on the next start (#7). Background runs are WorkManager work and go on anyway.
        runsJob?.cancel()
        releaseWakeLock()
        stopSelf(startId)
    }

    override fun onDestroy() {
        runsJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
