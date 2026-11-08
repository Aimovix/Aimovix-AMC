package com.agent.mobile.agent

import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.RiskLevel
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class AgentToolsTest {

    private fun call(name: String, vararg args: Pair<String, String>) = ToolCall(name = name, arguments = mapOf(*args))

    @Test
    fun everyProviderSchemaContainsEveryTool() {
        val names = AgentTools.ALL.map { it.name }.toSet()
        val openAi = AgentTools.openAiTools()
        val claude = AgentTools.claudeTools()
        val gemini = AgentTools.geminiFunctionDeclarations()
        assertEquals(names, (0 until openAi.length()).map { openAi.getJSONObject(it).getJSONObject("function").getString("name") }.toSet())
        assertEquals(names, (0 until claude.length()).map { claude.getJSONObject(it).getString("name") }.toSet())
        assertEquals(names, (0 until gemini.length()).map { gemini.getJSONObject(it).getString("name") }.toSet())

        val exec = (0 until claude.length()).map { claude.getJSONObject(it) }.first { it.getString("name") == AgentTools.EXECUTE_COMMAND }
        val required = exec.getJSONObject("input_schema").getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("command", required.getString(0))

        val list = (0 until gemini.length()).map { gemini.getJSONObject(it) }.first { it.getString("name") == AgentTools.LIST_SCHEDULED_TASKS }
        assertFalse("Gemini rejects empty parameter objects", list.has("parameters"))
    }

    @Test
    fun riskAssessment() {
        assertEquals(RiskLevel.LOW, AgentTools.assess(call(AgentTools.READ_FILE, "path" to "notes.txt")).level)
        assertEquals(RiskLevel.HIGH, AgentTools.assess(call(AgentTools.READ_FILE, "path" to "~/.ssh/id_rsa")).level)
        assertEquals(RiskLevel.MEDIUM, AgentTools.assess(call(AgentTools.WRITE_FILE, "path" to "proj/main.py", "content" to "print(1)")).level)
        assertEquals(RiskLevel.HIGH, AgentTools.assess(call(AgentTools.WRITE_FILE, "path" to "~/.bashrc", "content" to "x")).level)
        assertTrue(AgentTools.assess(call(AgentTools.WRITE_FILE, "path" to "/data/data/com.termux/files/home/.termux_agent_token", "content" to "x")).isBlocked)
        assertTrue(AgentTools.assess(call(AgentTools.WEB_FETCH, "url" to "file:///etc/passwd")).isBlocked)
        assertEquals(RiskLevel.MEDIUM, AgentTools.assess(call(AgentTools.WEB_FETCH, "url" to "https://example.com")).level)
        assertEquals(RiskLevel.LOW, AgentTools.assess(call(AgentTools.REMEMBER, "note" to "likes tea")).level)
        assertTrue(AgentTools.assess(call(AgentTools.EXECUTE_COMMAND, "command" to "rm -rf /")).isBlocked)
        assertTrue(AgentTools.assess(call("delete_everything")).isBlocked)
    }

    @Test
    fun describeIsReadable() {
        assertEquals("ls -la", AgentTools.describe(call(AgentTools.EXECUTE_COMMAND, "command" to " ls -la ")))
        assertEquals("write_file a.txt [5 chars]", AgentTools.describe(call(AgentTools.WRITE_FILE, "path" to "a.txt", "content" to "hello")))
        assertEquals(
            "schedule_task \"Weather\" in 5 min, every 120 min",
            AgentTools.describe(call(AgentTools.SCHEDULE_TASK, "title" to "Weather", "prompt" to "p", "delay_minutes" to "5", "repeat_every_minutes" to "120"))
        )
        assertTrue(AgentTools.approvalPreview(call(AgentTools.WRITE_FILE, "path" to "a.sh", "content" to "echo hi")).contains("echo hi"))
    }
}
