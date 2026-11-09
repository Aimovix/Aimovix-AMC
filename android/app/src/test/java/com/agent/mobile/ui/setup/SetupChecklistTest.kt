package com.agent.mobile.ui.setup

import org.junit.Assert.*
import org.junit.Test

class SetupChecklistTest {

    private val nothing = SetupFacts(
        termuxInstalled = false,
        termuxApiInstalled = false,
        runCommandGranted = false,
        batteryUnrestricted = false,
        bridgeConnected = false
    )

    @Test
    fun stepsFollowTheJourneyInOrder() {
        assertEquals(
            listOf(SetupStep.TERMUX, SetupStep.TERMUX_API, SetupStep.RUN_COMMAND, SetupStep.BACKGROUND, SetupStep.PAIRED),
            setupItems(nothing).map { it.step }
        )
    }

    @Test
    fun firstOpenStepIsTheNextOne() {
        assertEquals(SetupStep.TERMUX, nextSetupStep(setupItems(nothing)))
    }

    @Test
    fun pairingIsNextWhenEverythingElseIsDone() {
        val facts = nothing.copy(termuxInstalled = true, termuxApiInstalled = true, runCommandGranted = true, batteryUnrestricted = true)
        assertEquals(SetupStep.PAIRED, nextSetupStep(setupItems(facts)))
    }

    @Test
    fun nothingIsNextWhenAllStepsAreDone() {
        val facts = SetupFacts(true, true, true, true, true)
        assertNull(nextSetupStep(setupItems(facts)))
        assertTrue(setupItems(facts).all { it.done })
    }

    @Test
    fun doneCountFollowsTheFacts() {
        val facts = nothing.copy(termuxInstalled = true, runCommandGranted = true)
        assertEquals(2, setupItems(facts).count { it.done })
    }

    @Test
    fun everyStepHasTextAndAnAction() {
        SetupStep.values().forEach { step ->
            assertTrue(setupStepTitle(step).isNotBlank())
            assertTrue(setupStepHint(step).isNotBlank())
            assertTrue(setupStepAction(step).isNotBlank())
        }
    }

    @Test
    fun onlyTheTwoAppsLinkToFDroid() {
        assertEquals("https://f-droid.org/packages/com.termux/", setupStepUrl(SetupStep.TERMUX))
        assertEquals("https://f-droid.org/packages/com.termux.api/", setupStepUrl(SetupStep.TERMUX_API))
        assertNull(setupStepUrl(SetupStep.PAIRED))
    }
}
