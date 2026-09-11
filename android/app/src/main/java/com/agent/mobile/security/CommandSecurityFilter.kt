package com.agent.mobile.security

import com.agent.mobile.data.model.ExecutionMode
import java.util.regex.Pattern

enum class RiskLevel(val displayName: String, val colorHex: Long) {
    LOW("Low risk", 0xFF00E676),
    MEDIUM("Medium risk", 0xFFFFB300),
    HIGH("High risk", 0xFFFF3366),
    BLOCKED("Security block", 0xFFD50000)
}

data class SecurityAssessment(
    val level: RiskLevel,
    val reason: String,
    val isBlocked: Boolean = level == RiskLevel.BLOCKED
)

object CommandSecurityFilter {

    // Blacklist: Commands that cause catastrophic, irreversible damage or malicious remote execution
    private val BLACKLIST_PATTERNS = listOf(
        Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+(/(?:\\s+|$)|/\\*|~)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+\\*\\s*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("mkfs(\\.[a-zA-Z0-9]+)?\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+if=/dev/(zero|urandom)\\s+of=/dev/", Pattern.CASE_INSENSITIVE),
        Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:", Pattern.CASE_INSENSITIVE), // Fork bomb
        Pattern.compile(">\\s*/dev/sd[a-z]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("chmod\\s+(-[a-zA-Z]*R\\s+)?000\\s+/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(curl|wget)\\s+[^|]+\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE)
    )

    // High Risk: Data deletion, Telephony, SMS, Camera, System Reboot, Process Killing, Obfuscation & Dynamic Evaluation
    private val HIGH_RISK_PATTERNS = listOf(
        // Obfuscation & Dynamic execution guards
        Pair(Pattern.compile("\\bbase64\\s+(-d|--decode)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Base64-encoded shell execution detected"),
        Pair(Pattern.compile("\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Piped shell command (dynamic execution)"),
        Pair(Pattern.compile("\\b(eval|exec)\\s+", Pattern.CASE_INSENSITIVE), "Dynamic execution via eval/exec"),
        Pair(Pattern.compile("\\b(python|python3|node|perl|ruby|sh|bash)\\s+(-c|-e)\\b", Pattern.CASE_INSENSITIVE), "Inline code executed by an interpreter"),
        Pair(Pattern.compile("\\b(xxd|hexdump)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Hex-encoded shell execution detected"),

        // Sensitive Hardware, Privacy & System Actions
        Pair(Pattern.compile("\\brm\\b"), "File deletion detected"),
        Pair(Pattern.compile("\\btermux-sms-send\\b"), "Sending an SMS over your mobile network"),
        Pair(Pattern.compile("\\btermux-telephony-call\\b"), "Starting a phone call"),
        Pair(Pattern.compile("\\btermux-camera-photo\\b"), "Access to the phone camera"),
        Pair(Pattern.compile("\\btermux-contact-list\\b"), "Reading your private contacts"),
        Pair(Pattern.compile("\\b(pkill|kill|killall)\\b"), "Stopping system or background processes"),
        Pair(Pattern.compile("\\b(reboot|shutdown|poweroff)\\b"), "System restart requested"),
        Pair(Pattern.compile("\\bchmod\\b"), "Changing filesystem permissions"),
        Pair(Pattern.compile(">\\s*/dev/"), "Direct device access"),
        Pair(Pattern.compile("\\btermux-storage-get\\b"), "Access to external phone storage")
    )

    // Medium Risk: File modification, scripts, networking, package installation
    private val MEDIUM_RISK_PATTERNS = listOf(
        Pair(Pattern.compile("\\b(touch|mkdir|mv|cp)\\b"), "Filesystem modification"),
        Pair(Pattern.compile("\\b(python|python3|bash|sh|node)\\s+[^\\s-]+\\b"), "Executing a script file"),
        Pair(Pattern.compile("\\b(curl|wget)\\b"), "Network request / download"),
        Pair(Pattern.compile("\\b(pkg|apt|apt-get|pip)\\s+install\\b"), "Package or software installation"),
        Pair(Pattern.compile("\\bgit\\s+(clone|push|pull)\\b"), "Git Repository Operation"),
        Pair(Pattern.compile(">>|\\bcat\\s+<<"), "Creating files / appending data")
    )

    private var customWhitelist = listOf<Pattern>()
    private var customBlacklist = listOf<Pattern>()
    private var isStrictMode = false

    fun setCustomRules(whitelist: List<String>, blacklist: List<String>, strict: Boolean = false) {
        customWhitelist = whitelist.mapNotNull {
            try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null }
        }
        customBlacklist = blacklist.mapNotNull {
            try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null }
        }
        isStrictMode = strict
    }

    fun analyze(command: String): SecurityAssessment {
        val trimmed = command.trim()
        val normalized = normalizeCommand(trimmed)

        // 1. Check Custom Blacklist
        for (pattern in customBlacklist) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Custom Blacklist: command blocked by a user-defined rule."
                )
            }
        }

        // 2. Check Catastrophic Blacklist
        for (pattern in BLACKLIST_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Command blocked because it may cause catastrophic system damage."
                )
            }
        }

        // 4. Check High Risk
        for ((pattern, desc) in HIGH_RISK_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.HIGH,
                    reason = desc
                )
            }
        }

        // Shell syntax and scripts can hide side effects; never whitelist them.
        if (trimmed.any { it in "$" + "\u0060;&|<>(){}\n\r\\" } ||
            Regex("""\b(python[0-9.]*|bash|sh|node|perl|ruby)\b""").containsMatchIn(normalized)) {
            return SecurityAssessment(RiskLevel.HIGH, "Shell composition or executable code requires approval.")
        }

        val commandName = normalized.substringBefore(' ')
        val recognizedMedium = commandName in setOf("touch", "mkdir", "mv", "cp", "curl", "wget", "pkg", "apt", "apt-get", "pip", "git")

        // Only explicitly recognized operations can receive an allowlist exception.
        // 5. Check Medium Risk
        for ((pattern, desc) in MEDIUM_RISK_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = if (!recognizedMedium) RiskLevel.HIGH else if (customWhitelist.any { it.matcher(trimmed).matches() }) RiskLevel.LOW else RiskLevel.MEDIUM,
                    reason = desc
                )
            }
        }

        // A small explicit diagnostic allowlist; unknown commands are not read-only.
        val diagnostic = Regex("""^(?:pwd|whoami|date|uptime|termux-battery-status|uname(?:\s+-[a-zA-Z]+)?|ls(?:\s+[-a-zA-Z0-9_./~*]+)*|echo(?:\s+[a-zA-Z0-9 _.,:!?/-]+)?)$""")
        return if (diagnostic.matches(trimmed)) {
            SecurityAssessment(RiskLevel.LOW, "Recognized diagnostic command.")
        } else {
            SecurityAssessment(RiskLevel.HIGH, "Unrecognized command requires approval.")
        }
    }

    private fun normalizeCommand(command: String): String {
        // Strip quotes and escaping backslashes to thwart simple token-splitting obfuscation (e.g. r'm' or \rm)
        return command
            .replace("\\", "")
            .replace("'", "")
            .replace("\"", "")
    }

    /** Explicit and strict modes always require approval. High risks cannot be exempted. */
    fun shouldRequireApproval(assessment: SecurityAssessment, mode: ExecutionMode): Boolean {
        if (assessment.isBlocked) return false
        if (isStrictMode || mode == ExecutionMode.STEP_BY_STEP) return true
        return assessment.level == RiskLevel.HIGH || assessment.level == RiskLevel.MEDIUM
    }
}
