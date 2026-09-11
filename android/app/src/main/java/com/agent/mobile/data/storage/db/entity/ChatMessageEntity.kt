package com.agent.mobile.data.storage.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String,
    val text: String,
    val toolCallJson: String? = null,
    val toolResultJson: String? = null,
    val streamingTerminalOutput: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "COMPLETED",
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)
