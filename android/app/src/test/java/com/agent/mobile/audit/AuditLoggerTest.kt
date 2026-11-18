package com.agent.mobile.audit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.agent.PhoneTools
import com.agent.mobile.data.storage.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditLoggerTest {

    private lateinit var db: AppDatabase
    private var now = 1_000_000L
    private lateinit var logger: AuditLogger

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        logger = AuditLogger(db) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun entry(i: Int, decision: AuditDecision = AuditDecision.ALLOWED) = AuditEntry(
        runId = "run-${i / 10}", sessionId = "s", trigger = "SCHEDULE", mode = "UNATTENDED", preset = "UNATTENDED",
        tool = "execute_command", arguments = """{"command":"echo $i"}""", decision = decision,
        rule = "preset:TURBO", reason = "r$i", riskLevel = "LOW", resultSummary = "$i", durationMs = 5, exitCode = 0
    )

    @Test
    fun entriesFormAVerifiableChain() = runBlocking {
        val first = logger.log(entry(1))
        assertEquals(AuditLogger.GENESIS, first.prevHash)
        val second = logger.log(entry(2))
        assertEquals(first.hash, second.prevHash)
        repeat(20) { logger.log(entry(it + 3)) }
        val check = logger.verify()
        assertTrue(check.summary, check.ok)
        assertEquals(22, check.checked)
    }

    @Test
    fun changingAnEntryIsDetected() = runBlocking {
        repeat(5) { logger.log(entry(it)) }
        db.openHelper.writableDatabase.execSQL("UPDATE audit_events SET decision = 'ALLOWED', reason = 'nothing to see' WHERE id = 3")
        val check = logger.verify()
        assertFalse(check.ok)
        assertEquals(3L, check.brokenAtId)
    }

    @Test
    fun removingAnEntryInTheMiddleIsDetected() = runBlocking {
        repeat(5) { logger.log(entry(it)) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM audit_events WHERE id = 3")
        val check = logger.verify()
        assertFalse(check.ok)
        assertEquals(4L, check.brokenAtId)
    }

    @Test
    fun retentionRemovesAPrefixAndKeepsTheChainValid() = runBlocking {
        repeat(10) { logger.log(entry(it)) }
        now += 100L * 24 * 60 * 60_000
        repeat(5) { logger.log(entry(100 + it)) }
        val removed = logger.rotate(retentionDays = 30)
        assertEquals(10, removed)
        val all = logger.since(0)
        assertEquals("5 recent entries plus the retention note", 6, all.size)
        assertTrue(all.last().reason.contains("Removed 10"))
        assertTrue(logger.verify().ok)

        // The size limit also only cuts the oldest end.
        repeat(300) { logger.log(entry(200 + it)) }
        logger.rotate(retentionDays = 365, maxEntries = 100)
        assertTrue(logger.since(0).size <= 102)
        assertTrue(logger.verify().ok)
    }

    @Test
    fun redactionHidesSecretsAndOneTimeCodes() {
        val password = AuditRedactor.arguments(PhoneTools.SCREEN_TYPE, mapOf("target" to "e4", "text" to "hunter2"), secret = true)
        assertFalse(password.contains("hunter2"))
        assertEquals("e4", JSONObject(password).getString("target"))

        val otp = AuditRedactor.arguments(PhoneTools.SCREEN_TYPE, mapOf("target" to "e9", "text" to "482913"), secret = false)
        assertFalse(otp.contains("482913"))
        val reply = AuditRedactor.arguments(PhoneTools.NOTIFICATION_REPLY, mapOf("id" to "n1", "text" to "Your code is 7731"), secret = false)
        assertFalse(reply.contains("7731"))

        val shell = AuditRedactor.scrub("curl -H 'Authorization: Bearer abcdef1234567890' https://x.io?api_key=SECRET123 && export TOKEN=zzz")
        assertFalse(shell.contains("abcdef1234567890"))
        assertFalse(shell.contains("SECRET123"))
        assertFalse(shell.contains("zzz"))
        assertTrue(AuditRedactor.scrub("key AIzaSyA1234567890abcdefghijklmnop").contains(AuditRedactor.MASK))

        val long = AuditRedactor.arguments("write_file", mapOf("path" to "a.txt", "content" to "x".repeat(5_000)), secret = false)
        assertTrue(long.length < 600)
        assertTrue(long.contains("5000 chars"))
    }

    @Test
    fun exportsJsonlAndMarkdown() = runBlocking {
        logger.log(entry(1))
        logger.log(entry(2, AuditDecision.AUTO_DENIED))
        val events = logger.since(0)
        val jsonl = AuditExport.toJsonl(events).trim().lines()
        assertEquals(2, jsonl.size)
        val line = JSONObject(jsonl[1])
        assertEquals("AUTO_DENIED", line.getString("decision"))
        assertEquals(events[1].hash, line.getString("hash"))
        assertEquals("echo 2", line.getJSONObject("arguments").getString("command"))
        val md = AuditExport.toMarkdown(events, logger.verify())
        assertTrue(md.contains("Audit chain intact"))
        assertTrue(md.contains("auto-denied"))
        assertEquals(1, AuditExport.groupByRun(events).size)
    }
}
