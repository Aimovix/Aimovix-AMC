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
    val isBlocked: Boolean = level == RiskLevel.BLOCKED,
    /**
     * True when the action itself is sensitive (privacy, deletion, process control, obfuscation).
     * HIGH assessments that only mean "not on the small diagnostic allowlist" are not sensitive;
     * the Autonomous preset runs those without asking.
     */
    val sensitive: Boolean = level == RiskLevel.HIGH,
    /**
     * Actions that affect other people or cost money (SMS, calls, reading contacts/messages,
     * camera, microphone). They always need the user's personal confirmation, in every preset
     * including Turbo. Background runs never perform them on their own: they pause and ask, and
     * continue only after the user approved that exact call.
     */
    val alwaysConfirm: Boolean = false,
    /**
     * When set, one approval covers every later call with the same key in the same run (e.g.
     * "read:com.whatsapp" lets the agent read and navigate that app after one approval). Only
     * used for reading and navigating; sending, paying, deleting and similar never get a key.
     */
    val grantKey: String? = null,
    /** The call carries a secret (typing into a password field): its text is never logged or stored. */
    val redact: Boolean = false
)

object CommandSecurityFilter {

    private val ZERO_WIDTH_REGEX = Regex("[\u200B-\u200D\uFEFF\u0000]")
    private val IFS_REGEX = Regex("""\$(?:\{IFS[^}]*\}|IFS(?:\$9)?)""")
    private val MULTI_SLASH_REGEX = Regex("/{2,}")
    // `$cmd args`, `x=1; ${CMD}`, `foo | $X` ... a variable in command position.
    private val INDIRECT_COMMAND_REGEX = Regex("""(^|[;&|(\n]|\$\()\s*(\w+=\S*\s+)*\$[{A-Za-z_]""")

