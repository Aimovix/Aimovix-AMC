package com.agent.mobile.agent

/**
 * Android side effects the agent can trigger through its tools. Kept behind an interface so
 * the agent engine stays testable on the JVM.
 */
interface AgentPlatform {
    fun notifyUser(title: String, message: String)

    /** True while the app's UI is visible; used to avoid redundant notifications. */
    fun isAppInForeground(): Boolean = false

    /** Schedules an unattended agent run. Returns the task id. */
    fun scheduleAgentTask(title: String, prompt: String, delayMinutes: Long, repeatEveryMinutes: Long): String

    /** Human-readable list of scheduled agent tasks (one per line). */
    suspend fun listAgentTasks(): String

    /** Returns true when a task with that id existed and was cancelled. */
    suspend fun cancelAgentTask(taskId: String): Boolean
}
