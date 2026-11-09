package com.agent.mobile.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.agent.mobile.agent.PhoneActionResult
import com.agent.mobile.agent.PhoneController
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.agent.RunMode
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.storage.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of [PhoneController]. Screen tools run through [PhoneControlService];
 * one action at a time (a mutex), observe → act → settle → observe again.
 *
 * Privacy gate: the screen after an action is only returned when the app in front may be read
 * in this run. Blocked apps, AMC itself and security settings are never shown; messaging,
 * contacts and camera apps only after the user approved reading them ([grants]).
 */
class AndroidPhoneController(
    private val context: Context,
    private val prefs: PreferenceManager
) : PhoneController {

    companion object {
        private const val TAG = "PhoneController"
        private const val APPS_CACHE_MS = 60_000L
    }

    private val mutex = Mutex()

    /** The last screen returned to the model; its ids are what the model refers to. */
    @Volatile
    private var modelSnapshot: ScreenSnapshot? = null

    /** App in front when a call was risk-checked; the action is refused if it changed before execution. */
    private val checkedForeground = ConcurrentHashMap<String, String>()

    private val labelCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var appsCache: Pair<Long, List<AppEntry>>? = null

    private val pm: PackageManager get() = context.packageManager

    // -------------------------------------------------------------------------------------------
    // PhoneController
    // -------------------------------------------------------------------------------------------

    override fun excludedTools(mode: RunMode): Set<String> = buildSet {
        val serviceOn = PhoneControlService.instance != null
        if (!serviceOn) addAll(PhoneTools.SCREEN_TOOLS)
        if (mode == RunMode.REMOTE && !prefs.loadPhoneControlRemote()) addAll(PhoneTools.SCREEN_TOOLS)
        if (mode == RunMode.UNATTENDED && !prefs.loadPhoneControlBackground()) addAll(PhoneTools.SCREEN_TOOLS)
    }

    override fun beginControl(onStop: () -> Unit): AutoCloseable = PhoneControlSession.begin(context, onStop)

    override suspend fun riskContext(call: ToolCall): PhoneRiskContext {
        val blocklist = prefs.loadPhoneBlocklist()
        val svc = PhoneControlService.instance
        val a = call.arguments
        val (targetPkg, targetLabel) = if (call.name == PhoneTools.OPEN_APP) {
            val app = resolveApp(a["name"].orEmpty())
            app?.packageName to app?.label
        } else null to null
        if (svc == null) {
            return PhoneRiskContext(blocklist = blocklist, targetPackage = targetPkg, targetLabel = targetLabel, ownPackage = context.packageName)
        }
        val locked = svc.isKeyguardLocked()
        val fresh = if (locked) null else svc.capture(::labelOf, maxChars = 1_500)
        val snap = fresh?.snapshot
        val fg = snap?.packageName ?: svc.activePackage()
        fg?.let { checkedForeground[call.id] = it }
        val titles = snap?.elements?.map { it.label }?.filter { it.isNotBlank() }?.take(4).orEmpty()
        val target = if (snap == null) null else when (call.name) {
            PhoneTools.SCREEN_TAP -> tapTargetInfo(call, snap)
            PhoneTools.SCREEN_TYPE -> fieldInfo(a["target"], snap)
            else -> null
        }
        return PhoneRiskContext(
            keyguardLocked = locked,
            foregroundPackage = fg,
            foregroundLabel = fg?.let { labelOf(it) },
            windowClass = svc.windowClass,
            screenTitles = titles,
            target = target,
            targetPackage = targetPkg,
            targetLabel = targetLabel,
            blocklist = blocklist,
            ownPackage = context.packageName
        )
    }

    override suspend fun execute(call: ToolCall, mode: RunMode, grants: Set<String>): PhoneActionResult = mutex.withLock {
        val expectedForeground = checkedForeground.remove(call.id)
        try {
            executeLocked(call, grants, expectedForeground)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${call.name} failed: ${e.message}")
            PhoneActionResult("${call.name} failed: ${e.message ?: e.javaClass.simpleName}", ok = false)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------------------------

    private suspend fun executeLocked(call: ToolCall, grants: Set<String>, expectedForeground: String?): PhoneActionResult {
        val a = call.arguments
        if (call.name == PhoneTools.APPS_LIST) {
            return PhoneActionResult(AppMatcher.format(installedApps(), a["filter"], prefs.loadPhoneBlocklist()))
        }
        val svc = PhoneControlService.instance
            ?: return fail("Phone control is off. Ask the user to enable \"AMC Phone Control\" in AMC → Setup (Android accessibility settings).")

        if (call.name in PhoneTools.SCREEN_ACTIONS || call.name == PhoneTools.WAIT_FOR) {
            if (svc.isKeyguardLocked()) return fail("The phone is locked. AMC never operates or bypasses the lock screen; ask the user to unlock it.")
        }
        // The approval was for the app that was in front then; do not act on a different one.
        if (call.name in setOf(PhoneTools.SCREEN_TAP, PhoneTools.SCREEN_TYPE, PhoneTools.SCREEN_SCROLL, PhoneTools.SCREEN_SWIPE)) {
            val now = svc.activePackage()
            if (expectedForeground != null && now != null && now != expectedForeground) {
                modelSnapshot = null
                return fail("The app in front changed since this action was checked (now ${labelOf(now)}). Nothing was done; read the screen again.")
            }
        }

        return when (call.name) {
            PhoneTools.SCREEN_READ -> observe(svc, grants, headline = null, settle = false, filter = a["filter"])
            PhoneTools.SCREEN_TAP -> tap(svc, call, grants)
            PhoneTools.SCREEN_TYPE -> type(svc, call, grants)
            PhoneTools.SCREEN_SCROLL -> scroll(svc, call, grants)
            PhoneTools.SCREEN_SWIPE -> {
                fun n(k: String) = a[k]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                val ok = svc.swipe(n("x1"), n("y1"), n("x2"), n("y2"), a["duration_ms"]?.trim()?.toDoubleOrNull()?.toLong() ?: 300L)
                if (!ok) fail("The swipe could not be performed (outside the screen or cancelled).")
                else observe(svc, grants, "Swiped.")
            }
            PhoneTools.SCREEN_GLOBAL -> {
                val action = a["action"].orEmpty().trim().lowercase()
                if (!svc.global(action)) fail("Could not press '$action'.") else observe(svc, grants, "Pressed $action.")
            }
            PhoneTools.OPEN_APP -> openApp(svc, a["name"].orEmpty(), grants)
            PhoneTools.WAIT_FOR -> waitFor(svc, call, grants)
            PhoneTools.SCREEN_CAPTURE -> screenshot(svc, call, grants)
            else -> fail("Unknown phone tool '${call.name}'.")
        }
    }

    private suspend fun tap(svc: PhoneControlService, call: ToolCall, grants: Set<String>): PhoneActionResult {
        val a = call.arguments
        val longPress = PhoneTools.isTrue(a["long_press"])
        val target = a["target"]?.trim().orEmpty()
        if (target.isEmpty()) {
            val x = a["x"]?.trim()?.toDoubleOrNull()?.toInt() ?: return fail("x and y are required without a target.")
            val y = a["y"]?.trim()?.toDoubleOrNull()?.toInt() ?: return fail("x and y are required without a target.")
            if (!svc.tapAt(x, y, longPress)) return fail("The tap at $x,$y could not be performed (outside the screen?).")
            return observe(svc, grants, "${if (longPress) "Long-pressed" else "Tapped"} at $x,$y.")
        }
        val fresh = svc.capture(::labelOf) ?: return fail("The screen content is not available.")
        val el = locate(target, fresh.snapshot)
            ?: return fail("No element \"$target\" on the screen now (ids change when the screen changes). Read the screen again.")
        val clickEl = ElementMatcher.clickTarget(fresh.snapshot, el, longPress)
        val node = fresh.node(clickEl)
        val actionable = if (longPress) clickEl.node.longClickable else clickEl.node.clickable
        var done = node != null && actionable && svc.click(node, longPress)
        if (!done) done = svc.tapAt(el.node.bounds.centerX, el.node.bounds.centerY, longPress)
        if (!done) return fail("Could not tap ${describe(el)}.")
        return observe(svc, grants, "${if (longPress) "Long-pressed" else "Tapped"} ${describe(el)}.")
    }

    private suspend fun type(svc: PhoneControlService, call: ToolCall, grants: Set<String>): PhoneActionResult {
        val a = call.arguments
        val text = a["text"].orEmpty()
        val fresh = svc.capture(::labelOf) ?: return fail("The screen content is not available.")
        val field = findField(a["target"], fresh.snapshot)
            ?: return fail("No input field found. Tap the field first, or pass its id as target.")
        val node = fresh.node(field) ?: return fail("The input field is no longer available.")
        if (!svc.setText(node, text)) {
            // Some fields only accept text once focused by a real tap.
            svc.tapAt(field.node.bounds.centerX, field.node.bounds.centerY, false)
            delay(300)
            val again = svc.capture(::labelOf)
            val focused = again?.snapshot?.let { s -> ElementMatcher.relocate(field, s) ?: s.elements.firstOrNull { it.node.focused && it.node.editable } }
            val retryNode = focused?.let { again.node(it) }
            if (retryNode == null || !svc.setText(retryNode, text)) return fail("The field did not accept text.")
        }
        val what = if (field.node.password) "the password field" else describe(field)
        var headline = "Entered ${text.length} characters into $what."
        if (PhoneTools.isTrue(a["submit"])) {
            delay(150)
            val current = svc.capture(::labelOf)
            val target = current?.snapshot?.let { s -> ElementMatcher.relocate(field, s) ?: s.elements.firstOrNull { it.node.focused && it.node.editable } }
            val submitNode = target?.let { current.node(it) } ?: node
            headline += if (svc.imeEnter(submitNode)) " Pressed Enter." else
                " Enter could not be pressed (needs Android 11); tap the send or search button instead."
        }
        return observe(svc, grants, headline)
    }

    private suspend fun scroll(svc: PhoneControlService, call: ToolCall, grants: Set<String>): PhoneActionResult {
        val a = call.arguments
        val direction = a["direction"].orEmpty().trim().lowercase()
        val fresh = svc.capture(::labelOf) ?: return fail("The screen content is not available.")
        val before = fresh.snapshot.text
        val container = a["target"]?.takeIf { it.isNotBlank() }?.let { locate(it, fresh.snapshot) }
            ?.let { scrollableAncestor(fresh.snapshot, it) }
            ?: fresh.snapshot.elements.filter { it.node.scrollable }.maxByOrNull { it.node.bounds.area() }
        var done = container?.let { el -> fresh.node(el)?.let { svc.scroll(it, direction) } } == true
        if (!done) {
            val b = container?.node?.bounds ?: svc.screenBounds()
            val cx = b.centerX
            val cy = b.centerY
            val dy = (b.height * 0.3).toInt()
            val dx = (b.width * 0.3).toInt()
            done = when (direction) {
                "down" -> svc.swipe(cx, cy + dy, cx, cy - dy, 350)
                "up" -> svc.swipe(cx, cy - dy, cx, cy + dy, 350)
                "left" -> svc.swipe(cx - dx, cy, cx + dx, cy, 350)
                else -> svc.swipe(cx + dx, cy, cx - dx, cy, 350)
            }
        }
        if (!done) return fail("Could not scroll $direction.")
        val result = observe(svc, grants, "Scrolled $direction.")
        return if (result.ok && modelSnapshot?.text == before) {
            result.copy(text = result.text.replaceFirst("Scrolled $direction.", "Scrolled $direction, but the screen did not change (end of the list?)."))
        } else result
    }

    private suspend fun openApp(svc: PhoneControlService, name: String, grants: Set<String>): PhoneActionResult {
        val app = resolveApp(name) ?: return fail("No installed app matches \"$name\". Use apps_list to find it.")
        prefs.loadPhoneBlocklist().blockReason(app.packageName, app.label)?.let {
            return fail("$it. AMC does not open it; the user can release it in Settings → Phone control.")
        }
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return fail("${app.label} cannot be opened from the launcher.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        svc.startActivity(intent)
        val deadline = SystemClock.uptimeMillis() + 6_000L
        while (SystemClock.uptimeMillis() < deadline && svc.activePackage() != app.packageName) delay(200)
        val headline = if (svc.activePackage() == app.packageName) "Opened ${app.label}." else "Started ${app.label}, but it is not in front yet."
        return observe(svc, grants, headline)
    }

    private suspend fun waitFor(svc: PhoneControlService, call: ToolCall, grants: Set<String>): PhoneActionResult {
        val a = call.arguments
        val text = a["text"].orEmpty().trim()
        val gone = PhoneTools.isTrue(a["gone"])
        val timeoutMs = (a["timeout_seconds"]?.trim()?.toDoubleOrNull()?.toLong() ?: 10L).coerceIn(1L, 60L) * 1000L
        val start = SystemClock.uptimeMillis()
        while (true) {
            val snap = svc.capture(::labelOf)?.snapshot
            val present = snap != null && locate(text, snap) != null
            if (present != gone) {
                val waited = (SystemClock.uptimeMillis() - start) / 1000.0
                return observe(svc, grants, "\"$text\" ${if (gone) "disappeared" else "appeared"} after ${"%.1f".format(waited)} s.", settle = false)
            }
            if (SystemClock.uptimeMillis() - start >= timeoutMs) {
                val obs = observe(svc, grants, "Timed out after ${timeoutMs / 1000} s: \"$text\" ${if (gone) "is still there" else "did not appear"}.", settle = false)
                return obs.copy(ok = false)
            }
            delay(500)
        }
    }

    private suspend fun screenshot(svc: PhoneControlService, call: ToolCall, grants: Set<String>): PhoneActionResult {
        if (svc.isKeyguardLocked()) return fail("The phone is locked. AMC takes no screenshots of the lock screen.")
        val cap = svc.capture(::labelOf, maxChars = 1_500) ?: return fail("The screen content is not available right now.")
        // Same privacy gate as the element list: no image of a screen the run may not see.
        gateReason(cap.snapshot, svc.windowClass, grants)?.let { return fail(it) }
        return when (val shot = ScreenCapture.capture(context, svc)) {
            is ScreenCapture.Result.Refused -> fail(shot.reason)
            is ScreenCapture.Result.Image -> PhoneActionResult(
                text = "Screenshot of ${cap.snapshot.appLabel} (${shot.width}x${shot.height}) attached as an image. " +
                    "Coordinates in it are scaled; use screen_read for exact element positions.",
                imageBase64 = shot.base64Jpeg,
                sendImageToUser = PhoneTools.isTrue(call.arguments["send_to_user"])
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Observation and privacy gate
    // -------------------------------------------------------------------------------------------

    /** Waits until the UI is quiet (no events for a moment), at most a few seconds. */
    private suspend fun settle(svc: PhoneControlService, quietMs: Long = 350L, maxMs: Long = 2_500L) {
        delay(150)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - svc.lastEventAt >= quietMs) return
            delay(100)
        }
    }

    private suspend fun observe(
        svc: PhoneControlService,
        grants: Set<String>,
        headline: String?,
        settle: Boolean = true,
        filter: String? = null
    ): PhoneActionResult {
        if (settle) settle(svc)
        fun withHeadline(body: String) = if (headline == null) body else "$headline\n\n$body"
        if (svc.isKeyguardLocked()) {
            modelSnapshot = null
            return PhoneActionResult(withHeadline("The phone is locked. AMC does not read or operate the lock screen."))
        }
        val cap = svc.capture(::labelOf, filter = filter)
            ?: return PhoneActionResult(withHeadline("(The screen content is not available right now. Wait a moment and read it again.)"))
        val snap = cap.snapshot
        gateReason(snap, svc.windowClass, grants)?.let { reason ->
            modelSnapshot = null
            return PhoneActionResult(withHeadline(reason), ok = headline != null)
        }
        modelSnapshot = snap
        return PhoneActionResult(withHeadline(snap.text))
    }

    /** Why the current screen must not be shown in this run, or null when it may be. */
    private fun gateReason(snap: ScreenSnapshot, windowClass: String?, grants: Set<String>): String? {
        val pkg = snap.packageName
        val name = snap.appLabel
        if (pkg == context.packageName) return "AMC itself is in front. Its screen is not read. Use screen_global home or open_app."
        val blocklist = prefs.loadPhoneBlocklist()
        blocklist.blockReason(pkg, name, windowClass)?.let {
            return "$name is in front. $it, so its screen is not shown. Use screen_global back or home."
        }
        val titles = snap.elements.map { it.label }.filter { it.isNotBlank() }.take(4)
        val ctx = PhoneRiskContext(foregroundPackage = pkg, foregroundLabel = name, screenTitles = titles, blocklist = blocklist, ownPackage = context.packageName)
        if (PhoneToolPolicy.isSecuritySettingsScreen(ctx)) {
            return "A security settings page is in front; it is on the AMC block list and not shown. Use screen_global back."
        }
        return when (PhoneToolPolicy.appKind(pkg, name, context.packageName)) {
            AppKind.MESSAGING, AppKind.CONTACTS_CALLS, AppKind.CAMERA -> if ("read:$pkg" in grants) null else
                "$name is in front. Its screen can show private messages, contacts or photos, so it is only shown after the user approves: call screen_read."
            AppKind.SYSTEM_UI -> if ("notifications" in grants) null else
                "The notification shade or a system dialog is in front. It can show private messages, so it is only shown after the user approves: call screen_read."
            else -> null
        }
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    private fun fail(text: String) = PhoneActionResult(text, ok = false)

    private fun describe(el: ScreenElement): String {
        val label = if (el.node.password) "" else ScreenTreeSerializer.clean(el.label).orEmpty()
        return "[${el.id}] ${el.node.role}" + if (label.isNotEmpty()) " \"$label\"" else ""
    }

    /** An id from the last reading (re-located in [fresh]) or a text match in [fresh]. */
    private fun locate(target: String, fresh: ScreenSnapshot): ScreenElement? {
        val id = ElementMatcher.parseId(target)
        if (id != null) {
            val old = modelSnapshot?.byId(id) ?: return fresh.byId(id)
            return ElementMatcher.relocate(old, fresh)
        }
        return ElementMatcher.findByText(fresh, target)
    }

    private fun findField(target: String?, snap: ScreenSnapshot): ScreenElement? {
        if (!target.isNullOrBlank()) {
            val el = locate(target, snap) ?: return null
            if (el.node.editable || el.node.password) return el
            // A label next to a field: the first editable descendant.
            return snap.elements.firstOrNull { it.parentId == el.id && it.node.editable } ?: el
        }
        return snap.elements.firstOrNull { it.node.focused && it.node.editable }
            ?: snap.elements.filter { it.node.editable }.singleOrNull()
    }

    private fun scrollableAncestor(snap: ScreenSnapshot, el: ScreenElement): ScreenElement? {
        var current: ScreenElement? = el
        while (current != null) {
            if (current.node.scrollable) return current
            current = current.parentId?.let { snap.byId(it) }
        }
        return null
    }

    private fun tapTargetInfo(call: ToolCall, fresh: ScreenSnapshot): TargetInfo? {
        val a = call.arguments
        val target = a["target"]?.trim().orEmpty()
        val longPress = PhoneTools.isTrue(a["long_press"])
        val el = if (target.isNotEmpty()) locate(target, fresh) else {
            val x = a["x"]?.trim()?.toDoubleOrNull()?.toInt()
            val y = a["y"]?.trim()?.toDoubleOrNull()?.toInt()
            if (x == null || y == null) null else ElementMatcher.elementAt(fresh, x, y)
        } ?: return null
        val click = ElementMatcher.clickTarget(fresh, el, longPress)
        val labels = listOf(el.label, ElementMatcher.effectiveLabel(fresh, click)).filter { it.isNotBlank() }.distinct()
        return TargetInfo(
            label = labels.joinToString(" / "),
            password = el.node.password,
            editable = el.node.editable,
            viewId = click.node.viewId ?: el.node.viewId
        )
    }

    private fun fieldInfo(target: String?, fresh: ScreenSnapshot): TargetInfo? {
        val field = findField(target, fresh) ?: return null
        return TargetInfo(label = field.label, password = field.node.password, editable = field.node.editable, viewId = field.node.viewId)
    }

    fun labelOf(packageName: String): String {
        if (packageName.isEmpty()) return "unknown app"
        return labelCache.getOrPut(packageName) {
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) {
                packageName
            }
        }
    }

    /** Launchable apps (cached for a minute). */
    fun installedApps(): List<AppEntry> {
        val cached = appsCache
        val now = SystemClock.uptimeMillis()
        if (cached != null && now - cached.first < APPS_CACHE_MS) return cached.second
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            AppEntry(info.loadLabel(pm).toString(), pkg)
        }.distinctBy { it.packageName }
        appsCache = now to apps
        return apps
    }

    private fun resolveApp(name: String): AppEntry? {
        if (name.isBlank()) return null
        return AppMatcher.bestMatch(installedApps(), name)
    }
}
