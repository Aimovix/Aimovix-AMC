package com.agent.mobile.phone

import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.agent.mobile.agent.PhoneActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * System features reachable with ordinary intents and permissions: alarms and timers, calendar
 * entries (the user saves them), sharing, links, settings pages, the clipboard and usage stats.
 * Activities are started from the accessibility service when it runs (Android allows that from
 * the background), otherwise from the app.
 */
internal class SystemIntents(private val context: Context, private val labelOf: (String) -> String) {

    private fun ok(text: String) = PhoneActionResult(text)
    private fun fail(text: String) = PhoneActionResult(text, ok = false)

    private fun launcher(): Context = PhoneControlService.instance ?: context

    private fun start(intent: Intent, what: String): PhoneActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return fail("No app on this phone can $what.")
        return try {
            launcher().startActivity(intent)
            ok("Done: $what." + if (PhoneControlService.instance == null) " (If nothing opened, AMC needs to be in front or phone control on.)" else "")
        } catch (e: Exception) {
            fail("Could not $what: ${e.message}")
        }
    }

    fun setAlarm(args: Map<String, String>): PhoneActionResult = when (val spec = SystemIntentSpecs.parseAlarm(args)) {
        is SystemIntentSpecs.Alarm.Invalid -> fail(spec.reason)
        is SystemIntentSpecs.Alarm.At -> start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, spec.label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "set an alarm for %02d:%02d".format(spec.hour, spec.minute)
        )
        is SystemIntentSpecs.Alarm.Timer -> start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, spec.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, spec.label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            "start a ${SystemIntentSpecs.formatDuration(spec.seconds * 1000L).replace("<1 min", "${spec.seconds} s")} timer"
        )
    }

    fun addCalendarEvent(args: Map<String, String>): PhoneActionResult {
        val (event, error) = SystemIntentSpecs.parseEvent(args)
        if (event == null) return fail(error ?: "Invalid event.")
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        if (event.location.isNotEmpty()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
        if (event.description.isNotEmpty()) intent.putExtra(CalendarContract.Events.DESCRIPTION, event.description)
        val r = start(intent, "open the calendar with the new event \"${event.title}\"")
        return if (r.ok) ok(r.text + " The user saves it in the calendar app (or you tap Save with phone control).") else r
    }

    fun share(args: Map<String, String>): PhoneActionResult {
        val text = args["text"].orEmpty()
        if (text.isBlank()) return fail("Text to share is required.")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        args["subject"]?.takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        return start(Intent.createChooser(send, null), "open the share sheet")
    }

    fun openUrl(url: String): PhoneActionResult {
        if (!SystemIntentSpecs.isOpenableUrl(url)) return fail("Only http:// and https:// links can be opened.")
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())), "open ${SystemIntentSpecs.host(url)}")
    }

    fun openSettings(page: String, packageName: String?): PhoneActionResult {
        val key = page.trim().lowercase()
        val action = SystemIntentSpecs.SETTINGS_PAGES[key]
            ?: return fail("Unknown settings page '$page'. Available: ${SystemIntentSpecs.SETTINGS_PAGES.keys.joinToString()}.")
        val intent = Intent(action)
        if (key in SystemIntentSpecs.APP_PAGES) {
            val pkg = packageName?.trim().orEmpty().ifEmpty { context.packageName }
            if (key == "app_details") intent.data = Uri.parse("package:$pkg")
            else intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        }
        return start(intent, "open the $key settings")
    }

    suspend fun clipboard(args: Map<String, String>): PhoneActionResult = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (args["action"].orEmpty().trim().lowercase()) {
            "set" -> {
                val text = args["text"] ?: return@withContext fail("Text is required for action=set.")
                cm.setPrimaryClip(ClipData.newPlainText("AMC", text))
                ok("Copied ${text.length} characters to the clipboard.")
            }
            "get" -> {
                val clip = try {
                    cm.primaryClip
                } catch (e: SecurityException) {
                    null
                }
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                when {
                    text == null -> fail(
                        "Android only lets the app in front read the clipboard. Open AMC first, or use " +
                            "execute_command with termux-clipboard-get."
                    )
                    text.isEmpty() -> ok("The clipboard is empty.")
                    else -> ok("Clipboard (${text.length} characters):\n" + text.take(4_000))
                }
            }
            else -> fail("action must be get or set.")
        }
    }

    fun hasUsageAccess(): Boolean = PhoneControlSetup.hasUsageAccess(context)

    fun usage(args: Map<String, String>): PhoneActionResult {
        if (!hasUsageAccess()) return fail("Usage access is not granted. Ask the user to allow it in AMC → Setup.")
        val hours = (args["hours"]?.trim()?.toDoubleOrNull()?.toInt() ?: 24).coerceIn(1, 24 * 7)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(end - hours * 3_600_000L, end)
        val list = stats.values.map { s ->
            SystemIntentSpecs.AppUsage(s.packageName, labelOf(s.packageName), s.totalTimeInForeground, s.lastTimeUsed)
        }
        return ok(SystemIntentSpecs.formatUsage(list, hours))
    }
}
