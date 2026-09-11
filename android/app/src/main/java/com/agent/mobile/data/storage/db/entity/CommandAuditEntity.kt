package com.agent.mobile.data.storage.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_audits")
data class CommandAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String? = null,
    val command: String,
    val riskLevel: String,
    val riskReason: String,
    val executionDurationMs: Long = 0L,
    val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val wasApproved: Boolean = true
)
