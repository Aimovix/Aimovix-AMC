package com.agent.mobile.channel

import com.agent.mobile.agent.AttachmentSink
import com.agent.mobile.agent.AutonomousAgentEngine
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.SecurityAssessment

/** One message from a channel user that the agent should work on. */
data class TurnRequest(
    /** AMC chat session that holds this channel conversation, or null to start a new one. */
    val sessionId: String?,
    val title: String,
    val text: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    val approval: suspend (ToolCall, SecurityAssessment) -> Boolean,
    val attachments: AttachmentSink
)

/**
 * The agent as seen by a messaging channel: one instance per channel chat. The production
 * implementation is [EngineChannelAgent]; tests use fakes.
 */
interface ChannelAgent {
    val isBusy: Boolean

    /** Runs one turn and returns the agent's final reply. Cancelling the caller stops the run. */
    suspend fun run(request: TurnRequest): AutonomousAgentEngine.UnattendedResult

    /** Adds a message to the running turn ("steering"). Returns false when nothing is running. */
    fun steer(text: String): Boolean

    /** One or two lines for the /status command. */
    fun statusLine(): String

    fun close()
}
