package com.agent.mobile.data.storage.db.dao

import androidx.room.*
import com.agent.mobile.data.storage.db.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionSync(sessionId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("SELECT * FROM chat_messages WHERE text LIKE '%' || :query || '%' OR streamingTerminalOutput LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(query: String): List<ChatMessageEntity>

    /** Full-text-ish search over what the user and the agent said (tool output excluded), newest first. */
    @Query("SELECT * FROM chat_messages WHERE (role = 'USER' OR role = 'ASSISTANT') AND text LIKE '%' || :query || '%' AND sessionId != :excludeSessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchConversationText(query: String, excludeSessionId: String, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND (text LIKE '%' || :query || '%' OR streamingTerminalOutput LIKE '%' || :query || '%') ORDER BY timestamp ASC")
    suspend fun searchMessagesInSession(sessionId: String, query: String): List<ChatMessageEntity>
}
