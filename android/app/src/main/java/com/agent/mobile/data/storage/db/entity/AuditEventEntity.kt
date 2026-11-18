package com.agent.mobile.data.storage.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the tamper-evident audit log: every tool call of every run (shell, files, web,
 * memory, scheduling, sub-agents, phone control, Telegram), with the decision that let it run or
 * not. Entries are chained: [hash] covers all fields plus [prevHash], the hash of the entry before.
 * Only AMC writes here; no agent tool can read, change or delete this table.
 */
@Entity(
    tableName = "audit_events",
    indices = [Index("runId"), Index("timestamp"), Index("decision")]
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** Identifies one run (one engine loop); continuations of a task have their own run ids. */
    val runId: String,
    val sessionId: String?,
    /** What started the run: USER, SCHEDULE, HEARTBEAT, NOTIFICATION, TELEGRAM, SUBAGENT, RESUME, LEGACY, SYSTEM. */
    val origin: String,
    /** INTERACTIVE, UNATTENDED, REMOTE, SUBAGENT (how the run was attended). */
    val mode: String,
    /** Security preset active at that moment (UNATTENDED when Turbo runs as unattended mode). */
    val preset: String,
    val tool: String,
    /** App the call is about (phone control), when known. */
    val app: String?,
    /** Arguments as JSON, secrets and one-time codes redacted, long values shortened. */
    val arguments: String,
    /** See [com.agent.mobile.audit.AuditDecision]. */
    val decision: String,
    /** The rule that decided, e.g. "preset:AUTONOMOUS", "unattended:send_messages", "floor:bridge_token". */
    val rule: String,
    val reason: String,
    val riskLevel: String,
    /** First characters of the result (stdout, else stderr). */
    val resultSummary: String,
    val durationMs: Long,
    /** Exit code of the call; null when it did not run. */
    val exitCode: Int?,
    val prevHash: String,
    val hash: String
)
