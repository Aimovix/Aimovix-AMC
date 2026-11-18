package com.agent.mobile.audit

import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RunReportTest {

    private var id = 0L
    private fun e(tool: String, decision: AuditDecision, args: String, rule: String = "preset:TURBO", exit: Int? = 0, app: String? = null, reason: String = "") =
        AuditEventEntity(
            id = ++id, timestamp = id, runId = "r", sessionId = "s", origin = "SCHEDULE", mode = "UNATTENDED", preset = "UNATTENDED",
            tool = tool, app = app, arguments = args, decision = decision.name, rule = rule, reason = reason, riskLevel = "",
            resultSummary = "out", durationMs = 1, exitCode = if (decision == AuditDecision.AUTO_DENIED || decision == AuditDecision.BLOCKED) null else exit,
            prevHash = "", hash = ""
        )

    @Test
    fun reportNamesChangesRefusalsAndErrors() {
        val events = listOf(
            e("write_file", AuditDecision.ALLOWED, """{"path":"~/report.md","content":"x"}"""),
            e("execute_command", AuditDecision.AUTO_ALLOWED, """{"command":"termux-sms-send -n 1 hi"}""", rule = "unattended:send_messages"),
            e("screen_tap", AuditDecision.AUTO_ALLOWED, """{"target":"e3"}""", rule = "unattended:app_control", app = "com.example.notes"),
            e("execute_command", AuditDecision.AUTO_DENIED, """{"command":"termux-telephony-call 1"}""", rule = "unattended:calls", reason = "Calls are refused"),
            e("execute_command", AuditDecision.BLOCKED, """{"command":"rm -rf /"}""", rule = "security:blocked", reason = "catastrophic"),
            e("execute_command", AuditDecision.ALLOWED, """{"command":"make"}""", exit = 2)
        )
        val report = RunReport.build("⏰ Nightly", "Build and report", "Built, one step refused.", success = true, events = events)
        assertEquals(RunReport.Outcome.PARTIAL, report.outcome)
        assertEquals(listOf("~/report.md"), report.filesChanged)
        assertEquals(listOf("com.example.notes"), report.appsOperated)
        assertEquals(1, report.messagesSent.size)
        assertTrue(report.autoDenied.single().contains("Calls are refused"))
        assertEquals(1, report.blocked.size)
        assertTrue(report.errors.single().contains("exit 2"))
        val text = report.toText()
        assertTrue(text.contains("partly done"))
        assertTrue(text.contains("Refused by the unattended rules (1)"))

        assertEquals(RunReport.Outcome.DONE, RunReport.build("t", "g", "ok", true, events.take(3)).outcome)
        assertEquals(RunReport.Outcome.BLOCKED, RunReport.build("t", "g", "x", false, events.take(1), "limit: 20 messages").outcome)
    }

    @Test
    fun dailySummaryCountsDecisionsAndListsRefusals() {
        assertNull(DailySummary.build(emptyList()))
        val events = listOf(
            e("execute_command", AuditDecision.ALLOWED, "{}"),
            e("execute_command", AuditDecision.AUTO_DENIED, "{}", reason = "Calls are refused"),
            e("screen_tap", AuditDecision.AUTO_ALLOWED, "{}"),
            e("execute_command", AuditDecision.ALLOWED, "{}", exit = 1)
        )
        val text = DailySummary.build(events)!!
        assertTrue(text.startsWith("1 run(s), 4 actions"))
        assertTrue(text.contains("auto-denied 1"))
        assertTrue(text.contains("Calls are refused"))
        assertTrue(text.contains("1 action(s) failed"))
    }
}
