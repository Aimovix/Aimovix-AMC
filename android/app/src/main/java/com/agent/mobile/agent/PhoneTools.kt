package com.agent.mobile.agent

import com.agent.mobile.agent.AgentTools.Definition
import com.agent.mobile.agent.AgentTools.Param
import com.agent.mobile.agent.AgentTools.ParamType
import com.agent.mobile.data.model.ToolCall

/**
 * Phone control tools: operating apps through the accessibility service, screenshots,
 * notifications, system intents and the optional Shizuku shell. Part of [AgentTools.ALL].
 */
object PhoneTools {

    // Screen (accessibility service)
    const val SCREEN_READ = "screen_read"
    const val SCREEN_TAP = "screen_tap"
    const val SCREEN_TYPE = "screen_type"
    const val SCREEN_SCROLL = "screen_scroll"
    const val SCREEN_SWIPE = "screen_swipe"
    const val SCREEN_GLOBAL = "screen_global"
    const val OPEN_APP = "open_app"
    const val APPS_LIST = "apps_list"
    const val WAIT_FOR = "wait_for"
    const val SCREEN_CAPTURE = "screen_capture"

    // Notifications (notification listener)
    const val NOTIFICATIONS_LIST = "notifications_list"
    const val NOTIFICATION_OPEN = "notification_open"
    const val NOTIFICATION_REPLY = "notification_reply"
    const val NOTIFICATION_DISMISS = "notification_dismiss"
    const val NOTIFICATION_TRIGGER = "notification_trigger"

    val NOTIFICATION_TOOLS: Set<String> = setOf(
        NOTIFICATIONS_LIST, NOTIFICATION_OPEN, NOTIFICATION_REPLY, NOTIFICATION_DISMISS, NOTIFICATION_TRIGGER
    )

    // System intents (no special permissions)
    const val SET_ALARM = "set_alarm"
    const val CALENDAR_ADD = "calendar_add"
    const val SHARE_TEXT = "share_text"
    const val OPEN_URL = "open_url"
    const val OPEN_SETTINGS = "open_settings"
    const val CLIPBOARD = "clipboard"
    const val USAGE_STATS = "usage_stats"

    val INTENT_TOOLS: Set<String> = setOf(SET_ALARM, CALENDAR_ADD, SHARE_TEXT, OPEN_URL, OPEN_SETTINGS, CLIPBOARD, USAGE_STATS)

    /** Tools that drive the screen. They show the "AMC is in control" overlay while they run. */
    val SCREEN_TOOLS: Set<String> = setOf(
        SCREEN_READ, SCREEN_TAP, SCREEN_TYPE, SCREEN_SCROLL, SCREEN_SWIPE, SCREEN_GLOBAL, OPEN_APP, WAIT_FOR, SCREEN_CAPTURE
    )

    /** Screen tools that change something on the phone (everything except reading and waiting). */
    val SCREEN_ACTIONS: Set<String> = setOf(SCREEN_TAP, SCREEN_TYPE, SCREEN_SCROLL, SCREEN_SWIPE, SCREEN_GLOBAL, OPEN_APP)

    /** Every phone control tool. */
    val ALL_NAMES: Set<String> get() = DEFINITIONS.map { it.name }.toSet()

    /** Output that shows third-party content (screens, app names) and must be treated as untrusted. */
    val UNTRUSTED_OUTPUT: Set<String> = SCREEN_TOOLS + APPS_LIST + NOTIFICATIONS_LIST + NOTIFICATION_OPEN + CLIPBOARD + USAGE_STATS

    /** Polling and scrolling repeat by nature; the step budget limits them instead of loop protection. */
    val LOOP_EXEMPT: Set<String> = setOf(SCREEN_READ, SCREEN_SCROLL, SCREEN_GLOBAL, WAIT_FOR, SCREEN_CAPTURE)

    fun isPhoneTool(name: String): Boolean = name in ALL_NAMES

