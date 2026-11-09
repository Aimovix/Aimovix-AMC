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

    /**
     * Runs a phone tool. [grants] are the per-run approvals the user gave (for example
     * "read:com.whatsapp"); the result never shows a private app's screen without one.
     */
    suspend fun execute(call: ToolCall, mode: RunMode, grants: Set<String>): PhoneActionResult

    /** Tools to hide from the model in [mode] (feature switched off, or Shizuku not running). */
    fun excludedTools(mode: RunMode): Set<String>

    /**
     * Shows the visible "AMC is in control" overlay with a Stop button and the ongoing
     * notification. [onStop] runs when the user taps Stop. Closing the handle hides both.
     */
    fun beginControl(onStop: () -> Unit): AutoCloseable
}

/** Result of a phone tool. [imageBase64] is passed to vision models as an image input. */
data class PhoneActionResult(
    val text: String,
    val ok: Boolean = true,
    val imageBase64: String? = null,
    val imageMimeType: String = "image/jpeg",
    /** The user explicitly asked for the screenshot: deliver it to them (app or chat), not only to the model. */
    val sendImageToUser: Boolean = false
)
