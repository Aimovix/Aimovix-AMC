package com.agent.mobile.data.storage.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelProvider: String = "GEMINI",
    val modelName: String = "gemini-2.0-flash",
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val estimatedCostUsd: Double = 0.0
)