    val DEFINITIONS: List<Definition> = listOf(
        Definition(
            SCREEN_READ,
            "Read what is on the phone screen right now as a compact element list. Each element has an id " +
                "like [e12], a role, its text, flags (click, edit, scroll, on/off) and its centre @x,y. Use the ids " +
                "with screen_tap and screen_type. Password fields never show their content.",
            listOf(Param("filter", ParamType.STRING, "Optional: only list elements whose text, description or id contains this.", required = false))
        ),
        Definition(
            SCREEN_TAP,
            "Tap an element on the screen, by id from the last screen reading (e.g. 'e12') or by its visible text. " +
                "Use x and y only when the element has no id. Returns the screen after the tap so you can verify it worked.",
            listOf(
                Param("target", ParamType.STRING, "Element id like 'e12', or the element's visible text.", required = false),
                Param("x", ParamType.INTEGER, "Screen x coordinate (only without target).", required = false),
                Param("y", ParamType.INTEGER, "Screen y coordinate (only without target).", required = false),
                Param("long_press", ParamType.BOOLEAN, "true for a long press.", required = false)
            )
        ),
        Definition(
            SCREEN_TYPE,
            "Type text into an input field (replaces its content). Without target, the focused field is used. " +
                "Set submit=true to press the keyboard's Enter/Search/Send action afterwards. Returns the new screen.",
            listOf(
                Param("text", ParamType.STRING, "The text to enter."),
                Param("target", ParamType.STRING, "Field id like 'e5', or its visible text or hint.", required = false),
                Param("submit", ParamType.BOOLEAN, "true to press Enter after typing.", required = false)
            )
        ),
        Definition(
            SCREEN_SCROLL,
            "Scroll the screen or a list to reveal more content. 'down' shows what is below, 'up' what is above. Returns the new screen.",
            listOf(
                Param("direction", ParamType.STRING, "'down', 'up', 'left' or 'right'."),
                Param("target", ParamType.STRING, "Optional id of the list to scroll (default: the largest scrollable area).", required = false)
            )
        ),
        Definition(
            SCREEN_SWIPE,
            "Swipe from one point to another (for sliders, carousels, pull-to-refresh, or apps without scroll support).",
            listOf(
                Param("x1", ParamType.INTEGER, "Start x."),
                Param("y1", ParamType.INTEGER, "Start y."),
                Param("x2", ParamType.INTEGER, "End x."),
                Param("y2", ParamType.INTEGER, "End y."),
                Param("duration_ms", ParamType.INTEGER, "Optional duration (default 300).", required = false)
            )
        ),
        Definition(
            SCREEN_GLOBAL,
            "Press a system button: 'back', 'home', 'recents', 'notifications' (open the notification shade) or 'quick_settings'.",
            listOf(Param("action", ParamType.STRING, "back | home | recents | notifications | quick_settings"))
        ),
        Definition(
            OPEN_APP,
            "Open an installed app by its name (e.g. 'WhatsApp', 'Spotify') or package name. Returns the app's first screen.",
            listOf(Param("name", ParamType.STRING, "App name or package name."))
        ),
        Definition(
            APPS_LIST,
            "List installed apps that can be opened, with name and package.",
            listOf(Param("filter", ParamType.STRING, "Optional part of the name or package to look for.", required = false))
        ),
        Definition(
            WAIT_FOR,
            "Wait until an element with this text appears on the screen (or disappears with gone=true), up to a timeout. " +
                "Use it after actions that load (sending, downloading, opening a page).",
            listOf(
                Param("text", ParamType.STRING, "Text, description or id of the element to wait for."),
                Param("timeout_seconds", ParamType.INTEGER, "Maximum wait (default 10, max 60).", required = false),
                Param("gone", ParamType.BOOLEAN, "true to wait until it disappears.", required = false)
            )
        ),
        Definition(
            SCREEN_CAPTURE,
            "Take a screenshot and look at it (for images, maps, games, charts or layouts the element list cannot " +
                "describe). Prefer screen_read for text and buttons; it is cheaper and gives element ids. Protected " +
                "windows (FLAG_SECURE) give no image.",
            listOf(
                Param(
                    "send_to_user", ParamType.BOOLEAN,
                    "true only when the user explicitly asked to see the screenshot; it is then also sent to them " +
                        "(app or chat). Default false: only you see it.", required = false
                )
            )
        ),
        Definition(
            NOTIFICATIONS_LIST,
            "List the phone's current notifications (newest first) with short ids like [n3], app, title, text and " +
                "whether you can reply or open them. One-time codes are hidden.",
            listOf(Param("app", ParamType.STRING, "Optional app name or package to filter by.", required = false))
        ),
        Definition(
            NOTIFICATION_OPEN,
            "Open a notification like a tap (it opens the app at that message). Returns the new screen when phone control is on.",
            listOf(Param("id", ParamType.STRING, "Notification id from notifications_list, e.g. 'n3'."))
        ),
        Definition(
            NOTIFICATION_REPLY,
            "Reply to a message notification directly (WhatsApp, Telegram, SMS …) without opening the app.",
            listOf(
                Param("id", ParamType.STRING, "Notification id from notifications_list."),
                Param("text", ParamType.STRING, "The reply to send.")
            )
        ),
        Definition(
            NOTIFICATION_DISMISS,
            "Dismiss (clear) a notification.",
            listOf(Param("id", ParamType.STRING, "Notification id from notifications_list."))
        ),
        Definition(
            NOTIFICATION_TRIGGER,
            "Automate: when a notification from an app arrives, start a background task. action='add' needs app " +
                "and prompt (and optionally contains); 'list' shows triggers; 'remove' needs id. Only add triggers " +
                "the user asked for.",
            listOf(
                Param("action", ParamType.STRING, "add | list | remove"),
                Param("app", ParamType.STRING, "App name or package (for add).", required = false),
                Param("contains", ParamType.STRING, "Only when the title or text contains this (optional).", required = false),
                Param("prompt", ParamType.STRING, "Complete instruction for the background run (for add).", required = false),
                Param("id", ParamType.STRING, "Trigger id (for remove).", required = false)
            )
        ),
        Definition(
            SET_ALARM,
            "Set an alarm (hour + minute) or start a timer (timer_seconds) in the clock app.",
            listOf(
                Param("hour", ParamType.INTEGER, "Alarm hour 0-23.", required = false),
                Param("minute", ParamType.INTEGER, "Alarm minute 0-59 (default 0).", required = false),
                Param("timer_seconds", ParamType.INTEGER, "Timer length in seconds (instead of hour/minute).", required = false),
                Param("label", ParamType.STRING, "Optional label.", required = false)
            )
        ),
        Definition(
            CALENDAR_ADD,
            "Open the calendar with a prefilled new event; the event is saved when the user (or you, with phone control) taps Save.",
            listOf(
                Param("title", ParamType.STRING, "Event title."),
                Param("start", ParamType.STRING, "Start as 'yyyy-MM-dd HH:mm' (or 'yyyy-MM-dd' for all day), local time."),
                Param("end", ParamType.STRING, "End as 'yyyy-MM-dd HH:mm' (default: one hour after start).", required = false),
                Param("all_day", ParamType.BOOLEAN, "true for an all-day event.", required = false),
                Param("location", ParamType.STRING, "Optional location.", required = false),
                Param("description", ParamType.STRING, "Optional notes.", required = false)
            )
        ),
        Definition(
            SHARE_TEXT,
            "Open Android's share sheet with a text; the user picks the app and sends it.",
            listOf(
                Param("text", ParamType.STRING, "Text to share."),
                Param("subject", ParamType.STRING, "Optional subject (for e-mail).", required = false)
            )
        ),
        Definition(
            OPEN_URL,
            "Open a web link (http/https) in the browser or the app that handles it.",
            listOf(Param("url", ParamType.STRING, "The link."))
        ),
        Definition(
            OPEN_SETTINGS,
            "Open a system settings page: settings, wifi, bluetooth, network, airplane, data_usage, nfc, display, sound, " +
                "battery, location, date, language, storage, apps, app_details, app_notifications, usage_access. " +
                "Changing something there with screen tools always asks the user.",
            listOf(
                Param("page", ParamType.STRING, "One of the pages listed above."),
                Param("package", ParamType.STRING, "App package for app_details and app_notifications.", required = false)
            )
        ),
        Definition(
            CLIPBOARD,
            "Read (action=get) or replace (action=set) the clipboard text.",
            listOf(
                Param("action", ParamType.STRING, "get | set"),
                Param("text", ParamType.STRING, "Text for action=set.", required = false)
            )
        ),
        Definition(
            USAGE_STATS,
            "Screen time: which apps were used and for how long (needs usage access).",
            listOf(Param("hours", ParamType.INTEGER, "Look back this many hours (default 24, max 168).", required = false))
        )
    )

