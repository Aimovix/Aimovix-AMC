package com.agent.mobile.phone

import com.agent.mobile.agent.AgentTools
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class PrivilegedCommandPolicyTest {

    private val notes = PhoneRiskContext(foregroundPackage = "com.example.notes", foregroundLabel = "Notes")

    private fun assess(cmd: String, ctx: PhoneRiskContext = notes) =
        AgentTools.assess(ToolCall(name = PhoneTools.PRIVILEGED_SHELL, arguments = mapOf("command" to cmd)), ctx)

    @Test
    fun onlyTheListedBinariesAsPlainArguments() {
        assertTrue(assess("rm -rf /sdcard").isBlocked)
        assertTrue(assess("sh -c id").isBlocked)
        assertTrue(assess("settings get global x; reboot").isBlocked)
        assertTrue(assess("dumpsys battery | grep level").isBlocked)
        assertTrue(assess("am start \$(cat x)").isBlocked)
        assertTrue(assess("input text 'hi'").isBlocked)
        assertTrue(assess("").isBlocked)
        assertEquals(listOf("settings", "get", "global", "x"), PrivilegedCommandPolicy.tokenize("settings  get global x"))
    }

    @Test
    fun readingIsHighRiskAndAsksInAutonomousButRunsInTurbo() {
        val a = assess("settings get global airplane_mode_on")
        assertEquals(RiskLevel.HIGH, a.level)
        assertFalse(a.alwaysConfirm)
        assertTrue(CommandSecurityFilter.shouldRequireApproval(a, SecurityPreset.AUTONOMOUS))
        assertFalse(CommandSecurityFilter.shouldRequireApproval(a, SecurityPreset.TURBO))
        assertEquals(RiskLevel.HIGH, assess("dumpsys battery").level)
        assertEquals(RiskLevel.HIGH, assess("pm list packages").level)
    }

    @Test
    fun changesAlwaysAsk() {
        listOf(
            "settings put global airplane_mode_on 1",
            "pm uninstall com.example.game",
            "pm install /sdcard/app.apk",
            "pm clear com.example.notes",
            "pm grant com.agent.mobile android.permission.READ_SMS",
            "pm disable-user com.example.game",
            "am start -a android.intent.action.CALL -d tel:110",
            "am start -a android.intent.action.SENDTO -d smsto:123",
            "dumpsys notification",
            "pm frobnicate"
        ).forEach { cmd -> assertTrue(cmd, assess(cmd).alwaysConfirm) }
    }

    @Test
    fun blockedAppsAreOffLimits() {
        assertTrue(assess("am start -n com.x8bit.bitwarden/.MainActivity").isBlocked)
        assertTrue(assess("pm clear com.starfinanz.smob.android.sfinanzstatus").isBlocked)
        assertTrue(assess("am force-stop de.number26.android").isBlocked)
    }

    @Test
    fun rawInputDependsOnTheAppInFront() {
        assertEquals(RiskLevel.HIGH, assess("input keyevent KEYCODE_HOME").level)
        assertFalse(assess("input keyevent KEYCODE_HOME").alwaysConfirm)
        assertTrue(assess("input tap 500 900", PhoneRiskContext(foregroundPackage = "com.whatsapp", foregroundLabel = "WhatsApp")).alwaysConfirm)
        assertTrue(assess("input text hello", PhoneRiskContext()).alwaysConfirm)
        assertTrue(assess("input tap 1 1", PhoneRiskContext(foregroundPackage = "com.x8bit.bitwarden")).isBlocked)
        assertTrue(assess("input keyevent 82", notes.copy(keyguardLocked = true)).isBlocked)
    }

    @Test
    fun screencapNeedsAFileAndRespectsPrivacy() {
        assertTrue(assess("screencap -p").isBlocked)
        assertEquals(RiskLevel.HIGH, assess("screencap -p /sdcard/Download/s.png").level)
        assertTrue(assess("screencap -p /sdcard/s.png", PhoneRiskContext(foregroundPackage = "com.whatsapp", foregroundLabel = "WhatsApp")).alwaysConfirm)
        assertTrue(assess("screencap -p /sdcard/s.png", PhoneRiskContext(foregroundPackage = PhoneRiskContext.OWN_PACKAGE)).isBlocked)
        assertTrue(assess("screencap -p /sdcard/s.png", notes.copy(keyguardLocked = true)).isBlocked)
    }
}
