package com.agent.mobile.agent

import com.agent.mobile.data.storage.db.dao.AgentQueueDao
import com.agent.mobile.data.storage.db.entity.AgentRunEntity

/**
 * Checkpoints of runs that do not live in a WorkManager worker (the chat), so they survive the
 * app being closed, the process being killed or the phone rebooting (review finding #7). The
 * engine records start, progress and end; when the app goes away mid-run the task is handed to a
 * background run, and on the next start of AMC (app or boot) a run that was still RUNNING in a
 * process that no longer exists continues in the background with the restart note.
 */
interface RunCheckpointer {
    suspend fun started(runId: String, sessionId: String, title: String)
    suspend fun progressed(runId: String)
    suspend fun finished(runId: String, status: String)
    /** The app is going away while [runId] works: continue the same session in the background. */
    fun handOff(runId: String, sessionId: String, title: String)
}

/** Room-backed [RunCheckpointer]. [enqueue] schedules the background run that continues a session. */
class RoomRunCheckpointer(
    private val dao: AgentQueueDao,
    private val enqueue: (sessionId: String, title: String, note: String) -> Unit
) : RunCheckpointer {

    override suspend fun started(runId: String, sessionId: String, title: String) {
        dao.upsertRun(AgentRunEntity(id = runId, sessionId = sessionId, title = title.take(80), kind = KIND, status = AgentRunEntity.STATUS_RUNNING))
    }

    override suspend fun progressed(runId: String) {
        val run = dao.getRun(runId) ?: return
        if (run.status == AgentRunEntity.STATUS_RUNNING) dao.setRunStatus(runId, AgentRunEntity.STATUS_RUNNING, System.currentTimeMillis())
    }

    override suspend fun finished(runId: String, status: String) {
        dao.setRunStatus(runId, status, System.currentTimeMillis())
    }

    override fun handOff(runId: String, sessionId: String, title: String) {
        enqueue(sessionId, title, ResumeRequest.HANDOFF_NOTE)
    }

    /**
     * Continues chat runs that were RUNNING when their process died (not running now, recent).
     * Returns how many were continued.
     */
    suspend fun resumeInterrupted(activeRunIds: Set<String>, now: Long = System.currentTimeMillis()): Int {
        val stale = dao.runsWithStatus(AgentRunEntity.STATUS_RUNNING).filter {
            it.kind == KIND && it.id !in activeRunIds && now - it.updatedAt < RESTART_WINDOW_MS && it.sessionId.isNotBlank()
        }
        for (run in stale) {
            dao.setRunStatus(run.id, AgentRunEntity.STATUS_CONTINUED, now)
            enqueue(run.sessionId, run.title, ResumeRequest.RESTART_NOTE)
        }
        return stale.size
    }

    companion object {
        const val KIND = "interactive"
        /** Runs older than this are not continued after a restart (stale task). */
        const val RESTART_WINDOW_MS = 6L * 60 * 60_000
    }
}
