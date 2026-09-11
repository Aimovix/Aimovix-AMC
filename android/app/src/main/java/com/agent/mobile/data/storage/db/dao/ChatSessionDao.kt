package com.agent.mobile.data.storage.db.dao

import androidx.room.*
import com.agent.mobile.data.storage.db.entity.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession)

    @Update
    suspend fun updateSession(session: ChatSession)

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun getAllSessionsSync(): List<ChatSession>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): ChatSession?

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_sessions SET totalPromptTokens = totalPromptTokens + :promptTokens, totalCompletionTokens = totalCompletionTokens + :completionTokens, estimatedCostUsd = estimatedCostUsd + :cost, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun addMetrics(sessionId: String, promptTokens: Int, completionTokens: Int, cost: Double, updatedAt: Long = System.currentTimeMillis())
}
