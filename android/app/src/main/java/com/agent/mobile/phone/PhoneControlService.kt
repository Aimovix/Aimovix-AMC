package com.agent.mobile.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "AMC Phone Control": the accessibility service that lets the agent read the screen and operate
 * apps like a person (tap, type, scroll, swipe, system buttons). It does nothing on its own; every
 * action comes from an agent tool call that went through the risk check and approvals, and runs
 * while the "AMC is in control" overlay is visible.
 *
 * Password fields are never read: their text is not copied out of the node.
 */
class PhoneControlService : AccessibilityService() {

    /** One capture of the active window: the serialized snapshot and the live nodes behind it. */
    class Capture(val snapshot: ScreenSnapshot, val nodes: List<AccessibilityNodeInfo>) {
        fun node(element: ScreenElement): AccessibilityNodeInfo? = nodes.getOrNull(element.node.ref)
    }

    companion object {
        private const val TAG = "PhoneControlService"
        private const val MAX_NODES = 2_500
        private const val MAX_DEPTH = 64

        @Volatile
        var instance: PhoneControlService? = null
            private set

        /** True when the user switched the service on in the system accessibility settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, PhoneControlService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }

    @Volatile
    var foregroundPackage: String? = null
        private set

    @Volatile
    var windowClass: String? = null
        private set

    /** Uptime of the last UI change event; used to wait until the screen has settled. */
    @Volatile
    var lastEventAt: Long = 0L
        private set

    internal val overlay by lazy { ControlOverlay(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Phone control connected")
        PhoneControlSession.onServiceAvailable(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            // Our own overlay (a plain view, not an activity) must not count as the app in front.
            val isOwnOverlay = pkg == packageName && cls?.startsWith(packageName) != true
            if (!pkg.isNullOrEmpty() && !isOwnOverlay) {
                foregroundPackage = pkg
                windowClass = cls
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlay.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        overlay.hide()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    // -------------------------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------------------------

    fun isKeyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    @Suppress("DEPRECATION")
    fun screenBounds(): Bounds {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Bounds(b.left, b.top, b.right, b.bottom)
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Bounds(0, 0, m.widthPixels, m.heightPixels)
        }
    }

    /** Package of the window that has focus right now (falls back to the last window event). */
    fun activePackage(): String? = rootInActiveWindow?.packageName?.toString() ?: foregroundPackage

    /** Captures the active window. Returns null when no window content is available. */
    fun capture(appLabel: (String) -> String, filter: String? = null, maxChars: Int = ScreenTreeSerializer.DEFAULT_MAX_CHARS): Capture? {
        val root = rootInActiveWindow ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val tree = convert(root, nodes, 0)
        val pkg = root.packageName?.toString() ?: foregroundPackage.orEmpty()
        val snapshot = ScreenTreeSerializer.serialize(
            root = tree,
            packageName = pkg,
            appLabel = appLabel(pkg),
            screen = screenBounds(),
            maxChars = maxChars,
            filter = filter
        )
        return Capture(snapshot, nodes)
    }

    private fun convert(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int): UiNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        val ref = out.size
        out += n
        val children = if (depth < MAX_DEPTH && !n.isPassword) {
            (0 until n.childCount).mapNotNull { i ->
                if (out.size >= MAX_NODES) null else n.getChild(i)?.let { convert(it, out, depth + 1) }
            }
        } else emptyList()
        val password = n.isPassword
        return UiNode(
            className = n.className?.toString().orEmpty(),
            // A password's text never leaves the node.
            text = if (password) null else n.text?.toString(),
            contentDescription = if (password) null else n.contentDescription?.toString(),
            hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString() else null,
            viewId = n.viewIdResourceName,
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            focused = n.isFocused,
            selected = n.isSelected,
            enabled = n.isEnabled,
            password = password,
            visible = n.isVisibleToUser,
            ref = ref,
            children = children
        )
    }

    // -------------------------------------------------------------------------------------------
    // Acting
    // -------------------------------------------------------------------------------------------

    fun click(node: AccessibilityNodeInfo, longPress: Boolean): Boolean =
        node.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the keyboard's action key (Enter, Search, Send). Needs Android 11. */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    /** Scrolls a scrollable node. Uses directional actions when the node offers them. */
    fun scroll(node: AccessibilityNodeInfo, direction: String): Boolean {
        val directional = when (direction) {
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (node.actionList.any { it.id == directional.id } && node.performAction(directional.id)) return true
        val forward = direction == "down" || direction == "right"
        return node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun global(action: String): Boolean {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(id)
    }

    /** Taps (or long-presses) a point with a gesture. */
    suspend fun tapAt(x: Int, y: Int, longPress: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(path, if (longPress) 800L else 60L, x, y, x, y)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(path, durationMs.coerceIn(50L, 3_000L), x1, y1, x2, y2)
    }

    private suspend fun gesture(path: Path, durationMs: Long, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val screen = screenBounds()
        if (!screen.contains(x1, y1) || !screen.contains(x2, y2)) return false
        // The overlay must not catch the gesture (it would press Stop).
        val hidOverlay = overlay.hideIfCovering(x1, y1) or overlay.hideIfCovering(x2, y2)
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val description = GestureDescription.Builder().addStroke(stroke).build()
            return suspendCancellableCoroutine { cont ->
                val dispatched = dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        } finally {
            if (hidOverlay) overlay.restore()
        }
    }
}
