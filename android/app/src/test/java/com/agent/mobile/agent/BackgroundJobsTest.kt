package com.agent.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the generated job commands in a real bash with a temporary HOME. */
class BackgroundJobsTest {

    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var home: File

    @Before
    fun setUp() {
        assumeTrue("needs bash and /proc", File("/bin/bash").exists() && File("/proc/self/stat").exists())
        home = tmp.newFolder("home")
    }

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("bash", "-c", cmd).apply { environment()["HOME"] = home.path }.redirectErrorStream(true).start()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText()
    }

    private fun writeScript(id: String, body: String) {
        File(home, ".amc/jobs").mkdirs()
        File(home, ".amc/jobs/$id.sh").writeText(body + "\n")
    }

    @Test
    fun aJobRunsDetachedAndReportsOutputAndExitCode() {
        val id = BackgroundJobs.newId()
        writeScript(id, "echo one; sleep 1; echo \"two 'quoted'\"; exit 3")
        val started = sh(BackgroundJobs.startCommand(id))
        assertTrue(started, started.startsWith("started pid "))

        var offset = 0L
        val seen = StringBuilder()
        var status: BackgroundJobs.Status? = null
        repeat(10) {
            status = BackgroundJobs.parseStatus(sh(BackgroundJobs.statusCommand(id, offset, 3)))
            seen.append(status!!.output)
            offset += status!!.output.toByteArray().size
            if (status!!.state == "exited") return@repeat
        }
        assertEquals("exited", status!!.state)
        assertEquals(3, status!!.exitCode)
        assertEquals("one\ntwo 'quoted'\n", seen.toString())
        assertTrue(BackgroundJobs.describeStatus(id, 0, status!!).contains("exit code 3"))
    }

    @Test
    fun stopEndsTheJobAndItsChildren() {
        val id = BackgroundJobs.newId()
        writeScript(id, "sleep 60 & sleep 60")
        sh(BackgroundJobs.startCommand(id))
        Thread.sleep(500)
        val pid = File(home, ".amc/jobs/$id.pid").readText().trim()
        assertTrue(sh(BackgroundJobs.statusCommand(id, 0, 0)).contains("STATE running"))

        assertTrue(sh(BackgroundJobs.stopCommand(id)).contains("stopped"))
        assertTrue("The job process is gone", sh("kill -0 $pid 2>/dev/null && echo alive || echo dead").contains("dead"))
        val status = BackgroundJobs.parseStatus(sh(BackgroundJobs.statusCommand(id, 0, 0)))
        assertNotNull(status)
        assertFalse(status!!.state == "running")
        assertTrue(sh(BackgroundJobs.listCommand()).contains(id))
    }

    @Test
    fun unknownJobsAndIds() {
        assertNull(BackgroundJobs.parseStatus(sh(BackgroundJobs.statusCommand("jnope1", 0, 0))))
        assertTrue(BackgroundJobs.isValidId(BackgroundJobs.newId()))
        assertFalse(BackgroundJobs.isValidId("j1; rm -rf ~"))
        assertTrue(sh(BackgroundJobs.listCommand()).contains("No background jobs."))
    }

    @Test
    fun jobsFollowTheCommandRules() {
        val sms = AgentTools.assess(com.agent.mobile.data.model.ToolCall(name = AgentTools.JOB_START, arguments = mapOf("command" to "termux-sms-send -n 1 hi")))
        assertTrue(sms.alwaysConfirm)
        val wipe = AgentTools.assess(com.agent.mobile.data.model.ToolCall(name = AgentTools.JOB_START, arguments = mapOf("command" to "rm -rf ~")))
        assertTrue(wipe.isBlocked)
        val bad = AgentTools.assess(com.agent.mobile.data.model.ToolCall(name = AgentTools.JOB_STOP, arguments = mapOf("job_id" to "x; reboot")))
        assertTrue(bad.isBlocked)
    }
}
