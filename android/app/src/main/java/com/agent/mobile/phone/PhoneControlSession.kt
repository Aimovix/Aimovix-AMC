package com.agent.mobile.phone

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.agent.mobile.MainActivity
import com.agent.mobile.R
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * While the agent operates the phone, the user always sees it and can stop it at once:
 * a small "AMC is in control" overlay with a Stop button, and an ongoing notification with a
 * Stop action. Stop cancels every run that is controlling the phone.
 */
object PhoneControlSession {
    private const val TAG = "PhoneControlSession"
    const val CHANNEL_ID = "amc_phone_control"
    const val NOTIFICATION_ID = 1101

    private val stopHandlers = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var appContext: Context? = null

    val isActive: Boolean get() = stopHandlers.isNotEmpty()

    /** Starts showing the overlay and notification; [onStop] runs when the user taps Stop. */
    fun begin(context: Context, onStop: () -> Unit): AutoCloseable {
        appContext = context.applicationContext
        val first = stopHandlers.isEmpty()
        stopHandlers += onStop
        if (first) showUi()
        return AutoCloseable { end(onStop) }
    }

    private fun end(handler: () -> Unit) {
        if (stopHandlers.remove(handler) && stopHandlers.isEmpty()) hideUi()
    }

    /** Stop button (overlay or notification): ends every run that controls the phone. */
    fun requestStop() {
        val handlers = stopHandlers.toList()
        stopHandlers.clear()
        hideUi()
        handlers.forEach { handler ->
            try {
                handler()
            } catch (e: Exception) {
                Log.w(TAG, "Stop handler failed: ${e.message}")
            }
        }
    }

    /** The service (re)connected while a run is active: show the overlay again. */
    internal fun onServiceAvailable(service: PhoneControlService) {
        if (isActive) service.overlay.show { requestStop() }
    }

    private fun showUi() {
        PhoneControlService.instance?.overlay?.show { requestStop() }
        appContext?.let { postNotification(it) }
    }

    private fun hideUi() {
        PhoneControlService.instance?.overlay?.hide()
        appContext?.let { ctx ->
            try {
                ScreenCapture.release(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "Could not end screen capture: ${e.message}")
            }
            try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel the notification: ${e.message}")
            }
        }
    }

    private fun postNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.phone_control_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.phone_control_channel_description)
                }
            )
        }
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, PhoneControlStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.phone_control_notification_title))
            .setContentText(context.getString(R.string.phone_control_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.phone_control_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification failed: ${e.message}")
        }
    }
}

/** "Stop" action of the phone control notification. */
class PhoneControlStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PhoneControlSession.requestStop()
    }
}

/**
 * The visible "AMC is in control" pill at the top of the screen. It is an accessibility overlay,
 * so it needs no extra permission and stays above every app while the agent works.
 */
internal class ControlOverlay(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    @Volatile
    private var rect: Rect? = null

    private val windowManager: WindowManager
        get() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    fun show(onStop: () -> Unit) {
        main.post {
            if (view != null) return@post
            try {
                val v = build(onStop)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(2)
                }
                windowManager.addView(v, lp)
                view = v
                params = lp
                v.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                    val loc = IntArray(2)
                    changed.getLocationOnScreen(loc)
                    rect = Rect(loc[0], loc[1], loc[0] + changed.width, loc[1] + changed.height)
                }
            } catch (e: Exception) {
                Log.w("ControlOverlay", "Could not show overlay: ${e.message}")
            }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            try {
                windowManager.removeView(v)
            } catch (e: Exception) {
                Log.w("ControlOverlay", "Could not remove overlay: ${e.message}")
            }
            view = null
            params = null
            rect = null
        }
    }

    /** Lets touches pass through the overlay when a gesture starts or ends on it. Returns true if it did. */
    fun hideIfCovering(x: Int, y: Int): Boolean {
        val r = rect ?: return false
        if (!r.contains(x, y)) return false
        setTouchable(false)
        return true
    }

    fun restore() = setTouchable(true)

    private fun setTouchable(touchable: Boolean) {
        val latch = CountDownLatch(1)
        val apply = Runnable {
            try {
                val v = view
                val lp = params
                if (v != null && lp != null) {
                    lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(v, lp)
                }
            } catch (e: Exception) {
                Log.w("ControlOverlay", "Could not update overlay: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else {
            main.post(apply)
            latch.await(500, TimeUnit.MILLISECONDS)
        }
    }

    private fun build(onStop: () -> Unit): View {
        val ctx = service
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF21C1C1E.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF3A3A3C.toInt())
            }
            setPadding(dp(14), dp(4), dp(4), dp(4))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF34D399.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
        }
        val label = TextView(ctx).apply {
            text = ctx.getString(R.string.phone_control_overlay_title)
            setTextColor(0xFFF5F5F7.toInt())
            textSize = 13f
        }
        val stop = TextView(ctx).apply {
            text = ctx.getString(R.string.phone_control_stop)
            contentDescription = ctx.getString(R.string.phone_control_stop_description)
            setTextColor(0xFFFF6A61.toInt())
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minWidth = dp(64)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(0x33FF453A)
                cornerRadius = dp(20).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onStop() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        root.addView(dot)
        root.addView(label)
        root.addView(stop)
        return root
    }
}
