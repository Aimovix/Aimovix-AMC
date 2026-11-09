package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.phone.PhoneRiskContext
import com.agent.mobile.phone.PhoneToolPolicy
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import com.agent.mobile.security.SecurityAssessment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for every tool the agent can call.
 *
 * Each provider (OpenAI-compatible, Gemini, Claude) gets its tool schema generated from
 * [ALL], so adding a tool here makes it available everywhere.
 */
object AgentTools {

    const val EXECUTE_COMMAND = "execute_command"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val WEB_FETCH = "web_fetch"
    const val REMEMBER = "remember"
    const val FORGET = "forget"
    const val UPDATE_PLAN = "update_plan"
    const val NOTIFY_USER = "notify_user"
    const val SCHEDULE_TASK = "schedule_task"
    const val LIST_SCHEDULED_TASKS = "list_scheduled_tasks"
    const val CANCEL_SCHEDULED_TASK = "cancel_scheduled_task"
    const val WEB_SEARCH = "web_search"
    const val DELEGATE_TASK = "delegate_task"
    const val SEARCH_HISTORY = "search_history"
    const val SEND_FILE = "send_file"

    enum class ParamType(val jsonType: String, val geminiType: String) {
        STRING("string", "STRING"),
        INTEGER("integer", "INTEGER"),
        BOOLEAN("boolean", "BOOLEAN"),
        /** A list of objects described by [Param.items]. Arrives as a JSON array string in the arguments. */
        ARRAY("array", "ARRAY"),
        OBJECT("object", "OBJECT")
    }

    data class Param(
        val name: String,
        val type: ParamType,
        val description: String,
        val required: Boolean = true,
        /** For ARRAY: the properties of each item (an object). */
        val items: List<Param> = emptyList()
    )

    data class Definition(
        val name: String,
        val description: String,
        val params: List<Param>
    )

