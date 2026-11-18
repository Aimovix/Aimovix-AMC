package com.agent.mobile.audit

import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Audit log exports: JSONL (one entry per line, with hashes) and readable Markdown, plus grouping by run. */
object AuditExport {

    /** One run in the timeline: its entries oldest first. */
    data class RunGroup(val runId: String, val events: List<AuditEventEntity>) {
        val first: AuditEventEntity get() = events.first()
        val last: AuditEventEntity get() = events.last()
        val trigger: String get() = first.origin
        fun count(decision: AuditDecision): Int = events.count { it.decision == decision.name }
    }

    /** Groups [events] by run, newest run first; entries inside a run oldest first. */
    fun groupByRun(events: List<AuditEventEntity>): List<RunGroup> =
        events.groupBy { it.runId }
            .map { (runId, list) -> RunGroup(runId, list.sortedBy { it.id }) }
            .sortedByDescending { it.last.id }

    fun toJsonl(events: List<AuditEventEntity>): String = events.sortedBy { it.id }.joinToString("\n") { e ->
        JSONObject().apply {
            put("id", e.id)
            put("time", iso(e.timestamp))
            put("timestamp", e.timestamp)
            put("run_id", e.runId)
            put("session_id", e.sessionId ?: JSONObject.NULL)
            put("trigger", e.origin)
            put("mode", e.mode)
            put("preset", e.preset)
            put("tool", e.tool)
            put("app", e.app ?: JSONObject.NULL)
            put("arguments", runCatching { JSONObject(e.arguments) }.getOrElse { e.arguments })
            put("decision", e.decision)
            put("rule", e.rule)
            put("reason", e.reason)
            put("risk_level", e.riskLevel)
            put("result", e.resultSummary)
            put("duration_ms", e.durationMs)
            put("exit_code", e.exitCode ?: JSONObject.NULL)
            put("prev_hash", e.prevHash)
            put("hash", e.hash)
        }.toString()
    } + if (events.isEmpty()) "" else "\n"

    fun toMarkdown(events: List<AuditEventEntity>, check: ChainCheck?): String = buildString {
        append("# AMC audit log\n\n")
        append("- Exported: ").append(iso(System.currentTimeMillis())).append('\n')
        append("- Entries: ").append(events.size).append('\n')
        if (check != null) append("- Chain: ").append(check.summary).append('\n')
        append('\n')
        for (run in groupByRun(events)) {
            append("## Run ").append(run.runId.take(24)).append(" · ").append(run.trigger.lowercase())
            append(" · ").append(time(run.first.timestamp)).append(" – ").append(time(run.last.timestamp)).append("\n\n")
            append("| Time | Tool | Decision | Rule | Reason | Result |\n|---|---|---|---|---|---|\n")
            for (e in run.events) {
                append("| ").append(time(e.timestamp))
                append(" | ").append(cell(e.tool + (e.app?.let { " ($it)" } ?: "")))
                append(" | ").append(AuditDecision.of(e.decision)?.label ?: e.decision)
                append(" | ").append(cell(e.rule))
                append(" | ").append(cell(e.reason.take(160)))
                append(" | ").append(cell(e.resultSummary.take(120)))
                if (e.exitCode != null) append(" (exit ").append(e.exitCode).append(')')
                append(" |\n")
            }
            append('\n')
        }
    }

    private fun cell(text: String) = text.replace("|", "\\|").replace('\n', ' ').trim()

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))

    fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
