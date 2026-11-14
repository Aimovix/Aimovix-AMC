package com.agent.mobile.data.storage.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An action a background run wanted to take but that needs the user's approval. The run stops,
 * the user is asked, and a new run continues the same session after the answer.
 */
@Entity(tableName = "pending_approvals", indices = [Index("status")])
data class PendingApprovalEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val runTitle: String,
    val toolName: String,
    /** The real arguments as a JSON object (app-private database; calls with secrets are never parked). */
    val argumentsJson: String,
    val description: String,
    val riskLevel: String,
    val riskReason: String,
    val fingerprint: String,
    val grantKey: String? = null,
    val chain: Int = 0,
    val status: String = STATUS_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val decidedAt: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
    }
}