    private val CORE: List<Definition> = listOf(
        Definition(
            EXECUTE_COMMAND,
            "Run a Bash command in Termux on the user's phone (Linux shell + Termux:API). " +
                "Returns stdout, stderr and exit_code. Use for anything the other tools do not cover.",
            listOf(
                Param("command", ParamType.STRING, "The exact Bash command to execute, e.g. 'termux-battery-status' or 'ls -la ~/projects'."),
                Param("timeout_seconds", ParamType.INTEGER, "Optional timeout in seconds (default 90, max 1800). Use a larger value for installs and builds.", required = false)
            )
        ),
        Definition(
            READ_FILE,
            "Read a UTF-8 text file from the Termux home directory or shared storage (max 5 MiB).",
            listOf(Param("path", ParamType.STRING, "Absolute path or path relative to the Termux home directory."))
        ),
        Definition(
            WRITE_FILE,
            "Create or overwrite a text file (scripts, notes, configs, code). Parent folders are created automatically. " +
                "Prefer this over shell heredocs for multi-line content.",
            listOf(
                Param("path", ParamType.STRING, "Absolute path or path relative to the Termux home directory."),
                Param("content", ParamType.STRING, "The full file content."),
                Param("mode", ParamType.STRING, "'overwrite' (default) or 'append'.", required = false)
            )
        ),
        Definition(
            WEB_FETCH,
            "Download a web page or API response over HTTP(S) and return it as readable text (HTML is converted to text).",
            listOf(Param("url", ParamType.STRING, "The http:// or https:// URL to fetch."))
        ),
        Definition(
            WEB_SEARCH,
            "Search the web (no API key needed). Returns titles, URLs and snippets of the top results; " +
                "read the most promising ones with web_fetch. Use it for current information, docs, prices, news.",
            listOf(
                Param("query", ParamType.STRING, "Search terms, e.g. 'termux-api battery status json'."),
                Param("max_results", ParamType.INTEGER, "Number of results (1-15, default 8).", required = false)
            )
        ),
        Definition(
            REMEMBER,
            "Save a durable fact to long-term memory (user preferences, names, recurring tasks, paths, lessons learned). " +
                "Memory is shown to you at the start of every future conversation.",
            listOf(Param("note", ParamType.STRING, "One short, self-contained fact to remember."))
        ),
        Definition(
            FORGET,
            "Remove outdated or wrong entries from long-term memory.",
            listOf(Param("text", ParamType.STRING, "Text that identifies the memory entries to delete (case-insensitive substring)."))
        ),
        Definition(
            UPDATE_PLAN,
            "Create or update the visible task plan for multi-step work. Call it at the start of a complex task and whenever a step's status changes.",
            listOf(
                Param(
                    "plan", ParamType.STRING,
                    "One step per line, each prefixed with a status: '[ ]' pending, '[>]' in progress, '[x]' done. Example: \"[x] Inspect files\\n[>] Write script\\n[ ] Test script\""
                )
            )
        ),
        Definition(
            NOTIFY_USER,
            "Show an Android notification to the user. Use it for results of background work or when something needs their attention.",
            listOf(
                Param("title", ParamType.STRING, "Short notification title."),
                Param("message", ParamType.STRING, "Notification body text.")
            )
        ),
        Definition(
            SCHEDULE_TASK,
            "Schedule yourself to run a task later, unattended, in the background (reminders, periodic checks, recurring reports). " +
                "The prompt you write is everything the future run will know, so make it complete and self-contained.",
            listOf(
                Param("title", ParamType.STRING, "Short name for the task."),
                Param("prompt", ParamType.STRING, "Complete instruction for the future run."),
                Param("delay_minutes", ParamType.INTEGER, "Minutes from now until the first run (0 = as soon as possible).", required = false),
                Param("repeat_every_minutes", ParamType.INTEGER, "Repeat interval in minutes (minimum 15). Omit or 0 for a one-time task.", required = false)
            )
        ),
        Definition(
            DELEGATE_TASK,
            "Hand a self-contained sub-task to a sub-agent. It works independently with a fresh context and the same " +
                "tools (except delegate_task), then returns a report. Use it for research, reading or comparing many " +
                "files or pages, or trying out an approach, to keep your own context focused. The report is all you get, " +
                "so ask for exactly the facts, paths or results you need.",
            listOf(
                Param("task", ParamType.STRING, "Complete instruction for the sub-agent, including the expected result format."),
                Param("context", ParamType.STRING, "Optional background the sub-agent needs (paths, findings so far, constraints).", required = false)
            )
        ),
        Definition(
            SEARCH_HISTORY,
            "Search earlier conversations with the user (their messages and your replies) for a keyword or phrase. " +
                "Use it when the user refers to something discussed before that is not in long-term memory.",
            listOf(
                Param("query", ParamType.STRING, "Word or phrase to look for (case-insensitive)."),
                Param("max_results", ParamType.INTEGER, "Number of matches (1-20, default 8).", required = false)
            )
        ),
        Definition(
            SEND_FILE,
            "Deliver a file (photo, screenshot, PDF, audio, report, script) to the user. In the app it opens in the " +
                "Artifacts panel; when the user is chatting via Telegram it is uploaded to that chat. Max 5 MB.",
            listOf(
                Param("path", ParamType.STRING, "Absolute path or path relative to the Termux home directory."),
                Param("caption", ParamType.STRING, "Optional short caption.", required = false)
            )
        ),
        Definition(
            LIST_SCHEDULED_TASKS,
            "List the background tasks you have scheduled, with their ids and states.",
            emptyList()
        ),
        Definition(
            CANCEL_SCHEDULED_TASK,
            "Cancel a scheduled background task.",
            listOf(Param("task_id", ParamType.STRING, "The id returned by schedule_task or list_scheduled_tasks."))
        )
    )

    /** Core tools followed by the phone control tools ([PhoneTools]). */
    val ALL: List<Definition> = CORE + PhoneTools.DEFINITIONS

