package com.agent.mobile.audit

import com.agent.mobile.data.storage.db.entity.AuditEventEntity

/** The optional daily summary: runs, actions by decision, and what was refused or blocked. */
object DailySummary {
    /** Null when nothing happened (no notification then). */
    fun build(events: List<AuditEventEntity>): String? {
        val calls = events.filter { it.decision != AuditDecision.INFO.name && it.runId != "retention" }
        if (calls.isEmpty()) return null
        val runs = calls.map { it.runId.substringBefore("/sub-") }.distinct().size
        fun n(d: AuditDecision) = calls.count { it.decision == d.name }
        return buildString {
            append(runs).append(" run(s), ").append(calls.size).append(" actions in the last 24 hours.\n")
            append("Allowed ").append(n(AuditDecision.ALLOWED) + n(AuditDecision.APPROVED))
            append(" · auto-allowed ").append(n(AuditDecision.AUTO_ALLOWED))
            append(" · auto-denied ").append(n(AuditDecision.AUTO_DENIED))
            append(" · blocked ").append(n(AuditDecision.BLOCKED)).append('\n')
            val refused = calls.filter { it.decision == AuditDecision.AUTO_DENIED.name || it.decision == AuditDecision.BLOCKED.name }
            if (refused.isNotEmpty()) {
                append("\nRefused or blocked:\n")
                refused.take(5).forEach { append("• ").append(it.tool).append(": ").append(it.reason.take(120)).append('\n') }
                if (refused.size > 5) append("• … and ").append(refused.size - 5).append(" more (see the audit log)\n")
            }
            val failed = calls.count { (it.exitCode ?: 0) != 0 }
            if (failed > 0) append("\n").append(failed).append(" action(s) failed.")
        }.trim()
    }
}
