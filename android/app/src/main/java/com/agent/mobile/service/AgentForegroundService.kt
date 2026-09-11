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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
}
