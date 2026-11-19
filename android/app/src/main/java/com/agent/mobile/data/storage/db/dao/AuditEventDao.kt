package com.agent.mobile.data.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

/** Audit log access for AMC itself. There is deliberately no update method. */
@Dao
interface AuditEventDao {
    @Insert
    suspend fun insert(event: AuditEventEntity): Long

    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT 1")
    suspend fun last(): AuditEventEntity?

    @Query("SELECT * FROM audit_events WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE runId = :runId ORDER BY id ASC")
    suspend fun forRun(runId: String): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun forSession(sessionId: String): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE timestamp >= :since ORDER BY id ASC")
    suspend fun since(since: Long): List<AuditEventEntity>

    /** Newest first, at most [limit] rows (the screen loads more on demand). */
    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AuditEventEntity>>

    @Query("SELECT COUNT(*) FROM audit_events")
    suspend fun count(): Int

    @Query("SELECT MIN(id) FROM audit_events")
    suspend fun firstId(): Long?

    @Query("SELECT MAX(id) FROM audit_events WHERE timestamp < :before")
    suspend fun lastIdBefore(before: Long): Long?

    @Query("SELECT MAX(id) FROM audit_events")
    suspend fun maxId(): Long?

    /** Removes a prefix of the log (never a gap in the middle, so the rest stays a valid chain). */
    @Query("DELETE FROM audit_events WHERE id <= :maxId")
    suspend fun deleteUpTo(maxId: Long): Int
}
