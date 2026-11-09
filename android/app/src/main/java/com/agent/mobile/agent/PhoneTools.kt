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

    /** Tools that drive the screen. They show the "AMC is in control" overlay while they run. */
    val SCREEN_TOOLS: Set<String> = setOf(
        SCREEN_READ, SCREEN_TAP, SCREEN_TYPE, SCREEN_SCROLL, SCREEN_SWIPE, SCREEN_GLOBAL, OPEN_APP, WAIT_FOR
    )

    /** Screen tools that change something on the phone (everything except reading and waiting). */
    val SCREEN_ACTIONS: Set<String> = setOf(SCREEN_TAP, SCREEN_TYPE, SCREEN_SCROLL, SCREEN_SWIPE, SCREEN_GLOBAL, OPEN_APP)

    /** Every phone control tool. */
    val ALL_NAMES: Set<String> get() = DEFINITIONS.map { it.name }.toSet()

    /** Output that shows third-party content (screens, app names) and must be treated as untrusted. */
    val UNTRUSTED_OUTPUT: Set<String> = SCREEN_TOOLS + APPS_LIST

    /** Polling and scrolling repeat by nature; the step budget limits them instead of loop protection. */
    val LOOP_EXEMPT: Set<String> = setOf(SCREEN_READ, SCREEN_SCROLL, SCREEN_GLOBAL, WAIT_FOR)

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
