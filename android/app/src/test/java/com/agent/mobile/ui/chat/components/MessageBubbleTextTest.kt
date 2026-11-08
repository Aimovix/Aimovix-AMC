package com.agent.mobile.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBubbleTextTest {

    @Test
    fun decorativeLeadingSymbolsAreRemovedFromSystemNotes() {
        assertEquals("Execution canceled by the user.", cleanSystemText("🛑 Execution canceled by the user."))
        assertEquals("AI API error: timeout", cleanSystemText("⚠️ AI API error: timeout"))
        assertEquals("Step limit of 30 reached.", cleanSystemText("ℹ️ Step limit of 30 reached."))
        assertEquals("Security block: rm was blocked.", cleanSystemText("🛡️ Security block: rm was blocked."))
    }

    @Test
    fun plainWordingIsUnchanged() {
        assertEquals("Heartbeat: all quiet", cleanSystemText("Heartbeat: all quiet"))
        assertEquals("Memory saved (3 entries)", cleanSystemText("Memory saved (3 entries)"))
    }

    @Test
    fun symbolsInsideTheTextStayUntouched() {
        assertEquals("Battery at 80% ⚡ charging", cleanSystemText("Battery at 80% ⚡ charging"))
    }
}
