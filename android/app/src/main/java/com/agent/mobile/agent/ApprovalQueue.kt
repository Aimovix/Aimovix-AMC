package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Keeps approvals that a background (UNATTENDED) run could not ask for. Instead of silently
 * skipping the action, the run parks it, the user is asked (notification, optionally Telegram),
 * and a new run continues the same session after the answer ([ResumeRequest]).
 */
interface ApprovalQueue {
    /** Stores [request] and asks the user. Returns the queue id, or null when it could not be parked. */
    suspend fun park(request: ParkedApproval): String?
}

/** One action waiting for the user's decision. [call] holds the real arguments (never a redacted call). */
data class ParkedApproval(
    val sessionId: String,
    val runTitle: String,
    val call: ToolCall,
    val description: String,
    val riskLevel: String,
    val riskReason: String,
    val fingerprint: String,
    val grantKey: String?,
    /** How many runs of this task already ran before this one (resumes and continuations). */
    val chain: Int
)

/**
 * Continues an existing session in a new background run: after an approval was answered, after
 * the step or time budget of a background slot ran out, or after AMC was restarted mid-task.
 */
data class ResumeRequest(
    val sessionId: String,
    /** Shown to the model as the new user turn (prefixed "[AMC]"). */
    val note: String,
    /** The approved call. Executed exactly once before the model continues; null otherwise. */
    val approvedCall: ToolCall? = null,
    /** Fingerprint the user approved; [approvedCall] runs only when it still matches. */
    val approvedFingerprint: String? = null,
    /** Phone tools are not replayed (the screen changed); their per-app grant is restored instead. */
    val grantKey: String? = null,
    val chain: Int = 0
) {
    companion object {
        /** Upper bound of runs per task (first run + resumes + continuations). */
        const val MAX_CHAIN = 6

        fun approvedNote(description: String) =
            "[AMC] The user approved the paused action `${description.take(300)}`."

        fun rejectedNote(description: String) =
            "[AMC] The user rejected the paused action `${description.take(300)}`. Do not retry it. Continue the " +
                "task without it if that makes sense, otherwise finish with a short summary of what is done and what is not."

        const val CONTINUE_NOTE =
            "[AMC] Continuation: the previous background slot ended (step or time budget). Look at what is " +
                "already done above and continue the task from there. Do not redo finished steps."

        const val RESTART_NOTE =
            "[AMC] AMC was restarted (app closed or phone rebooted) while you worked on this task. Look at what " +
                "is already done above and continue. Repeat a step only when its result is missing."
    }
}

/** Binds an approval to exactly one call: tool name plus all real arguments. */
object ApprovalFingerprint {
    private val json = Json
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun of(call: ToolCall): String = of(call.name, call.arguments)

    fun of(name: String, arguments: Map<String, String>): String {
        val canonical = buildString {
            append(name)
            arguments.toSortedMap().forEach { (k, v) -> append('\u0000').append(k).append('=').append(v) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun encodeArguments(arguments: Map<String, String>): String = json.encodeToString(mapSerializer, arguments)

    fun decodeArguments(text: String): Map<String, String>? = try {
        json.decodeFromString(mapSerializer, text)
    } catch (e: Exception) {
        null
    }
}
