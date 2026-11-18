package com.agent.mobile.unattended

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.phone.PackageBlocklist
import com.agent.mobile.phone.PhoneRiskContext
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.security.SecurityAssessment

/**
 * The floor of unattended mode: what is never done without a person watching, whatever the
 * category settings say. In supervised modes the same actions are blocked already or always ask
 * (unchanged); in unattended mode nobody could be asked, so they are blocked.
 *
 * Shell commands that touch the bridge token are blocked in every mode by
 * [com.agent.mobile.security.CommandSecurityFilter]; catastrophic targets are blocked there too.
 */
object SecurityFloor {

    const val RULE_PREFIX = "floor:"

    private val OWN_PACKAGE = Regex(Regex.escape(PhoneRiskContext.OWN_PACKAGE) + """\b""")
    private val OWN_DATA_DIR = Regex("""/data/(data|user/\d+|user_de/\d+)/""" + Regex.escape(PhoneRiskContext.OWN_PACKAGE))

    /** Settings keys that change device security (lock, accounts, admin, debugging, installs, accessibility). */
    private val SECURITY_SETTING_KEY = Regex(
        """(lock|password|pin|pattern|biometric|fingerprint|face|credential|keyguard|device_admin|admin|adb|development|usb|install_non_market|unknown_sources|package_verifier|verifier|accessibility|enabled_accessibility|notification_listener|trust|secure_frp|encryption|vpn|always_on_vpn|private_dns)""",
        RegexOption.IGNORE_CASE
    )
    private val READ_ONLY_PM = setOf("list", "path", "dump", "resolve-activity", "query-activities", "has-feature")

    private fun blocked(rule: String, reason: String) =
        SecurityAssessment(RiskLevel.BLOCKED, "Unattended floor: $reason") to "$RULE_PREFIX$rule"

    /**
     * Returns the blocking assessment and its rule id when [call] hits the floor in unattended
     * mode, or null.
     */
    fun check(call: ToolCall, base: SecurityAssessment): Pair<SecurityAssessment, String>? {
        if (base.isBlocked) return null
        val a = call.arguments
        when (call.name) {
            AgentTools.EXECUTE_COMMAND, AgentTools.JOB_START -> {
                val cmd = a["command"].orEmpty()
                if (OWN_PACKAGE.containsMatchIn(cmd) || OWN_DATA_DIR.containsMatchIn(cmd)) {
                    return blocked("amc_self", "commands that address AMC itself (its app, data or settings) are never run unattended.")
                }
            }
            AgentTools.READ_FILE, AgentTools.WRITE_FILE, AgentTools.EDIT_FILE, AgentTools.SEND_FILE -> {
                val path = a["path"].orEmpty()
                if (OWN_DATA_DIR.containsMatchIn(path) || path.contains("/" + PhoneRiskContext.OWN_PACKAGE + "/")) {
                    return blocked("amc_self", "AMC's own files (settings, persona, memory) are off limits for the agent.")
                }
            }
            PhoneTools.PRIVILEGED_SHELL -> {
                val tokens = a["command"].orEmpty().trim().split(Regex("\\s+"))
                val bin = tokens.firstOrNull().orEmpty()
                val sub = tokens.getOrNull(1).orEmpty()
                val touchesSelf = tokens.any { OWN_PACKAGE.containsMatchIn(it) }
                if (touchesSelf && !(bin == "pm" && sub in READ_ONLY_PM) && bin != "dumpsys") {
                    return blocked("amc_self", "AMC's own app, permissions and settings are never changed unattended.")
                }
                if (bin == "settings" && sub in setOf("put", "delete", "reset")) {
                    val namespace = tokens.getOrNull(2).orEmpty()
                    val key = tokens.getOrNull(3).orEmpty()
                    if (namespace == "secure" || SECURITY_SETTING_KEY.containsMatchIn(key)) {
                        return blocked("security_settings", "Android security settings are never changed unattended.")
                    }
                }
            }
        }
        return null
    }

    /**
     * The phone block list in unattended mode: the built-in entries (banking, payment, password,
     * authenticator apps and security settings) always apply, releases made for supervised use do
     * not (the name check for banking-like apps applies again), and the user's own entries stay.
     */
    fun blocklist(user: PackageBlocklist): PackageBlocklist =
        PackageBlocklist(entries = (PackageBlocklist.DEFAULT_ENTRIES + user.entries).distinct(), allowed = emptySet())
}
