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
import kotlinx.coroutines.launch
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

    /** Notification id of an approval request (stable per tool call, so it can be cancelled). */
    private fun approvalId(callId: String): Int = 5_000 + (callId.hashCode() and 0x0FFF)

    /**
     * "AMC needs your approval" with Approve / Reject buttons. Approve requires unlocking the
     * phone (Android 12+), so nobody can approve from the lock screen.
     */
    fun notifyApproval(context: Context, callId: String, description: String) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; the approval is only shown in the app")
            return
        }
        ensureChannel(appContext)
        fun action(approve: Boolean): PendingIntent = PendingIntent.getBroadcast(
            appContext,
            approvalId(callId) * 2 + if (approve) 1 else 0,
            Intent(appContext, ApprovalActionReceiver::class.java)
                .putExtra(ApprovalActionReceiver.EXTRA_CALL_ID, callId)
                .putExtra(ApprovalActionReceiver.EXTRA_APPROVE, approve),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val approve = NotificationCompat.Action.Builder(0, appContext.getString(com.agent.mobile.R.string.phone_control_approve), action(true))
            .setAuthenticationRequired(true)
            .build()
        val reject = NotificationCompat.Action.Builder(0, appContext.getString(com.agent.mobile.R.string.phone_control_reject), action(false))
            .build()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(appContext.getString(com.agent.mobile.R.string.phone_control_approval_title))
            .setContentText(description.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(description.take(1_000)))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(reject)
            .addAction(approve)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(approvalId(callId), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Approval notification failed: ${e.message}")
        }
    }

    fun cancelApproval(context: Context, callId: String) {
        try {
            (context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(approvalId(callId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the approval notification: ${e.message}")
        }
    }
}

/** Approve / Reject buttons of the approval notification. */
class ApprovalActionReceiver : android.content.BroadcastReceiver() {
    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_APPROVE = "approve"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val parkedId = com.agent.mobile.service.approval.ApprovalQueueManager.parkedIdOf(callId)
        if (parkedId != null) {
            // A background run parked this action and has ended; record the answer and resume the task.
            val pending = goAsync()
            val appContext = context.applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    com.agent.mobile.AgentRuntime.get(appContext).approvalQueue.decide(parkedId, approve)
                } catch (e: Exception) {
                    Log.w("ApprovalAction", "Could not record the decision for $parkedId: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (!com.agent.mobile.agent.ApprovalActions.decide(callId, approve)) {
            Log.i("ApprovalAction", "Approval $callId was already decided")
        }
        AgentNotifier.cancelApproval(context, callId)
    }
}
