package com.agent.mobile.service.scheduler

import androidx.work.NetworkType
import com.agent.mobile.agent.TaskConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskConditionsTest {

    @Test
    fun conditionsAreParsedAndUnknownWordsReported() {
        val (ok, unknown) = TaskConditions.parse("WiFi, charging;battery_ok")
        assertEquals(setOf("wifi", "charging", "battery_ok"), ok)
        assertTrue(unknown.isEmpty())
        assertEquals(listOf("sunshine"), TaskConditions.parse("wifi sunshine").second)
        assertTrue(TaskConditions.parse(null).first.isEmpty())
    }

    @Test
    fun conditionsBecomeWorkManagerConstraints() {
        val scheduler = SchedulerManager
        val backup = scheduler.constraintsFor(setOf(TaskConditions.WIFI, TaskConditions.CHARGING))
        assertEquals(NetworkType.UNMETERED, backup.requiredNetworkType)
        assertTrue(backup.requiresCharging())
        assertFalse(backup.requiresBatteryNotLow())

        val plain = scheduler.constraintsFor(emptySet())
        assertEquals(NetworkType.CONNECTED, plain.requiredNetworkType)
        assertFalse(plain.requiresCharging())
        assertTrue(scheduler.constraintsFor(setOf(TaskConditions.BATTERY_OK, TaskConditions.STORAGE_OK)).let {
            it.requiresBatteryNotLow() && it.requiresStorageNotLow()
        })
    }
}
