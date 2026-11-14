package com.agent.mobile.service.approval

import android.content.Context
import android.util.Log
import com.agent.mobile.agent.ApprovalQueue
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
        AgentNotifier.notifyApproval(context, CALL_PREFIX + id, notificationText(entity))
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
    suspend fun decide(id: String, approved: Boolean): Boolean {
        val status = if (approved) PendingApprovalEntity.STATUS_APPROVED else PendingApprovalEntity.STATUS_REJECTED
        val changed = withContext(Dispatchers.IO) { dao.decideApproval(id, status, System.currentTimeMillis()) }
        AgentNotifier.cancelApproval(context, CALL_PREFIX + id)
        if (changed == 0) return false
        onDecided(id)
        return true
    }

    suspend fun pendingNow(): List<PendingApprovalEntity> = withContext(Dispatchers.IO) { dao.pendingApprovalsSync() }

    companion object {
        private const val TAG = "ApprovalQueue"

        /** Prefix of notification call ids that belong to parked approvals (not to a waiting run). */
        const val CALL_PREFIX = "park:"
        private const val PRUNE_AFTER_MS = 14L * 24 * 60 * 60_000

        fun parkedIdOf(callId: String): String? = callId.removePrefix(CALL_PREFIX).takeIf { callId.startsWith(CALL_PREFIX) && it.isNotBlank() }

        fun notificationText(entity: PendingApprovalEntity): String =
            "${entity.runTitle.ifBlank { "Background task" }}: ${entity.description}\n${entity.riskReason}".take(1_000)
    }
}
