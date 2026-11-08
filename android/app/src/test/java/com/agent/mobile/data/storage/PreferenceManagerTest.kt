package com.agent.mobile.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.ui.setup.ApkDownloadHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferenceManagerTest {

    private lateinit var preferenceManager: PreferenceManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testPrefs = context.getSharedPreferences("test_amc_prefs", Context.MODE_PRIVATE)
        testPrefs.edit().clear().commit()
        preferenceManager = PreferenceManager(context, testPrefs)
    }

    @Test
    fun testTutorialCompletionFlagCycle() {
        // Initially, user has not completed tutorial
        preferenceManager.setTutorialCompleted(false)
        assertFalse(preferenceManager.hasCompletedTutorial())

        // Mark as completed
        preferenceManager.setTutorialCompleted(true)
        assertTrue(preferenceManager.hasCompletedTutorial())

        // Can be reset if needed
        preferenceManager.setTutorialCompleted(false)
        assertFalse(preferenceManager.hasCompletedTutorial())
    }

    @Test
    fun testApkDownloadHelperUrls() {
        assertTrue(ApkDownloadHelper.TERMUX_APK_URL.startsWith("https://"))
        assertTrue(ApkDownloadHelper.TERMUX_APK_URL.endsWith(".apk"))
        assertTrue(ApkDownloadHelper.TERMUX_API_APK_URL.startsWith("https://"))
        assertTrue(ApkDownloadHelper.TERMUX_API_APK_URL.endsWith(".apk"))
    }

    @Test
    fun testLegalConsentAndAutonomyDefaults() {
        assertEquals(0, preferenceManager.loadAcceptedLegalVersion())
        assertEquals(0L, preferenceManager.loadAcceptedLegalAt())
        preferenceManager.saveAcceptedLegalVersion(com.agent.mobile.ui.legal.LegalTexts.VERSION)
        assertEquals(com.agent.mobile.ui.legal.LegalTexts.VERSION, preferenceManager.loadAcceptedLegalVersion())
        assertTrue(preferenceManager.loadAcceptedLegalAt() > 0L)

        assertEquals(com.agent.mobile.data.model.SecurityPreset.AUTONOMOUS, preferenceManager.loadSecurityPreset())
        assertEquals(PreferenceManager.DEFAULT_MAX_STEPS, preferenceManager.loadMaxSteps())
        preferenceManager.saveMaxSteps(1000)
        assertEquals(PreferenceManager.MAX_MAX_STEPS, preferenceManager.loadMaxSteps())
        assertFalse(preferenceManager.loadHeartbeatEnabled())
        preferenceManager.saveHeartbeatIntervalMinutes(5)
        assertEquals(15L, preferenceManager.loadHeartbeatIntervalMinutes())
    }

    @Test
    fun testLegalTextsAreCompleteInBothLanguages() {
        val en = com.agent.mobile.ui.legal.LegalTexts.sections(com.agent.mobile.ui.legal.LegalTexts.Language.EN)
        val de = com.agent.mobile.ui.legal.LegalTexts.sections(com.agent.mobile.ui.legal.LegalTexts.Language.DE)
        assertEquals(en.size, de.size)
        assertTrue(en.all { it.title.isNotBlank() && it.body.length > 50 })
        assertTrue(de.all { it.title.isNotBlank() && it.body.length > 50 })
        assertEquals(3, com.agent.mobile.ui.legal.LegalTexts.consentLabels(com.agent.mobile.ui.legal.LegalTexts.Language.DE).size)
    }
}

