package com.agent.mobile.audit

import androidx.room.withTransaction
import com.agent.mobile.data.storage.db.AppDatabase
import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** How a tool call was decided. Stored as text in [AuditEventEntity.decision]. */
enum class AuditDecision(val label: String) {
    /** Ran without asking: the preset allows it. */
    ALLOWED("allowed"),
    /** Unattended mode: allowed by a category rule instead of asking. */
    AUTO_ALLOWED("auto-allowed"),
    /** Unattended mode: refused by a category rule or the injection guard; the model got a hint. */
    AUTO_DENIED("auto-denied"),
    /** Never allowed (security block, the floor). */
    BLOCKED("blocked"),
    /** The user approved it. */
    APPROVED("approved by user"),
    /** The user rejected it or did not answer in time. */
    REJECTED("rejected by user"),
    /** Waiting for the user's answer (background run parked). */
    PARKED("parked"),
    /** Needed an approval nobody could give in this run; skipped. */
    SKIPPED("skipped"),
    /** Not run for another reason (loop protection, invalid arguments, unknown tool, run limit). */
    NOT_RUN("not run"),
    /** A note about the run itself (start, end, limit reached, retention). */
    INFO("info");

    companion object {
        fun of(value: String): AuditDecision? = entries.firstOrNull { it.name == value }
    }
}

/** What the engine knows about one tool call; [AuditLogger] adds time, chain and hash. */
data class AuditEntry(
    val runId: String,
    val sessionId: String?,
    val trigger: String,
    val mode: String,
    val preset: String,
    val tool: String,
    val app: String? = null,
    val arguments: String = "{}",
    val decision: AuditDecision,
    val rule: String,
    val reason: String,
    val riskLevel: String = "",
    val resultSummary: String = "",
    val durationMs: Long = 0L,
    val exitCode: Int? = null
)

/** Result of [AuditLogger.verify]. */
data class ChainCheck(val ok: Boolean, val checked: Int, val brokenAtId: Long? = null, val problem: String = "") {
    val summary: String
        get() = if (ok) "Audit chain intact ($checked entries checked)."
        else "Audit chain broken at entry $brokenAtId: $problem"
}

/**
 * Writes the tamper-evident audit log. Each entry's hash covers its fields and the previous
 * entry's hash; the read-last-then-insert happens in one database transaction, so concurrent
 * runs (chat, background worker, Telegram) still form one valid chain.
 *
 * Retention deletes the oldest entries; the oldest remaining entry then anchors the chain (its
 * prevHash is accepted as given) and an INFO entry records the deletion.
 */
class AuditLogger(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val dao = db.auditEventDao()

    suspend fun log(entry: AuditEntry): AuditEventEntity = db.withTransaction {
        val prev = dao.last()?.hash ?: GENESIS
        val unsigned = AuditEventEntity(
            timestamp = clock(),
            runId = entry.runId,
            sessionId = entry.sessionId,
            origin = entry.trigger,
            mode = entry.mode,
            preset = entry.preset,
            tool = entry.tool.take(80),
            app = entry.app?.take(200),
            arguments = entry.arguments.take(MAX_ARGUMENTS_CHARS),
            decision = entry.decision.name,
            rule = entry.rule.take(120),
            reason = entry.reason.take(MAX_REASON_CHARS),
            riskLevel = entry.riskLevel,
            resultSummary = entry.resultSummary.take(MAX_RESULT_CHARS),
            durationMs = entry.durationMs,
            exitCode = entry.exitCode,
            prevHash = prev,
            hash = ""
        )
        val signed = unsigned.copy(hash = hashOf(unsigned))
        val id = dao.insert(signed)
        signed.copy(id = id)
    }

    /** Checks every entry's hash and the links between them, oldest first. */
    suspend fun verify(): ChainCheck {
        var checked = 0
        var expectedPrev: String? = null
        var after = 0L
        while (true) {
            val page = dao.pageAfter(after, PAGE)
            if (page.isEmpty()) break
            for (e in page) {
                if (expectedPrev != null && e.prevHash != expectedPrev) {
                    return ChainCheck(false, checked, e.id, "it does not link to the entry before it (an entry was removed or changed)")
                }
                if (hashOf(e) != e.hash) {
                    return ChainCheck(false, checked, e.id, "its content does not match its hash (the entry was changed)")
                }
                expectedPrev = e.hash
                checked++
                after = e.id
            }
        }
        return ChainCheck(true, checked)
    }

    /**
     * Deletes entries older than [retentionDays] and keeps at most [maxEntries]. Records how many
     * were removed. Returns that number.
     */
    suspend fun rotate(retentionDays: Int, maxEntries: Int = DEFAULT_MAX_ENTRIES): Int {
        val before = clock() - retentionDays.coerceAtLeast(1) * DAY_MS
        val removed = db.withTransaction {
            // A prefix by id only: a clock set back must not punch a hole into the chain.
            val byAge = dao.lastIdBefore(before) ?: 0L
            val bySize = (dao.maxId() ?: 0L) - maxEntries.coerceAtLeast(100)
            val upTo = maxOf(byAge, bySize)
            if (upTo > 0) dao.deleteUpTo(upTo) else 0
        }
        // The legacy command_audits table follows the same retention (#38).
        db.commandAuditDao().deleteBefore(before)
        if (removed > 0) {
            log(
                AuditEntry(
                    runId = "retention", sessionId = null, trigger = "SYSTEM", mode = "-", preset = "-",
                    tool = "audit_retention", decision = AuditDecision.INFO, rule = "retention:${retentionDays}d",
                    reason = "Removed $removed entries older than $retentionDays days or beyond $maxEntries entries. " +
                        "The oldest remaining entry anchors the chain."
                )
            )
        }
        return removed
    }

    fun recent(limit: Int = 500): Flow<List<AuditEventEntity>> = dao.recent(limit)

    suspend fun forRun(runId: String): List<AuditEventEntity> = dao.forRun(runId)

    /** Every entry of a chat session (a background task and its continuations share one). */
    suspend fun forSession(sessionId: String): List<AuditEventEntity> = dao.forSession(sessionId)

    suspend fun since(since: Long): List<AuditEventEntity> = dao.since(since)

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
        const val DEFAULT_RETENTION_DAYS = 90
        const val DEFAULT_MAX_ENTRIES = 50_000
        const val MAX_ARGUMENTS_CHARS = 2_000
        const val MAX_REASON_CHARS = 600
        const val MAX_RESULT_CHARS = 500
        private const val PAGE = 500
        private const val DAY_MS = 24L * 60 * 60_000

        /** SHA-256 over every field except id and hash, in a fixed order with separators. */
        fun hashOf(e: AuditEventEntity): String {
            val canonical = listOf(
                e.prevHash, e.timestamp.toString(), e.runId, e.sessionId.orEmpty(), e.origin, e.mode, e.preset,
                e.tool, e.app.orEmpty(), e.arguments, e.decision, e.rule, e.reason, e.riskLevel, e.resultSummary,
                e.durationMs.toString(), e.exitCode?.toString() ?: "-"
            ).joinToString("\u001F")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
