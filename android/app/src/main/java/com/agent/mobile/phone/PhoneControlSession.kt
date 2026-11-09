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
import kotlin.coroutines.resume

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

    /**
     * Asks the user with an Approve / Reject panel directly above the app in front (the app the
     * action is about stays in front). Returns null when phone control is not connected; then
     * the caller asks in the chat instead. Cancelling the coroutine removes the panel.
     */
    suspend fun requestApproval(title: String, detail: String): Boolean? {
        val overlay = PhoneControlService.instance?.overlay ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            overlay.showApproval(title, detail) { approved ->
                if (cont.isActive) cont.resume(approved)
            }
            cont.invokeOnCancellation { overlay.hideApproval() }
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
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
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
 * The visible "AMC is in control" pill at the top of the screen and, while the agent waits for a
 * decision, an Approve / Reject panel above the app in front. Both are accessibility overlays, so
 * they need no extra permission and stay above every app while the agent works.
 *
 * The approval panel exists because asking in the AMC chat meant switching to AMC, after which
 * AMC was the app in front and every screen action was refused ("the app in front changed").
 */
internal class ControlOverlay(private val service: AccessibilityService) {
    companion object {
        private const val TAG = "ControlOverlay"
        /** Buttons stay inactive this long, so a tap aimed at the app below cannot hit them. */
        const val ARM_DELAY_MS = 600L
    }

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var approvalView: View? = null

    @Volatile
    private var rect: Rect? = null

    @Volatile
    private var approvalRect: Rect? = null

    @Volatile
    private var passThrough = false

    /** True while an approval panel waits for the user; the agent performs no gestures then. */
    @Volatile
    var approvalPending: Boolean = false
        private set

    private val windowManager: WindowManager
        get() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    /** Height of the status bar / display cutout, so the pill does not sit on the punch-hole camera. */
    @Suppress("DEPRECATION")
    private fun topInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            return insets.top
        }
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(24)
    }

    fun show(onStop: () -> Unit) {
        main.post {
            if (view != null) return@post
            try {
                val v = build(onStop)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        // The display must not lock while approvals or slow model turns are pending.
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    // Below the status bar and the camera cutout, at the side: titles and search bars stay free.
                    y = topInset() + dp(4)
                    x = dp(8)
                }
                windowManager.addView(v, lp)
                view = v
                params = lp
                passThrough = false
                v.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                    val loc = IntArray(2)
                    changed.getLocationOnScreen(loc)
                    rect = Rect(loc[0], loc[1], loc[0] + changed.width, loc[1] + changed.height)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not show overlay: ${e.message}")
            }
        }
    }

    fun hide() {
        main.post {
            val v = view
            if (v != null) {
                try {
                    windowManager.removeView(v)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove overlay: ${e.message}")
                }
            }
            view = null
            params = null
            rect = null
            removeApprovalView()
        }
    }

    /** True when a point lies on the control pill. */
    fun covers(x: Int, y: Int): Boolean = rect?.contains(x, y) == true

    /** True when a point lies on the approval panel; such gestures are refused. */
    fun coversApproval(x: Int, y: Int): Boolean = approvalRect?.contains(x, y) == true

    /**
     * Lets touches pass through the pill (or not). Returns true only when the change is
     * confirmed applied, so a gesture is never dispatched onto a still touchable Stop button.
     */
    fun setPassThrough(enabled: Boolean): Boolean {
        val latch = CountDownLatch(1)
        var applied = false
        val apply = Runnable {
            try {
                val v = view
                val lp = params
                if (v == null || lp == null) {
                    applied = true
                } else {
                    lp.flags = if (enabled) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    windowManager.updateViewLayout(v, lp)
                    applied = true
                }
                passThrough = enabled
            } catch (e: Exception) {
                Log.w(TAG, "Could not update overlay: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else {
            main.post(apply)
            if (!latch.await(500, TimeUnit.MILLISECONDS)) return false
        }
        return applied
    }

    /**
     * Shows the Approve / Reject panel above the app in front. [onDecision] runs once, with the
     * user's choice. The buttons only react after [ARM_DELAY_MS].
     */
    fun showApproval(title: String, detail: String, onDecision: (Boolean) -> Unit) {
        approvalPending = true
        main.post {
            removeApprovalView()
            try {
                var decided = false
                val decide: (Boolean) -> Unit = { approved ->
                    if (!decided) {
                        decided = true
                        removeApprovalView()
                        onDecision(approved)
                    }
                }
                val v = buildApproval(title, detail, decide)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM
                    y = dp(48)
                }
                windowManager.addView(v, lp)
                approvalView = v
                v.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                    val loc = IntArray(2)
                    changed.getLocationOnScreen(loc)
                    approvalRect = Rect(loc[0], loc[1], loc[0] + changed.width, loc[1] + changed.height)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the approval panel: ${e.message}")
                approvalPending = false
                approvalRect = null
            }
        }
    }

    /** Removes the approval panel without a decision (answered in the chat, run stopped). */
    fun hideApproval() {
        main.post { removeApprovalView() }
    }

    private fun removeApprovalView() {
        val v = approvalView
        if (v != null) {
            try {
                windowManager.removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the approval panel: ${e.message}")
            }
        }
        approvalView = null
        approvalRect = null
        approvalPending = false
    }

    private fun buildApproval(title: String, detail: String, decide: (Boolean) -> Unit): View {
        val ctx = service
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF51C1C1E.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFF3A3A3C.toInt())
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val wrapper = LinearLayout(ctx).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFF5F5F7.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(ctx).apply {
            text = detail.take(400)
            setTextColor(0xFFD1D1D6.toInt())
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun button(label: String, color: Int, fill: Int, approved: Boolean) = TextView(ctx).apply {
            text = label
            setTextColor(color)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            minWidth = dp(104)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(22).toFloat()
            }
            isClickable = true
            isFocusable = true
            // Inactive at first: a gesture aimed at the app below must not approve by accident.
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { decide(approved) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
        val reject = button(ctx.getString(R.string.phone_control_reject), 0xFFFF6A61.toInt(), 0x33FF453A, false)
        val approve = button(ctx.getString(R.string.phone_control_approve), 0xFF0B0B0C.toInt(), 0xFF34D399.toInt(), true)
        row.addView(reject)
        row.addView(approve)
        root.addView(row)
        main.postDelayed({
            listOf(reject, approve).forEach {
                it.isEnabled = true
                it.alpha = 1f
            }
        }, ARM_DELAY_MS)
        return wrapper
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
