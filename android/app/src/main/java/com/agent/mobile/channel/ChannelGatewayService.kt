package com.agent.mobile.channel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agent.mobile.AgentRuntime
import com.agent.mobile.MainActivity
import com.agent.mobile.channel.telegram.TelegramGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the messaging channel gateway (Telegram long polling) alive while AMC is in the
 * background. Uses the `specialUse` foreground service type, which has no daily time limit.
 */
class ChannelGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = buildNotification("Starting Telegram channel…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter the foreground: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val runtime = try {
            AgentRuntime.get(applicationContext)
        } catch (e: IllegalStateException) {
            // Encrypted storage is locked; the system will restart the sticky service later.
            Log.w(TAG, "Secure storage unavailable: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = runtime.preferenceManager
        if (!prefs.loadTelegramEnabled() ||
            prefs.loadAcceptedLegalVersion() < com.agent.mobile.ui.legal.LegalTexts.VERSION
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.telegram.start()
        if (statusJob == null) {
            statusJob = scope.launch {
                runtime.telegram.status.collect { status ->
                    val text = when (status) {
                        is TelegramGateway.Status.Running -> "Telegram: listening as @${status.botUsername}"
                        is TelegramGateway.Status.Starting -> "Telegram: connecting…"
                        is TelegramGateway.Status.Error -> "Telegram: ${status.message}"
                        TelegramGateway.Status.Stopped -> "Telegram: stopped"
                    }
                    updateNotification(text)
                    if (status is TelegramGateway.Status.Error && status.fatal) stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        try {
            AgentRuntime.get(applicationContext).telegram.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop the gateway: ${e.message}")
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messaging channels", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the Telegram connection open so you can reach your agent remotely"
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ChannelGatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("AMC remote channel")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Pause", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification update failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ChannelGatewayService"
        const val CHANNEL_ID = "amc_channels"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.agent.mobile.channel.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ChannelGatewayService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the channel service: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ChannelGatewayService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not stop the channel service: ${e.message}", e)
            }
        }
    }
}
