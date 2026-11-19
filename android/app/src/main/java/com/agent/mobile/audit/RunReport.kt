package com.agent.mobile.audit

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import org.json.JSONObject

/**
 * The report at the end of an unattended run, built from the audit log of that run: goal,
 * outcome, what changed (files, apps, sent messages), what the rules refused and why, errors.
 * Delivered as a notification and, when Telegram is connected, as a message.
 */
data class RunReport(
    val title: String,
    val goal: String,
    val outcome: Outcome,
    val summary: String,
    val filesChanged: List<String>,
    val appsOperated: List<String>,
    val messagesSent: List<String>,
    val autoDenied: List<String>,
    val blocked: List<String>,
    val errors: List<String>,
    /** Questions the agent recorded with ask_user (nobody could answer them during the run). */
    val questions: List<String>,
    val toolCalls: Int,
    val endReason: String?
) {
    enum class Outcome(val label: String) { DONE("done"), PARTIAL("partly done"), BLOCKED("blocked") }

    /** Plain text for the notification and Telegram (no tables). */
    fun toText(maxChars: Int = 3_500): String = buildString {
        append("Result: ").append(outcome.label)
        endReason?.let { append(" (").append(it).append(')') }
        append(" · ").append(toolCalls).append(" actions\n")
        if (goal.isNotBlank()) append("Goal: ").append(goal.take(200)).append('\n')
        append('\n').append(summary.take(1_200)).append('\n')
        section("Changed files", filesChanged)
        section("Apps operated", appsOperated)
        section("Messages sent", messagesSent)
        section("Refused by the unattended rules", autoDenied)
        section("Blocked", blocked)
        section("Errors", errors)
        section("Questions for you (the agent decided itself)", questions)
    }.trim().take(maxChars)

    private fun StringBuilder.section(name: String, items: List<String>) {
        if (items.isEmpty()) return
        append('\n').append(name).append(" (").append(items.size).append("):\n")
        items.take(6).forEach { append("• ").append(it.take(160)).append('\n') }
        if (items.size > 6) append("• … and ").append(items.size - 6).append(" more\n")
    }

    companion object {
        private val RAN = setOf(AuditDecision.ALLOWED.name, AuditDecision.AUTO_ALLOWED.name, AuditDecision.APPROVED.name)
        private val FILE_COMMAND = Regex("""\b(rm|mv|cp|mkdir|touch|tee|chmod|unzip|tar|git\s+(clone|pull|checkout|commit))\b|>""")

        fun build(
            title: String,
            goal: String,
            summary: String,
            success: Boolean,
            events: List<AuditEventEntity>,
            endReason: String? = null
        ): RunReport {
            val calls = events.filter { it.decision != AuditDecision.INFO.name }
            val ran = calls.filter { it.decision in RAN }
            fun arg(e: AuditEventEntity, key: String) = runCatching { JSONObject(e.arguments).optString(key) }.getOrDefault("")

            val files = ran.filter { it.exitCode == 0 }.mapNotNull { e ->
                when (e.tool) {
                    AgentTools.WRITE_FILE -> arg(e, "path").ifBlank { null }
                    AgentTools.EXECUTE_COMMAND -> arg(e, "command").takeIf { FILE_COMMAND.containsMatchIn(it) }?.let { "command: $it" }
                    else -> null
                }
            }.distinct()
            val apps = ran.filter { PhoneTools.isPhoneTool(it.tool) && it.app != null }.mapNotNull { it.app }.distinct()
            val sent = ran.filter { it.rule.endsWith("send_messages") }.map { e ->
                val what = arg(e, "command").ifBlank { arg(e, "text") }.ifBlank { e.tool }
                (e.app?.let { "$it: " } ?: "") + what
            }
            val denied = calls.filter { it.decision == AuditDecision.AUTO_DENIED.name }.map { "${describe(e = it)} — ${it.reason}" }
            val blocked = calls.filter { it.decision == AuditDecision.BLOCKED.name }.map { "${describe(it)} — ${it.reason}" }
            val errors = ran.filter { (it.exitCode ?: 0) != 0 }.map { "${describe(it)} (exit ${it.exitCode}): ${it.resultSummary.take(100)}" }
            val questions = calls.filter { it.tool == AgentTools.ASK_USER }.map { arg(it, "question") }.filter { it.isNotBlank() }
            val finish = calls.lastOrNull { it.tool == AgentTools.FINISH_TASK }?.let { arg(it, "status") }
            val outcome = when {
                !success || finish == "blocked" -> Outcome.BLOCKED
                finish == "partial" -> Outcome.PARTIAL
                denied.isNotEmpty() || blocked.isNotEmpty() -> Outcome.PARTIAL
                else -> Outcome.DONE
            }
            return RunReport(title, goal, outcome, summary, files, apps, sent, denied, blocked, errors, questions, calls.size, endReason)
        }

        private fun describe(e: AuditEventEntity): String {
            val cmd = runCatching { JSONObject(e.arguments).optString("command") }.getOrDefault("")
            return if (cmd.isNotBlank()) cmd.take(100) else e.tool + (e.app?.let { " in $it" } ?: "")
        }
    }
}
