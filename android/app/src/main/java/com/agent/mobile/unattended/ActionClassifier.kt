package com.agent.mobile.unattended

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.phone.ActionCategory
import com.agent.mobile.security.SecurityAssessment

/**
 * Maps a tool call that would ask the user to an [UnattendedCategory]. Uses the call itself
 * (shell command, tool) and the reason the risk check gave (it names what the action does, also
 * for script contents and screen taps). When several categories apply, the most restrictive one
 * wins: a script that sends an SMS and starts a call is a call.
 */
object ActionClassifier {

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Ordered from most to least restrictive; the first match wins. */
    private val SHELL_RULES: List<Pair<Regex, UnattendedCategory>> = listOf(
        r("""\btermux-telephony-call\b|android\.intent\.action\.(CALL|DIAL)|\btel:""") to UnattendedCategory.CALLS,
        r("""\b(pm|apt|pkg)\s+(install|uninstall)\b.*\.apk\b|\bpm\s+(install|uninstall)""") to UnattendedCategory.STORE_INSTALLS,
        r("""\btermux-sms-send\b|\bsmsto?:|\bmailto:|android\.intent\.action\.SEND""") to UnattendedCategory.SEND_MESSAGES,
        r("""\btermux-(camera-photo|camera-info|microphone-record)\b""") to UnattendedCategory.CAMERA_MIC,
        r("""\btermux-location\b""") to UnattendedCategory.LOCATION,
        r("""\btermux-(sms-list|sms-inbox|contact-list|call-log|clipboard-get|clipboard-set|telephony-cellinfo|telephony-deviceinfo|fingerprint)\b""") to UnattendedCategory.PRIVATE_DATA,
        r("""\b(pkill|kill|killall|reboot|shutdown|poweroff)\b""") to UnattendedCategory.PROCESS_CONTROL,
        r("""\b(pkg|apt|apt-get|pip|pip3|npm)\s+(install|upgrade|update)\b""") to UnattendedCategory.PACKAGE_INSTALL,
        r("""\brm\b|\brmdir\b|-delete\b|\bshred\b|\bunlink\b""") to UnattendedCategory.FILE_DELETE
    )

    /** Reasons of the risk checks (shell, script contents, phone policy, privileged shell). */
    private val REASON_RULES: List<Pair<Regex, UnattendedCategory>> = listOf(
        r(Regex.escape(ActionCategory.PAYMENT.reason)) to UnattendedCategory.PAYMENTS,
        r(Regex.escape(ActionCategory.CALL.reason) + "|places a phone call|telephony / phone calls") to UnattendedCategory.CALLS,
        r(Regex.escape(ActionCategory.INSTALL.reason) + "|app store or installer") to UnattendedCategory.STORE_INSTALLS,
        r(Regex.escape(ActionCategory.ACCOUNT.reason) + "|password field|credential or key file|ssh") to UnattendedCategory.ACCOUNTS,
        r(Regex.escape(ActionCategory.SEND.reason) + "|sends an sms|could send a message") to UnattendedCategory.SEND_MESSAGES,
        r(Regex.escape(ActionCategory.DELETE.reason) + "|clears an app's data") to UnattendedCategory.DELETE_APP_DATA,
        r("""takes a photo|records audio|camera|microphone""") to UnattendedCategory.CAMERA_MIC,
        r("""location""") to UnattendedCategory.LOCATION,
        r("""notification shade|your notifications|dumps notifications""") to UnattendedCategory.NOTIFICATIONS,
        r("""private messages|contact data|contacts or call history|call history|clipboard|accounts, clipboard or telephony""") to UnattendedCategory.PRIVATE_DATA,
        r("""system settings|system dialog|app permissions|enables, disables or hides|system package settings|configuration file""") to UnattendedCategory.SYSTEM_SETTINGS,
        r("""stopping system or background processes|system restart|stops app processes""") to UnattendedCategory.PROCESS_CONTROL,
        r("""file deletion""") to UnattendedCategory.FILE_DELETE,
        r("""base64|hex-encoded|piped shell|eval/exec|inline code|subshell|device access|hidden in a variable""") to UnattendedCategory.ADVANCED_SHELL
    )

    fun classify(call: ToolCall, assessment: SecurityAssessment): UnattendedCategory {
        val reason = assessment.reason
        when (call.name) {
            AgentTools.EXECUTE_COMMAND -> {
                val command = call.arguments["command"].orEmpty()
                // A script's content is only visible through the reason ("Script ~/x.sh: …").
                val fromReason = REASON_RULES.firstOrNull { it.first.containsMatchIn(reason) }?.second
                val fromCommand = SHELL_RULES.firstOrNull { it.first.containsMatchIn(command) }?.second
                return listOfNotNull(fromReason, fromCommand).minByOrNull { restrictiveness(it) }
                    ?: UnattendedCategory.ADVANCED_SHELL
            }
            AgentTools.READ_FILE, AgentTools.SEND_FILE -> return UnattendedCategory.ACCOUNTS
            AgentTools.WRITE_FILE -> {
                val path = call.arguments["path"].orEmpty()
                return if (path.contains(".ssh") || path.contains(".gnupg")) UnattendedCategory.ACCOUNTS else UnattendedCategory.SYSTEM_SETTINGS
            }
            PhoneTools.NOTIFICATION_REPLY -> return UnattendedCategory.SEND_MESSAGES
            PhoneTools.NOTIFICATIONS_LIST -> return UnattendedCategory.NOTIFICATIONS
            PhoneTools.CLIPBOARD -> return UnattendedCategory.PRIVATE_DATA
        }
        REASON_RULES.firstOrNull { it.first.containsMatchIn(reason) }?.second?.let { return it }
        return if (PhoneTools.isPhoneTool(call.name)) UnattendedCategory.APP_CONTROL else UnattendedCategory.ADVANCED_SHELL
    }

    /** Lower is more restrictive: denied-by-default categories first, then the order of the enum. */
    private fun restrictiveness(c: UnattendedCategory): Int = (if (c.defaultAllowed) 100 else 0) + c.ordinal
}
