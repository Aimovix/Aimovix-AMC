package com.agent.mobile.ui.settings.components

import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.security.CommandSecurityFilter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PolicySimulatorTest {

    @Before
    fun resetCustomRules() {
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
    }

    private fun verdicts(command: String): Map<SecurityPreset, PolicyVerdict> =
        PolicySimulator.evaluate(command)!!.verdicts.associate { it.preset to it.verdict }

    @Test
    fun emptyCommandHasNoVerdict() {
        assertNull(PolicySimulator.evaluate("   "))
    }

    @Test
    fun destructiveCommandIsBlockedInEveryMode() {
        verdicts("rm -rf /").values.forEach { assertEquals(PolicyVerdict.BLOCKED, it) }
    }

    @Test
    fun smsAlwaysAsksEvenInTurbo() {
        verdicts("termux-sms-send -n 0123456 \"Hi\"").values.forEach { assertEquals(PolicyVerdict.ASKS, it) }
    }

    @Test
    fun deletingAFileRunsOnlyInTurbo() {
        val v = verdicts("rm ~/old-notes.txt")
        assertEquals(PolicyVerdict.ASKS, v[SecurityPreset.AUTONOMOUS])
        assertEquals(PolicyVerdict.ASKS, v[SecurityPreset.DEFAULT])
        assertEquals(PolicyVerdict.RUNS, v[SecurityPreset.TURBO])
    }

    @Test
    fun packageInstallRunsInAutonomousButAsksInSupervised() {
        val v = verdicts("pkg install -y jq")
        assertEquals(PolicyVerdict.RUNS, v[SecurityPreset.AUTONOMOUS])
        assertEquals(PolicyVerdict.ASKS, v[SecurityPreset.DEFAULT])
    }

    @Test
    fun unrecognizedCommandsAskInSupervisedMode() {
        assertEquals(PolicyVerdict.ASKS, verdicts("cat ~/notes.txt")[SecurityPreset.DEFAULT])
    }

    @Test
    fun reasonComesFromTheFilter() {
        assertTrue(PolicySimulator.evaluate("rm ~/old.txt")!!.reason.contains("deletion", ignoreCase = true))
    }

    // --- Phone control tools ------------------------------------------------------------------

    @Test
    fun sendingInWhatsAppAsksInEveryModeIncludingTurbo() {
        val v = verdicts("screen_tap Send @com.whatsapp")
        v.values.forEach { assertEquals(PolicyVerdict.ASKS, it) }
        assertTrue(PolicySimulator.evaluate("screen_tap Send @com.whatsapp")!!.reason.contains("Sends a message"))
    }

    @Test
    fun openingABankingAppIsBlockedEverywhere() {
        verdicts("open_app Sparkasse").values.forEach { assertEquals(PolicyVerdict.BLOCKED, it) }
        verdicts("open_app com.x8bit.bitwarden").values.forEach { assertEquals(PolicyVerdict.BLOCKED, it) }
    }

    @Test
    fun readingAnOrdinaryScreenRunsInAutonomousAndAsksInSupervised() {
        val v = verdicts("screen_read @com.android.chrome")
        assertEquals(PolicyVerdict.RUNS, v[SecurityPreset.AUTONOMOUS])
        assertEquals(PolicyVerdict.ASKS, v[SecurityPreset.DEFAULT])
        assertEquals(PolicyVerdict.RUNS, v[SecurityPreset.TURBO])
    }

    @Test
    fun screenToolsAreOffInTelegramByDefaultAndAskWhenEnabled() {
        assertEquals(PolicyVerdict.OFF, PolicySimulator.evaluate("screen_read @com.android.chrome")!!.remote)
        assertEquals(PolicyVerdict.ASKS, PolicySimulator.evaluate("screen_read @com.android.chrome", remoteScreenControl = true)!!.remote)
        assertNull("shell commands have no separate remote row", PolicySimulator.evaluate("ls")!!.remote)
    }

    @Test
    fun messengerReadingExplainsTheOnePerTaskApproval() {
        val r = PolicySimulator.evaluate("open_app com.whatsapp")!!
        r.verdicts.forEach { assertEquals(PolicyVerdict.ASKS, it.verdict) }
        assertNotNull(r.grantNote)
    }

    @Test
    fun passwordTypingAndSettingsChangesAlwaysAsk() {
        verdicts("screen_type hunter2 password=true @com.example.shop").values.forEach { assertEquals(PolicyVerdict.ASKS, it) }
        verdicts("screen_tap Wi-Fi @com.android.settings").values.forEach { assertEquals(PolicyVerdict.ASKS, it) }
    }

    @Test
    fun parsesToolSyntax() {
        val (call, ctx) = PolicySimulator.parseTool("screen_tap \"Add to list\" @com.example.shop")
        assertEquals("screen_tap", call.name)
        assertEquals("Add to list", call.arguments["target"])
        assertEquals("com.example.shop", ctx.foregroundPackage)
        assertEquals("Add to list", ctx.target!!.label)
    }

    @Test
    fun everyPhoneToolCanBeSimulated() {
        com.agent.mobile.agent.PhoneTools.DEFINITIONS.forEach { def ->
            assertNotNull(def.name, PolicySimulator.evaluate(def.name + " x @com.example.app"))
        }
    }

    @Test
    fun executeCommandPrefixIsCheckedAsShell() {
        verdicts("execute_command rm -rf /").values.forEach { assertEquals(PolicyVerdict.BLOCKED, it) }
    }
}
