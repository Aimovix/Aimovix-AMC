package com.agent.mobile.phone

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.agent.RunMode
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.security.SecurityAssessment
import org.junit.Assert.*
import org.junit.Test

class PhoneToolPolicyTest {

    private fun call(name: String, vararg args: Pair<String, String>) = ToolCall(name = name, arguments = args.toMap())

    private fun ctx(pkg: String?, label: String? = pkg, target: TargetInfo? = null, titles: List<String> = emptyList(), locked: Boolean = false) =
        PhoneRiskContext(keyguardLocked = locked, foregroundPackage = pkg, foregroundLabel = label, target = target, screenTitles = titles)

    private fun assess(c: ToolCall, context: PhoneRiskContext): SecurityAssessment = AgentTools.assess(c, context)

    private fun asksInEveryPreset(a: SecurityAssessment) =
        SecurityPreset.values().all { CommandSecurityFilter.shouldRequireApproval(a, it) }

    @Test
    fun readingAnOrdinaryAppIsLowRisk() {
        val a = assess(call(PhoneTools.SCREEN_READ), ctx("com.android.chrome", "Chrome"))
        assertEquals(RiskLevel.LOW, a.level)
        assertFalse(a.alwaysConfirm)
        assertFalse(CommandSecurityFilter.shouldRequireApproval(a, SecurityPreset.AUTONOMOUS))
        assertTrue(CommandSecurityFilter.shouldRequireApproval(a, SecurityPreset.DEFAULT))
    }

    @Test
    fun ordinaryTapIsMediumAndScrollIsLow() {
        val tap = assess(call(PhoneTools.SCREEN_TAP, "target" to "e3"), ctx("com.example.notes", "Notes", TargetInfo("Add note")))
        assertEquals(RiskLevel.MEDIUM, tap.level)
        assertFalse(CommandSecurityFilter.shouldRequireApproval(tap, SecurityPreset.AUTONOMOUS))
        val scroll = assess(call(PhoneTools.SCREEN_SCROLL, "direction" to "down"), ctx("com.example.notes"))
        assertEquals(RiskLevel.LOW, scroll.level)
    }

    @Test
    fun blockedAppsAreBlockedForEveryScreenTool() {
        val bank = ctx("com.starfinanz.smob.android.sfinanzstatus", "Sparkasse")
        listOf(
            call(PhoneTools.SCREEN_READ),
            call(PhoneTools.SCREEN_TAP, "target" to "e1"),
            call(PhoneTools.SCREEN_TYPE, "text" to "1"),
            call(PhoneTools.SCREEN_SCROLL, "direction" to "down"),
            call(PhoneTools.WAIT_FOR, "text" to "Konto")
        ).forEach { assertTrue(it.name, assess(it, bank).isBlocked) }
        // Leaving a blocked app is allowed.
        assertFalse(assess(call(PhoneTools.SCREEN_GLOBAL, "action" to "home"), bank).isBlocked)
    }

    @Test
    fun openingABlockedAppIsBlockedByPackageOrName() {
        assertTrue(assess(call(PhoneTools.OPEN_APP, "name" to "Bitwarden"),
            PhoneRiskContext(targetPackage = "com.x8bit.bitwarden", targetLabel = "Bitwarden")).isBlocked)
        assertTrue(assess(call(PhoneTools.OPEN_APP, "name" to "Sparkasse"), PhoneRiskContext()).isBlocked)
        assertFalse(assess(call(PhoneTools.OPEN_APP, "name" to "Spotify"),
            PhoneRiskContext(targetPackage = "com.spotify.music", targetLabel = "Spotify")).isBlocked)
    }

