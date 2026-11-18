package com.agent.mobile.unattended

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.SecurityAssessment

/**
 * The fixed rule that replaces the approval question in unattended mode. Never waits:
 * - category allowed (and, for a category denied by default that the user unlocked, the
 *   injection guard agrees) → run it and log it as auto-allowed;
 * - otherwise → do not run it, log it as auto-denied and tell the model why and what to do instead.
 */
object UnattendedPolicy {

    /** Version of the opt-in dialog. Unattended mode is active only after the user accepted it. */
    const val CONSENT_VERSION = 1

    enum class Outcome { ALLOW, DENY }

    data class Decision(val outcome: Outcome, val category: UnattendedCategory?, val rule: String, val reason: String) {
        val allowed: Boolean get() = outcome == Outcome.ALLOW
    }

    /** Words in the user's task that show it asked for an action of a category denied by default. */
    val CATEGORY_TASK_WORDS: Map<UnattendedCategory, Regex> = mapOf(
        UnattendedCategory.PAYMENTS to Regex("""\b(pay|buy|purchase|order|checkout|subscribe|book|donate|transfer|bezahl|zahl|kauf|bestell|abonn|buch|überweis|spend)""", RegexOption.IGNORE_CASE),
        UnattendedCategory.CALLS to Regex("""\b(call|phone|dial|ring|anruf|ruf\w* .*\ban\b|telefon)""", RegexOption.IGNORE_CASE),
        UnattendedCategory.ACCOUNTS to Regex("""\b(log ?in|log ?out|sign ?in|sign ?up|account|password|register|credential|ssh|anmeld|einlogg|abmeld|konto|passwort|registrier|zugang)""", RegexOption.IGNORE_CASE),
        UnattendedCategory.STORE_INSTALLS to Regex("""\b(install|uninstall|update|store|play store|f-droid|apk|installier|deinstallier|aktualisier)""", RegexOption.IGNORE_CASE)
    )

    fun decide(category: UnattendedCategory, settings: UnattendedSettings, guard: InjectionGuard?, content: String): Decision {
        if (!settings.isAllowed(category)) {
            return Decision(
                Outcome.DENY, category, "unattended:${category.id}",
                "\"${category.title}\" is not allowed in unattended mode" +
                    if (category.defaultAllowed) " (switched off in Settings → Unattended mode)." else " (denied by default; it can be unlocked in Settings → Unattended mode)."
            )
        }
        if (!category.defaultAllowed && guard != null) {
            val verdict = guard.check(InjectionGuard.Kind.UNLOCKED_CATEGORY, content, CATEGORY_TASK_WORDS[category])
            if (!verdict.allowed) return Decision(Outcome.DENY, category, "injection:${category.id}", verdict.reason)
        }
        return Decision(Outcome.ALLOW, category, "unattended:${category.id}", "\"${category.title}\" is allowed in unattended mode.")
    }

    /** What the model reads instead of the result of an auto-denied action. */
    fun hint(reason: String): String =
        "Not executed: AMC's unattended rules refused this action. $reason Nobody can be asked in this run. Do not retry it " +
            "and do not try to reach the same result another way. Continue with the parts of the task that need no such " +
            "action, and say in your summary what is left for the user."

    /** The kind of protected action [call] is (memory, schedule, trigger, skill), or null. */
    fun guardedKind(call: ToolCall): InjectionGuard.Kind? = when (call.name) {
        AgentTools.REMEMBER -> InjectionGuard.Kind.MEMORY
        AgentTools.SCHEDULE_TASK -> InjectionGuard.Kind.SCHEDULE
        AgentTools.WRITE_FILE -> if (call.arguments["path"].orEmpty().contains(".amc/skills")) InjectionGuard.Kind.SKILL else null
        PhoneTools.NOTIFICATION_TRIGGER -> if (call.arguments["action"].orEmpty().trim().equals("add", ignoreCase = true)) InjectionGuard.Kind.TRIGGER else null
        else -> null
    }

    /** The text a protected or unlocked action would store or act on (checked against untrusted data). */
    fun contentOf(call: ToolCall, assessment: SecurityAssessment?): String {
        val a = call.arguments
        return when (call.name) {
            AgentTools.REMEMBER -> a["note"].orEmpty()
            AgentTools.SCHEDULE_TASK -> a["title"].orEmpty() + "\n" + a["prompt"].orEmpty()
            AgentTools.WRITE_FILE -> a["content"].orEmpty()
            AgentTools.EXECUTE_COMMAND -> a["command"].orEmpty()
            else -> a.values.joinToString("\n") + "\n" + assessment?.reason.orEmpty()
        }
    }
}
