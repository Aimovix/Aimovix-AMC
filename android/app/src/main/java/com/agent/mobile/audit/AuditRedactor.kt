package com.agent.mobile.audit

import com.agent.mobile.agent.PhoneTools
import org.json.JSONObject

/**
 * Prepares tool arguments for the audit log: secrets never reach it.
 * - Calls that carry a secret (typing into a password field) keep only the argument names.
 * - One-time codes (4-8 digits typed or replied, or after words like "code", "TAN", "PIN") are masked.
 * - Key/token/password assignments and bearer tokens are masked in every value.
 * - Long values (file contents, prompts) are shortened.
 */
object AuditRedactor {

    const val MASK = "[redacted]"
    private const val MAX_VALUE_CHARS = 300

    /** Arguments that hold text typed or sent somewhere. */
    private val TYPED_KEYS = setOf("text", "reply", "message")

    private val OTP_AFTER_WORD = Regex(
        """(?i)\b(code|otp|tan|pin|passcode|verification|verify|bestätigungscode|sicherheitscode|einmalcode|kennung)\b(\W{0,6}|\s+(?:is|ist|lautet|was|war)\W{0,4})(\d{4,8})\b"""
    )
    private val BARE_OTP = Regex("""^\s*\d{4,8}\s*$""")
    private val SECRET_ASSIGNMENT = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|token|password|passwd|pwd|secret|client[_-]?secret|auth)(\s*[:=]\s*)("[^"]*"|'[^']*'|\S+)"""
    )
    private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val PROVIDER_KEY = Regex("""\b(sk-[A-Za-z0-9_-]{12,}|AIza[0-9A-Za-z_-]{20,}|ghp_[A-Za-z0-9]{20,}|xox[abpr]-[A-Za-z0-9-]{10,})""")

    /** Masks secrets in free text (commands, descriptions, results). */
    fun scrub(text: String): String {
        var s = text
        s = OTP_AFTER_WORD.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        s = SECRET_ASSIGNMENT.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        s = BEARER.replace(s, "Bearer $MASK")
        s = PROVIDER_KEY.replace(s, MASK)
        return s
    }

    /**
     * The arguments of [toolName] as a JSON object for the log. [secret] is true when the call
     * carries a secret (the risk check marked it redact): then no value is kept at all.
     */
    fun arguments(toolName: String, arguments: Map<String, String>, secret: Boolean): String {
        val out = JSONObject()
        for ((key, raw) in arguments.toSortedMap()) {
            val value = when {
                secret && (key in TYPED_KEYS || key == "steps") -> MASK
                key in TYPED_KEYS && BARE_OTP.matches(raw) -> MASK
                toolName == PhoneTools.SCREEN_BATCH && key == "steps" -> maskTypedTextInSteps(raw)
                else -> scrub(raw)
            }
            out.put(key, shorten(value))
        }
        return out.toString()
    }

    private fun maskTypedTextInSteps(steps: String): String =
        Regex(""""text"\s*:\s*"(\d{4,8})"""").replace(scrub(steps)) { """"text":"$MASK"""" }

    private fun shorten(value: String): String =
        if (value.length <= MAX_VALUE_CHARS) value else value.take(MAX_VALUE_CHARS) + "… (${value.length} chars)"
}
