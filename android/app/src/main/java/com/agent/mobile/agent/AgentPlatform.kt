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

    /** [scheduleAgentTask] that waits for Wi-Fi, charging or enough battery (#23). */
    fun scheduleAgentTask(
        title: String,
        prompt: String,
        delayMinutes: Long,
        repeatEveryMinutes: Long,
        conditions: TaskConditions
    ): String = scheduleAgentTask(title, prompt, delayMinutes, repeatEveryMinutes)

    /** The latest agent runs with status and outcome, one per line (#23). Empty when unknown. */
    suspend fun listRecentRuns(limit: Int): String = ""

    /** Human-readable list of scheduled agent tasks (one per line). */
    suspend fun listAgentTasks(): String

    /** Returns true when a task with that id existed and was cancelled. */
    suspend fun cancelAgentTask(taskId: String): Boolean
}

/** When a scheduled task may start (#23). */
data class TaskConditions(
    val requiresWifi: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = false
) {
    val any: Boolean get() = requiresWifi || requiresCharging || requiresBatteryNotLow

    fun describe(): String = listOfNotNull(
        "Wi-Fi".takeIf { requiresWifi }, "charging".takeIf { requiresCharging }, "battery not low".takeIf { requiresBatteryNotLow }
    ).joinToString(", ")
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

/**
 * Thrown by an approval handler when the question could not be delivered at all (network
 * problem). Unlike a rejection, the agent may ask again later in the task (#28).
 */
class ApprovalUnreachableException(message: String) : Exception(message)

/** Receives files from the `send_file` tool during a channel conversation (e.g. uploads them to Telegram). */
fun interface AttachmentSink {
    /** Delivers the file and returns a short status text for the model. */
    suspend fun sendFile(fileName: String, bytes: ByteArray, caption: String): String
}

/** What started a run. Recorded in the audit log and shown in run reports. */
object RunTrigger {
    const val USER = "USER"
    const val SCHEDULE = "SCHEDULE"
    const val HEARTBEAT = "HEARTBEAT"
    const val NOTIFICATION = "NOTIFICATION"
    const val TELEGRAM = "TELEGRAM"
    const val SUBAGENT = "SUBAGENT"
    const val RESUME = "RESUME"

    fun defaultFor(mode: RunMode): String = when (mode) {
        RunMode.INTERACTIVE -> USER
        RunMode.UNATTENDED -> SCHEDULE
        RunMode.REMOTE -> TELEGRAM
        RunMode.SUBAGENT -> SUBAGENT
    }
}
