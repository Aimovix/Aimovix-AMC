package com.agent.mobile.ui.setup

import com.agent.mobile.phone.ShizukuShell
import com.agent.mobile.ui.settings.components.blocklistEntryLabel
import org.junit.Assert.*
import org.junit.Test

class PhoneControlChecklistTest {

    private val termuxDone = SetupFacts(true, true, true, true, true)

    @Test
    fun termuxChecklistIsUnchangedByPhoneControl() {
        assertEquals(5, setupItems(termuxDone).size)
        assertNull(nextSetupStep(setupItems(termuxDone)))
    }

    @Test
    fun phoneStepsInOrderWithRestrictedSettingsOnlyWhenItApplies() {
        assertEquals(
            listOf(SetupStep.ACCESSIBILITY, SetupStep.NOTIFICATIONS, SetupStep.USAGE_STATS, SetupStep.SHIZUKU),
            phoneControlItems(termuxDone).map { it.step }
        )
        assertEquals(
            listOf(SetupStep.RESTRICTED_SETTINGS, SetupStep.ACCESSIBILITY, SetupStep.NOTIFICATIONS, SetupStep.USAGE_STATS, SetupStep.SHIZUKU),
            phoneControlItems(termuxDone.copy(restrictedSettingsApply = true)).map { it.step }
        )
    }

    @Test
    fun restrictedSettingsComeFirstOnAndroid13Sideloads() {
        val facts = termuxDone.copy(restrictedSettingsApply = true)
        assertEquals(SetupStep.RESTRICTED_SETTINGS, nextPhoneControlStep(phoneControlItems(facts)))
        // Done as soon as one of the permissions behind it works (the op itself is not always readable).
        assertEquals(SetupStep.NOTIFICATIONS, nextPhoneControlStep(phoneControlItems(facts.copy(accessibilityEnabled = true))))
        assertEquals(SetupStep.ACCESSIBILITY, nextPhoneControlStep(phoneControlItems(facts.copy(restrictedSettingsAllowed = true))))
    }

    @Test
    fun usageAndShizukuAreOptional() {
        val items = phoneControlItems(termuxDone.copy(accessibilityEnabled = true, notificationAccess = true))
        assertTrue(phoneControlReady(items))
        assertEquals("optional steps are offered afterwards", SetupStep.USAGE_STATS, nextPhoneControlStep(items))
        assertTrue(items.filter { it.step == SetupStep.USAGE_STATS || it.step == SetupStep.SHIZUKU }.all { it.optional })
        val all = phoneControlItems(
            termuxDone.copy(accessibilityEnabled = true, notificationAccess = true, usageAccess = true, shizuku = ShizukuShell.Status.READY)
        )
        assertNull(nextPhoneControlStep(all))
    }

    @Test
    fun requiredStepsComeBeforeOptionalOnes() {
        val items = phoneControlItems(termuxDone.copy(usageAccess = false, accessibilityEnabled = false))
        assertEquals(SetupStep.ACCESSIBILITY, nextPhoneControlStep(items))
        assertFalse(phoneControlReady(items))
    }

    @Test
    fun shizukuOffersExactlyOneActionPerState() {
        val labels = ShizukuShell.Status.values().map { shizukuAction(it) }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("Get Shizuku", shizukuAction(ShizukuShell.Status.NOT_INSTALLED))
        assertEquals("Allow AMC in Shizuku", shizukuAction(ShizukuShell.Status.NO_PERMISSION))
        ShizukuShell.Status.values().forEach { assertTrue(shizukuHint(it).isNotBlank()) }
    }

    @Test
    fun restrictedSettingsHintExplainsTheMenuPath() {
        val hint = setupStepHint(SetupStep.RESTRICTED_SETTINGS)
        assertTrue(hint.contains("GitHub"))
        assertTrue(hint.contains("Allow restricted settings"))
        assertTrue(hint.contains("⋮"))
        assertEquals("Open App info", setupStepAction(SetupStep.RESTRICTED_SETTINGS))
    }

    @Test
    fun disclosureSaysWhatTheServiceReadsAndDoes() {
        val text = PHONE_CONTROL_DISCLOSURE.joinToString(" ") { it.first + " " + it.second }
        listOf("password", "Stop", "lock screen", "AI provider", "Banking", "Always asks", "audit log", "Accessibility").forEach {
            assertTrue(it, text.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun blocklistLabelsAreReadable() {
        assertEquals("Security settings pages", blocklistEntryLabel("settings:security"))
        assertEquals("com.android.vending (only billing)", blocklistEntryLabel("com.android.vending/com.google.android.finsky.billing"))
        assertEquals("com.x8bit.bitwarden", blocklistEntryLabel("com.x8bit.bitwarden"))
    }
}
