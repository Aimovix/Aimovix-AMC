package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall
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

    enum class ParamType(val jsonType: String, val geminiType: String) {
        STRING("string", "STRING"),
        INTEGER("integer", "INTEGER")
    }

    data class Param(
        val name: String,
        val type: ParamType,
        val description: String,
        val required: Boolean = true
    )

    data class Definition(
        val name: String,
        val description: String,
        val params: List<Param>
    )

    val ALL: List<Definition> = listOf(
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

    private val byName = ALL.associateBy { it.name }

    fun isKnown(name: String): Boolean = byName.containsKey(name)

    // ---------------------------------------------------------------------------------------
    // Provider schemas
    // ---------------------------------------------------------------------------------------

    private fun jsonSchema(def: Definition, typeOf: (ParamType) -> String, objectType: String): JSONObject =
        JSONObject().apply {
            put("type", objectType)
            put("properties", JSONObject().apply {
                def.params.forEach { p ->
                    put(p.name, JSONObject().apply {
                        put("type", typeOf(p.type))
                        put("description", p.description)
                    })
                }
            })
            put("required", JSONArray().apply { def.params.filter { it.required }.forEach { put(it.name) } })
        }

    /** OpenAI / Groq / OpenRouter / llama.cpp / Ollama `tools` array. */
    fun openAiTools(): JSONArray = JSONArray().apply {
        ALL.forEach { def ->
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
    fun claudeTools(): JSONArray = JSONArray().apply {
        ALL.forEach { def ->
            put(JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                put("input_schema", jsonSchema(def, { it.jsonType }, "object"))
            })
        }
    }

    /** Gemini `function_declarations` array. Gemini rejects empty `properties`, so parameterless tools omit `parameters`. */
    fun geminiFunctionDeclarations(): JSONArray = JSONArray().apply {
        ALL.forEach { def ->
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
    fun describe(call: ToolCall): String {
        val a = call.arguments
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

    /** Risk assessment for any tool call. Shell commands go through [CommandSecurityFilter]. */
    fun assess(call: ToolCall): SecurityAssessment {
        val a = call.arguments
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
            CANCEL_SCHEDULED_TASK -> SecurityAssessment(RiskLevel.LOW, "Cancelling a background task")
            SCHEDULE_TASK -> {
                if (a["prompt"].isNullOrBlank()) SecurityAssessment(RiskLevel.BLOCKED, "A prompt is required.")
                else SecurityAssessment(RiskLevel.MEDIUM, "Scheduling unattended background work")
            }
            else -> SecurityAssessment(RiskLevel.BLOCKED, "Unknown tool '${call.name}'.")
        }
    }
}
