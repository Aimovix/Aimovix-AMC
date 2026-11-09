package com.agent.mobile.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.agent.mobile.agent.PhoneActionResult
import com.agent.mobile.agent.PhoneController
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.agent.RunMode
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.data.storage.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of [PhoneController]. Screen tools run through [PhoneControlService] on
 * the [PhoneThread] (never the main thread); one action at a time (a mutex), and only one run at
 * a time may operate the screen ([ScreenLease]). Each action: check → act → settle → observe.
 *
 * Privacy gate: the screen after an action is only returned when the app in front may be read
 * in this run. Blocked apps, AMC itself and security settings are never shown; messaging,
 * contacts and camera apps only after the user approved reading them ([grants]).
 */
class AndroidPhoneController(
    private val context: Context,
    private val prefs: PreferenceManager,
    /** Schedules an unattended agent run (used by notification triggers). */
    private val scheduleTask: (title: String, prompt: String) -> Unit = { _, _ -> }
) : PhoneController {

    companion object {
        private const val TAG = "PhoneController"
        private const val APPS_CACHE_MS = 60_000L
        private const val DEFAULT_RUN = "default"
        /** Settle: quiet time after the last event of the app in front, and the hard cap. */
        private const val SETTLE_QUIET_MS = 300L
        private const val SETTLE_CAP_MS = 1_200L
        /** A capture is reused while the app sent no event since and it is younger than this. */
        private const val CAPTURE_REUSE_MS = 4_000L
        private const val RESTORE_WAIT_MS = 3_000L

        /** Identity of an action's target: package, label and view id. Approval covers exactly this. */
        fun fingerprint(pkg: String?, target: TargetInfo?): String? =
            if (pkg == null || target == null) null else "$pkg|${target.label.trim()}|${target.viewId.orEmpty()}"
    }

    private val mutex = Mutex()

    /** Per-run view of the screen: what the model saw, and what it saw when the current turn began. */
    internal class RunState {
        /** The last screen returned to the model; its ids are what the model refers to. */
        @Volatile var modelSnapshot: ScreenSnapshot? = null
        /** [modelSnapshot] at the start of the current model turn (all calls of a turn use it). */
        @Volatile var turnSnapshot: ScreenSnapshot? = null
    }

    private val runs = ConcurrentHashMap<String, RunState>()
    private fun run(runId: String): RunState = runs.getOrPut(runId) { RunState() }

    /** What a call looked like when it was risk-checked; the action is refused if that changed. */
    internal data class CheckedCall(
        val foreground: String,
        val fingerprint: String?,
        /** screen_actions: the fingerprint of each step's target when the batch was checked. */
        val stepFingerprints: List<String?> = emptyList()
    )

    private val checked = ConcurrentHashMap<String, CheckedCall>()
    private val approvedCalls = ConcurrentHashMap.newKeySet<String>()

    private val lease = ScreenLease()

    /** The last capture and when it was taken; reused by the next step while nothing changed. */
    @Volatile
    private var cache: Pair<Long, PhoneControlService.Capture>? = null

    private val labelCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var appsCache: Pair<Long, List<AppEntry>>? = null

    /** Notifications from the last notifications_list, by short id. */
    @Volatile
    private var lastNotifications: Map<String, NotificationInfo> = emptyMap()

    private val triggerLock = Any()

    private val pm: PackageManager get() = context.packageManager

    private val intents by lazy { SystemIntents(context, ::labelOf) }

    // -------------------------------------------------------------------------------------------
    // PhoneController
    // -------------------------------------------------------------------------------------------

    override fun excludedTools(mode: RunMode): Set<String> = buildSet {
        ShizukuShell.startListening()
        val serviceOn = PhoneControlService.instance != null
        if (!serviceOn) addAll(PhoneTools.SCREEN_TOOLS)
        if (mode == RunMode.REMOTE && !prefs.loadPhoneControlRemote()) addAll(PhoneTools.SCREEN_TOOLS)
        if (mode == RunMode.UNATTENDED && !prefs.loadPhoneControlBackground()) addAll(PhoneTools.SCREEN_TOOLS)
        if (AmcNotificationListener.instance == null) addAll(PhoneTools.NOTIFICATION_TOOLS)
        if (!intents.hasUsageAccess()) add(PhoneTools.USAGE_STATS)
        if (ShizukuShell.status(context) != ShizukuShell.Status.READY) add(PhoneTools.PRIVILEGED_SHELL)
    }

    override fun beginControl(onStop: () -> Unit): AutoCloseable = PhoneControlSession.begin(context, onStop)

    override fun beginTurn(runId: String) {
        val state = run(runId)
        state.turnSnapshot = state.modelSnapshot
    }

    override fun endRun(runId: String) {
        runs.remove(runId)
        lease.release(runId)
    }

    override fun noteApproved(callId: String) {
        approvedCalls += callId
    }

    override fun releaseCall(callId: String) {
        checked.remove(callId)
        approvedCalls.remove(callId)
    }

    override suspend fun requestOnScreenApproval(title: String, detail: String): Boolean? =
        PhoneControlSession.requestApproval(title, detail)

    override suspend fun riskContext(call: ToolCall): PhoneRiskContext = riskContext(call, DEFAULT_RUN)

    override suspend fun riskContext(call: ToolCall, runId: String): PhoneRiskContext = withContext(PhoneThread.dispatcher) {
        riskContextOnPhoneThread(call, run(runId))
    }

    private fun riskContextOnPhoneThread(call: ToolCall, state: RunState): PhoneRiskContext {
        val blocklist = prefs.loadPhoneBlocklist()
        val svc = PhoneControlService.instance
        val a = call.arguments
        val (targetPkg, targetLabel) = when (call.name) {
            PhoneTools.OPEN_APP -> resolveApp(a["name"].orEmpty()).let { it?.packageName to it?.label }
            PhoneTools.NOTIFICATION_TRIGGER -> a["app"]?.takeIf { it.isNotBlank() }
                ?.let { name -> resolveApp(name)?.let { it.packageName to it.label } ?: (null to name) } ?: (null to null)
            in PhoneTools.NOTIFICATION_TOOLS -> lastNotifications[a["id"].orEmpty().trim().lowercase()]
                .let { it?.packageName to it?.appLabel }
            else -> null to null
        }
        if (call.name in PhoneTools.INTENT_TOOLS) return PhoneRiskContext(blocklist = blocklist, ownPackage = context.packageName)
        if (call.name in PhoneTools.NOTIFICATION_TOOLS) {
            val locked = svc?.isKeyguardLocked() ?: false
            return PhoneRiskContext(keyguardLocked = locked, blocklist = blocklist, targetPackage = targetPkg, targetLabel = targetLabel, ownPackage = context.packageName)
        }
        if (svc == null) {
            return PhoneRiskContext(blocklist = blocklist, targetPackage = targetPkg, targetLabel = targetLabel, ownPackage = context.packageName)
        }
        val locked = svc.isKeyguardLocked()
        val fresh = if (locked) null else captureCached(svc)
        val snap = fresh?.snapshot
        val fg = snap?.packageName ?: svc.activePackage()
        val titles = snap?.elements?.map { it.label }?.filter { it.isNotBlank() }?.take(4).orEmpty()
        val target = if (snap == null) null else when (call.name) {
            PhoneTools.SCREEN_TAP -> tapTargetInfo(call.arguments, snap, state)
            PhoneTools.SCREEN_TYPE -> fieldInfo(a["target"], snap, state)
            else -> null
        }
        // A batch runs on the current screen: resolve every step's target there.
        val stepTargets = if (snap == null || call.name != PhoneTools.SCREEN_BATCH) emptyList() else
            ActionBatchParser.parse(a["steps"]).steps.map { step ->
                when (step.toolName) {
                    PhoneTools.SCREEN_TAP -> tapTargetInfo(step.args, snap, state)
                    PhoneTools.SCREEN_TYPE -> fieldInfo(step.args["target"], snap, state)
                    else -> null
                }
            }
        fg?.let { checked[call.id] = CheckedCall(it, fingerprint(it, target), stepTargets.map { t -> fingerprint(it, t) }) }
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
            ownPackage = context.packageName,
            stepTargets = stepTargets
        )
    }

    override suspend fun execute(call: ToolCall, mode: RunMode, grants: Set<String>): PhoneActionResult =
        execute(call, mode, grants, DEFAULT_RUN)

    override suspend fun execute(call: ToolCall, mode: RunMode, grants: Set<String>, runId: String): PhoneActionResult =
        withContext(PhoneThread.dispatcher) {
            mutex.withLock {
                val expected = checked.remove(call.id)
                val approved = approvedCalls.remove(call.id)
                try {
                    if (call.name in PhoneTools.SCREEN_TOOLS) {
                        lease.acquire(runId)?.let { return@withLock PhoneActionResult(it, ok = false, transient = true) }
                    }
                    executeLocked(call, grants, expected, approved, run(runId))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${call.name} failed: ${e.message}")
                    PhoneActionResult("${call.name} failed: ${e.message ?: e.javaClass.simpleName}", ok = false)
                }
            }
        }

    // -------------------------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------------------------

    private suspend fun executeLocked(
        call: ToolCall,
        grants: Set<String>,
        expected: CheckedCall?,
        approved: Boolean,
        state: RunState
    ): PhoneActionResult {
        val a = call.arguments
        if (call.name == PhoneTools.APPS_LIST) {
            return PhoneActionResult(AppMatcher.format(installedApps(), a["filter"], prefs.loadPhoneBlocklist()))
        }
        if (call.name in PhoneTools.NOTIFICATION_TOOLS) return notificationTool(call, grants, state)
        when (call.name) {
            PhoneTools.SET_ALARM -> return intents.setAlarm(a)
            PhoneTools.CALENDAR_ADD -> return intents.addCalendarEvent(a)
            PhoneTools.SHARE_TEXT -> return intents.share(a)
            PhoneTools.OPEN_URL -> return intents.openUrl(a["url"].orEmpty())
            PhoneTools.OPEN_SETTINGS -> return intents.openSettings(a["page"].orEmpty(), a["package"])
            PhoneTools.CLIPBOARD -> return intents.clipboard(a)
            PhoneTools.USAGE_STATS -> return intents.usage(a)
            PhoneTools.PRIVILEGED_SHELL -> return privilegedShell(a["command"].orEmpty())
        }
        val svc = PhoneControlService.instance
            ?: return fail("Phone control is off. Ask the user to enable \"AMC Phone Control\" in AMC → Setup (Android accessibility settings).")

        if (call.name in PhoneTools.SCREEN_TOOLS) {
            // A dark display is not the agent's fault: nothing to read or tap until the user wakes it.
            if (!svc.isInteractive()) {
                return PhoneActionResult("The display is off. Nothing was done; wait until the user turns the screen on.", ok = false, transient = true)
            }
        }
        if (call.name in PhoneTools.SCREEN_ACTIONS || call.name == PhoneTools.WAIT_FOR || call.name == PhoneTools.SCREEN_BATCH) {
            if (svc.isKeyguardLocked()) return fail("The phone is locked. AMC never operates or bypasses the lock screen; ask the user to unlock it.")
        }
        if (call.name in GUARDED_TOOLS) {
            guardTarget(svc, call, expected, approved, state)?.let { return it }
        }

        return when (call.name) {
            PhoneTools.SCREEN_READ -> observe(svc, grants, state, headline = null, settle = false, filter = a["filter"])
            PhoneTools.SCREEN_TAP -> single(svc, grants, state, doTap(svc, a, state))
            PhoneTools.SCREEN_TYPE -> single(svc, grants, state, doType(svc, a, state), AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
            PhoneTools.SCREEN_SCROLL -> scroll(svc, a, grants, state)
            PhoneTools.SCREEN_SWIPE -> single(svc, grants, state, doSwipe(svc, a))
            PhoneTools.SCREEN_GLOBAL -> single(svc, grants, state, doGlobal(svc, a))
            PhoneTools.OPEN_APP -> openApp(svc, a["name"].orEmpty(), grants, state)
            PhoneTools.WAIT_FOR -> waitFor(svc, call, grants, state)
            PhoneTools.SCREEN_CAPTURE -> screenshot(svc, call, grants)
            PhoneTools.SCREEN_BATCH -> ActionBatchExecutor(this, svc, grants, context.packageName)
                .run(call.id, a, state.toBatchContext(), expected, approved)
            else -> fail("Unknown phone tool '${call.name}'.")
        }
    }

    private val GUARDED_TOOLS = setOf(PhoneTools.SCREEN_TAP, PhoneTools.SCREEN_TYPE, PhoneTools.SCREEN_SCROLL, PhoneTools.SCREEN_SWIPE)

    /**
     * The approval was for the app (and element) that was in front then; never act on a different
     * one. If AMC itself came to the front (the user approved in the chat), bring the app back first.
     */
    private suspend fun guardTarget(
        svc: PhoneControlService,
        call: ToolCall,
        expected: CheckedCall?,
        approved: Boolean,
        state: RunState
    ): PhoneActionResult? {
        expected ?: return null
        var now = svc.activePackage()
        if (now == context.packageName && expected.foreground != context.packageName) {
            now = restoreForeground(svc, expected.foreground)
        }
        if (now != null && now != expected.foreground) {
            state.modelSnapshot = null
            return fail("The app in front changed since this action was checked (now ${labelOf(now)}). Nothing was done; read the screen again.")
        }
        if (approved && expected.fingerprint != null) {
            val snap = captureCached(svc)?.snapshot ?: return fail("The screen content is not available; nothing was done.")
            val target = when (call.name) {
                PhoneTools.SCREEN_TAP -> tapTargetInfo(call.arguments, snap, state)
                PhoneTools.SCREEN_TYPE -> fieldInfo(call.arguments["target"], snap, state)
                else -> null
            }
            if (fingerprint(snap.packageName, target) != expected.fingerprint) {
                state.modelSnapshot = null
                return fail("The screen changed while the approval was pending (the approved element is no longer there). Nothing was done; read the screen again and ask again if needed.")
            }
        }
        return null
    }

    /**
     * Brings [pkg] back to the front after the user switched to AMC to approve. Only
     * FLAG_ACTIVITY_NEW_TASK: the app's existing task comes to the front unchanged.
     */
    private suspend fun restoreForeground(svc: PhoneControlService, pkg: String): String? {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return svc.activePackage()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            svc.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring $pkg back: ${e.message}")
            return svc.activePackage()
        }
        val deadline = SystemClock.uptimeMillis() + RESTORE_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline && svc.activePackage() != pkg) delay(100)
        settle(svc, SystemClock.uptimeMillis())
        cache = null
        return svc.activePackage()
    }

    /** Outcome of one screen action before the screen is read again. */
    internal sealed class Act {
        data class Done(val headline: String) : Act()
        data class Failed(val reason: String) : Act()
    }

    private suspend fun single(
        svc: PhoneControlService,
        grants: Set<String>,
        state: RunState,
        act: Act,
        expectEvent: Int? = null
    ): PhoneActionResult = when (act) {
        is Act.Failed -> fail(act.reason)
        is Act.Done -> observe(svc, grants, state, act.headline, expectEvent = expectEvent)
    }

    internal suspend fun doTap(svc: PhoneControlService, a: Map<String, String>, state: RunState): Act {
        val longPress = PhoneTools.isTrue(a["long_press"])
        val target = a["target"]?.trim().orEmpty()
        val verb = if (longPress) "Long-pressed" else "Tapped"
        if (target.isEmpty()) {
            val x = a["x"]?.trim()?.toDoubleOrNull()?.toInt() ?: return Act.Failed("x and y are required without a target.")
            val y = a["y"]?.trim()?.toDoubleOrNull()?.toInt() ?: return Act.Failed("x and y are required without a target.")
            cache = null
            if (!svc.tapAt(x, y, longPress)) return Act.Failed("The tap at $x,$y could not be performed (outside the screen, on the AMC overlay, or cancelled).")
            return Act.Done("$verb at $x,$y.")
        }
        val fresh = captureCached(svc) ?: return Act.Failed("The screen content is not available.")
        val el = locate(target, fresh.snapshot, state)
            ?: return Act.Failed("No element \"$target\" on the screen now (ids change when the screen changes). Read the screen again.")
        val clickEl = ElementMatcher.clickTarget(fresh.snapshot, el, longPress)
        val node = fresh.node(clickEl)
        val actionable = if (longPress) clickEl.node.longClickable else clickEl.node.clickable
        cache = null
        var done = node != null && actionable && svc.click(node, longPress)
        if (!done) done = svc.tapAt(el.node.bounds.centerX, el.node.bounds.centerY, longPress)
        if (!done) return Act.Failed("Could not tap ${describe(el)}.")
        return Act.Done("$verb ${describe(el)}.")
    }

    internal suspend fun doType(svc: PhoneControlService, a: Map<String, String>, state: RunState): Act {
        val text = a["text"].orEmpty()
        val fresh = captureCached(svc) ?: return Act.Failed("The screen content is not available.")
        val field = findField(a["target"], fresh.snapshot, state)
            ?: return Act.Failed("No input field found. Tap the field first, or pass its id as target.")
        val node = fresh.node(field) ?: return Act.Failed("The input field is no longer available.")
        cache = null
        val entered = svc.enterText(node, text)
        if (!entered.ok) return Act.Failed(entered.diagnostic)
        val what = if (field.node.password) "the password field" else describe(field)
        var headline = "Entered ${text.length} characters into $what" + if (entered.method != "set_text") " (via ${entered.method})." else "."
        if (PhoneTools.isTrue(a["submit"])) {
            headline += " " + submit(svc, field, text)
        }
        return Act.Done(headline)
    }

    /** Submits typed text: the send button in messengers (verified by the field emptying), the keyboard action elsewhere. */
    private suspend fun submit(svc: PhoneControlService, field: ScreenElement, text: String): String {
        delay(150)
        val current = svc.capture(::labelOf) ?: return "The screen was not readable after typing; nothing was submitted."
        val snap = current.snapshot
        val located = ElementMatcher.relocate(field, snap) ?: snap.elements.firstOrNull { it.node.focused && it.node.editable }
        val kind = PhoneToolPolicy.appKind(snap.packageName, snap.appLabel, context.packageName)
        return when (val plan = if (located == null) SubmitResolver.Plan.ImeAction else SubmitResolver.plan(snap, located, kind)) {
            is SubmitResolver.Plan.TapButton -> {
                val node = current.node(plan.button)
                cache = null
                val tapped = (node != null && svc.click(node, false)) ||
                    svc.tapAt(plan.button.node.bounds.centerX, plan.button.node.bounds.centerY, false)
                if (!tapped) return "The send button ${describe(plan.button)} could not be tapped; the text was not sent."
                settle(svc, SystemClock.uptimeMillis())
                val after = svc.capture(::labelOf)?.snapshot
                val fieldAfter = after?.let { ElementMatcher.relocate(located!!, it) }
                if (after != null && SubmitResolver.fieldCleared(fieldAfter, text)) "Tapped send ${describe(plan.button)}; the message field is empty now (sent)."
                else "Tapped send ${describe(plan.button)}, but the text is still in the field: it was probably NOT sent. Read the screen."
            }
            is SubmitResolver.Plan.NotFound -> "${plan.reason} Nothing was sent; tap the send button yourself (screen_read shows it)."
            SubmitResolver.Plan.ImeAction -> {
                val node = located?.let { current.node(it) }
                cache = null
                if (node != null && svc.imeEnter(node)) "Pressed the keyboard action key (Enter / Search)."
                else "Enter could not be pressed (needs Android 11); tap the send or search button instead."
            }
        }
    }

    internal suspend fun doScroll(svc: PhoneControlService, a: Map<String, String>, state: RunState): Act {
        val direction = a["direction"].orEmpty().trim().lowercase()
        val fresh = captureCached(svc) ?: return Act.Failed("The screen content is not available.")
        val container = a["target"]?.takeIf { it.isNotBlank() }?.let { locate(it, fresh.snapshot, state) }
            ?.let { scrollableAncestor(fresh.snapshot, it) }
            ?: fresh.snapshot.elements.filter { it.node.scrollable }.maxByOrNull { it.node.bounds.area() }
        cache = null
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
        return if (done) Act.Done("Scrolled $direction.") else Act.Failed("Could not scroll $direction.")
    }

    internal suspend fun doSwipe(svc: PhoneControlService, a: Map<String, String>): Act {
        fun n(k: String) = a[k]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        cache = null
        val ok = svc.swipe(n("x1"), n("y1"), n("x2"), n("y2"), a["duration_ms"]?.trim()?.toDoubleOrNull()?.toLong() ?: 300L)
        return if (ok) Act.Done("Swiped.") else Act.Failed("The swipe could not be performed (outside the screen, on the AMC overlay, or cancelled).")
    }

    internal fun doGlobal(svc: PhoneControlService, a: Map<String, String>): Act {
        val action = a["action"].orEmpty().trim().lowercase()
        cache = null
        return if (svc.global(action)) Act.Done("Pressed $action.") else Act.Failed("Could not press '$action'.")
    }

    private suspend fun scroll(svc: PhoneControlService, a: Map<String, String>, grants: Set<String>, state: RunState): PhoneActionResult {
        val before = captureCached(svc)?.snapshot?.text
        val direction = a["direction"].orEmpty().trim().lowercase()
        val result = single(svc, grants, state, doScroll(svc, a, state))
        return if (result.ok && before != null && state.modelSnapshot?.text == before) {
            result.copy(text = result.text.replaceFirst("Scrolled $direction.", "Scrolled $direction, but the screen did not change (end of the list?)."))
        } else result
    }

    private suspend fun openApp(svc: PhoneControlService, name: String, grants: Set<String>, state: RunState): PhoneActionResult {
        val app = resolveApp(name) ?: return fail("No installed app matches \"$name\". Use apps_list to find it.")
        prefs.loadPhoneBlocklist().blockReason(app.packageName, app.label)?.let {
            return fail("$it. AMC does not open it; the user can release it in Settings → Phone control.")
        }
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return fail("${app.label} cannot be opened from the launcher.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        cache = null
        svc.startActivity(intent)
        val deadline = SystemClock.uptimeMillis() + 6_000L
        while (SystemClock.uptimeMillis() < deadline && svc.activePackage() != app.packageName) delay(200)
        val headline = if (svc.activePackage() == app.packageName) "Opened ${app.label}." else "Started ${app.label}, but it is not in front yet."
        return observe(svc, grants, state, headline)
    }

    private suspend fun waitFor(svc: PhoneControlService, call: ToolCall, grants: Set<String>, state: RunState): PhoneActionResult {
        val a = call.arguments
        val text = a["text"].orEmpty().trim()
        val gone = PhoneTools.isTrue(a["gone"])
        val timeoutMs = (a["timeout_seconds"]?.trim()?.toDoubleOrNull()?.toLong() ?: 10L).coerceIn(1L, 60L) * 1000L
        val start = SystemClock.uptimeMillis()
        while (true) {
            val snap = captureCached(svc)?.snapshot
            val present = snap != null && locate(text, snap, state) != null
            if (present != gone) {
                val waited = (SystemClock.uptimeMillis() - start) / 1000.0
                return observe(svc, grants, state, "\"$text\" ${if (gone) "disappeared" else "appeared"} after ${"%.1f".format(waited)} s.", settle = false)
            }
            if (SystemClock.uptimeMillis() - start >= timeoutMs) {
                val obs = observe(svc, grants, state, "Timed out after ${timeoutMs / 1000} s: \"$text\" ${if (gone) "is still there" else "did not appear"}.", settle = false)
                return obs.copy(ok = false)
            }
            delay(500)
        }
    }

    private suspend fun screenshot(svc: PhoneControlService, call: ToolCall, grants: Set<String>): PhoneActionResult {
        if (svc.isKeyguardLocked()) return fail("The phone is locked. AMC takes no screenshots of the lock screen.")
        val cap = captureCached(svc) ?: return fail("The screen content is not available right now.")
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

    private suspend fun privilegedShell(command: String): PhoneActionResult {
        val args = PrivilegedCommandPolicy.tokenize(command) ?: return fail("Only one plain command is allowed (no shell syntax).")
        if (args.first() !in PrivilegedCommandPolicy.ALLOWED_BINARIES) return fail("Command not allowed in privileged_shell.")
        if (ShizukuShell.status(context) != ShizukuShell.Status.READY) {
            return fail("Shizuku is not running or AMC has no Shizuku permission. Ask the user to set it up in AMC → Setup.")
        }
        val r = ShizukuShell.run(context, args)
        val body = buildString {
            append("exit_code: ").append(r.exitCode)
            if (r.stdout.isNotBlank()) append("\n").append(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) append("\nstderr: ").append(r.stderr.trimEnd())
        }
        return PhoneActionResult(body, ok = r.exitCode == 0 && !r.timedOut)
    }

    // -------------------------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------------------------

    private suspend fun notificationTool(call: ToolCall, grants: Set<String>, state: RunState): PhoneActionResult {
        val a = call.arguments
        if (call.name == PhoneTools.NOTIFICATION_TRIGGER) return triggerTool(call)
        val listener = AmcNotificationListener.instance
            ?: return fail("Notification access is off. Ask the user to allow it in AMC → Setup.")
        val blocklist = prefs.loadPhoneBlocklist()
        if (call.name == PhoneTools.NOTIFICATIONS_LIST) {
            val items = listener.snapshot(::labelOf)
            lastNotifications = items.associate { it.second.id to it.second }
            return PhoneActionResult(NotificationFormatter.format(items.map { it.second }, blocklist, appFilter = a["app"]))
        }
        val id = a["id"].orEmpty().trim().lowercase()
        val info = lastNotifications[id] ?: return fail("Unknown notification id '$id'. Call notifications_list first.")
        blocklist.blockReason(info.packageName, info.appLabel)?.let { return fail("$it. AMC does not touch its notifications.") }
        val sbn = listener.find(info.key) ?: return fail("That notification is gone. Call notifications_list again.")
        return when (call.name) {
            PhoneTools.NOTIFICATION_OPEN -> {
                if (!listener.open(sbn)) return fail("This notification cannot be opened.")
                val svc = PhoneControlService.instance ?: return PhoneActionResult("Opened the notification of ${info.appLabel}.")
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (SystemClock.uptimeMillis() < deadline && svc.activePackage() != info.packageName) delay(200)
                observe(svc, grants, state, "Opened the notification of ${info.appLabel}.")
            }
            PhoneTools.NOTIFICATION_REPLY -> {
                val text = a["text"].orEmpty()
                if (listener.reply(sbn, text)) PhoneActionResult("Reply sent via ${info.appLabel} (${text.length} characters).")
                else fail("This notification has no reply action. Open it and reply in the app instead.")
            }
            PhoneTools.NOTIFICATION_DISMISS -> {
                listener.dismiss(sbn)
                PhoneActionResult("Dismissed the notification of ${info.appLabel}.")
            }
            else -> fail("Unknown notification tool '${call.name}'.")
        }
    }

    private fun triggerTool(call: ToolCall): PhoneActionResult {
        val a = call.arguments
        synchronized(triggerLock) {
            val triggers = prefs.loadNotificationTriggers()
            return when (a["action"].orEmpty().trim().lowercase()) {
                "list" -> PhoneActionResult(NotificationTriggers.describe(triggers))
                "remove" -> {
                    val id = a["id"].orEmpty().trim()
                    val rest = triggers.filter { it.id != id }
                    if (rest.size == triggers.size) fail("No trigger with id '$id'.") else {
                        prefs.saveNotificationTriggers(rest)
                        PhoneActionResult("Removed trigger $id.")
                    }
                }
                "add" -> {
                    if (triggers.size >= NotificationTriggers.MAX_TRIGGERS) return fail("At most ${NotificationTriggers.MAX_TRIGGERS} triggers. Remove one first.")
                    val app = resolveApp(a["app"].orEmpty()) ?: return fail("No installed app matches \"${a["app"]}\".")
                    prefs.loadPhoneBlocklist().blockReason(app.packageName, app.label)?.let { return fail("$it.") }
                    val trigger = NotificationTrigger(
                        packageName = app.packageName,
                        appLabel = app.label,
                        contains = a["contains"]?.trim()?.takeIf { it.isNotEmpty() },
                        prompt = a["prompt"].orEmpty().trim().take(4_000)
                    )
                    prefs.saveNotificationTriggers(triggers + trigger)
                    PhoneActionResult(
                        "Trigger ${trigger.id} added: notifications from ${app.label}" +
                            (trigger.contains?.let { " containing \"$it\"" } ?: "") +
                            " start a background task (at most once a minute). Notification access must stay on."
                    )
                }
                else -> fail("action must be add, list or remove.")
            }
        }
    }

    /** Called by the notification listener for every new notification: fires matching triggers. */
    fun onNotificationPosted(packageName: String, title: String, text: String) {
        val toFire = mutableListOf<NotificationTrigger>()
        synchronized(triggerLock) {
            val triggers = prefs.loadNotificationTriggers()
            if (triggers.isEmpty()) return
            val blocklist = prefs.loadPhoneBlocklist()
            val now = System.currentTimeMillis()
            val updated = triggers.map { t ->
                if (NotificationTriggers.matches(t, packageName, title, text) && NotificationTriggers.shouldFire(t, now) &&
                    !blocklist.isBlocked(t.packageName, t.appLabel)
                ) {
                    toFire += t
                    t.copy(lastFiredAt = now)
                } else t
            }
            if (toFire.isNotEmpty()) prefs.saveNotificationTriggers(updated)
        }
        toFire.forEach { t ->
            try {
                scheduleTask("Notification: ${t.appLabel}", NotificationTriggers.runPrompt(t, title, text))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the trigger task: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Observation and privacy gate
    // -------------------------------------------------------------------------------------------

    /**
     * Captures the screen, or reuses the last capture while the app in front sent no event since
     * (the risk check and the action then share one capture instead of reading the tree twice).
     */
    internal fun captureCached(svc: PhoneControlService): PhoneControlService.Capture? {
        val now = SystemClock.uptimeMillis()
        cache?.let { (at, cap) ->
            if (svc.lastEventAt < at && now - at < CAPTURE_REUSE_MS) return cap
        }
        val cap = svc.capture(::labelOf) ?: return null
        cache = now to cap
        return cap
    }

    /**
     * Waits until the app in front is quiet, at most [capMs]: an event of the app must have
     * arrived after [since] (of [expectEvent] when given, e.g. text changed after typing), then
     * no event for a short moment. Events of AMC, the keyboard and the status bar do not count.
     */
    internal suspend fun settle(svc: PhoneControlService, since: Long, expectEvent: Int? = null, capMs: Long = SETTLE_CAP_MS) {
        delay(80)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < capMs) {
            val reacted = if (expectEvent != null) svc.lastEventAt(expectEvent) >= since else svc.lastEventAt >= since
            if (reacted && SystemClock.uptimeMillis() - svc.lastEventAt >= SETTLE_QUIET_MS) return
            delay(50)
        }
    }

    private suspend fun observe(
        svc: PhoneControlService,
        grants: Set<String>,
        state: RunState,
        headline: String?,
        settle: Boolean = true,
        filter: String? = null,
        expectEvent: Int? = null
    ): PhoneActionResult {
        if (settle) settle(svc, SystemClock.uptimeMillis() - 200, expectEvent)
        fun withHeadline(body: String) = if (headline == null) body else "$headline\n\n$body"
        if (!svc.isInteractive()) {
            state.modelSnapshot = null
            return PhoneActionResult(withHeadline("The display is off. Nothing can be read until the user turns it on."), ok = headline != null, transient = headline == null)
        }
        if (svc.isKeyguardLocked()) {
            state.modelSnapshot = null
            return PhoneActionResult(withHeadline("The phone is locked. AMC does not read or operate the lock screen."))
        }
        val cap = (if (filter == null) captureCached(svc) else svc.capture(::labelOf, filter = filter))
            ?: return PhoneActionResult(withHeadline("(The screen content is not available right now. Wait a moment and read it again.)"))
        val snap = cap.snapshot
        gateReason(snap, svc.windowClass, grants)?.let { reason ->
            state.modelSnapshot = null
            return PhoneActionResult(withHeadline(reason), ok = headline != null)
        }
        state.modelSnapshot = snap
        return PhoneActionResult(withHeadline(snap.text))
    }

    /** Batch support: the screen the batch's ids refer to, and recording what the model sees last. */
    internal inner class BatchContext(private val state: RunState) {
        val baseSnapshot: ScreenSnapshot? get() = state.turnSnapshot ?: state.modelSnapshot
        fun publish(snapshot: ScreenSnapshot?) {
            state.modelSnapshot = snapshot
        }
        suspend fun tap(svc: PhoneControlService, a: Map<String, String>) = doTap(svc, a, state)
        suspend fun type(svc: PhoneControlService, a: Map<String, String>) = doType(svc, a, state)
        suspend fun scroll(svc: PhoneControlService, a: Map<String, String>) = doScroll(svc, a, state)
        suspend fun swipe(svc: PhoneControlService, a: Map<String, String>) = doSwipe(svc, a)
        fun global(svc: PhoneControlService, a: Map<String, String>) = doGlobal(svc, a)
        fun locate(target: String, fresh: ScreenSnapshot) = this@AndroidPhoneController.locate(target, fresh, state)
        fun targetInfo(name: String, a: Map<String, String>, fresh: ScreenSnapshot): TargetInfo? = when (name) {
            PhoneTools.SCREEN_TAP -> tapTargetInfo(a, fresh, state)
            PhoneTools.SCREEN_TYPE -> fieldInfo(a["target"], fresh, state)
            else -> null
        }
    }

    private fun RunState.toBatchContext() = BatchContext(this)

    internal fun gate(snap: ScreenSnapshot, windowClass: String?, grants: Set<String>): String? = gateReason(snap, windowClass, grants)

    /** Why the current screen must not be shown in this run, or null when it may be. */
    private fun gateReason(snap: ScreenSnapshot, windowClass: String?, grants: Set<String>): String? {
        val pkg = snap.packageName
        val name = snap.appLabel
        if (pkg == context.packageName) return "AMC itself is in front. Its screen is not read. Use screen_global home or open_app."
        if (pkg in PhoneToolPolicy.TERMINAL_PACKAGES) return "$name is in front. ${PhoneToolPolicy.TERMINAL_REASON}"
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

    internal fun describe(el: ScreenElement): String {
        val label = if (el.node.password) "" else ScreenTreeSerializer.clean(el.label).orEmpty()
        return "[${el.id}] ${el.node.role}" + if (label.isNotEmpty()) " \"$label\"" else ""
    }

    /**
     * An id the model saw (resolved against the screen at the start of this turn and re-located
     * in [fresh]) or a text match in [fresh].
     */
    private fun locate(target: String, fresh: ScreenSnapshot, state: RunState): ScreenElement? {
        val id = ElementMatcher.parseId(target)
        if (id != null) {
            val seen = state.turnSnapshot ?: state.modelSnapshot
            val old = seen?.byId(id) ?: return fresh.byId(id)
            return ElementMatcher.relocate(old, fresh)
        }
        return ElementMatcher.findByText(fresh, target)
    }

    /** The field to type into; never an element that cannot take text. */
    private fun findField(target: String?, snap: ScreenSnapshot, state: RunState): ScreenElement? {
        if (!target.isNullOrBlank()) {
            val el = locate(target, snap, state) ?: return null
            return ElementMatcher.findInputField(snap, el)
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

    private fun tapTargetInfo(a: Map<String, String>, fresh: ScreenSnapshot, state: RunState): TargetInfo? {
        val target = a["target"]?.trim().orEmpty()
        val longPress = PhoneTools.isTrue(a["long_press"])
        val el = if (target.isNotEmpty()) locate(target, fresh, state) else {
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

    private fun fieldInfo(target: String?, fresh: ScreenSnapshot, state: RunState): TargetInfo? {
        val field = findField(target, fresh, state) ?: return null
        // The hint, not the typed text: the identity of a field must not change while typing.
        val label = field.node.hint?.takeIf { it.isNotBlank() } ?: field.label
        return TargetInfo(label = label, password = field.node.password, editable = field.node.editable, viewId = field.node.viewId)
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
