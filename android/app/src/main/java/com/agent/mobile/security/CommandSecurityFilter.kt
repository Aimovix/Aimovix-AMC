package com.agent.mobile.security

import com.agent.mobile.data.model.SecurityPreset
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

    private val ZERO_WIDTH_REGEX = Regex("[\u200B-\u200D\uFEFF\u0000]")
    private val IFS_REGEX = Regex("""\$(?:\{IFS[^}]*\}|IFS(?:\$9)?)""")
    private val MULTI_SLASH_REGEX = Regex("/{2,}")

    // Blacklist: Commands that cause catastrophic, irreversible damage or malicious remote execution
    private val BLACKLIST_PATTERNS = listOf(
        // Destructive recursive file deletion regex fallback
        Pattern.compile("(?:^|[;/&|\\s])(?:\\S+/)?rm\\s+.*(-[a-zA-Z]*[rR][a-zA-Z]*[fF][a-zA-Z]*|-[a-zA-Z]*[fF][a-zA-Z]*[rR][a-zA-Z]*)\\s+.*?(/(?:\\s+|$)|/\\*|~|\\*|\\\$HOME)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[;/&|\\s])(?:\\S+/)?rm\\s+.*(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\\s+.*(-[a-zA-Z]*[fF][a-zA-Z]*|--force)\\s+.*?(/(?:\\s+|$)|/\\*|~|\\*|\\\$HOME)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:^|[;/&|\\s])(?:\\S+/)?rm\\s+.*(-[a-zA-Z]*[fF][a-zA-Z]*|--force)\\s+.*(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\\s+.*?(/(?:\\s+|$)|/\\*|~|\\*|\\\$HOME)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("mkfs(\\.[a-zA-Z0-9]+)?\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("mke2fs\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("wipefs\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("dd\\s+.*of=/dev/(zero|urandom|sd[a-z]|block/)", Pattern.CASE_INSENSITIVE),
        Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:", Pattern.CASE_INSENSITIVE), // Classic fork bomb
        Pattern.compile("\\b([a-zA-Z0-9_]+)\\s*\\(\\)\\s*\\{\\s*\\1\\s*\\|\\s*\\1\\s*&\\s*\\}\\s*;\\s*\\1", Pattern.CASE_INSENSITIVE), // Generalized fork bomb
        Pattern.compile(">\\s*/dev/(sd[a-z]|block/)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("chmod\\s+(-[a-zA-Z]*R\\s+)?(000|777)\\s+/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(curl|wget|fetch)\\s+[^|]+\\|\\s*(?:/\\S+/)?(ba|z|da)?sh\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(curl|wget|fetch)\\s+[^|]+\\|\\s*(?:/\\S+/)?(python[0-9.]*|perl|ruby|node)\\b", Pattern.CASE_INSENSITIVE)
    )

    // High Risk: Data deletion, Telephony, SMS, Camera, System Reboot, Process Killing, Obfuscation & Dynamic Evaluation
    private val HIGH_RISK_PATTERNS = listOf(
        // Obfuscation & Dynamic execution guards
        Pair(Pattern.compile("\\bbase64\\s+(-d|--decode)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Base64-encoded shell execution detected"),
        Pair(Pattern.compile("\\|\\s*(ba|z|da)?sh\\b", Pattern.CASE_INSENSITIVE), "Piped shell command (dynamic execution)"),
        Pair(Pattern.compile("\\b(eval|exec)\\s+", Pattern.CASE_INSENSITIVE), "Dynamic execution via eval/exec"),
        Pair(Pattern.compile("\\b(python|python3|node|perl|ruby|sh|bash)\\s+(-c|-e)\\b", Pattern.CASE_INSENSITIVE), "Inline code executed by an interpreter"),
        Pair(Pattern.compile("\\b(xxd|hexdump)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Hex-encoded shell execution detected"),
        Pair(Pattern.compile("\\b(sh|bash)\\s+-c\\b", Pattern.CASE_INSENSITIVE), "Subshell command execution via -c"),

        // Sensitive Hardware, Privacy & System Actions
        Pair(Pattern.compile("\\brm\\b"), "File deletion detected"),
        Pair(Pattern.compile("\\btermux-sms-(send|list)\\b"), "Accessing or sending SMS over your mobile network"),
        Pair(Pattern.compile("\\btermux-telephony-(call|cellinfo|deviceinfo)\\b"), "Access to telephony / phone calls"),
        Pair(Pattern.compile("\\btermux-camera-(photo|info)\\b"), "Access to the phone camera"),
        Pair(Pattern.compile("\\btermux-contact-list\\b"), "Reading your private contacts"),
        Pair(Pattern.compile("\\btermux-location\\b"), "Access to precise GPS/network device location"),
        Pair(Pattern.compile("\\btermux-microphone-record\\b"), "Recording device microphone audio"),
        Pair(Pattern.compile("\\btermux-fingerprint\\b"), "Biometric sensor query"),
        Pair(Pattern.compile("\\btermux-clipboard-(get|set)\\b"), "Accessing or modifying device clipboard"),
        Pair(Pattern.compile("\\b(pkill|kill|killall)\\b"), "Stopping system or background processes"),
        Pair(Pattern.compile("\\b(reboot|shutdown|poweroff)\\b"), "System restart requested"),
        Pair(Pattern.compile("\\b(chmod|chown)\\b"), "Changing filesystem permissions or ownership"),
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
        val validWhite = whitelist.mapNotNull {
            try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null }
        }
        val validBlack = blacklist.mapNotNull {
            try { Pattern.compile(it, Pattern.CASE_INSENSITIVE) } catch (e: Exception) { null }
        }
        if (whitelist.isEmpty() || validWhite.isNotEmpty()) {
            customWhitelist = validWhite
        }
        if (blacklist.isEmpty() || validBlack.isNotEmpty()) {
            customBlacklist = validBlack
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

        // 2. Check Catastrophic Blacklist (Regex Patterns)
        for (pattern in BLACKLIST_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Command blocked because it may cause catastrophic system damage."
                )
            }
        }

        // 3. Structural & Token Analysis across command segments (handles $IFS, argument ordering, chaining, find -delete)
        val rawStatements = trimmed.split(Regex("[;&|\n\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val normalizedStatements = normalized.split(Regex("[;&|\n\r]+")).map { it.trim() }.filter { it.isNotEmpty() }

        for (stmt in (rawStatements + normalizedStatements)) {
            val normalizedStmt = normalizeCommand(stmt)
            if (isCatastrophicRm(stmt) || isCatastrophicRm(normalizedStmt)) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Command blocked: destructive recursive file deletion targeting root, storage, home, or wildcards."
                )
            }
            if (isCatastrophicFind(stmt) || isCatastrophicFind(normalizedStmt)) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Command blocked: find with destructive deletion targeting root or storage."
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

    fun normalizeCommand(command: String): String {
        return command
            // 1. Remove zero-width & invisible bypass characters
            .replace(ZERO_WIDTH_REGEX, "")
            // 2. Normalize Unicode whitespace and tabs to single standard space
            .replace(Regex("[\\s\u00A0\u2000-\u200A\u202F\u205F\u3000]+"), " ")
            // 3. Normalize IFS variable references to a standard space
            .replace(IFS_REGEX, " ")
            // 4. Strip quotes and escaping backslashes to thwart token-splitting obfuscation (e.g. r'm', "rm", \rm)
            .replace("\\", "")
            .replace("'", "")
            .replace("\"", "")
            // 5. Strip empty shell parameters used for token-splitting (e.g. r$@m, r$*m)
            .replace("$@", "")
            .replace("$*", "")
            // 6. Collapse consecutive slashes
            .replace(MULTI_SLASH_REGEX, "/")
            .trim()
    }

    fun isValidRegex(pattern: String): Boolean {
        if (pattern.isBlank()) return false
        return try {
            Pattern.compile(pattern)
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun normalizePath(path: String): String {
        var p = path.trim().trim('\'', '"')
        p = p.replace(MULTI_SLASH_REGEX, "/")
        var changed = true
        while (changed) {
            val before = p
            p = p.replace("/./", "/")
            if (p.endsWith("/.")) {
                p = p.substring(0, p.length - 2)
            }
            if (p.length > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length - 1)
            }
            p = p.replace(MULTI_SLASH_REGEX, "/")
            changed = (p != before)
        }
        return p.ifEmpty { "/" }
    }

    private fun isCatastrophicTarget(target: String): Boolean {
        val clean = normalizePath(target)
        // 1. Literal root and global wildcards
        if (clean in setOf("/", "/*", "/.", "~", "~/*", "~.*", "*", ".", "..", "\$HOME", "\${HOME}", "\$PREFIX", "\${PREFIX}")) {
            return true
        }
        if (clean == "\$HOME/*" || clean == "\${HOME}/*" || clean == "\$PREFIX/*" || clean == "\${PREFIX}/*") {
            return true
        }
        // 2. Termux environment roots
        if (clean in setOf(
            "/data/data/com.termux",
            "/data/data/com.termux/*",
            "/data/data/com.termux/files",
            "/data/data/com.termux/files/home",
            "/data/data/com.termux/files/usr"
        )) {
            return true
        }
        // 3. User storage roots and wildcards (wiping all photos/documents/downloads)
        if (clean in setOf(
            "/sdcard",
            "/sdcard/*",
            "/storage",
            "/storage/*",
            "/storage/emulated",
            "/storage/emulated/*",
            "/storage/emulated/0",
            "/storage/emulated/0/*",
            "/storage/self",
            "/storage/self/*",
            "/storage/self/primary",
            "/storage/self/primary/*"
        )) {
            return true
        }
        // 4. Android system partition roots
        if (clean in setOf(
            "/system", "/system/*",
            "/vendor", "/vendor/*",
            "/apex", "/apex/*",
            "/data", "/data/*",
            "/etc", "/etc/*",
            "/dev", "/dev/*"
        )) {
            return true
        }
        return false
    }

    private fun isCatastrophicRm(command: String): Boolean {
        val tokens = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false

        // Skip wrapper commands
        var cmdIndex = 0
        while (cmdIndex < tokens.size && tokens[cmdIndex] in setOf("env", "nohup", "busybox", "sudo", "command", "exec")) {
            cmdIndex++
        }
        if (cmdIndex < tokens.size && tokens[cmdIndex] == "timeout") {
            cmdIndex += 2 // skip timeout <duration>
        }
        if (cmdIndex >= tokens.size) return false

        val cmdName = tokens[cmdIndex].substringAfterLast('/')
        if (cmdName != "rm") return false

        val args = tokens.subList(cmdIndex + 1, tokens.size)
        var isRecursive = false
        var hasCatastrophicTarget = false

        for (arg in args) {
            if (arg == "--no-preserve-root") continue
            if (arg == "--recursive") {
                isRecursive = true
            } else if (arg.startsWith("-") && !arg.startsWith("--")) {
                val flags = arg.substring(1)
                if (flags.contains('r', ignoreCase = true)) {
                    isRecursive = true
                }
            } else {
                if (isCatastrophicTarget(arg)) {
                    hasCatastrophicTarget = true
                }
            }
        }

        return isRecursive && hasCatastrophicTarget
    }

    private fun isCatastrophicFind(command: String): Boolean {
        val tokens = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false

        var cmdIndex = 0
        if (tokens[0] in setOf("busybox", "sudo", "exec")) cmdIndex = 1
        if (cmdIndex >= tokens.size) return false

        val cmdName = tokens[cmdIndex].substringAfterLast('/')
        if (cmdName != "find") return false

        val args = tokens.subList(cmdIndex + 1, tokens.size)
        val hasDelete = args.contains("-delete") || command.contains("rm\\s+-[a-zA-Z]*[rR]".toRegex())
        val hasCatastrophicTarget = args.any { !it.startsWith("-") && isCatastrophicTarget(it) }

        return hasDelete && hasCatastrophicTarget
    }

    /**
     * Evaluates whether a command requires manual user approval based on the active SecurityPreset.
     * Catastrophic commands (isBlocked) are unconditionally rejected and never prompt for approval.
     */
    fun shouldRequireApproval(assessment: SecurityAssessment, preset: SecurityPreset): Boolean {
        if (assessment.isBlocked) return false
        return when (preset) {
            SecurityPreset.TURBO -> false
            SecurityPreset.DEFAULT -> true
            SecurityPreset.FULL_MACHINE -> true
            SecurityPreset.CUSTOM -> {
                if (isStrictMode) true
                else assessment.level == RiskLevel.HIGH || assessment.level == RiskLevel.MEDIUM
            }
        }
    }
}
