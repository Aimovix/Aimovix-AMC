package com.agent.mobile.phone

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.agent.mobile.AgentRuntime

/**
 * Notification access for the agent: list, open, reply (RemoteInput) and dismiss notifications,
 * and start background tasks when a notification from a chosen app arrives (triggers).
 * It only reads notifications when a tool asks for them; nothing is stored.
 */
class AmcNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AmcNotificationListener"

        @Volatile
        var instance: AmcNotificationListener? = null
            private set

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun extractTitle(n: Notification): String =
            n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        fun extractText(n: Notification): String {
            val extras = n.extras ?: return ""
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            return (big ?: lines ?: text).orEmpty()
        }

        /** The action that takes a typed reply, if any. */
        fun replyAction(n: Notification): Notification.Action? {
            val actions = n.actions ?: return null
            val withInput = actions.filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
            }
            return withInput.firstOrNull()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        try {
            val runtime = AgentRuntime.get(applicationContext)
            (runtime.phoneController as? AndroidPhoneController)?.onNotificationPosted(
                sbn.packageName, extractTitle(sbn.notification), extractText(sbn.notification)
            )
        } catch (e: IllegalStateException) {
            // Encrypted storage locked (before first unlock): no triggers.
        } catch (e: Exception) {
            Log.w(TAG, "Trigger check failed: ${e.message}")
        }
    }

    /** Current notifications, newest first, without AMC's own. */
    fun snapshot(labelOf: (String) -> String): List<Pair<StatusBarNotification, NotificationInfo>> {
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            emptyArray()
        }
        return active
            .filter { it.packageName != packageName && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .mapIndexed { i, sbn ->
                val n = sbn.notification
                sbn to NotificationInfo(
                    id = "n${i + 1}",
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = labelOf(sbn.packageName),
                    title = extractTitle(n),
                    text = extractText(n),
                    postedAt = sbn.postTime,
                    canReply = replyAction(n) != null,
                    canOpen = n.contentIntent != null,
                    actions = n.actions.orEmpty().filter { a -> a.remoteInputs.isNullOrEmpty() }.map { it.title?.toString().orEmpty() },
                    ongoing = sbn.isOngoing
                )
            }
    }

    fun find(key: String): StatusBarNotification? = try {
        activeNotifications.orEmpty().firstOrNull { it.key == key }
    } catch (e: SecurityException) {
        null
    }

    /** Opens the notification like a tap. */
    fun open(sbn: StatusBarNotification): Boolean {
        val intent = sbn.notification.contentIntent ?: return false
        send(intent, null)
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(sbn.key)
        return true
    }

    /** Sends [text] through the notification's reply action. */
    fun reply(sbn: StatusBarNotification, text: String): Boolean {
        val action = replyAction(sbn.notification) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        send(action.actionIntent, fill)
        return true
    }

    fun dismiss(sbn: StatusBarNotification) = cancelNotification(sbn.key)

    private fun send(pending: PendingIntent, fill: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pending.send(this, 0, fill, null, null, null, options.toBundle())
        } else {
            pending.send(this, 0, fill)
        }
    }
}
