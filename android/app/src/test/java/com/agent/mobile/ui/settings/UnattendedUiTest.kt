package com.agent.mobile.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import com.agent.mobile.audit.AuditDecision
import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import com.agent.mobile.ui.settings.components.AuditFilter
import com.agent.mobile.ui.settings.components.UnattendedOptInDialog
import com.agent.mobile.ui.settings.components.UnattendedSettingsPanel
import com.agent.mobile.ui.settings.components.filterAudit
import com.agent.mobile.unattended.UnattendedCategory
import com.agent.mobile.unattended.UnattendedSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UnattendedUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun optInShowsCategoriesAndFloorAndBothAnswersAreTheSameSize() {
        var accepted = 0
        var declined = 0
        compose.setContent { UnattendedOptInDialog(onAccept = { accepted++ }, onDecline = { declined++ }) }
        compose.onNodeWithText("Send messages", substring = true).assertExists()
        compose.onNodeWithText("Payments and purchases", substring = true).assertExists()
        compose.onNodeWithText("Banking, payment, password", substring = true).assertExists()
        val keep = compose.onNodeWithText("Keep current mode").fetchSemanticsNode().boundsInRoot
        val turnOn = compose.onNodeWithText("Turn on unattended mode").fetchSemanticsNode().boundsInRoot
        // Text bounds differ with the label; the buttons around them share width and height.
        val keepButton = compose.onNodeWithText("Keep current mode").fetchSemanticsNode().parent!!.boundsInRoot
        val onButton = compose.onNodeWithText("Turn on unattended mode").fetchSemanticsNode().parent!!.boundsInRoot
        assertEquals(keepButton.width, onButton.width, 1f)
        assertEquals(keepButton.height, onButton.height, 1f)
        assertTrue(keep.height > 0 && turnOn.height > 0)
        compose.onNodeWithText("Keep current mode").performClick()
        assertEquals(1, declined)
        compose.onNodeWithText("Turn on unattended mode").performClick()
        assertEquals(1, accepted)
    }

    @Test
    fun categoriesCanBeSwitchedPerAction() {
        var saved: UnattendedSettings? = null
        compose.setContent {
            var s by remember { mutableStateOf(UnattendedSettings()) }
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                UnattendedSettingsPanel(s, onChange = { s = it; saved = it }, onTurnOff = {})
            }
        }
        compose.onNodeWithContentDescription("Phone calls: refused").performScrollTo().assertIsOff().performClick()
        assertTrue(saved!!.isAllowed(UnattendedCategory.CALLS))
        compose.onNodeWithContentDescription("Send messages: allowed").performScrollTo().assertIsOn().performClick()
        assertFalse(saved!!.isAllowed(UnattendedCategory.SEND_MESSAGES))
    }

    @Test
    fun auditFiltersByDecisionToolAndApp() {
        fun e(id: Long, tool: String, decision: AuditDecision, app: String? = null) = AuditEventEntity(
            id = id, timestamp = id, runId = "r", sessionId = null, origin = "USER", mode = "INTERACTIVE", preset = "TURBO",
            tool = tool, app = app, arguments = "{}", decision = decision.name, rule = "x", reason = "", riskLevel = "",
            resultSummary = "", durationMs = 0, exitCode = null, prevHash = "", hash = ""
        )
        val events = listOf(
            e(1, "execute_command", AuditDecision.ALLOWED),
            e(2, "screen_tap", AuditDecision.AUTO_ALLOWED, "com.whatsapp"),
            e(3, "screen_tap", AuditDecision.AUTO_DENIED, "com.shop"),
            e(4, "execute_command", AuditDecision.BLOCKED)
        )
        assertEquals(listOf(3L), filterAudit(events, AuditFilter.AUTO_DENIED, null, "").map { it.id })
        assertEquals(listOf(2L, 3L), filterAudit(events, AuditFilter.ALL, "screen_tap", "").map { it.id })
        assertEquals(listOf(2L), filterAudit(events, AuditFilter.ALL, null, "whatsapp").map { it.id })
        assertEquals(listOf(4L), filterAudit(events, AuditFilter.BLOCKED, "execute_command", "").map { it.id })
    }
}
