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
}
