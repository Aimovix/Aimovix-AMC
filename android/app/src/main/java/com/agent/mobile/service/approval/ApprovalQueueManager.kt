package com.agent.mobile.service.approval

import android.content.Context
import android.util.Log
import com.agent.mobile.agent.ApprovalQueue
import com.agent.mobile.agent.ApprovalRules
import com.agent.mobile.agent.ParkedApproval
import com.agent.mobile.data.storage.db.dao.AgentQueueDao
import com.agent.mobile.data.storage.db.entity.PendingApprovalEntity
import com.agent.mobile.service.AgentNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Android side of [ApprovalQueue]: stores parked approvals in Room, asks the user with an
 * Approve / Reject notification (Approve needs an unlocked phone) and, when set up, through
 * [remoteAsker] (Telegram). The first answer wins and schedules the run that continues the task.
 */
class ApprovalQueueManager(
    private val context: Context,
    private val dao: AgentQueueDao,
    /** Schedules the background run that continues the task after a decision. */
    private val onDecided: (approvalId: String) -> Unit
) : ApprovalQueue {

    /** Optional second place to ask (paired Telegram chats). Failures never block parking. */
    @Volatile
    var remoteAsker: (suspend (PendingApprovalEntity) -> Unit)? = null

    val pending: Flow<List<PendingApprovalEntity>> = dao.pendingApprovals()

    override suspend fun park(request: ParkedApproval): String {
        val id = UUID.randomUUID().toString()
        val entity = PendingApprovalEntity(
            id = id,
            sessionId = request.sessionId,
            runTitle = request.runTitle.take(120),
            toolName = request.call.name,
            argumentsJson = com.agent.mobile.agent.ApprovalFingerprint.encodeArguments(request.call.arguments),
            description = request.description.take(2_000),
            riskLevel = request.riskLevel,
            riskReason = request.riskReason.take(500),
            fingerprint = request.fingerprint,
            grantKey = request.grantKey,
            chain = request.chain
        )
        withContext(Dispatchers.IO) {
            dao.pruneDecided(System.currentTimeMillis() - PRUNE_AFTER_MS)
            dao.insertApproval(entity)
        }
        if (isQuestion(entity)) {
            AgentNotifier.notifyQuestion(context, CALL_PREFIX + id, entity.runTitle, entity.description, optionsOf(entity))
        } else {
            AgentNotifier.notifyApproval(context, CALL_PREFIX + id, notificationText(entity), allowAlways = isRememberable(entity))
        }
        ApprovalExpiryWorker.schedule(context, id)
        try {
            remoteAsker?.invoke(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask through the channel: ${e.message}")
        }
        return id
    }

    /**
     * Records the user's answer. Returns true when this was the first answer (the task then
     * continues in a new background run), false when the approval was already decided.
     */
    suspend fun decide(id: String, approved: Boolean, remember: Boolean = false): Boolean {
        val status = if (approved) PendingApprovalEntity.STATUS_APPROVED else PendingApprovalEntity.STATUS_REJECTED
        val changed = withContext(Dispatchers.IO) { dao.decideApproval(id, status, System.currentTimeMillis()) }
        AgentNotifier.cancelApproval(context, CALL_PREFIX + id)
        if (changed == 0) return false
        if (approved && remember) {
            withContext(Dispatchers.IO) { dao.getApproval(id) }?.let { entity ->
                val call = callOf(entity)
                ApprovalRules.shared?.remember(call, com.agent.mobile.agent.AgentTools.assess(call))
            }
        }
        onDecided(id)
        return true
    }

    /** The user's answer to a parked question. True for the first answer (the task then continues). */
    suspend fun answer(id: String, text: String): Boolean {
        val answer = text.trim().take(2_000)
        if (answer.isEmpty()) return false
        val entity = withContext(Dispatchers.IO) { dao.getApproval(id) } ?: return false
        if (!isQuestion(entity)) return false
        val args = (com.agent.mobile.agent.ApprovalFingerprint.decodeArguments(entity.argumentsJson) ?: emptyMap()) +
            (PendingApprovalEntity.ANSWER_KEY to answer)
        val changed = withContext(Dispatchers.IO) {
            dao.answerApproval(
                id, PendingApprovalEntity.STATUS_ANSWERED,
                com.agent.mobile.agent.ApprovalFingerprint.encodeArguments(args), System.currentTimeMillis()
            )
        }
        AgentNotifier.cancelApproval(context, CALL_PREFIX + id)
        if (changed == 0) return false
        onDecided(id)
        return true
    }

    /** Nobody answered in time: the task continues without the action (see [ApprovalExpiryWorker]). */
    suspend fun expire(id: String): Boolean {
        val changed = withContext(Dispatchers.IO) {
            dao.decideApproval(id, PendingApprovalEntity.STATUS_EXPIRED, System.currentTimeMillis())
        }
        AgentNotifier.cancelApproval(context, CALL_PREFIX + id)
        if (changed == 0) return false
        onDecided(id)
        return true
    }

    /** Shows the question again for an approval that is still open. */
    suspend fun remind(id: String) {
        val entity = withContext(Dispatchers.IO) { dao.getApproval(id) } ?: return
        if (entity.status != PendingApprovalEntity.STATUS_PENDING) return
        if (isQuestion(entity)) {
            AgentNotifier.notifyQuestion(context, CALL_PREFIX + id, entity.runTitle, "⏰ Still waiting: " + entity.description, optionsOf(entity))
            return
        }
        AgentNotifier.notifyApproval(
            context, CALL_PREFIX + id, "⏰ Still waiting for you. " + notificationText(entity), allowAlways = isRememberable(entity)
        )
    }

    suspend fun pendingNow(): List<PendingApprovalEntity> = withContext(Dispatchers.IO) { dao.pendingApprovalsSync() }

    companion object {
        private const val TAG = "ApprovalQueue"

        /** Prefix of notification call ids that belong to parked approvals (not to a waiting run). */
        const val CALL_PREFIX = "park:"
        private const val PRUNE_AFTER_MS = 14L * 24 * 60 * 60_000

        fun parkedIdOf(callId: String): String? = callId.removePrefix(CALL_PREFIX).takeIf { callId.startsWith(CALL_PREFIX) && it.isNotBlank() }

        private fun callOf(entity: PendingApprovalEntity) = com.agent.mobile.data.model.ToolCall(
            name = entity.toolName,
            arguments = com.agent.mobile.agent.ApprovalFingerprint.decodeArguments(entity.argumentsJson) ?: emptyMap()
        )

        fun isQuestion(entity: PendingApprovalEntity) = entity.toolName == com.agent.mobile.agent.AgentTools.ASK_USER

        /** Answer suggestions of a parked question ("a|b|c"). */
        fun optionsOf(entity: PendingApprovalEntity): List<String> =
            (com.agent.mobile.agent.ApprovalFingerprint.decodeArguments(entity.argumentsJson)?.get("options") ?: "")
                .split('|').map { it.trim() }.filter { it.isNotEmpty() }.take(5)

        /** Whether "Always allow" may be offered for this action. */
        fun isRememberable(entity: PendingApprovalEntity): Boolean {
            val call = callOf(entity)
            return ApprovalRules.isRememberable(call, com.agent.mobile.agent.AgentTools.assess(call))
        }

        fun notificationText(entity: PendingApprovalEntity): String =
            "${entity.runTitle.ifBlank { "Background task" }}: ${entity.description}\n${entity.riskReason}".take(1_000)
    }
}
