package com.agent.mobile.agent

/**
 * Android side effects the agent can trigger through its tools. Kept behind an interface so
 * the agent engine stays testable on the JVM.
 */
interface AgentPlatform {
    fun notifyUser(title: String, message: String)

    /** True while the app's UI is visible; used to avoid redundant notifications. */
    fun isAppInForeground(): Boolean = false

    /**
     * Tells the user that [callId] waits for approval, with Approve / Reject actions where the
     * platform supports them (routed through [ApprovalActions]).
     */
    fun notifyApprovalNeeded(callId: String, description: String) = notifyUser("AMC needs your approval", description)

    /** The approval for [callId] was decided (or timed out): remove its notification. */
    fun cancelApprovalNotification(callId: String) {}

    /** Schedules an unattended agent run. Returns the task id. */
    fun scheduleAgentTask(title: String, prompt: String, delayMinutes: Long, repeatEveryMinutes: Long): String

    /**
     * Like [scheduleAgentTask], but the run waits for [conditions] ([TaskConditions]: Wi-Fi,
     * charging, battery or storage not low). Platforms without conditions ignore them.
     */
    fun scheduleAgentTask(title: String, prompt: String, delayMinutes: Long, repeatEveryMinutes: Long, conditions: Set<String>): String =
        scheduleAgentTask(title, prompt, delayMinutes, repeatEveryMinutes)

    /** The latest background runs with title, status and time, newest first ("" when not available). */
    suspend fun listRecentRuns(limit: Int): String = ""

    /** Human-readable list of scheduled agent tasks (one per line). */
    suspend fun listAgentTasks(): String

    /** Returns true when a task with that id existed and was cancelled. */
    suspend fun cancelAgentTask(taskId: String): Boolean
}

/** Conditions a scheduled task can wait for (values of schedule_task's `conditions`). */
object TaskConditions {
    const val WIFI = "wifi"
    const val CHARGING = "charging"
    const val BATTERY_OK = "battery_ok"
    const val STORAGE_OK = "storage_ok"
    val ALL = setOf(WIFI, CHARGING, BATTERY_OK, STORAGE_OK)

    /** "wifi, charging" → {wifi, charging}; unknown words are returned separately. */
    fun parse(text: String?): Pair<Set<String>, List<String>> {
        val words = text.orEmpty().split(Regex("[,;\\s]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return words.filter { it in ALL }.toSet() to words.filter { it !in ALL }
    }
}

/** How a run of the agent is attended. Changes the system prompt and how approvals are handled. */
enum class RunMode {
    /** The user watches in the app and approves in the chat. */
    INTERACTIVE,

    /** Scheduled task or heartbeat: nobody is watching; approval-gated actions are skipped. */
    UNATTENDED,

    /** Conversation through a messaging channel (Telegram); approvals go to the chat as buttons. */
    REMOTE,

    /** A delegated sub-task; approvals are forwarded to the parent run. */
    SUBAGENT
}

/** Receives files from the `send_file` tool during a channel conversation (e.g. uploads them to Telegram). */
fun interface AttachmentSink {
    /** Delivers the file and returns a short status text for the model. */
    suspend fun sendFile(fileName: String, bytes: ByteArray, caption: String): String
}
