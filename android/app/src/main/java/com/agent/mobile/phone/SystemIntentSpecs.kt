package com.agent.mobile.phone

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Validation and formatting for the system intent tools; pure Kotlin so it can be tested. */
object SystemIntentSpecs {

    sealed class Alarm {
        data class At(val hour: Int, val minute: Int, val label: String) : Alarm()
        data class Timer(val seconds: Int, val label: String) : Alarm()
        data class Invalid(val reason: String) : Alarm()
    }

    fun parseAlarm(args: Map<String, String>): Alarm {
        val label = args["label"].orEmpty().trim().take(60)
        val timer = args["timer_seconds"]?.trim()?.toDoubleOrNull()?.toInt()
        if (timer != null) {
            return if (timer in 1..86_400) Alarm.Timer(timer, label) else Alarm.Invalid("timer_seconds must be between 1 and 86400.")
        }
        val hour = args["hour"]?.trim()?.toDoubleOrNull()?.toInt()
        val minute = args["minute"]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        return when {
            hour == null -> Alarm.Invalid("Give hour (and minute) for an alarm, or timer_seconds for a timer.")
            hour !in 0..23 || minute !in 0..59 -> Alarm.Invalid("hour must be 0-23 and minute 0-59.")
            else -> Alarm.At(hour, minute, label)
        }
    }

    data class CalendarEvent(val title: String, val beginMs: Long, val endMs: Long, val allDay: Boolean, val location: String, val description: String)

    /** Parses "yyyy-MM-dd HH:mm" (or "yyyy-MM-dd" for all-day events) in [zone]. */
    fun parseEvent(args: Map<String, String>, zone: TimeZone = TimeZone.getDefault()): Pair<CalendarEvent?, String?> {
        val title = args["title"].orEmpty().trim()
        if (title.isEmpty()) return null to "A title is required."
        val startText = args["start"].orEmpty().trim()
        if (startText.isEmpty()) return null to "start is required (yyyy-MM-dd HH:mm, or yyyy-MM-dd for all day)."
        val allDay = args["all_day"]?.trim()?.lowercase() in setOf("true", "1", "yes") || Regex("""^\d{4}-\d{2}-\d{2}$""").matches(startText)
        val begin = parseTime(startText, zone) ?: return null to "Could not read start '$startText'. Use yyyy-MM-dd HH:mm."
        val endText = args["end"].orEmpty().trim()
        val end = if (endText.isEmpty()) begin + if (allDay) 86_400_000L else 3_600_000L
        else parseTime(endText, zone) ?: return null to "Could not read end '$endText'. Use yyyy-MM-dd HH:mm."
        if (end < begin) return null to "end is before start."
        return CalendarEvent(title.take(200), begin, end, allDay, args["location"].orEmpty().trim().take(200), args["description"].orEmpty().trim().take(2_000)) to null
    }

    private fun parseTime(text: String, zone: TimeZone): Long? {
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone; isLenient = false }
            try {
                val date = fmt.parse(text) ?: continue
                if (fmt.format(date) == text || pattern == "yyyy-MM-dd'T'HH:mm") return date.time
            } catch (e: ParseException) {
                // try the next pattern
            }
        }
        return null
    }

    /** Only web links: no javascript:, intent:, file: or content: URIs. */
    fun isOpenableUrl(url: String): Boolean {
        val u = url.trim()
        return Regex("""^https?://[^\s/?#]+[^\s]*$""", RegexOption.IGNORE_CASE).matches(u)
    }

    fun host(url: String): String = url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

    /**
     * Settings pages the agent may open. Security, accessibility, device admin, developer and
     * "install unknown apps" pages are deliberately missing.
     */
    val SETTINGS_PAGES: Map<String, String> = linkedMapOf(
        "settings" to "android.settings.SETTINGS",
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "network" to "android.settings.WIRELESS_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "data_usage" to "android.settings.DATA_USAGE_SETTINGS",
        "nfc" to "android.settings.NFC_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "apps" to "android.settings.APPLICATION_SETTINGS",
        "app_details" to "android.settings.APPLICATION_DETAILS_SETTINGS",
        "app_notifications" to "android.settings.APP_NOTIFICATION_SETTINGS",
        "usage_access" to "android.settings.USAGE_ACCESS_SETTINGS"
    )

    /** Pages that are about one app and need its package. */
    val APP_PAGES = setOf("app_details", "app_notifications")

    data class AppUsage(val packageName: String, val label: String, val foregroundMs: Long, val lastUsed: Long)

    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "<1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun formatUsage(usage: List<AppUsage>, hours: Int, max: Int = 15): String {
        val used = usage.filter { it.foregroundMs >= 60_000 }.sortedByDescending { it.foregroundMs }
        if (used.isEmpty()) return "No app was used for a minute or more in the last $hours h."
        val total = used.sumOf { it.foregroundMs }
        return buildString {
            append("Screen time in the last ").append(hours).append(" h: ").append(formatDuration(total)).append('\n')
            used.take(max).forEach { u ->
                append("- ").append(u.label).append(" (").append(u.packageName).append("): ").append(formatDuration(u.foregroundMs)).append('\n')
            }
            if (used.size > max) append("… ").append(used.size - max).append(" more apps.\n")
        }.trimEnd()
    }
}
