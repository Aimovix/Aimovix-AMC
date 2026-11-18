package com.agent.mobile.service

import android.os.Looper
import com.agent.mobile.agent.RunMode
import com.agent.mobile.agent.RunRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** #20: the wake lock is held only while an agent run works. */
@RunWith(RobolectricTestRunner::class)
class ForegroundServiceWakeLockTest {

    @Test
    fun wakeLockFollowsActiveRuns() {
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Idle: no wake lock", service.holdsWakeLock)

        RunRegistry.register(RunRegistry.ActiveRun("w1", "Backup", RunMode.UNATTENDED, "SCHEDULE", null, unattended = true)) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("A run is working: wake lock held", service.holdsWakeLock)

        RunRegistry.unregister("w1")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Run ended: wake lock released", service.holdsWakeLock)
        controller.destroy()
    }
}
