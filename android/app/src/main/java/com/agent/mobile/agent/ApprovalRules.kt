package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.SecurityAssessment
import java.io.File

/**
 * Actions the user chose to "always allow". A rule covers exactly one action (tool name plus all
 * its arguments, see [ApprovalFingerprint]), so a periodic task that needs the same command every
 * run asks only once. Third-party and privacy actions (SMS, calls, contacts, camera, microphone),
 * blocked actions, secrets and screen actions are never rememberable.
 */
class ApprovalRules(private val file: File) {

    private val lock = Any()

    fun allows(call: ToolCall, assessment: SecurityAssessment): Boolean =
        isRememberable(call, assessment) && ApprovalFingerprint.of(call) in load()

    fun remember(call: ToolCall, assessment: SecurityAssessment): Boolean {
        if (!isRememberable(call, assessment)) return false
        synchronized(lock) {
            val rules = (load() + ApprovalFingerprint.of(call)).toList().takeLast(MAX_RULES)
            file.parentFile?.mkdirs()
            file.writeText(rules.joinToString("\n"))
        }
        return true
    }

    fun count(): Int = load().size

    fun clear() = synchronized(lock) { file.delete(); Unit }

    private fun load(): Set<String> = synchronized(lock) {
        if (!file.exists()) emptySet() else file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }

    companion object {
        private const val MAX_RULES = 200

        /** Shared by every engine (interactive, background, channel); set by the app runtime. */
        @Volatile
        var shared: ApprovalRules? = null

        fun isRememberable(call: ToolCall, assessment: SecurityAssessment): Boolean =
            !assessment.alwaysConfirm && !assessment.isBlocked && !assessment.redact &&
                !PhoneTools.isPhoneTool(call.name) && call.name != AgentTools.DELEGATE_TASK
    }
}
