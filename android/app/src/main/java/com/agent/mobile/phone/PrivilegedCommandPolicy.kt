package com.agent.mobile.phone

import com.agent.mobile.security.RiskLevel
import com.agent.mobile.security.SecurityAssessment

/**
 * Risk check for privileged_shell (ADB rights through Shizuku). Every command is at least HIGH
 * risk. Only `input`, `pm`, `settings`, `dumpsys`, `screencap` and `am` may run, as a plain
 * argument list (no shell: no pipes, redirects, variables or chaining). Changes to apps, settings
 * and permissions always ask; blocked apps and AMC's own permissions are off limits.
 */
object PrivilegedCommandPolicy {

    val ALLOWED_BINARIES = setOf("input", "pm", "settings", "dumpsys", "screencap", "am")

    private val SHELL_SYNTAX = Regex("""[;&|`$<>(){}\\\n\r"']""")
    private val PACKAGE_TOKEN = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    /** Splits a command into arguments; null when it contains shell syntax. */
    fun tokenize(command: String): List<String>? {
        val c = command.trim()
        if (c.isEmpty() || SHELL_SYNTAX.containsMatchIn(c)) return null
        return c.split(Regex("\\s+"))
    }

    private fun high(reason: String) = SecurityAssessment(RiskLevel.HIGH, "Privileged (ADB) command: $reason", sensitive = true)
    private fun blocked(reason: String) = SecurityAssessment(RiskLevel.BLOCKED, reason)
    private fun confirm(reason: String) = SecurityAssessment(
        level = RiskLevel.HIGH,
        reason = "Privileged (ADB) command: $reason. Always requires your confirmation.",
        sensitive = true,
        alwaysConfirm = true
    )

    fun assess(command: String, ctx: PhoneRiskContext): SecurityAssessment {
        val tokens = tokenize(command)
            ?: return blocked("privileged_shell takes one plain command (no pipes, redirects, quotes, variables or chaining).")
        val bin = tokens.first()
        if (bin !in ALLOWED_BINARIES) {
            return blocked("Only ${ALLOWED_BINARIES.sorted().joinToString()} are allowed in privileged_shell. Use execute_command for other work.")
        }
        val args = tokens.drop(1)
        val sub = args.firstOrNull().orEmpty()

        // Packages named anywhere in the command: blocked apps are off limits.
        for (t in args) {
            val pkg = PACKAGE_TOKEN.find(t.substringAfter("package:").substringBefore('/'))?.value ?: continue
            ctx.blocklist.blockReason(pkg, null, t.substringAfter('/', ""))?.let {
                return blocked("$it. The user can release it in Settings → Phone control.")
            }
        }
        val touchesSelf = args.any { it.contains(ctx.ownPackage) }

        return when (bin) {
            "input" -> {
                if (ctx.keyguardLocked) return blocked("The phone is locked. AMC never operates or bypasses the lock screen.")
                hardBlockForeground(ctx)?.let { return it }
                when (PhoneToolPolicy.appKind(ctx.foregroundPackage, ctx.foregroundLabel, ctx.ownPackage)) {
                    AppKind.OTHER -> if (ctx.foregroundPackage == null) confirm("raw input into an unknown app cannot be verified")
                    else high("raw input ($sub) into ${PhoneToolPolicy.appName(ctx)}")
                    else -> confirm("raw input into ${PhoneToolPolicy.appName(ctx)} cannot be verified (it could send, pay or change settings)")
                }
            }
            "screencap" -> {
                if (ctx.keyguardLocked) return blocked("The phone is locked. AMC takes no screenshots of the lock screen.")
                hardBlockForeground(ctx)?.let { return it }
                if (args.none { it.startsWith("/") }) return blocked("screencap needs an output file, e.g. screencap -p /sdcard/Download/shot.png")
                when (PhoneToolPolicy.appKind(ctx.foregroundPackage, ctx.foregroundLabel, ctx.ownPackage)) {
                    AppKind.MESSAGING, AppKind.CONTACTS_CALLS, AppKind.CAMERA, AppKind.SYSTEM_UI ->
                        confirm("screenshot of private content in ${PhoneToolPolicy.appName(ctx)}")
                    else -> high("saves a screenshot to a file")
                }
            }
            "pm" -> when {
                sub in setOf("install", "install-create", "install-write", "install-commit", "install-existing", "uninstall") ->
                    confirm(ActionCategory.INSTALL.reason)
                sub == "clear" -> confirm(ActionCategory.DELETE.reason + " (clears an app's data)")
                sub in setOf("grant", "revoke", "reset-permissions", "set-permission-flags") ->
                    confirm(if (touchesSelf) "changes AMC's own permissions" else "changes app permissions")
                sub in setOf("disable", "disable-user", "enable", "hide", "unhide", "suspend", "unsuspend", "default-state") ->
                    confirm("enables, disables or hides an app")
                sub.startsWith("set-") || sub == "trim-caches" -> confirm("changes system package settings")
                sub in setOf("list", "path", "dump", "resolve-activity", "query-activities", "has-feature") -> high("reads package information")
                else -> confirm("unrecognised pm command")
            }
            "settings" -> when (sub) {
                "get", "list" -> high("reads system settings")
                else -> confirm("changes system settings")
            }
            "am" -> {
                val joined = args.joinToString(" ")
                when {
                    Regex("""android\.intent\.action\.(CALL|DIAL)|\btel:""").containsMatchIn(joined) -> confirm(ActionCategory.CALL.reason)
                    Regex("""\b(sms|smsto|mms|mmsto|mailto):|android\.intent\.action\.SEND""").containsMatchIn(joined) -> confirm(ActionCategory.SEND.reason)
                    sub in setOf("force-stop", "kill", "kill-all", "stop-service", "crash") -> high("stops app processes")
                    sub in setOf("start", "start-activity", "start-service", "start-foreground-service", "broadcast") ->
                        if (ctx.keyguardLocked && sub.startsWith("start")) blocked("The phone is locked. AMC never operates or bypasses the lock screen.")
                        else high("starts an activity, service or broadcast")
                    else -> confirm("unrecognised am command")
                }
            }
            "dumpsys" -> when (sub) {
                "notification" -> confirm("dumps notifications, which can contain private messages")
                "account", "clipboard", "telephony.registry", "sms" -> confirm("dumps accounts, clipboard or telephony data")
                else -> high("reads system service state")
            }
            else -> blocked("Not allowed.")
        }
    }

    private fun hardBlockForeground(ctx: PhoneRiskContext): SecurityAssessment? {
        val pkg = ctx.foregroundPackage ?: return null
        if (pkg == ctx.ownPackage) return blocked("AMC does not operate its own app.")
        ctx.blocklist.blockReason(pkg, ctx.foregroundLabel, ctx.windowClass)?.let {
            return blocked("$it. The user can release it in Settings → Phone control.")
        }
        if (PhoneToolPolicy.isSecuritySettingsScreen(ctx)) return blocked("Security settings are on the AMC block list.")
        return null
    }
}