    private fun flag(value: String?): Boolean = value?.trim()?.lowercase() in setOf("true", "1", "yes")

    /** One-line description for approval cards, loop detection and the audit log. Typed text is shown only when [redact] is false. */
    fun describe(call: ToolCall, redact: Boolean = false): String? {
        val a = call.arguments
        return when (call.name) {
            SCREEN_READ -> "screen_read" + a["filter"]?.takeIf { it.isNotBlank() }?.let { " filter=\"${it.take(60)}\"" }.orEmpty()
            SCREEN_TAP -> {
                val what = a["target"]?.takeIf { it.isNotBlank() }?.let { "\"${it.take(80)}\"" }
                    ?: "at ${a["x"].orEmpty()},${a["y"].orEmpty()}"
                (if (flag(a["long_press"])) "screen_tap (long) " else "screen_tap ") + what
            }
            SCREEN_TYPE -> {
                val text = a["text"].orEmpty()
                val shown = if (redact) "[${text.length} characters hidden]" else "\"${text.replace('\n', ' ').take(120)}\""
                buildString {
                    append("screen_type ").append(shown)
                    a["target"]?.takeIf { it.isNotBlank() }?.let { append(" into \"").append(it.take(60)).append('"') }
                    if (flag(a["submit"])) append(" + Enter")
                }
            }
            SCREEN_SCROLL -> "screen_scroll ${a["direction"].orEmpty()}" + a["target"]?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
            SCREEN_SWIPE -> "screen_swipe ${a["x1"].orEmpty()},${a["y1"].orEmpty()} → ${a["x2"].orEmpty()},${a["y2"].orEmpty()}"
            SCREEN_GLOBAL -> "screen_global ${a["action"].orEmpty()}"
            OPEN_APP -> "open_app ${a["name"].orEmpty()}"
            APPS_LIST -> "apps_list" + a["filter"]?.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
            WAIT_FOR -> "wait_for \"${a["text"].orEmpty().take(80)}\"" + if (flag(a["gone"])) " to disappear" else ""
            SCREEN_CAPTURE -> "screen_capture" + if (flag(a["send_to_user"])) " (send to user)" else ""
            NOTIFICATIONS_LIST -> "notifications_list" + a["app"]?.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
            NOTIFICATION_OPEN -> "notification_open ${a["id"].orEmpty()}"
            NOTIFICATION_REPLY -> "notification_reply ${a["id"].orEmpty()}: \"${a["text"].orEmpty().replace('\n', ' ').take(200)}\""
            NOTIFICATION_DISMISS -> "notification_dismiss ${a["id"].orEmpty()}"
            SET_ALARM -> a["timer_seconds"]?.takeIf { it.isNotBlank() }?.let { "set_alarm timer ${it}s" }
                ?: "set_alarm ${a["hour"].orEmpty()}:${a["minute"].orEmpty().ifBlank { "00" }.padStart(2, '0')}"
            CALENDAR_ADD -> "calendar_add \"${a["title"].orEmpty().take(80)}\" ${a["start"].orEmpty()}"
            SHARE_TEXT -> "share_text \"${a["text"].orEmpty().replace('\n', ' ').take(120)}\""
            OPEN_URL -> "open_url ${a["url"].orEmpty().take(200)}"
            OPEN_SETTINGS -> "open_settings ${a["page"].orEmpty()}" + a["package"]?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            CLIPBOARD -> if (a["action"].equals("set", ignoreCase = true)) "clipboard set [${a["text"]?.length ?: 0} chars]" else "clipboard ${a["action"].orEmpty()}"
            USAGE_STATS -> "usage_stats ${a["hours"].orEmpty().ifBlank { "24" }}h"
            NOTIFICATION_TRIGGER -> when (a["action"].orEmpty().trim().lowercase()) {
                "add" -> "notification_trigger add: ${a["app"].orEmpty()}" +
                    a["contains"]?.takeIf { it.isNotBlank() }?.let { " containing \"$it\"" }.orEmpty() +
                    " → \"${a["prompt"].orEmpty().replace('\n', ' ').take(120)}\""
                "remove" -> "notification_trigger remove ${a["id"].orEmpty()}"
                else -> "notification_trigger ${a["action"].orEmpty()}"
            }
            else -> null
        }
    }

    /** Arguments safe to store: secrets are replaced. */
    fun redactedArguments(call: ToolCall): Map<String, String> =
        if (call.name == SCREEN_TYPE) call.arguments + ("text" to "••••••") else call.arguments

    /** The call as it may be shown, stored and replayed: secret arguments replaced, raw provider JSON dropped. */
    fun redacted(call: ToolCall): ToolCall = call.copy(arguments = redactedArguments(call), rawJson = "")

    fun isTrue(value: String?): Boolean = flag(value)
}
