package com.agent.mobile.data.storage.db.dao

import androidx.room.*
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: CommandAuditEntity)

    @Query("SELECT * FROM command_audits ORDER BY timestamp DESC")
    fun getAllAudits(): Flow<List<CommandAuditEntity>>

    @Query("SELECT * FROM command_audits ORDER BY timestamp DESC")
    suspend fun getAllAuditsSync(): List<CommandAuditEntity>

    @Query("SELECT * FROM command_audits WHERE riskLevel = :riskLevel ORDER BY timestamp DESC")
    fun getAuditsByRiskLevel(riskLevel: String): Flow<List<CommandAuditEntity>>

    @Query("SELECT * FROM command_audits WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getAuditsForSession(sessionId: String): List<CommandAuditEntity>

    @Query("SELECT * FROM command_audits WHERE command LIKE '%' || :query || '%' OR riskReason LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchAudits(query: String): List<CommandAuditEntity>

    @Query("DELETE FROM command_audits")
    suspend fun clearAudits()
}
