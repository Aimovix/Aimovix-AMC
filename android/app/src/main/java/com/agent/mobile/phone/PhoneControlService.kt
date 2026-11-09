package com.agent.mobile.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
        /** Time budget for one capture; a hanging app yields a partial screen instead of a frozen run. */
        const val CAPTURE_BUDGET_MS = 1_200L
        /** Extra time a gesture gets beyond its own duration before it counts as lost. */
        private const val GESTURE_GRACE_MS = 1_500L
        /** System back / home gestures start this close to the screen edge. */
        private const val EDGE_DP = 32
        private val IGNORED_EVENT_PACKAGES = setOf("com.android.systemui")

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

    /**
     * Uptime of the last UI change event of the app in front; used to wait until the screen has
     * settled. Events of AMC itself (overlay, streaming chat), the keyboard and the status bar do
     * not count, otherwise settle() would always wait for its cap.
     */
    @Volatile
    var lastEventAt: Long = 0L
        private set

    /** Uptime of the last event per type (window state, content, text changed, ...), relevant events only. */
    private val lastEventByType = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun lastEventAt(type: Int): Long = lastEventByType[type] ?: 0L

    internal val overlay by lazy { ControlOverlay(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Phone control connected")
        PhoneControlSession.onServiceAvailable(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.uptimeMillis()
        val pkg = event.packageName?.toString()
        val relevant = pkg != packageName && pkg !in IGNORED_EVENT_PACKAGES && !isImePackage(pkg) ||
            // Windows appearing and disappearing always count (dialogs, keyboard, notification shade).
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (relevant) {
            lastEventAt = now
            lastEventByType[event.eventType] = now
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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

    @Volatile
    private var imePackages: Set<String>? = null

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val known = imePackages ?: try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .inputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }.also { imePackages = it }
        return pkg in known
    }

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

    /** False while the display is off: nothing can be read or operated then (not an error of the agent). */
    fun isInteractive(): Boolean = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false

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

    private fun activeRoot(): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
        else rootInActiveWindow

    /**
     * Captures the active window plus popups of the same app (menus, autocomplete lists,
     * spinners and bottom sheets live in their own windows). Returns null when no window content
     * is available. Stops after [CAPTURE_BUDGET_MS] and marks the snapshot partial.
     */
    fun capture(appLabel: (String) -> String, filter: String? = null, maxChars: Int = ScreenTreeSerializer.DEFAULT_MAX_CHARS): Capture? {
        val root = activeRoot() ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val budget = CaptureBudget(SystemClock.uptimeMillis() + CAPTURE_BUDGET_MS)
        val pkg = root.packageName?.toString() ?: foregroundPackage.orEmpty()
        val main = convert(root, nodes, 0, budget)
        val popups = popupRoots(root, pkg).mapNotNull { if (budget.expired()) null else convert(it, nodes, 0, budget) }
        val tree = if (popups.isEmpty()) main else UiNode(bounds = screenBounds(), children = listOf(main) + popups)
        val snapshot = ScreenTreeSerializer.serialize(
            root = tree,
            packageName = pkg,
            appLabel = appLabel(pkg),
            screen = screenBounds(),
            maxChars = maxChars,
            filter = filter,
            partial = budget.partial
        )
        return Capture(snapshot, nodes)
    }

    /** Other application windows of [pkg] above the active one (popups, menus, dropdowns). */
    private fun popupRoots(active: AccessibilityNodeInfo, pkg: String): List<AccessibilityNodeInfo> {
        if (pkg.isEmpty()) return emptyList()
        return try {
            val activeId = active.windowId
            val all = windows
            val activeLayer = all.firstOrNull { it.id == activeId }?.layer ?: return emptyList()
            all.filter { w ->
                w.id != activeId && w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.layer > activeLayer
            }.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read popup windows: ${e.message}")
            emptyList()
        }
    }

    private class CaptureBudget(val deadline: Long) {
        var partial = false
        fun expired(): Boolean {
            if (SystemClock.uptimeMillis() >= deadline) partial = true
            return partial
        }
    }

    private fun convert(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int, budget: CaptureBudget): UiNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        val ref = out.size
        out += n
        val children = if (depth < MAX_DEPTH && !n.isPassword) {
            (0 until n.childCount).mapNotNull { i ->
                if (out.size >= MAX_NODES || budget.expired()) null else childOf(n, i)?.let { convert(it, out, depth + 1, budget) }
            }
        } else emptyList()
        val password = n.isPassword
        val hint = n.hintText?.toString()
        val rawText = if (password) null else n.text?.toString()
        // An empty field reports its hint as text; the model must not think it is filled in.
        val showingHint = n.isEditable && (
            (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) ||
                (rawText != null && hint != null && rawText == hint)
            )
        return UiNode(
            className = n.className?.toString().orEmpty(),
            // A password's text never leaves the node.
            text = if (showingHint) null else rawText,
            contentDescription = if (password) null else n.contentDescription?.toString(),
            hint = hint,
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
            showingHint = showingHint,
            visible = n.isVisibleToUser,
            ref = ref,
            children = children
        )
    }

    private fun childOf(n: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) n.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) else n.getChild(i)

    // -------------------------------------------------------------------------------------------
    // Acting
    // -------------------------------------------------------------------------------------------

    fun click(node: AccessibilityNodeInfo, longPress: Boolean): Boolean =
        node.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)

    /** Enters [text] with focus, verification and the clipboard / Shizuku fallbacks. */
    suspend fun enterText(node: AccessibilityNodeInfo, text: String): TextInputStrategy.Result =
        TextInputStrategy(this).enter(node, text)

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
        // A start point at the very edge triggers the system back / home gesture instead.
        val screen = screenBounds()
        val (sx, sy) = clampStart(x1, y1, screen)
        val path = Path().apply {
            moveTo(sx.toFloat(), sy.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(path, durationMs.coerceIn(50L, 3_000L), sx, sy, x2, y2)
    }

    private fun clampStart(x: Int, y: Int, screen: Bounds): Pair<Int, Int> {
        val edge = (EDGE_DP * resources.displayMetrics.density).toInt()
        if (screen.width <= 4 * edge || screen.height <= 4 * edge) return x to y
        return x.coerceIn(screen.left + edge, screen.right - edge) to y.coerceIn(screen.top, screen.bottom - edge)
    }

    private suspend fun gesture(path: Path, durationMs: Long, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val screen = screenBounds()
        if (!screen.contains(x1, y1) || !screen.contains(x2, y2)) return false
        // Nothing is operated while the user decides on an approval, and never on the approval panel.
        if (overlay.approvalPending || overlay.coversApproval(x1, y1) || overlay.coversApproval(x2, y2)) return false
        // The overlay must not catch the gesture (it would press Stop). If it could not be made
        // pass-through in time (main thread busy), the gesture is refused rather than risked.
        val coversPill = overlay.covers(x1, y1) || overlay.covers(x2, y2)
        if (coversPill && !overlay.setPassThrough(true)) return false
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val description = GestureDescription.Builder().addStroke(stroke).build()
            // A real touch of the user cancels an injected gesture: try once more.
            repeat(2) { attempt ->
                when (dispatchOnce(description, durationMs)) {
                    GestureOutcome.COMPLETED -> return true
                    GestureOutcome.LOST, GestureOutcome.REFUSED -> return false
                    GestureOutcome.CANCELLED -> if (attempt == 0) kotlinx.coroutines.delay(150)
                }
            }
            return false
        } finally {
            if (coversPill) overlay.setPassThrough(false)
        }
    }

    private enum class GestureOutcome { COMPLETED, CANCELLED, REFUSED, LOST }

    /** Dispatches a gesture with callbacks on the phone thread and a timeout (no callback after a rebind or with the display off). */
    private suspend fun dispatchOnce(description: GestureDescription, durationMs: Long): GestureOutcome =
        withTimeoutOrNull(durationMs + GESTURE_GRACE_MS) {
            suspendCancellableCoroutine { cont ->
                val dispatched = dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureOutcome.COMPLETED)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureOutcome.CANCELLED)
                    }
                }, PhoneThread.handler)
                if (!dispatched && cont.isActive) cont.resume(GestureOutcome.REFUSED)
            }
        } ?: GestureOutcome.LOST
}