    private val byName = ALL.associateBy { it.name }

    fun isKnown(name: String): Boolean = byName.containsKey(name)

    /**
     * Tools offered to on-device models (provider LOCAL). Every tool schema costs prompt
     * prefill on the phone's CPU (all of them together are several thousand tokens per request),
     * and small models choose more reliably from a short list.
     */
    val LOCAL_PROFILE: Set<String> = setOf(
        EXECUTE_COMMAND, READ_FILE, WRITE_FILE, REMEMBER, NOTIFY_USER, WEB_SEARCH,
        PhoneTools.SCREEN_READ, PhoneTools.SCREEN_TAP, PhoneTools.SCREEN_TYPE, PhoneTools.SCREEN_SCROLL,
        PhoneTools.SCREEN_GLOBAL, PhoneTools.OPEN_APP
    )

    // ---------------------------------------------------------------------------------------
    // Provider schemas
    // ---------------------------------------------------------------------------------------

    private fun jsonSchema(def: Definition, typeOf: (ParamType) -> String, objectType: String): JSONObject =
        objectSchema(def.params, typeOf, objectType)

    private fun objectSchema(params: List<Param>, typeOf: (ParamType) -> String, objectType: String): JSONObject =
        JSONObject().apply {
            put("type", objectType)
            put("properties", JSONObject().apply {
                params.forEach { p ->
                    put(p.name, JSONObject().apply {
                        put("type", typeOf(p.type))
                        put("description", p.description)
                        if (p.type == ParamType.ARRAY) put("items", objectSchema(p.items, typeOf, typeOf(ParamType.OBJECT)))
                    })
                }
            })
            put("required", JSONArray().apply { params.filter { it.required }.forEach { put(it.name) } })
        }

    private fun enabled(excluded: Set<String>) = if (excluded.isEmpty()) ALL else ALL.filter { it.name !in excluded }

