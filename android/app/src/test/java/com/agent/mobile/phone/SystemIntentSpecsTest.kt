package com.agent.mobile.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class SystemIntentSpecsTest {

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    @Test
    fun alarmsAndTimersAreValidated() {
        assertEquals(SystemIntentSpecs.Alarm.At(7, 30, "Gym"), SystemIntentSpecs.parseAlarm(mapOf("hour" to "7", "minute" to "30", "label" to "Gym")))
        assertEquals(SystemIntentSpecs.Alarm.At(6, 0, ""), SystemIntentSpecs.parseAlarm(mapOf("hour" to "6")))
        assertEquals(SystemIntentSpecs.Alarm.Timer(300, "Tea"), SystemIntentSpecs.parseAlarm(mapOf("timer_seconds" to "300", "label" to "Tea")))
        assertTrue(SystemIntentSpecs.parseAlarm(mapOf("hour" to "24")) is SystemIntentSpecs.Alarm.Invalid)
        assertTrue(SystemIntentSpecs.parseAlarm(mapOf("hour" to "7", "minute" to "61")) is SystemIntentSpecs.Alarm.Invalid)
        assertTrue(SystemIntentSpecs.parseAlarm(mapOf("timer_seconds" to "0")) is SystemIntentSpecs.Alarm.Invalid)
        assertTrue(SystemIntentSpecs.parseAlarm(emptyMap()) is SystemIntentSpecs.Alarm.Invalid)
    }

    @Test
    fun eventsAreParsedInLocalTimeWithADefaultLength() {
        val (event, error) = SystemIntentSpecs.parseEvent(mapOf("title" to "Dentist", "start" to "2026-11-10 09:00", "location" to "Main St"), berlin)
        assertNull(error)
        assertEquals(1_794_297_600_000L, event!!.beginMs) // 2026-11-10 08:00 UTC
        assertEquals(event.beginMs + 3_600_000L, event.endMs)
        assertFalse(event.allDay)
        assertEquals("Main St", event.location)
    }

    @Test
    fun dateOnlyMeansAllDay() {
        val (event, _) = SystemIntentSpecs.parseEvent(mapOf("title" to "Holiday", "start" to "2026-12-24"), berlin)
        assertTrue(event!!.allDay)
        assertEquals(86_400_000L, event.endMs - event.beginMs)
    }

    @Test
    fun badEventsAreRejected() {
        assertNotNull(SystemIntentSpecs.parseEvent(mapOf("start" to "2026-11-10 09:00"), berlin).second)
        assertNotNull(SystemIntentSpecs.parseEvent(mapOf("title" to "x", "start" to "tomorrow"), berlin).second)
        assertNotNull(SystemIntentSpecs.parseEvent(mapOf("title" to "x", "start" to "2026-13-40 09:00"), berlin).second)
        assertNotNull(SystemIntentSpecs.parseEvent(mapOf("title" to "x", "start" to "2026-11-10 09:00", "end" to "2026-11-10 08:00"), berlin).second)
        assertNull(SystemIntentSpecs.parseEvent(mapOf("title" to "x", "start" to "2026-11-10T09:00"), berlin).second)
    }

    @Test
    fun onlyWebLinksCanBeOpened() {
        assertTrue(SystemIntentSpecs.isOpenableUrl("https://example.com/path?q=1"))
        assertTrue(SystemIntentSpecs.isOpenableUrl("http://localhost:8080"))
        assertFalse(SystemIntentSpecs.isOpenableUrl("javascript:alert(1)"))
        assertFalse(SystemIntentSpecs.isOpenableUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(SystemIntentSpecs.isOpenableUrl("file:///sdcard/secret.txt"))
        assertFalse(SystemIntentSpecs.isOpenableUrl("content://contacts/people"))
        assertEquals("example.com", SystemIntentSpecs.host("https://example.com/a?b"))
    }

    @Test
    fun securityRelevantSettingsPagesAreNotOffered() {
        val pages = SystemIntentSpecs.SETTINGS_PAGES
        listOf("security", "accessibility", "device_admin", "developer", "unknown_sources", "lock_screen", "accounts").forEach {
            assertFalse(it, pages.containsKey(it))
        }
        assertFalse(pages.values.any { it.contains("SECURITY") || it.contains("ACCESSIBILITY") || it.contains("DEVELOPMENT") || it.contains("UNKNOWN_APP") })
        assertEquals("android.settings.WIFI_SETTINGS", pages["wifi"])
    }

    @Test
    fun usageIsSortedAndSummarised() {
        val text = SystemIntentSpecs.formatUsage(
            listOf(
                SystemIntentSpecs.AppUsage("com.a", "A", 30 * 60_000L, 0),
                SystemIntentSpecs.AppUsage("com.b", "B", 2 * 3_600_000L + 5 * 60_000L, 0),
                SystemIntentSpecs.AppUsage("com.c", "C", 10_000L, 0)
            ), 24
        )
        val lines = text.lines()
        assertEquals("Screen time in the last 24 h: 2 h 35 min", lines[0])
        assertEquals("- B (com.b): 2 h 5 min", lines[1])
        assertEquals("- A (com.a): 30 min", lines[2])
        assertEquals(3, lines.size)
        assertEquals("No app was used for a minute or more in the last 3 h.", SystemIntentSpecs.formatUsage(emptyList(), 3))
    }
}
