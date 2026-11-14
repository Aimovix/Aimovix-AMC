package com.agent.mobile.data.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.data.storage.db.entity.PendingApprovalEntity
import kotlinx.coroutines.flow.Flow

/** Parked approvals and run checkpoints of background agent runs. */
@Dao
interface AgentQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApproval(approval: PendingApprovalEntity)

    @Query("SELECT * FROM pending_approvals WHERE id = :id")
    suspend fun getApproval(id: String): PendingApprovalEntity?

    @Query("SELECT * FROM pending_approvals WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun pendingApprovals(): Flow<List<PendingApprovalEntity>>

    @Query("SELECT * FROM pending_approvals WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun pendingApprovalsSync(): List<PendingApprovalEntity>

    /** Decides a pending approval. Returns 1 for the first decision, 0 when it was already decided. */
    @Query("UPDATE pending_approvals SET status = :status, decidedAt = :decidedAt WHERE id = :id AND status = 'PENDING'")
    suspend fun decideApproval(id: String, status: String, decidedAt: Long): Int

    @Query("DELETE FROM pending_approvals WHERE status != 'PENDING' AND decidedAt < :before")
    suspend fun pruneDecided(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun getRun(id: String): AgentRunEntity?

    @Query("UPDATE agent_runs SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setRunStatus(id: String, status: String, updatedAt: Long)

    @Query("DELETE FROM agent_runs WHERE updatedAt < :before AND status != 'RUNNING'")
    suspend fun pruneRuns(before: Long)
}