    /** OpenAI / Groq / OpenRouter / llama.cpp / Ollama `tools` array. */
    fun openAiTools(excluded: Set<String> = emptySet()): JSONArray = JSONArray().apply {
        enabled(excluded).forEach { def ->
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", def.name)
                    put("description", def.description)
                    put("parameters", jsonSchema(def, { it.jsonType }, "object"))
                })
            })
        }
    }

    /** Anthropic Messages API `tools` array. */
    fun claudeTools(excluded: Set<String> = emptySet()): JSONArray = JSONArray().apply {
        enabled(excluded).forEach { def ->
            put(JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                put("input_schema", jsonSchema(def, { it.jsonType }, "object"))
            })
        }
    }

    /** Gemini `function_declarations` array. Gemini rejects empty `properties`, so parameterless tools omit `parameters`. */
    fun geminiFunctionDeclarations(excluded: Set<String> = emptySet()): JSONArray = JSONArray().apply {
        enabled(excluded).forEach { def ->
            put(JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                if (def.params.isNotEmpty()) {
                    put("parameters", jsonSchema(def, { it.geminiType }, "OBJECT"))
                }
            })
        }
    }

    // ---------------------------------------------------------------------------------------
    // Presentation & risk
    // ---------------------------------------------------------------------------------------

    /**
     * A one-line, human-readable form of the call. For shell commands this is the command
     * itself; for other tools a compact `name(arg)` string. Used for approval cards, loop
     * detection and the audit log.
     */
    fun describe(call: ToolCall, redact: Boolean = false): String {
        val a = call.arguments
        PhoneTools.describe(call, redact)?.let { return it }
        return when (call.name) {
            EXECUTE_COMMAND -> a["command"]?.trim().orEmpty()
            READ_FILE -> "read_file ${a["path"].orEmpty()}"
            WRITE_FILE -> {
                val mode = if (a["mode"].equals("append", ignoreCase = true)) " (append)" else ""
                "write_file ${a["path"].orEmpty()}$mode [${a["content"]?.length ?: 0} chars]"
            }
            WEB_FETCH -> "web_fetch ${a["url"].orEmpty()}"
            REMEMBER -> "remember: ${a["note"].orEmpty().take(120)}"
            FORGET -> "forget: ${a["text"].orEmpty().take(120)}"
            UPDATE_PLAN -> "update_plan"
            NOTIFY_USER -> "notify_user: ${a["title"].orEmpty()}"
            SCHEDULE_TASK -> {
                val repeat = a["repeat_every_minutes"]?.toLongOrNull()?.takeIf { it > 0 }
                val delay = a["delay_minutes"]?.toLongOrNull() ?: 0
                buildString {
                    append("schedule_task \"").append(a["title"].orEmpty()).append("\" in ").append(delay).append(" min")
                    if (repeat != null) append(", every ").append(repeat).append(" min")
                }
            }
            LIST_SCHEDULED_TASKS -> "list_scheduled_tasks"
            CANCEL_SCHEDULED_TASK -> "cancel_scheduled_task ${a["task_id"].orEmpty()}"
            WEB_SEARCH -> "web_search: ${a["query"].orEmpty().take(160)}"
            DELEGATE_TASK -> "delegate_task: ${a["task"].orEmpty().replace('\n', ' ').take(160)}"
            SEARCH_HISTORY -> "search_history: ${a["query"].orEmpty().take(120)}"
            SEND_FILE -> "send_file ${a["path"].orEmpty()}"
            else -> "${call.name} ${JSONObject(a as Map<*, *>)}"
        }
    }

    /** Longer text for approval cards: shows what will be written or scheduled. */
    fun approvalPreview(call: ToolCall): String {
        val a = call.arguments
        return when (call.name) {
            WRITE_FILE -> buildString {
                append(describe(call))
                val content = a["content"].orEmpty()
                if (content.isNotEmpty()) {
                    append("\n---\n").append(content.take(1500))
                    if (content.length > 1500) append("\n… (${content.length - 1500} more characters)")
                }
            }
            SCHEDULE_TASK -> describe(call) + "\n---\n" + a["prompt"].orEmpty().take(1500)
            DELEGATE_TASK -> "delegate_task\n---\n" + a["task"].orEmpty().take(1500) +
                a["context"]?.takeIf { it.isNotBlank() }?.let { "\n\nContext: " + it.take(800) }.orEmpty()
            else -> describe(call)
        }
    }

    private val SENSITIVE_WRITE_TARGETS = listOf(
        Regex("""(^|/)\.termux_agent_token$"""),
        Regex("""(^|/)\.termux_agent(/|$)"""),
        Regex("""(^|/)\.ssh(/|$)"""),
        Regex("""(^|/)\.(bashrc|bash_profile|profile|zshrc)$"""),
        Regex("""(^|/)\.termux(/|$)"""),
        Regex("""^/(system|data/data/(?!com\.termux/files/home)|proc|dev)""")
    )

    private val SENSITIVE_READ_TARGETS = listOf(
        Regex("""(^|/)\.termux_agent_token$"""),
        Regex("""(^|/)\.ssh/"""),
        Regex("""(^|/)\.(netrc|git-credentials)$""")
    )

    /**
     * Risk assessment for any tool call. Shell commands go through [CommandSecurityFilter],
     * phone control tools through [PhoneToolPolicy] with [phone] describing the current screen
     * (without it, the policy assumes an unknown app in front).
     */
    fun assess(call: ToolCall, phone: PhoneRiskContext? = null): SecurityAssessment {
        val a = call.arguments
        if (PhoneTools.isPhoneTool(call.name)) return PhoneToolPolicy.assess(call, phone ?: PhoneRiskContext())
        return when (call.name) {
            EXECUTE_COMMAND -> {
                val cmd = a["command"]?.trim().orEmpty()
                if (cmd.isEmpty()) SecurityAssessment(RiskLevel.BLOCKED, "Empty command.")
                else CommandSecurityFilter.analyze(cmd)
            }
            READ_FILE -> {
                val path = a["path"].orEmpty()
                when {
                    path.isBlank() -> SecurityAssessment(RiskLevel.BLOCKED, "A file path is required.")
                    SENSITIVE_READ_TARGETS.any { it.containsMatchIn(path) } ->
                        SecurityAssessment(RiskLevel.HIGH, "Reading a credential or key file")
                    else -> SecurityAssessment(RiskLevel.LOW, "Read-only file access")
                }
            }
            WRITE_FILE -> {
                val path = a["path"].orEmpty()
                when {
                    path.isBlank() -> SecurityAssessment(RiskLevel.BLOCKED, "A file path is required.")
                    path.contains(".termux_agent_token") ->
                        SecurityAssessment(RiskLevel.BLOCKED, "Writing the bridge token file is not allowed.")
                    SENSITIVE_WRITE_TARGETS.any { it.containsMatchIn(path) } ->
                        SecurityAssessment(RiskLevel.HIGH, "Modifying a shell, SSH or Termux configuration file")
                    else -> SecurityAssessment(RiskLevel.MEDIUM, "Creating or modifying a file")
                }
            }
            WEB_FETCH -> {
                val url = a["url"].orEmpty().trim()
                if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                    SecurityAssessment(RiskLevel.BLOCKED, "Only http:// and https:// URLs can be fetched.")
                } else {
                    SecurityAssessment(RiskLevel.MEDIUM, "Network request (download)")
                }
            }
            REMEMBER, FORGET, UPDATE_PLAN, NOTIFY_USER, LIST_SCHEDULED_TASKS ->
                SecurityAssessment(RiskLevel.LOW, "Agent bookkeeping")
            WEB_SEARCH -> {
                if (a["query"].isNullOrBlank()) SecurityAssessment(RiskLevel.BLOCKED, "A search query is required.")
                else SecurityAssessment(RiskLevel.MEDIUM, "Web search (the query is sent to DuckDuckGo)")
            }
            DELEGATE_TASK -> {
                if (a["task"].isNullOrBlank()) SecurityAssessment(RiskLevel.BLOCKED, "A task description is required.")
                else SecurityAssessment(RiskLevel.LOW, "Delegating to a sub-agent; each of its actions is checked separately")
            }
            SEARCH_HISTORY -> {
                if (a["query"].isNullOrBlank()) SecurityAssessment(RiskLevel.BLOCKED, "A search query is required.")
                else SecurityAssessment(RiskLevel.LOW, "Searching earlier conversations")
            }
            SEND_FILE -> {
                val path = a["path"].orEmpty()
                when {
                    path.isBlank() -> SecurityAssessment(RiskLevel.BLOCKED, "A file path is required.")
                    path.contains(".termux_agent_token") ->
                        SecurityAssessment(RiskLevel.BLOCKED, "Sending the bridge token file is not allowed.")
                    SENSITIVE_READ_TARGETS.any { it.containsMatchIn(path) } ->
                        SecurityAssessment(RiskLevel.HIGH, "Sending a credential or key file")
                    else -> SecurityAssessment(RiskLevel.MEDIUM, "Sending a file to the user")
                }
            }
            CANCEL_SCHEDULED_TASK -> SecurityAssessment(RiskLevel.LOW, "Cancelling a background task")
            SCHEDULE_TASK -> {
                if (a["prompt"].isNullOrBlank()) SecurityAssessment(RiskLevel.BLOCKED, "A prompt is required.")
                else SecurityAssessment(RiskLevel.MEDIUM, "Scheduling unattended background work")
            }
            else -> SecurityAssessment(RiskLevel.BLOCKED, "Unknown tool '${call.name}'.")
        }
    }
}
