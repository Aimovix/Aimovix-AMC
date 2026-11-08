package com.agent.mobile.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.agent.mobile.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/** Posts agent notifications (results of background runs, reminders, approval requests). */
object AgentNotifier {
    private const val TAG = "AgentNotifier"
    const val CHANNEL_ID = "amc_agent_channel"
    private val nextId = AtomicInteger(3000)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Agent messages", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Messages, reminders and results from the autonomous agent"
                    }
                )
            }
        }
    }

    fun notify(context: Context, title: String, message: String): Int {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping notification '$title'")
            return -1
        }
        ensureChannel(appContext)
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = message.ifBlank { title }.take(4_000)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(120))
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = nextId.incrementAndGet()
        return try {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
            id
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification failed: ${e.message}")
            -1
        }
    }
}
