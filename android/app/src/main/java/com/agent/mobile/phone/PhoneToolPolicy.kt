package com.agent.mobile.phone

import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.agent.RunMode
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.security.SecurityAssessment

/** What the phone looks like for one call, as far as the risk check needs to know. */
data class PhoneRiskContext(
    val keyguardLocked: Boolean = false,
    val foregroundPackage: String? = null,
    val foregroundLabel: String? = null,
    val windowClass: String? = null,
    /** The first few texts of the screen (toolbar title area), used to spot security settings pages. */
    val screenTitles: List<String> = emptyList(),
    /** The element the call refers to, resolved from the last screen reading. Null when unknown. */
    val target: TargetInfo? = null,
    /** The app the call is about (open_app, notification tools), when resolved. */
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val blocklist: PackageBlocklist = PackageBlocklist.DEFAULT,
    val ownPackage: String = OWN_PACKAGE
) {
    companion object {
        const val OWN_PACKAGE = "com.agent.mobile"
    }
}

data class TargetInfo(
    val label: String,
    val password: Boolean = false,
    val editable: Boolean = false,
    val viewId: String? = null
)

/** What kind of app something happens in. Decides which actions always need the user. */
enum class AppKind { MESSAGING, CONTACTS_CALLS, CAMERA, SYSTEM_UI, SYSTEM_SETTINGS, INSTALLER, SELF, OTHER }

/** Sensitive meaning of a tap or a submitted text. Each always needs the user's confirmation. */
enum class ActionCategory(val reason: String) {
    SEND("Sends a message or post to other people"),
    PAYMENT("Payment, purchase or subscription"),
    INSTALL("Installs, updates or uninstalls an app"),
    DELETE("Deletes data"),
    ACCOUNT("Account, login or password action"),
    CALL("Starts a phone or video call")
}

/**
 * Risk classification for every phone control tool. Mirrors [com.agent.mobile.security.CommandSecurityFilter]:
 * the same [SecurityAssessment] drives approvals, the policy simulator and the audit log.
 *
 * Hard rules:
 * - Apps on the block list, AMC itself and security settings pages are BLOCKED.
 * - Nothing is operated while the phone is locked.
 * - Sending messages, payments, installs, deletions, account actions, calls and system settings
 *   changes always need the user (alwaysConfirm), in every mode.
 * - Reading or navigating messaging, contacts and camera apps is like reading SMS: always asked,
 *   but one approval covers that app for the rest of the run ([SecurityAssessment.grantKey]).
 */
