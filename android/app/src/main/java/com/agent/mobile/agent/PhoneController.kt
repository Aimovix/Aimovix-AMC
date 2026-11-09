package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.phone.PhoneRiskContext

/**
 * The phone control layer as the agent engine sees it. The Android implementation drives the
 * accessibility service, notification listener, system intents and Shizuku; tests use fakes.
 */
interface PhoneController {
    /** What the risk check needs to know for [call]: foreground app, resolved target, lock state, block list. */
    suspend fun riskContext(call: ToolCall): PhoneRiskContext

    /** [riskContext] for one run: element ids resolve against what that run's model saw. */
    suspend fun riskContext(call: ToolCall, runId: String): PhoneRiskContext = riskContext(call)

    /**
     * Runs a phone tool. [grants] are the per-run approvals the user gave (for example
     * "read:com.whatsapp"); the result never shows a private app's screen without one.
     */
    suspend fun execute(call: ToolCall, mode: RunMode, grants: Set<String>): PhoneActionResult

    /** [execute] for one run. Only one run at a time may operate the screen (a lease per run). */
    suspend fun execute(call: ToolCall, mode: RunMode, grants: Set<String>, runId: String): PhoneActionResult =
        execute(call, mode, grants)

    /** Tools to hide from the model in [mode] (feature switched off, or Shizuku not running). */
    fun excludedTools(mode: RunMode): Set<String>

    /**
     * Shows the visible "AMC is in control" overlay with a Stop button and the ongoing
     * notification. [onStop] runs when the user taps Stop. Closing the handle hides both.
     */
    fun beginControl(onStop: () -> Unit): AutoCloseable

    /**
     * A new model turn of [runId] starts: element ids in its tool calls refer to the screen the
     * model saw before this turn, even when an earlier call of the same turn changed the screen.
     */
    fun beginTurn(runId: String) {}

    /** The run ended: release its screen lease and per-run state. */
    fun endRun(runId: String) {}

    /** [callId] was approved by the user: execute it only if its target is still the same. */
    fun noteApproved(callId: String) {}

    /** [callId] will not run (rejected, blocked, loop protection): forget what was checked for it. */
    fun releaseCall(callId: String) {}

    /**
     * Asks for approval with a panel directly above the app in front, so the user does not have
     * to switch to AMC (which would put AMC in front and void the action). Null when not possible.
     */
    suspend fun requestOnScreenApproval(title: String, detail: String): Boolean? = null
}

/** Result of a phone tool. [imageBase64] is passed to vision models as an image input. */
data class PhoneActionResult(
    val text: String,
    val ok: Boolean = true,
    val imageBase64: String? = null,
    val imageMimeType: String = "image/jpeg",
    /** The user explicitly asked for the screenshot: deliver it to them (app or chat), not only to the model. */
    val sendImageToUser: Boolean = false,
    /**
     * The failure is not the agent's fault and passes by itself (display off, screen busy with
     * another task): it does not count towards loop protection.
     */
    val transient: Boolean = false
)