    @Test
    fun amcNeverOperatesItself() {
        val own = ctx(PhoneRiskContext.OWN_PACKAGE, "AMC")
        assertTrue(assess(call(PhoneTools.SCREEN_TAP, "target" to "Approve"), own).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_READ), own).isBlocked)
    }

    @Test
    fun lockedPhoneIsNeverOperated() {
        val locked = ctx("com.android.systemui", locked = true)
        assertTrue(assess(call(PhoneTools.SCREEN_TAP, "x" to "100", "y" to "200"), locked).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_TYPE, "text" to "1234"), locked).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_SWIPE, "x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4"), locked).isBlocked)
        assertTrue(assess(call(PhoneTools.OPEN_APP, "name" to "Maps"), locked).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_GLOBAL, "action" to "back"), locked).isBlocked)
        assertFalse("reading only reports that it is locked", assess(call(PhoneTools.SCREEN_READ), locked).isBlocked)
    }

    @Test
    fun sendingAMessageAlwaysAsksAndIsNeverGranted() {
        val a = assess(call(PhoneTools.SCREEN_TAP, "target" to "e7"), ctx("com.whatsapp", "WhatsApp", TargetInfo("Send")))
        assertTrue(a.alwaysConfirm)
        assertNull("sending is never covered by an earlier approval", a.grantKey)
        assertTrue(a.reason.contains("Sends a message"))
        assertTrue(asksInEveryPreset(a))
    }

    @Test
    fun sendButtonIsRecognisedByViewIdAndInOtherApps() {
        assertTrue(assess(call(PhoneTools.SCREEN_TAP, "target" to "e7"),
            ctx("com.example.forum", "Forum", TargetInfo("", viewId = "com.example.forum:id/btn_send"))).alwaysConfirm)
        assertTrue(assess(call(PhoneTools.SCREEN_TAP, "target" to "e2"),
            ctx("com.example.forum", "Forum", TargetInfo("Senden"))).alwaysConfirm)
    }

    @Test
    fun submittingTextInAMessengerIsSending() {
        val a = assess(call(PhoneTools.SCREEN_TYPE, "text" to "bin gleich da", "submit" to "true"),
            ctx("org.telegram.messenger", "Telegram", TargetInfo("Message", editable = true)))
        assertTrue(a.alwaysConfirm)
        assertNull(a.grantKey)
    }

    @Test
    fun readingAndTypingInAMessengerAsksOncePerApp() {
        val read = assess(call(PhoneTools.SCREEN_READ), ctx("com.whatsapp", "WhatsApp"))
        assertTrue(read.alwaysConfirm)
        assertEquals("read:com.whatsapp", read.grantKey)
        val type = assess(call(PhoneTools.SCREEN_TYPE, "text" to "Anna"), ctx("com.whatsapp", "WhatsApp", TargetInfo("Search", editable = true)))
        assertEquals("read:com.whatsapp", type.grantKey)
        val open = assess(call(PhoneTools.OPEN_APP, "name" to "WhatsApp"), PhoneRiskContext(targetPackage = "com.whatsapp", targetLabel = "WhatsApp"))
        assertEquals("read:com.whatsapp", open.grantKey)
    }

    @Test
    fun unverifiableTapsInAMessengerAlwaysAsk() {
        val a = assess(call(PhoneTools.SCREEN_TAP, "x" to "990", "y" to "2150"), ctx("com.whatsapp", "WhatsApp", target = null))
        assertTrue(a.alwaysConfirm)
        assertNull(a.grantKey)
        val swipe = assess(call(PhoneTools.SCREEN_SWIPE, "x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4"), ctx("com.whatsapp", "WhatsApp"))
        assertNull(swipe.grantKey)
        assertTrue(swipe.alwaysConfirm)
    }

    @Test
    fun paymentsInstallsDeletionsAccountsAndCallsAlwaysAsk() {
        val shop = "com.example.shop"
        listOf(
            "Jetzt kaufen" to "Payment",
            "Place order" to "Payment",
            "Install" to "Installs",
            "Uninstall" to "Installs",
            "Delete" to "Deletes",
            "Löschen" to "Deletes",
            "Sign in" to "Account",
            "Abmelden" to "Account",
            "Anrufen" to "call"
        ).forEach { (label, word) ->
            val a = assess(call(PhoneTools.SCREEN_TAP, "target" to label), ctx(shop, "Shop", TargetInfo(label)))
            assertTrue(label, a.alwaysConfirm)
            assertTrue("$label → ${a.reason}", a.reason.contains(word))
            assertNull(a.grantKey)
        }
    }

    @Test
    fun systemSettingsChangesAlwaysAskButScrollingDoesNot() {
        val settings = ctx("com.android.settings", "Settings", TargetInfo("Wi-Fi"), titles = listOf("Network & internet"))
        val tap = assess(call(PhoneTools.SCREEN_TAP, "target" to "Wi-Fi"), settings)
        assertTrue(tap.alwaysConfirm)
        assertTrue(tap.reason.contains("system settings"))
        assertFalse(assess(call(PhoneTools.SCREEN_SCROLL, "direction" to "down"), settings).alwaysConfirm)
        val permission = assess(call(PhoneTools.SCREEN_TAP, "target" to "Allow"),
            ctx("com.google.android.permissioncontroller", "Permission controller", TargetInfo("Allow")))
        assertTrue(permission.alwaysConfirm)
    }

    @Test
    fun securitySettingsPagesAreBlocked() {
        val page = ctx("com.android.settings", "Settings", TargetInfo("Screen lock"), titles = listOf("Security & privacy", "Screen lock"))
        assertTrue(assess(call(PhoneTools.SCREEN_READ), page).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_TAP, "target" to "e3"), page).isBlocked)
        val released = page.copy(blocklist = PackageBlocklist.DEFAULT.withoutEntry(PackageBlocklist.SECURITY_SETTINGS))
        assertFalse(assess(call(PhoneTools.SCREEN_READ), released).isBlocked)
    }

    @Test
    fun typingIntoAPasswordFieldAsksAndIsRedacted() {
        val c = call(PhoneTools.SCREEN_TYPE, "text" to "hunter2", "target" to "e4")
        val a = assess(c, ctx("com.example.shop", "Shop", TargetInfo("Password", password = true, editable = true)))
        assertTrue(a.alwaysConfirm)
        assertTrue(a.redact)
        assertFalse(AgentTools.describe(c, redact = true).contains("hunter2"))
        assertTrue(AgentTools.describe(c, redact = true).contains("7 characters hidden"))
        assertEquals("••••••", PhoneTools.redactedArguments(c)["text"])
    }

    @Test
    fun cameraActionsAlwaysAsk() {
        val a = assess(call(PhoneTools.SCREEN_TAP, "target" to "Shutter"), ctx("com.google.android.GoogleCamera", "Camera", TargetInfo("Shutter")))
        assertTrue(a.alwaysConfirm)
        assertNull(a.grantKey)
    }

    @Test
    fun notificationShadeNeedsApprovalOnce() {
        val a = assess(call(PhoneTools.SCREEN_GLOBAL, "action" to "notifications"), ctx("com.example.notes"))
        assertTrue(a.alwaysConfirm)
        assertEquals("notifications", a.grantKey)
        assertEquals(RiskLevel.LOW, assess(call(PhoneTools.SCREEN_GLOBAL, "action" to "back"), ctx("com.example.notes")).level)
        assertTrue(assess(call(PhoneTools.SCREEN_GLOBAL, "action" to "reboot"), ctx("com.example.notes")).isBlocked)
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertTrue(assess(call(PhoneTools.SCREEN_TAP), ctx("p.q")).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_SCROLL, "direction" to "sideways"), ctx("p.q")).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_SWIPE, "x1" to "1"), ctx("p.q")).isBlocked)
        assertTrue(assess(call(PhoneTools.OPEN_APP, "name" to " "), ctx("p.q")).isBlocked)
        assertTrue(assess(call(PhoneTools.WAIT_FOR), ctx("p.q")).isBlocked)
    }

    @Test
    fun remoteRunsAskForEveryScreenToolButNotForAppList() {
        PhoneTools.SCREEN_TOOLS.forEach { assertTrue(it, PhoneToolPolicy.requiresApprovalInMode(it, RunMode.REMOTE)) }
        assertFalse(PhoneToolPolicy.requiresApprovalInMode(PhoneTools.APPS_LIST, RunMode.REMOTE))
        assertFalse(PhoneToolPolicy.requiresApprovalInMode(PhoneTools.SCREEN_TAP, RunMode.INTERACTIVE))
    }

    @Test
    fun appKindsAreRecognised() {
        assertEquals(AppKind.MESSAGING, PhoneToolPolicy.appKind("com.whatsapp"))
        assertEquals(AppKind.MESSAGING, PhoneToolPolicy.appKind("org.example.chat", "Family Chat"))
        assertEquals(AppKind.CONTACTS_CALLS, PhoneToolPolicy.appKind("com.google.android.dialer"))
        assertEquals(AppKind.SYSTEM_SETTINGS, PhoneToolPolicy.appKind("com.android.settings"))
        assertEquals(AppKind.INSTALLER, PhoneToolPolicy.appKind("com.android.vending"))
        assertEquals(AppKind.OTHER, PhoneToolPolicy.appKind("com.spotify.music", "Spotify"))
        assertEquals(AppKind.SELF, PhoneToolPolicy.appKind(PhoneRiskContext.OWN_PACKAGE))
    }

    @Test
    fun appStoreTapsAlwaysAsk() {
        val a = assess(call(PhoneTools.SCREEN_TAP, "target" to "e5"), ctx("com.android.vending", "Play Store", TargetInfo("Open")))
        assertTrue(a.alwaysConfirm)
    }

    @Test
    fun screenshotsFollowTheSameRulesAsReading() {
        val maps = assess(call(PhoneTools.SCREEN_CAPTURE), ctx("com.google.android.apps.maps", "Maps"))
        assertEquals(RiskLevel.MEDIUM, maps.level)
        assertTrue(maps.reason.contains("AI provider"))
        assertTrue(assess(call(PhoneTools.SCREEN_CAPTURE), ctx("com.x8bit.bitwarden", "Bitwarden")).isBlocked)
        assertTrue(assess(call(PhoneTools.SCREEN_CAPTURE), ctx("com.example.notes", locked = true)).isBlocked)
        assertEquals("read:com.whatsapp", assess(call(PhoneTools.SCREEN_CAPTURE), ctx("com.whatsapp", "WhatsApp")).grantKey)
        assertTrue(PhoneToolPolicy.requiresApprovalInMode(PhoneTools.SCREEN_CAPTURE, RunMode.REMOTE))
    }

    private fun notif(pkg: String?, label: String? = pkg) = PhoneRiskContext(targetPackage = pkg, targetLabel = label)

    @Test
    fun readingNotificationsAsksOncePerRun() {
        val a = assess(call(PhoneTools.NOTIFICATIONS_LIST), PhoneRiskContext())
        assertTrue(a.alwaysConfirm)
        assertEquals("notifications", a.grantKey)
    }

    @Test
    fun replyingToANotificationIsSendingAndAlwaysAsks() {
        val a = assess(call(PhoneTools.NOTIFICATION_REPLY, "id" to "n1", "text" to "On my way"), notif("com.whatsapp", "WhatsApp"))
        assertTrue(a.alwaysConfirm)
        assertNull(a.grantKey)
        assertTrue(a.reason.contains("Sends a message"))
        assertTrue(asksInEveryPreset(a))
        assertTrue(assess(call(PhoneTools.NOTIFICATION_REPLY, "id" to "n1"), notif("com.whatsapp")).isBlocked)
    }

    @Test
    fun blockedAppsNotificationsCannotBeTouched() {
        val bank = notif("com.starfinanz.smob.android.sfinanzstatus", "Sparkasse")
        assertTrue(assess(call(PhoneTools.NOTIFICATION_OPEN, "id" to "n2"), bank).isBlocked)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_REPLY, "id" to "n2", "text" to "x"), bank).isBlocked)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_DISMISS, "id" to "n2"), bank).isBlocked)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_TRIGGER, "action" to "add", "app" to "Sparkasse", "prompt" to "x"), bank).isBlocked)
    }

    @Test
    fun openingAMessengerNotificationNeedsTheReadGrant() {
        assertEquals("read:com.whatsapp", assess(call(PhoneTools.NOTIFICATION_OPEN, "id" to "n1"), notif("com.whatsapp", "WhatsApp")).grantKey)
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.NOTIFICATION_OPEN, "id" to "n1"), notif("com.spotify.music", "Spotify")).level)
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.NOTIFICATION_DISMISS, "id" to "n1"), notif("com.spotify.music", "Spotify")).level)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_OPEN, "id" to "n1"), notif("com.spotify.music").copy(keyguardLocked = true)).isBlocked)
    }

    @Test
    fun notificationTriggersAlwaysAskWhenAdded() {
        val add = assess(call(PhoneTools.NOTIFICATION_TRIGGER, "action" to "add", "app" to "Gmail", "prompt" to "Summarise it"), notif("com.google.android.gm", "Gmail"))
        assertTrue(add.alwaysConfirm)
        assertTrue(add.reason.contains("untrusted"))
        assertEquals(RiskLevel.LOW, assess(call(PhoneTools.NOTIFICATION_TRIGGER, "action" to "list"), PhoneRiskContext()).level)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_TRIGGER, "action" to "remove"), PhoneRiskContext()).isBlocked)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_TRIGGER, "action" to "fire"), PhoneRiskContext()).isBlocked)
        assertTrue(assess(call(PhoneTools.NOTIFICATION_TRIGGER, "action" to "add", "app" to "Gmail"), PhoneRiskContext()).isBlocked)
    }

    @Test
    fun systemIntentsAreClassified() {
        val none = PhoneRiskContext()
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.SET_ALARM, "hour" to "7"), none).level)
        assertTrue(assess(call(PhoneTools.SET_ALARM, "hour" to "25"), none).isBlocked)
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.CALENDAR_ADD, "title" to "x", "start" to "2026-11-10 09:00"), none).level)
        assertTrue(assess(call(PhoneTools.CALENDAR_ADD, "title" to "x", "start" to "soon"), none).isBlocked)
        assertTrue(assess(call(PhoneTools.OPEN_URL, "url" to "javascript:alert(1)"), none).isBlocked)
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.OPEN_URL, "url" to "https://example.com"), none).level)
        assertEquals(RiskLevel.LOW, assess(call(PhoneTools.OPEN_SETTINGS, "page" to "wifi"), none).level)
        assertTrue(assess(call(PhoneTools.OPEN_SETTINGS, "page" to "security"), none).isBlocked)
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.SHARE_TEXT, "text" to "hi"), none).level)
        assertEquals(RiskLevel.MEDIUM, assess(call(PhoneTools.USAGE_STATS), none).level)
    }

    @Test
    fun clipboardIsSensitiveLikeTheTermuxCommand() {
        val get = assess(call(PhoneTools.CLIPBOARD, "action" to "get"), PhoneRiskContext())
        assertEquals(RiskLevel.HIGH, get.level)
        assertTrue(CommandSecurityFilter.shouldRequireApproval(get, SecurityPreset.AUTONOMOUS))
        assertEquals(CommandSecurityFilter.analyze("termux-clipboard-get").level, get.level)
        assertTrue(assess(call(PhoneTools.CLIPBOARD, "action" to "wipe"), PhoneRiskContext()).isBlocked)
    }
}