    // Blacklist: Commands that cause catastrophic, irreversible damage or malicious remote execution.
    // Recursive deletion is checked structurally (every rm invocation, also inside $(…), with its real
    // targets): a regex on "~" or a trailing "/" also blocked `rm -rf build/` and `rm -rf ~/proj/tmp`.
    private val BLACKLIST_PATTERNS = listOf(
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

    // Patterns whose routine forms need no approval in the Autonomous preset (see [isRoutine]).
    private val INLINE_CODE_PATTERN = Pattern.compile("\\b(python|python3|node|perl|ruby|sh|bash)\\s+(-c|-e)\\b", Pattern.CASE_INSENSITIVE)
    private val SUBSHELL_PATTERN = Pattern.compile("\\b(sh|bash)\\s+-c\\b", Pattern.CASE_INSENSITIVE)
    private val RM_PATTERN = Pattern.compile("\\brm\\b")
    private val CHMOD_PATTERN = Pattern.compile("\\b(chmod|chown)\\b")

    // High Risk: Data deletion, Telephony, SMS, Camera, System Reboot, Process Killing, Obfuscation & Dynamic Evaluation
    private val HIGH_RISK_PATTERNS = listOf(
        // Obfuscation & Dynamic execution guards
        Pair(Pattern.compile("\\bbase64\\s+(-d|--decode)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Base64-encoded shell execution detected"),
        Pair(Pattern.compile("\\|\\s*(ba|z|da)?sh\\b", Pattern.CASE_INSENSITIVE), "Piped shell command (dynamic execution)"),
        // Not find's -exec / -execdir options.
        Pair(Pattern.compile("(?<![-\\w])(eval|exec)\\s+", Pattern.CASE_INSENSITIVE), "Dynamic execution via eval/exec"),
        Pair(INLINE_CODE_PATTERN, "Inline code executed by an interpreter"),
        Pair(Pattern.compile("\\b(xxd|hexdump)\\b.*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE), "Hex-encoded shell execution detected"),
        Pair(SUBSHELL_PATTERN, "Subshell command execution via -c"),

        // Sensitive Hardware, Privacy & System Actions
        Pair(RM_PATTERN, "File deletion detected"),
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
        Pair(CHMOD_PATTERN, "Changing filesystem permissions or ownership"),
        // Redirecting to /dev/null, the standard streams or the terminal is everyday shell use.
        Pair(Pattern.compile(">\\s*/dev/(?!(?:null|stdout|stderr|tty|fd/[0-9]+)(?![\\w/]))"), "Direct device access"),
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

    // Third-party data, communication and recording: always confirmed by the user (legal safeguard).
    private val ALWAYS_CONFIRM_PATTERNS = listOf(
        Pair(Pattern.compile("\\btermux-sms-send\\b"), "Sends an SMS to another person (may cost money)"),
        Pair(Pattern.compile("\\btermux-sms-(list|inbox)\\b"), "Reads private messages from and to other people"),
        Pair(Pattern.compile("\\btermux-telephony-call\\b"), "Places a phone call (may cost money)"),
        Pair(Pattern.compile("\\btermux-contact-list\\b"), "Reads other people's contact data"),
        Pair(Pattern.compile("\\btermux-call-log\\b"), "Reads the call history with other people"),
        Pair(Pattern.compile("\\btermux-microphone-record\\b"), "Records audio (recording others without consent can be illegal)"),
        Pair(Pattern.compile("\\btermux-camera-photo\\b"), "Takes a photo (photographing others may need their consent)")
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
        val base = analyzeRisk(command)
        if (base.isBlocked) return base
        val normalized = normalizeCommand(command.trim())
        val legal = ALWAYS_CONFIRM_PATTERNS.firstOrNull { (pattern, _) ->
            pattern.matcher(command).find() || pattern.matcher(normalized).find()
        } ?: return base
        return SecurityAssessment(
            level = RiskLevel.HIGH,
            reason = "${legal.second}. Always requires your confirmation.",
            sensitive = true,
            alwaysConfirm = true
        )
    }

    private fun analyzeRisk(command: String, depth: Int = 0): SecurityAssessment {
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
        // rm invocations that are not at the start of a statement: $(rm -rf ~), backticks, xargs rm …
        for (text in listOf(trimmed, normalized)) {
            if (rmInvocations(text).any { isCatastrophicRm(it) }) {
                return SecurityAssessment(
                    level = RiskLevel.BLOCKED,
                    reason = "Command blocked: destructive recursive file deletion targeting root, storage, home, or wildcards."
                )
            }
        }

        // 4. Check High Risk. Routine forms of deletion, chmod and inline code are noted and the
        // remaining patterns still checked; any other match is sensitive.
        var routineReason: String? = null
        for ((pattern, desc) in HIGH_RISK_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                val routine = if (pattern === INLINE_CODE_PATTERN || pattern === SUBSHELL_PATTERN) {
                    // Code strings need their quotes; the normalized form must not hide further interpreter calls.
                    isRoutine(pattern, trimmed, depth) &&
                        INLINE_CODE_START.findAll(trimmed).count() == INLINE_CODE_START.findAll(normalized).count()
                } else {
                    isRoutine(pattern, trimmed, depth) && (normalized == trimmed || isRoutine(pattern, normalized, depth))
                }
                if (routine) {
                    if (routineReason == null) routineReason = desc
                    continue
                }
                return SecurityAssessment(
                    level = RiskLevel.HIGH,
                    reason = desc
                )
            }
        }

        // A command name taken from a variable or backticks hides what actually runs.
        if (INDIRECT_COMMAND_REGEX.containsMatchIn(trimmed) || trimmed.contains('\u0060')) {
            return SecurityAssessment(RiskLevel.HIGH, "Command name hidden in a variable or backticks.")
        }

        if (routineReason != null) {
            // Still HIGH (Supervised and Custom ask), but routine: the Autonomous preset runs it.
            return SecurityAssessment(
                RiskLevel.HIGH,
                "$routineReason (routine: files in the Termux home or working folder, or harmless inline code)",
                sensitive = false
            )
        }

        // Shell syntax and scripts can hide side effects; never whitelist them.
        if (trimmed.any { it in "$" + "\u0060;&|<>(){}\n\r\\" } ||
            Regex("""\b(python[0-9.]*|bash|sh|node|perl|ruby)\b""").containsMatchIn(normalized)) {
            return SecurityAssessment(RiskLevel.HIGH, "Shell composition or executable code requires approval.", sensitive = false)
        }

        val commandName = normalized.substringBefore(' ')
        val recognizedMedium = commandName in setOf("touch", "mkdir", "mv", "cp", "curl", "wget", "pkg", "apt", "apt-get", "pip", "git")

        // Only explicitly recognized operations can receive an allowlist exception.
        // 5. Check Medium Risk
        for ((pattern, desc) in MEDIUM_RISK_PATTERNS) {
            if (pattern.matcher(trimmed).find() || pattern.matcher(normalized).find()) {
                return SecurityAssessment(
                    level = if (!recognizedMedium) RiskLevel.HIGH else if (customWhitelist.any { it.matcher(trimmed).matches() }) RiskLevel.LOW else RiskLevel.MEDIUM,
                    reason = desc,
                    sensitive = false
                )
            }
        }

        // A small explicit diagnostic allowlist; unknown commands are not read-only.
        val diagnostic = Regex("""^(?:pwd|whoami|date|uptime|termux-battery-status|uname(?:\s+-[a-zA-Z]+)?|ls(?:\s+[-a-zA-Z0-9_./~*]+)*|echo(?:\s+[a-zA-Z0-9 _.,:!?/-]+)?)$""")
        return if (diagnostic.matches(trimmed)) {
            SecurityAssessment(RiskLevel.LOW, "Recognized diagnostic command.")
        } else {
            SecurityAssessment(RiskLevel.HIGH, "Unrecognized command requires approval.", sensitive = false)
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

    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    // A script file run by an interpreter, or executed directly as ./x or ~/x, at the start of a statement.
    private val SCRIPT_BY_INTERPRETER = Regex(
        """(?:^|[;&|(\n]\s*)(?:(?:nohup|exec|time|env|timeout\s+\S+)\s+)*(?:bash|sh|zsh|dash|source|\.|python[0-9.]*|node|perl|ruby)\s+(?:-[a-zA-Z]+\s+)*([^\s;&|<>'"()`$]+)"""
    )
    private val SCRIPT_DIRECT = Regex(
        """(?:^|[;&|(\n]\s*)(?:(?:nohup|exec|time|env|timeout\s+\S+)\s+)*((?:\./|~/)[^\s;&|<>'"()`$]+)"""
    )

    /** Script files [command] runs (bash x.sh, python3 job.py, ./run.sh, source env.sh), at most 3. */
    fun scriptFiles(command: String): List<String> =
        (SCRIPT_BY_INTERPRETER.findAll(command).map { it.groupValues[1] } + SCRIPT_DIRECT.findAll(command).map { it.groupValues[1] })
            .filter { it.isNotEmpty() && !it.startsWith("-") && it.contains(Regex("[/.]")) }
            .distinct().take(3).toList()

    /**
     * Applies the rules to the content of a script [path] that [base] runs: writing a file and then
     * running it must not get around a check that the same commands typed directly would get.
     * Blocked content blocks the call; third-party actions keep their mandatory confirmation.
     */
    fun withScriptContent(base: SecurityAssessment, path: String, content: String): SecurityAssessment {
        if (base.isBlocked) return base
        val code = content.take(200_000).lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        if (code.isBlank()) return base
        val script = analyze(code)
        val label = "Script $path: ${script.reason}"
        return when {
            script.isBlocked -> SecurityAssessment(RiskLevel.BLOCKED, label)
            script.alwaysConfirm -> base.copy(level = RiskLevel.HIGH, reason = label, sensitive = true, alwaysConfirm = true)
            script.level == RiskLevel.HIGH && script.sensitive && !(base.level == RiskLevel.HIGH && base.sensitive) ->
                base.copy(level = RiskLevel.HIGH, reason = label, sensitive = true)
            else -> base
        }
    }

    // An rm call anywhere in a command line (also after $(, backticks, quotes or xargs), up to the end of its statement.
    private val RM_INVOCATION = Regex("""(?:^|[\s;&|(=`'"])((?:[^\s;&|()`'"]*/)?\\?rm\s+[^;&|)\n\r`]*)""")

    private fun rmInvocations(command: String): List<String> =
        RM_INVOCATION.findAll(command).map { it.groupValues[1].trim().removePrefix("\\") }.toList()

    // Inline code: interpreter, optional flags, -c/-e and one quoted code string.
    private val INLINE_CODE = Regex(
        """\b(python[0-9.]*|node|perl|ruby|sh|bash)\s+(?:-[a-zA-Z]+\s+)*?(?:-c|-e)\s+(?:'([^']*)'|"((?:[^"\\]|\\.)*)")""",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_CODE_START = Regex("""\b(python[0-9.]*|node|perl|ruby|sh|bash)\s+(?:-[a-zA-Z]+\s+)*?(?:-c|-e)\b""", RegexOption.IGNORE_CASE)

    // Interpreter calls that run other programs, delete or kill, or build code at runtime.
    private val DANGEROUS_INLINE_CODE = Regex(
        """os\.system|subprocess|os\.(remove|unlink|rmdir|removedirs|kill|exec\w*|spawn\w*|popen)|shutil\.(rmtree|move)|""" +
            """\b(exec|eval|compile|__import__)\s*\(|child_process|execSync|spawnSync|\bspawn\(|unlinkSync|rmSync|rmdirSync|""" +
            """fs\.(rm|unlink|rmdir)|\bsystem\s*\(|\bkill\b|File\.delete|FileUtils\.rm|\bunlink\b|\brmtree\b|\bpopen\b|`|\bqx\b|%x\{""",
        RegexOption.IGNORE_CASE
    )

    /** True when the part of [command] that matched [pattern] is a routine, non-sensitive form. */
    private fun isRoutine(pattern: Pattern, command: String, depth: Int): Boolean = when {
        pattern === RM_PATTERN -> isRoutineRm(command)
        pattern === CHMOD_PATTERN -> isRoutineChmod(command)
        pattern === INLINE_CODE_PATTERN || pattern === SUBSHELL_PATTERN -> isHarmlessInlineCode(command, depth)
        else -> false
    }

    /**
     * A file the agent may delete or chmod without asking: inside the Termux home or the working
     * folder, never shared storage, never a variable, a wildcard-only target or a path with "..".
     */
    private fun isRoutineFileTarget(raw: String): Boolean {
        val t = raw.trim().trim('\'', '"')
        if (t.isEmpty() || t.startsWith("-")) return false
        if (t.any { it in "\$`{}()<>|;&\\" } || t.contains("..")) return false
        if (isCatastrophicTarget(t)) return false
        val clean = normalizePath(t)
        if (clean == "*" || clean.endsWith("/*")) return false
        val inHome = when {
            clean.startsWith("~/") -> clean.removePrefix("~/")
            clean.startsWith("$TERMUX_HOME/") -> clean.removePrefix("$TERMUX_HOME/")
            clean.startsWith("/") -> return false
            else -> clean.removePrefix("./")
        }
        // ~/storage links to the user's shared files (photos, downloads).
        return !(inHome == "storage" || inHome.startsWith("storage/"))
    }

    /** Every rm call deletes single files (not recursive) inside the Termux home or working folder. */
    private fun isRoutineRm(command: String): Boolean {
        val calls = rmInvocations(command)
        if (calls.isEmpty()) return false
        return calls.all { call ->
            val args = call.replace(REDIRECTION, " ").trim().split(Regex("\\s+")).drop(1)
            val targets = args.filter { !it.startsWith("-") }
            val flags = args.filter { it.startsWith("-") && it != "--" }
            val recursive = flags.any { it == "--recursive" || (!it.startsWith("--") && it.contains('r', ignoreCase = true)) }
            !recursive && targets.isNotEmpty() && targets.all { isRoutineFileTarget(it) }
        }
    }

    // Output redirections after a command (2>/dev/null, >log.txt, 2>&1) are not file arguments.
    private val REDIRECTION = Regex("""[0-9]*>>?&?\s*\S*""")

    private val CHMOD_INVOCATION = Regex("""(?:^|[\s;&|(])(chmod|chown)\s+([^;&|)\n\r]*)""")

    /** Non-recursive chmod (e.g. chmod +x script.sh) on files in the Termux home or working folder. */
    private fun isRoutineChmod(command: String): Boolean {
        val calls = CHMOD_INVOCATION.findAll(command).toList()
        if (calls.isEmpty()) return false
        return calls.all { m ->
            if (m.groupValues[1] != "chmod") return@all false
            val args = m.groupValues[2].replace(REDIRECTION, " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (args.any { it.startsWith("-") && (it == "--recursive" || it.contains('R')) }) return@all false
            val rest = args.filter { !it.startsWith("-") }
            // First argument is the mode, the rest are files.
            rest.size >= 2 && rest.drop(1).all { isRoutineFileTarget(it) }
        }
    }

    /**
     * Inline code whose content can be checked: shell code (-c) is analysed with the same rules,
     * other interpreters must not run programs, delete, kill or build code at runtime.
     */
    private fun isHarmlessInlineCode(command: String, depth: Int): Boolean {
        if (depth >= 3) return false
        val starts = INLINE_CODE_START.findAll(command).count()
        val blocks = INLINE_CODE.findAll(command).toList()
        // Code that is not one plainly quoted string cannot be checked.
        if (starts == 0 || blocks.size != starts) return false
        return blocks.all { m ->
            val interpreter = m.groupValues[1].lowercase()
            val code = m.groupValues[2].ifEmpty { m.groupValues[3].replace("\\\"", "\"") }
            if (interpreter == "sh" || interpreter == "bash") {
                // Code built at runtime ($(curl …), backticks, eval) cannot be checked.
                if (code.contains("\$(") || code.contains('`') || Regex("""\beval\b""").containsMatchIn(code)) return@all false
                val inner = analyzeRisk(code, depth + 1)
                !inner.isBlocked && !(inner.level == RiskLevel.HIGH && inner.sensitive)
            } else {
                !DANGEROUS_INLINE_CODE.containsMatchIn(code)
            }
        }
    }

    /** Resolves "." and ".." in an absolute or home-relative path; null when it cannot. */
    private fun canonical(path: String): String? {
        val absolute = when {
            path == "~" || path.startsWith("~/") -> TERMUX_HOME + path.removePrefix("~")
            path.startsWith("\$HOME") -> TERMUX_HOME + path.removePrefix("\$HOME")
            path.startsWith("\${HOME}") -> TERMUX_HOME + path.removePrefix("\${HOME}")
            path.startsWith("/") -> path
            else -> return null
        }
        val parts = ArrayDeque<String>()
        for (seg in absolute.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun isCatastrophicTarget(target: String): Boolean {
        val clean = normalizePath(target)
        // "..": judge the path it really points to (/tmp/.. is /, ~/.. is Termux's files folder).
        if (clean.split('/').any { it == ".." }) {
            // Relative: unknown base folder; only climbing out with nothing else ("..", "../*") is catastrophic.
            val resolved = canonical(clean) ?: return clean.split('/').all { it == ".." || it == "." || it == "*" }
            return isCatastrophicTarget(resolved)
        }
        if (clean == "./*" || clean == "./.*" || clean == ".*") return true
        // The user's shared files through Termux's storage links.
        if (clean in setOf(
            "~/storage", "~/storage/*", "~/storage/shared", "~/storage/shared/*",
            "\$HOME/storage", "\$HOME/storage/shared", "\$HOME/storage/shared/*",
            "$TERMUX_HOME/*", "$TERMUX_HOME/storage", "$TERMUX_HOME/storage/shared", "$TERMUX_HOME/storage/shared/*"
        )) {
            return true
        }
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
        if (assessment.alwaysConfirm) return true
        return when (preset) {
            SecurityPreset.TURBO -> false
            // Autonomous: routine work (reading, writing, scripts, installs, downloads) runs on its own;
            // privacy-sensitive and destructive actions still need the user.
            SecurityPreset.AUTONOMOUS -> assessment.level == RiskLevel.HIGH && assessment.sensitive
            SecurityPreset.DEFAULT -> true
            SecurityPreset.FULL_MACHINE -> true
            SecurityPreset.CUSTOM -> {
                if (isStrictMode) true
                else assessment.level == RiskLevel.HIGH || assessment.level == RiskLevel.MEDIUM
            }
        }
    }
}
