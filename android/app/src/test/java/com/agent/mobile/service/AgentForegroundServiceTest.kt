package com.agent.mobile.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The service's WakeLock is held only while a run is active (#20). */
@RunWith(RobolectricTestRunner::class)
class AgentForegroundServiceTest {

    @Test
    fun theWakeLockFollowsActiveRuns() {
        val controller = Robolectric.buildService(
            AgentForegroundService::class.java,
            Intent(ApplicationProvider.getApplicationContext(), AgentForegroundService::class.java)
        ).create().startCommand(0, 1)
        try {
            assertFalse("Idle app: no WakeLock", AgentForegroundService.isWakeLockHeldForTest())
            AgentForegroundService.setBusy(true)
            assertTrue(AgentForegroundService.isWakeLockHeldForTest())
            AgentForegroundService.setBusy(true)   // a second run (e.g. Telegram)
            AgentForegroundService.setBusy(false)
            assertTrue("One run still active", AgentForegroundService.isWakeLockHeldForTest())
            AgentForegroundService.setBusy(false)
            assertFalse(AgentForegroundService.isWakeLockHeldForTest())
            AgentForegroundService.setBusy(false)  // extra end: never below zero
            AgentForegroundService.setBusy(true)
            assertTrue(AgentForegroundService.isWakeLockHeldForTest())
            AgentForegroundService.setBusy(false)
        } finally {
            controller.destroy()
        }
    }
}
