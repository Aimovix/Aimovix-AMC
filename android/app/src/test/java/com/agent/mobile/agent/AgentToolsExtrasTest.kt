package com.agent.mobile.agent

import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.CommandSecurityFilter
import com.agent.mobile.security.RiskLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentToolsExtrasTest {

    private fun call(name: String, vararg args: Pair<String, String>) = ToolCall(name = name, arguments = mapOf(*args))

    @Test
    fun excludedToolsAreLeftOutOfEveryProviderSchema() {
        val excluded = setOf(AgentTools.DELEGATE_TASK)
        val openAi = AgentTools.openAiTools(excluded)
        val claude = AgentTools.claudeTools(excluded)
        val gemini = AgentTools.geminiFunctionDeclarations(excluded)
        val expected = AgentTools.ALL.size - 1
        assertEquals(expected, openAi.length())
        assertEquals(expected, claude.length())
        assertEquals(expected, gemini.length())
        assertFalse((0 until claude.length()).any { claude.getJSONObject(it).getString("name") == AgentTools.DELEGATE_TASK })
    }

    @Test
    fun newToolsHaveSensibleRisk() {
        assertEquals(RiskLevel.MEDIUM, AgentTools.assess(call(AgentTools.WEB_SEARCH, "query" to "weather berlin")).level)
        assertTrue(AgentTools.assess(call(AgentTools.WEB_SEARCH, "query" to " ")).isBlocked)
        assertEquals(RiskLevel.LOW, AgentTools.assess(call(AgentTools.DELEGATE_TASK, "task" to "research")).level)
        assertEquals(RiskLevel.LOW, AgentTools.assess(call(AgentTools.SEARCH_HISTORY, "query" to "vacation")).level)
        assertEquals(RiskLevel.MEDIUM, AgentTools.assess(call(AgentTools.SEND_FILE, "path" to "~/report.pdf")).level)
        assertEquals(RiskLevel.HIGH, AgentTools.assess(call(AgentTools.SEND_FILE, "path" to "~/.ssh/id_ed25519")).level)
        assertTrue(AgentTools.assess(call(AgentTools.SEND_FILE, "path" to "~/.termux_agent_token")).isBlocked)
    }

    @Test
    fun autonomousPresetRunsTheNewToolsWithoutPrompts() {
        CommandSecurityFilter.setCustomRules(emptyList(), emptyList(), false)
        listOf(
            call(AgentTools.WEB_SEARCH, "query" to "x"),
            call(AgentTools.DELEGATE_TASK, "task" to "x"),
            call(AgentTools.SEARCH_HISTORY, "query" to "x"),
            call(AgentTools.SEND_FILE, "path" to "photo.jpg")
        ).forEach {
            assertFalse(it.name, CommandSecurityFilter.shouldRequireApproval(AgentTools.assess(it), SecurityPreset.AUTONOMOUS))
        }
        assertTrue(
            CommandSecurityFilter.shouldRequireApproval(
                AgentTools.assess(call(AgentTools.SEND_FILE, "path" to "~/.ssh/id_rsa")), SecurityPreset.AUTONOMOUS
            )
        )
    }

    @Test
    fun describeAndPreview() {
        assertEquals("web_search: termux api", AgentTools.describe(call(AgentTools.WEB_SEARCH, "query" to "termux api")))
        assertEquals("send_file ~/a.png", AgentTools.describe(call(AgentTools.SEND_FILE, "path" to "~/a.png")))
        val preview = AgentTools.approvalPreview(call(AgentTools.DELEGATE_TASK, "task" to "Compare the logs", "context" to "in ~/logs"))
        assertTrue(preview.contains("Compare the logs"))
        assertTrue(preview.contains("Context: in ~/logs"))
    }

    @Test
    fun promptModesAndPersona() {
        val remote = AgentPrompts.build(memory = "", mode = RunMode.REMOTE, maxSteps = 30, persona = "Be brief.", skills = "- weather: forecast (~/.amc/skills/weather/SKILL.md)")
        assertTrue(remote.contains("MODE: REMOTE CHAT via Telegram"))
        assertTrue(remote.contains("### PERSONA AND STANDING INSTRUCTIONS"))
        assertTrue(remote.contains("Be brief."))
        assertTrue(remote.contains("- weather: forecast"))
        val sub = AgentPrompts.build(memory = "", mode = RunMode.SUBAGENT, maxSteps = 20)
        assertTrue(sub.contains("MODE: SUB-AGENT"))
        assertFalse(sub.contains("### PERSONA"))
        assertTrue(AgentPrompts.SYSTEM_PROMPT.contains("delegate_task"))
    }
}
