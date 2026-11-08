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
}
