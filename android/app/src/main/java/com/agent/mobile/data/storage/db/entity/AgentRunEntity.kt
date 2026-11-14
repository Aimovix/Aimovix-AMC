package com.agent.mobile.data.storage.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Checkpoint of one background run, keyed by its WorkManager id. When AMC is killed or the phone
 * reboots mid-run, WorkManager starts the same work again; a RUNNING record tells the worker to
 * continue the existing session instead of starting the task from scratch.
 */
@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val title: String,
    val kind: String,
    val status: String,
    val chain: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_PARKED = "PARKED"
        const val STATUS_CONTINUED = "CONTINUED"
    }
}
