package com.agent.mobile.agent

import com.agent.mobile.data.model.SecurityPreset
import com.agent.mobile.data.model.ToolCall
import com.agent.mobile.security.CommandSecurityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApprovalRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cmd(c: String) = ToolCall(name = AgentTools.EXECUTE_COMMAND, arguments = mapOf("command" to c))

    @Test
    fun anAlwaysAllowedActionCoversExactlyThatAction() {
        val rules = ApprovalRules(tmp.newFile())
        val kill = cmd("kill 4242")
        val a = AgentTools.assess(kill)
        assertTrue("kill asks in Autonomous mode", CommandSecurityFilter.shouldRequireApproval(a, SecurityPreset.AUTONOMOUS))

        assertTrue(rules.remember(kill, a))
        assertTrue(rules.allows(kill.copy(id = "another-call"), a))
        assertFalse("A different action is not covered", rules.allows(cmd("kill 1"), AgentTools.assess(cmd("kill 1"))))
        assertEquals(1, rules.count())
    }

    @Test
    fun thirdPartyAndPrivacyActionsAreNeverRemembered() {
        val rules = ApprovalRules(tmp.newFile())
        val sms = cmd("termux-sms-send -n 123 hi")
        val a = AgentTools.assess(sms)
        assertFalse(ApprovalRules.isRememberable(sms, a))
        assertFalse(rules.remember(sms, a))
        assertFalse(rules.allows(sms, a))
        val screen = ToolCall(name = PhoneTools.SCREEN_TAP, arguments = mapOf("id" to "3"))
        assertFalse(ApprovalRules.isRememberable(screen, com.agent.mobile.security.SecurityAssessment(com.agent.mobile.security.RiskLevel.HIGH, "x")))
    }

    @Test
    fun rulesSurviveANewInstance() {
        val file = tmp.newFile()
        val c = cmd("chmod -R 755 ~/site")
        ApprovalRules(file).remember(c, AgentTools.assess(c))
        assertTrue(ApprovalRules(file).allows(c, AgentTools.assess(c)))
        ApprovalRules(file).clear()
        assertFalse(ApprovalRules(file).allows(c, AgentTools.assess(c)))
    }

    @Test
    fun anExpiredApprovalResumesWithAClearNote() {
        val note = ResumeRequest.expiredNote("kill 4242")
        assertTrue(note.startsWith("[AMC]"))
        assertTrue(note.contains("in time"))
        assertTrue(note.contains("kill 4242"))
    }
}