object PhoneToolPolicy {

    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.facebook.mlite", "com.instagram.android",
        "com.discord", "com.snapchat.android", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.android.messaging", "com.Slack", "com.microsoft.teams", "com.skype.raider",
        "ch.threema.app", "ch.threema.app.libre", "com.viber.voip", "im.vector.app", "com.wire",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.android.email",
        "com.samsung.android.email.provider", "eu.faircode.email", "com.fsck.k9", "net.thunderbird.android",
        "de.gmx.mobile.android.mail", "de.web.mobile.android.mail", "com.twitter.android", "com.linkedin.android",
        "com.facebook.katana", "com.zhiliaoapp.musically", "org.joinmastodon.android", "com.reddit.frontpage",
        "com.google.android.apps.dynamite", "com.tencent.mm", "jp.naver.line.android", "com.kakao.talk"
    )
    private val CONTACTS_CALLS_PACKAGES = setOf(
        "com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts",
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.phone",
        "com.android.server.telecom", "com.google.android.apps.tachyon"
    )
    private val CAMERA_PACKAGES = setOf(
        "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera",
        "org.codeaurora.snapcam", "com.oneplus.camera", "com.motorola.camera3", "com.huawei.camera",
        "com.oppo.camera", "com.xiaomi.camera", "net.sourceforge.opencamera"
    )
    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings", "com.samsung.android.settings", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.settings.intelligence", "com.google.android.gms",
        "com.android.vpndialogs", "com.android.managedprovisioning", "com.android.devicelockcontroller"
    )
    private const val SYSTEM_UI = "com.android.systemui"

    /**
     * Terminal apps. Typing into them would run shell commands past the security filter and could
     * reveal the bridge token, so the screen tools never read or operate them.
     */
    val TERMINAL_PACKAGES = setOf("com.termux", "jackpal.androidterm", "com.termoneplus", "com.offsec.nhterm", "yarolegovich.materialterminal")

    const val TERMINAL_REASON = "AMC does not read or operate terminal apps through the screen (that would bypass the " +
        "command security filter). Use execute_command instead."
    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.vending",
        "org.fdroid.fdroid", "com.aurora.store", "com.sec.android.app.samsungapps", "com.huawei.appmarket"
    )

    private val MESSAGING_LABEL = Regex(
        """\b(messenger|messages|messaging|nachrichten|sms|mms|chat|chats|mail|e-mail|email|inbox|posteingang|signal|threema|whatsapp|telegram)\b""",
        RegexOption.IGNORE_CASE
    )

    private val SEND = Regex(
        """\b(send|senden|absenden|abschicken|verschicken|submit|publish|ver(ö|oe)ffentlichen|post|posten|tweet|reply all|weiterleiten|forward)\b""",
        RegexOption.IGNORE_CASE
    )
    private val SEND_ID = Regex("""(^|[_/])(send|btn_send|send_button|sendbutton|compose_send|submit)([_]|$)""", RegexOption.IGNORE_CASE)
    private val PAYMENT = Regex(
        """\b(pay|bezahlen|zahlen|jetzt zahlen|zahlungspflichtig|buy|kaufen|jetzt kaufen|purchase|checkout|kasse|place order|order now|bestellen|bestellung abschicken|subscribe|abonnieren|abo abschlie(ß|ss)en|donate|spenden|top up|aufladen|(ü|ue)berweisen|transfer money|confirm payment|zahlung best(ä|ae)tigen|book now|jetzt buchen|rent|mieten)\b""",
        RegexOption.IGNORE_CASE
    )
    private val INSTALL = Regex("""\b(install|installieren|uninstall|deinstallieren|update|aktualisieren)\b""", RegexOption.IGNORE_CASE)
    private val DELETE = Regex(
        """\b(delete|l(ö|oe)schen|remove|entfernen|erase|wipe|clear (data|storage|all)|daten l(ö|oe)schen|speicher leeren|reset|zur(ü|ue)cksetzen|factory|discard|verwerfen|empty trash|papierkorb leeren|delete account|konto l(ö|oe)schen)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ACCOUNT = Regex(
        """\b(sign in|signin|log ?in|anmelden|einloggen|sign out|log ?out|abmelden|ausloggen|sign up|registrieren|create account|konto erstellen|add account|konto hinzuf(ü|ue)gen|change password|passwort (ä|ae)ndern|forgot password|passwort vergessen|2fa|two-factor|zwei-faktor|verify|verifizieren|authorize|autorisieren|grant access|zugriff erlauben)\b""",
        RegexOption.IGNORE_CASE
    )
    private val CALL = Regex("""\b(call|anrufen|dial|w(ä|ae)hlen|voice call|video call|videoanruf|sprachanruf)\b""", RegexOption.IGNORE_CASE)

    /** Classifies an app by package and, for unknown packages, by its name. */
    fun appKind(packageName: String?, label: String? = null, ownPackage: String = PhoneRiskContext.OWN_PACKAGE): AppKind {
        val pkg = packageName.orEmpty()
        return when {
            pkg == ownPackage -> AppKind.SELF
            pkg in MESSAGING_PACKAGES -> AppKind.MESSAGING
            pkg in CONTACTS_CALLS_PACKAGES -> AppKind.CONTACTS_CALLS
            pkg in CAMERA_PACKAGES || pkg.contains(".camera") -> AppKind.CAMERA
            pkg == SYSTEM_UI -> AppKind.SYSTEM_UI
            pkg in SETTINGS_PACKAGES || pkg.endsWith(".settings") -> AppKind.SYSTEM_SETTINGS
            pkg in INSTALLER_PACKAGES || pkg.endsWith(".packageinstaller") -> AppKind.INSTALLER
            !label.isNullOrBlank() && MESSAGING_LABEL.containsMatchIn(label) -> AppKind.MESSAGING
            else -> AppKind.OTHER
        }
    }

    /** The sensitive meaning of tapping an element with this label / view id, if any. */
    fun categorize(label: String?, viewId: String? = null): ActionCategory? {
        val text = label.orEmpty()
        val id = viewId?.substringAfterLast('/').orEmpty()
        return when {
            PAYMENT.containsMatchIn(text) -> ActionCategory.PAYMENT
            SEND.containsMatchIn(text) || SEND_ID.containsMatchIn(id) -> ActionCategory.SEND
            INSTALL.containsMatchIn(text) -> ActionCategory.INSTALL
            DELETE.containsMatchIn(text) -> ActionCategory.DELETE
            ACCOUNT.containsMatchIn(text) -> ActionCategory.ACCOUNT
            CALL.containsMatchIn(text) -> ActionCategory.CALL
            else -> null
        }
    }

    /** Whether the current screen is a security page of the system settings. */
    fun isSecuritySettingsScreen(ctx: PhoneRiskContext): Boolean {
        if (!ctx.blocklist.blocksSecuritySettings) return false
        if (appKind(ctx.foregroundPackage, ctx.foregroundLabel, ctx.ownPackage) != AppKind.SYSTEM_SETTINGS) return false
        return ctx.screenTitles.take(4).any { PackageBlocklist.SECURITY_SETTINGS_TITLES.containsMatchIn(it) }
    }

    /**
     * In REMOTE runs (Telegram) every screen tool needs a button approval, whatever the preset,
     * and approvals are never carried over to later calls.
     */
    fun requiresApprovalInMode(toolName: String, mode: RunMode): Boolean =
        mode == RunMode.REMOTE && toolName in PhoneTools.SCREEN_TOOLS

    fun appName(ctx: PhoneRiskContext): String = ctx.foregroundLabel?.takeIf { it.isNotBlank() } ?: ctx.foregroundPackage ?: "the current app"

    private fun low(reason: String) = SecurityAssessment(RiskLevel.LOW, reason)
    private fun medium(reason: String) = SecurityAssessment(RiskLevel.MEDIUM, reason)
    private fun blocked(reason: String) = SecurityAssessment(RiskLevel.BLOCKED, reason)
    private fun confirm(reason: String, grantKey: String? = null, redact: Boolean = false) = SecurityAssessment(
        level = RiskLevel.HIGH,
        reason = "$reason. Always requires your confirmation.",
        sensitive = true,
        alwaysConfirm = true,
        grantKey = grantKey,
        redact = redact
    )

    /** Reading or navigating a private app: asked once per app and run. */
    private fun privateRead(kind: AppKind, pkg: String, name: String): SecurityAssessment {
        val what = when (kind) {
            AppKind.MESSAGING -> "Reads private messages in $name"
            AppKind.CONTACTS_CALLS -> "Reads contacts or call history in $name"
            AppKind.CAMERA -> "Uses the camera app $name"
            else -> "Reads private content in $name"
        }
        return confirm(
            "$what. Approving lets AMC read and navigate $name for the rest of this task; sending, calling and photos still ask each time",
            grantKey = "read:$pkg"
        )
    }

    private val PRIVATE_KINDS = setOf(AppKind.MESSAGING, AppKind.CONTACTS_CALLS, AppKind.CAMERA)

    /** Assessment for a phone control tool call. Unknown tools are BLOCKED. */
    fun assess(call: ToolCall, ctx: PhoneRiskContext): SecurityAssessment {
        val a = call.arguments
        return when (call.name) {
            PhoneTools.APPS_LIST -> low("Lists installed apps")
            PhoneTools.SCREEN_READ, PhoneTools.WAIT_FOR -> assessRead(call, ctx)
            PhoneTools.SCREEN_CAPTURE -> assessCapture(call, ctx)
            in PhoneTools.NOTIFICATION_TOOLS -> assessNotification(call, ctx)
            in PhoneTools.INTENT_TOOLS -> assessIntent(call)
            PhoneTools.PRIVILEGED_SHELL -> PrivilegedCommandPolicy.assess(call.arguments["command"].orEmpty(), ctx)
            PhoneTools.OPEN_APP -> assessOpenApp(a["name"].orEmpty(), ctx)
            PhoneTools.SCREEN_GLOBAL -> assessGlobal(a["action"].orEmpty().trim().lowercase(), ctx)
            PhoneTools.SCREEN_TAP, PhoneTools.SCREEN_TYPE, PhoneTools.SCREEN_SCROLL, PhoneTools.SCREEN_SWIPE ->
                assessScreenAction(call, ctx)
            else -> blocked("Unknown phone tool '${call.name}'.")
        }
    }

    /** Hard stops shared by every screen tool. Null when none applies. */
    private fun hardBlock(ctx: PhoneRiskContext, allowLocked: Boolean = false): SecurityAssessment? {
        if (ctx.keyguardLocked && !allowLocked) {
            return blocked("The phone is locked. AMC never operates or bypasses the lock screen; ask the user to unlock it.")
        }
        val pkg = ctx.foregroundPackage ?: return null
        if (pkg == ctx.ownPackage) {
            return blocked("AMC does not operate its own app (it could approve its own requests). Switch to another app first.")
        }
        if (pkg in TERMINAL_PACKAGES) return blocked(TERMINAL_REASON)
        ctx.blocklist.blockReason(pkg, ctx.foregroundLabel, ctx.windowClass)?.let {
            return blocked("$it. The user can release it in Settings → Phone control.")
        }
        if (isSecuritySettingsScreen(ctx)) {
            return blocked("Security settings (screen lock, passwords, accounts, accessibility, device admins) are on the AMC block list.")
        }
        return null
    }

    private fun assessRead(call: ToolCall, ctx: PhoneRiskContext): SecurityAssessment {
        if (call.name == PhoneTools.WAIT_FOR && call.arguments["text"].isNullOrBlank()) return blocked("A text to wait for is required.")
        if (ctx.keyguardLocked) return low("Reads the screen (the phone is locked, nothing is read)")
        hardBlock(ctx)?.let { return it }
        val pkg = ctx.foregroundPackage.orEmpty()
        val kind = appKind(pkg, ctx.foregroundLabel, ctx.ownPackage)
        if (kind in PRIVATE_KINDS) return privateRead(kind, pkg, appName(ctx))
        if (kind == AppKind.SYSTEM_UI) return confirm("Reads the notification shade, which can contain private messages", grantKey = "notifications")
        return low("Reads the screen of ${appName(ctx)}")
    }

    private fun assessCapture(call: ToolCall, ctx: PhoneRiskContext): SecurityAssessment {
        if (ctx.keyguardLocked) return blocked("The phone is locked. AMC takes no screenshots of the lock screen.")
        hardBlock(ctx)?.let { return it }
        val pkg = ctx.foregroundPackage.orEmpty()
        val kind = appKind(pkg, ctx.foregroundLabel, ctx.ownPackage)
        if (kind in PRIVATE_KINDS) return privateRead(kind, pkg, appName(ctx))
        if (kind == AppKind.SYSTEM_UI) return confirm("Screenshot of the notification shade, which can contain private messages", grantKey = "notifications")
        val toUser = if (PhoneTools.isTrue(call.arguments["send_to_user"])) " and shown to you" else ""
        return medium("Screenshot of ${appName(ctx)}, sent to the AI provider$toUser")
    }

    private fun assessNotification(call: ToolCall, ctx: PhoneRiskContext): SecurityAssessment {
        val a = call.arguments
        val pkg = ctx.targetPackage
        val name = ctx.targetLabel?.takeIf { it.isNotBlank() } ?: pkg ?: "an app"
        fun blockedApp(): SecurityAssessment? = ctx.blocklist.blockReason(pkg, ctx.targetLabel)?.let {
            blocked("$it. The user can release it in Settings → Phone control.")
        }
        return when (call.name) {
            PhoneTools.NOTIFICATIONS_LIST ->
                confirm("Reads your notifications, which can contain private messages", grantKey = "notifications")
            PhoneTools.NOTIFICATION_OPEN -> {
                if (a["id"].isNullOrBlank()) return blocked("A notification id is required.")
                if (ctx.keyguardLocked) return blocked("The phone is locked. AMC never operates or bypasses the lock screen; ask the user to unlock it.")
                blockedApp()?.let { return it }
                when (val kind = appKind(pkg, ctx.targetLabel, ctx.ownPackage)) {
                    in PRIVATE_KINDS -> privateRead(kind, pkg.orEmpty(), name)
                    else -> medium("Opens a notification of $name")
                }
            }
            PhoneTools.NOTIFICATION_REPLY -> {
                if (a["id"].isNullOrBlank() || a["text"].isNullOrBlank()) return blocked("A notification id and the reply text are required.")
                blockedApp()?.let { return it }
                confirm("${ActionCategory.SEND.reason}: replies via $name")
            }
            PhoneTools.NOTIFICATION_DISMISS -> {
                if (a["id"].isNullOrBlank()) return blocked("A notification id is required.")
                blockedApp()?.let { return it }
                medium("Dismisses a notification of $name")
            }
            else -> when (a["action"].orEmpty().trim().lowercase()) {
                "list" -> low("Lists notification triggers")
                "remove" -> if (a["id"].isNullOrBlank()) blocked("A trigger id is required.") else low("Removes a notification trigger")
                "add" -> when {
                    a["app"].isNullOrBlank() || a["prompt"].isNullOrBlank() -> blocked("app and prompt are required to add a trigger.")
                    else -> blockedApp() ?: confirm(
                        "Starts an unattended background task whenever a notification from $name arrives; " +
                            "the notification content is untrusted"
                    )
                }
                else -> blocked("action must be add, list or remove.")
            }
        }
    }

    private fun assessIntent(call: ToolCall): SecurityAssessment {
        val a = call.arguments
        return when (call.name) {
            PhoneTools.SET_ALARM -> when (val spec = SystemIntentSpecs.parseAlarm(a)) {
                is SystemIntentSpecs.Alarm.Invalid -> blocked(spec.reason)
                is SystemIntentSpecs.Alarm.Timer -> medium("Starts a timer in the clock app")
                is SystemIntentSpecs.Alarm.At -> medium("Sets an alarm in the clock app")
            }
            PhoneTools.CALENDAR_ADD -> {
                val (_, error) = SystemIntentSpecs.parseEvent(a)
                if (error != null) blocked(error) else medium("Opens the calendar with a new event to save")
            }
            PhoneTools.SHARE_TEXT ->
                if (a["text"].isNullOrBlank()) blocked("Text to share is required.")
                else medium("Opens the share sheet; the target app is chosen there")
            PhoneTools.OPEN_URL -> {
                val url = a["url"].orEmpty()
                if (!SystemIntentSpecs.isOpenableUrl(url)) blocked("Only http:// and https:// links can be opened.")
                else medium("Opens ${SystemIntentSpecs.host(url)}")
            }
            PhoneTools.OPEN_SETTINGS -> {
                val page = a["page"].orEmpty().trim().lowercase()
                if (page !in SystemIntentSpecs.SETTINGS_PAGES) blocked("Unknown or not allowed settings page '$page'.")
                else low("Opens the $page settings page (changes there always ask)")
            }
            PhoneTools.CLIPBOARD -> when (a["action"].orEmpty().trim().lowercase()) {
                "get" -> SecurityAssessment(RiskLevel.HIGH, "Reads the clipboard (it can contain passwords or private data)")
                "set" -> if (a["text"] == null) blocked("Text is required for action=set.")
                else SecurityAssessment(RiskLevel.HIGH, "Replaces the clipboard content")
                else -> blocked("action must be get or set.")
            }
            PhoneTools.USAGE_STATS -> medium("Reads which apps you used and for how long")
            else -> blocked("Unknown tool '${call.name}'.")
        }
    }

    private fun assessOpenApp(name: String, ctx: PhoneRiskContext): SecurityAssessment {
        if (name.isBlank()) return blocked("An app name is required.")
        if (ctx.keyguardLocked) return blocked("The phone is locked. AMC never operates or bypasses the lock screen; ask the user to unlock it.")
        val pkg = ctx.targetPackage
        val label = ctx.targetLabel ?: name
        ctx.blocklist.blockReason(pkg ?: name.takeIf { it.contains('.') }, label)?.let {
            return blocked("$it. The user can release it in Settings → Phone control.")
        }
        if (pkg == null) return medium("Opens an app (\"$name\")")
        if (pkg == ctx.ownPackage) return low("Opens AMC")
        return when (val kind = appKind(pkg, label, ctx.ownPackage)) {
            in PRIVATE_KINDS -> privateRead(kind, pkg, label)
            AppKind.SYSTEM_SETTINGS -> medium("Opens system settings (changes there always ask)")
            AppKind.INSTALLER -> medium("Opens an app store (installs and purchases always ask)")
            else -> medium("Opens $label")
        }
    }

    private fun assessGlobal(action: String, ctx: PhoneRiskContext): SecurityAssessment {
        if (action !in setOf("back", "home", "recents", "notifications", "quick_settings")) {
            return blocked("Unknown action '$action'. Use back, home, recents, notifications or quick_settings.")
        }
        if (ctx.keyguardLocked) return blocked("The phone is locked. AMC never operates or bypasses the lock screen; ask the user to unlock it.")
        return when (action) {
            "notifications", "quick_settings" ->
                confirm("Opens the notification shade, which can contain private messages", grantKey = "notifications")
            else -> low("Presses the system ${action.replace('_', ' ')} button")
        }
    }

    private fun assessScreenAction(call: ToolCall, ctx: PhoneRiskContext): SecurityAssessment {
        val a = call.arguments
        when (call.name) {
            PhoneTools.SCREEN_TYPE -> if (a["text"] == null) return blocked("The text to type is required.")
            PhoneTools.SCREEN_SCROLL -> if (a["direction"].orEmpty().trim().lowercase() !in setOf("up", "down", "left", "right")) {
                return blocked("Direction must be up, down, left or right.")
            }
            PhoneTools.SCREEN_TAP -> if (a["target"].isNullOrBlank() && (a["x"]?.trim()?.toDoubleOrNull() == null || a["y"]?.trim()?.toDoubleOrNull() == null)) {
                return blocked("A target (element id or text) or x and y coordinates are required.")
            }
            PhoneTools.SCREEN_SWIPE -> if (listOf("x1", "y1", "x2", "y2").any { a[it]?.trim()?.toDoubleOrNull() == null }) {
                return blocked("x1, y1, x2 and y2 are required.")
            }
        }
        hardBlock(ctx)?.let { return it }

        val pkg = ctx.foregroundPackage.orEmpty()
        val kind = appKind(pkg, ctx.foregroundLabel, ctx.ownPackage)
        val name = appName(ctx)
        val target = ctx.target
        val submit = PhoneTools.isTrue(a["submit"])
        val longPress = PhoneTools.isTrue(a["long_press"])

        // Password fields: a login, and the typed text is never logged or stored.
        if (call.name == PhoneTools.SCREEN_TYPE && target?.password == true) {
            return confirm("Types into a password field in $name (login). The text is never logged", redact = true)
        }

        // What the tap or submit means, from the element's label.
        val category = when (call.name) {
            PhoneTools.SCREEN_TAP -> categorize(target?.label, target?.viewId)
            PhoneTools.SCREEN_TYPE -> when {
                submit && kind == AppKind.MESSAGING -> ActionCategory.SEND
                submit -> categorize(target?.label, target?.viewId)?.takeIf { it != ActionCategory.SEND }
                else -> null
            }
            else -> null
        }
        if (category != null) return confirm("${category.reason} (in $name: \"${target?.label.orEmpty().take(60)}\")")

        val modifies = call.name != PhoneTools.SCREEN_SCROLL
        when (kind) {
            AppKind.SYSTEM_SETTINGS, AppKind.SYSTEM_UI -> if (modifies) {
                return confirm("Changes system settings or a system dialog ($name)")
            }
            AppKind.INSTALLER -> if (modifies) return confirm("Acts in an app store or installer ($name)")
            AppKind.CAMERA -> if (modifies) return confirm("Operates the camera ($name); photos of others may need their consent")
            AppKind.MESSAGING, AppKind.CONTACTS_CALLS -> {
                // A tap whose target is unknown could hit "send" or "call".
                if (call.name == PhoneTools.SCREEN_TAP && target == null) {
                    return confirm("Taps at a position in $name that AMC cannot verify (it could send a message or start a call)")
                }
                if (call.name == PhoneTools.SCREEN_SWIPE) {
                    return confirm("Swipes in $name (a swipe can reply, archive or delete)")
                }
                if (call.name == PhoneTools.SCREEN_TAP && longPress) {
                    return confirm("Long-presses in $name (opens actions such as delete or forward)")
                }
                return privateRead(kind, pkg, name)
            }
            else -> Unit
        }
        return when (call.name) {
            PhoneTools.SCREEN_SCROLL -> low("Scrolls in $name")
            PhoneTools.SCREEN_TYPE -> medium("Types text in $name")
            PhoneTools.SCREEN_SWIPE -> medium("Swipes in $name")
            else -> medium(if (longPress) "Long-presses an element in $name" else "Taps an element in $name")
        }
    }
}
