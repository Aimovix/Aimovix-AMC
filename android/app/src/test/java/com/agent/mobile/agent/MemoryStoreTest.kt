package com.agent.mobile.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(maxChars: Int = MemoryStore.DEFAULT_MAX_CHARS) = MemoryStore(File(tmp.root, "agent/MEMORY.md"), maxChars)

    @Test
    fun rememberForgetAndPersist() {
        val s = store()
        assertEquals("", s.asPromptText())
        s.remember("The user's name is Messa")
        s.remember("Prefers answers in German")
        assertEquals("Already in memory.", s.remember("the user's name is messa"))
        assertEquals(2, s.entries().size)
        assertTrue(s.entries()[0].matches(Regex("""- \[\d{4}-\d{2}-\d{2}] The user's name is Messa""")))

        // A new instance reads the same file.
        assertEquals(2, store().entries().size)

        assertEquals(1, s.forget("german"))
        assertEquals(0, s.forget("does not exist"))
        assertEquals(1, s.entries().size)
    }

    @Test
    fun oldestEntriesAreDroppedAtTheLimit() {
        val s = store(maxChars = 200)
        repeat(10) { s.remember("fact number $it with some padding text") }
        assertTrue(s.asPromptText().length <= 200)
        assertTrue(s.asPromptText().contains("fact number 9"))
        assertFalse(s.asPromptText().contains("fact number 0 "))
    }

    @Test
    fun replaceAllNormalisesLines() {
        val s = store()
        s.replaceAll("first\n\n- second\n")
        assertEquals(listOf("- first", "- second"), s.entries())
        s.clear()
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun promptIncludesMemoryAndMode() {
        val prompt = AgentPrompts.build(memory = "- [2026-01-01] likes tea", unattended = true, maxSteps = 25)
        assertTrue(prompt.startsWith(AgentPrompts.SYSTEM_PROMPT))
        assertTrue(prompt.contains("likes tea"))
        assertTrue(prompt.contains("UNATTENDED"))
        assertTrue(AgentPrompts.heartbeatPrompt("").contains(AgentPrompts.HEARTBEAT_OK))
    }
}
