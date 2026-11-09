package com.agent.mobile.phone

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.agent.mobile.agent.PhoneActionResult
import com.agent.mobile.agent.PhoneTools
import kotlinx.coroutines.delay

/**
 * Runs a screen_actions batch: up to eight steps with one screen reading at the end instead of
 * one after every step.
 *
 * Safety is the same as for single tools:
 * - Before each step the step is assessed again on the current screen (cached capture). A
 *   blocked step stops the batch. A step that always needs the user runs only if the batch was
 *   approved for exactly that target (package, label and view id unchanged); otherwise the batch
 *   stops and the model must ask for that step on its own.
 * - The batch stops at the first failed step, a target that is gone, another app in front, a
 *   new window or dialog, the lock screen, or an `expect` text that did not appear.
 * Between steps there is only a short event-driven settle, no reading.
 */
internal class ActionBatchExecutor(
    private val controller: AndroidPhoneController,
    private val svc: PhoneControlService,
    private val grants: Set<String>,
    private val ownPackage: String
) {
    companion object {
        private const val STEP_SETTLE_MS = 800L
    }

    suspend fun run(
        callId: String,
        args: Map<String, String>,
        ctx: AndroidPhoneController.BatchContext,
        checked: AndroidPhoneController.CheckedCall?,
        approved: Boolean
    ): PhoneActionResult {
        val parsed = ActionBatchParser.parse(args["steps"])
        parsed.error?.let { return PhoneActionResult(it, ok = false) }
        val observeMode = args["observe"]?.trim()?.lowercase()?.takeIf { it in setOf("final", "delta", "none") } ?: "final"
        val base = ctx.baseSnapshot
        val startPackage = svc.activePackage()
        val lines = mutableListOf<String>()
        var stopped: String? = null

        for ((i, step) in parsed.steps.withIndex()) {
            val n = i + 1
            if (svc.isKeyguardLocked()) {
                stopped = "the phone is locked"; break
            }
            val pkg = svc.activePackage()
            if (pkg != startPackage) {
                stopped = "another app is in front now (${pkg?.let(controller::labelOf) ?: "unknown"})"; break
            }
            val fresh = controller.captureCached(svc) ?: run { stopped = "the screen is not readable"; null } ?: break
            val stepCall = step.toToolCall(callId, i)

            // Re-check this step on the screen as it is now.
            val target = ctx.targetInfo(stepCall.name, stepCall.arguments, fresh.snapshot)
            val risk = PhoneToolPolicy.assess(
                stepCall,
                PhoneRiskContext(
                    keyguardLocked = false,
                    foregroundPackage = fresh.snapshot.packageName,
                    foregroundLabel = fresh.snapshot.appLabel,
                    windowClass = svc.windowClass,
                    screenTitles = fresh.snapshot.elements.map { it.label }.filter { it.isNotBlank() }.take(4),
                    target = target,
                    ownPackage = ownPackage
                )
            )
            if (risk.isBlocked) {
                lines += "✗ $n. ${step.action}: blocked (${risk.reason})"
                stopped = "step $n is blocked"; break
            }
            val coveredByGrant = risk.grantKey != null && risk.grantKey in grants
            if (risk.alwaysConfirm && !coveredByGrant) {
                val now = AndroidPhoneController.fingerprint(fresh.snapshot.packageName, target)
                val approvedThen = checked?.stepFingerprints?.getOrNull(i)
                if (!approved || now == null || now != approvedThen) {
                    lines += "✗ $n. ${step.action}: needs its own approval (${risk.reason})"
                    stopped = "step $n was not approved for this element; call it as a single tool"; break
                }
            }

            val windowsBefore = svc.lastEventAt(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            val startedAt = SystemClock.uptimeMillis()
            val act = when (step.action) {
                "tap" -> ctx.tap(svc, step.args)
                "type" -> ctx.type(svc, step.args)
                "scroll" -> ctx.scroll(svc, step.args)
                "swipe" -> ctx.swipe(svc, step.args)
                "global" -> ctx.global(svc, step.args)
                "wait_for" -> waitFor(step, ctx)
                else -> AndroidPhoneController.Act.Failed("unknown action")
            }
            when (act) {
                is AndroidPhoneController.Act.Failed -> {
                    lines += "✗ $n. ${step.action}: ${act.reason}"
                    stopped = "step $n failed"
                }
                is AndroidPhoneController.Act.Done -> lines += "✓ $n. ${act.headline}"
            }
            if (stopped != null) break
            val expectEvent = if (step.action == "type") AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED else null
            if (step.action != "wait_for") controller.settle(svc, startedAt, expectEvent, STEP_SETTLE_MS)

            step.expect?.let { expected ->
                val after = controller.captureCached(svc)?.snapshot
                if (after == null || ctx.locate(expected, after) == null) {
                    lines += "✗ $n. expected \"${expected.take(60)}\" is not on the screen"
                    stopped = "the expected result of step $n did not appear"
                }
            }
            if (stopped != null) break
            val isLast = i == parsed.steps.lastIndex
            if (!isLast && svc.lastEventAt(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) > windowsBefore) {
                stopped = "a new window or dialog opened after step $n"; break
            }
        }

        val done = lines.count { it.startsWith("✓") }
        val skipped = parsed.steps.size - lines.size
        val header = buildString {
            append("screen_actions: ").append(done).append('/').append(parsed.steps.size).append(" steps done")
            if (stopped != null) append(" — stopped: ").append(stopped)
            if (skipped > 0) append(" (").append(skipped).append(" not run)")
            append('.')
        }
        val report = (listOf(header) + lines).joinToString("\n")
        val ok = stopped == null
        if (observeMode == "none") return PhoneActionResult(report, ok = ok)
        return PhoneActionResult(observeAfter(report, observeMode, base, ctx), ok = ok)
    }

    private suspend fun waitFor(step: ActionBatchParser.Step, ctx: AndroidPhoneController.BatchContext): AndroidPhoneController.Act {
        val text = step.args["text"].orEmpty().trim()
        val gone = PhoneTools.isTrue(step.args["gone"])
        val timeoutMs = (step.args["timeout_seconds"]?.trim()?.toDoubleOrNull()?.toLong() ?: 5L).coerceIn(1L, 30L) * 1000L
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val snap = controller.captureCached(svc)?.snapshot
            val present = snap != null && ctx.locate(text, snap) != null
            if (present != gone) return AndroidPhoneController.Act.Done("\"${text.take(60)}\" ${if (gone) "disappeared" else "appeared"}.")
            delay(300)
        }
        return AndroidPhoneController.Act.Failed("\"${text.take(60)}\" ${if (gone) "is still there" else "did not appear"} within ${timeoutMs / 1000} s")
    }

    /** One reading after the batch: the change since the model's last reading, or the full screen. */
    private suspend fun observeAfter(
        report: String,
        mode: String,
        base: ScreenSnapshot?,
        ctx: AndroidPhoneController.BatchContext
    ): String {
        if (!svc.isInteractive()) {
            ctx.publish(null)
            return "$report\n\nThe display is off."
        }
        if (svc.isKeyguardLocked()) {
            ctx.publish(null)
            return "$report\n\nThe phone is locked. AMC does not read or operate the lock screen."
        }
        val cap = controller.captureCached(svc) ?: return "$report\n\n(The screen content is not available right now.)"
        controller.gate(cap.snapshot, svc.windowClass, grants)?.let { reason ->
            ctx.publish(null)
            return "$report\n\n$reason"
        }
        ctx.publish(cap.snapshot)
        val body = if (mode == "delta") ScreenDiff.delta(base, cap.snapshot) ?: cap.snapshot.text else cap.snapshot.text
        return "$report\n\n$body"
    }
}
