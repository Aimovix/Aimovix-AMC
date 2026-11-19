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

    /** #24: facts stay, lessons rotate. */
    @Test
    fun lessonsRotateBeforeFacts() {
        val s = store(maxChars = 300)
        s.remember("The user's phone is a Pixel 8")
        s.remember("Home folder is /data/data/com.termux/files/home")
        repeat(5) { s.rememberLesson("Problem: step $it failed. Cause: cause $it. Works: approach $it") }
        assertTrue(s.asPromptText().length <= 300)
        assertTrue(s.entries().any { it.contains("Pixel 8") })
        assertTrue(s.entries().any { it.contains("Home folder") })
        assertTrue(s.lessons().isNotEmpty())
        assertTrue(s.lessons().last().contains("approach 4"))
        assertFalse(s.asPromptText().contains("approach 0"))
        assertTrue(s.lessons().all { it.contains(MemoryStore.LESSON_TAG) })
    }

    @Test
    fun aLessonNeverPushesOutAFact() {
        val s = store(maxChars = 120)
        s.remember("fact one with quite a lot of padding text here")
        s.remember("fact two with some padding")
        assertEquals("Not saved: the memory is full of facts.", s.rememberLesson("Problem: x failed. Cause: y. Works: z"))
        assertEquals(2, s.entries().size)
        assertTrue(s.lessons().isEmpty())
    }

    @Test
    fun aNewLessonAboutTheSameProblemReplacesTheOldOne() {
        val s = store()
        s.rememberLesson("Problem: pip install fails. Cause: managed env. Works: use pkg")
        s.rememberLesson("Problem: pip install fails. Cause: managed env. Works: use a venv")
        assertEquals(1, s.lessons().size)
        assertTrue(s.lessons().single().contains("venv"))
        repeat(MemoryStore.MAX_LESSONS + 3) { s.rememberLesson("Problem: case $it. Works: way $it") }
        assertEquals(MemoryStore.MAX_LESSONS, s.lessons().size)
        assertTrue(s.lessons().last().contains("way ${MemoryStore.MAX_LESSONS + 2}"))
    }

    @Test
    fun reflectorOnlyAcceptsLessonsInTheRequiredForm() {
        assertNull(LessonReflector.parse("NONE"))
        assertNull(LessonReflector.parse("All good, nothing to learn."))
        assertNull(LessonReflector.parse("Problem: something"))
        assertEquals(
            "Problem: a. Cause: b. Works: c",
            LessonReflector.parse("Here is the lesson:\nProblem: a.  Cause: b.\nWorks: c")
        )
        assertTrue(LessonReflector.parse("Problem: " + "x".repeat(1000) + " Works: y")!!.length <= LessonReflector.MAX_LESSON_CHARS)

        fun reflect(success: Boolean = true, status: String? = null, failed: Int = 0, budget: Boolean = false, parked: Boolean = false, stopped: Boolean = false) =
            LessonReflector.shouldReflect(success, status, failed, budget, parked, stopped, providerFailed = false)
        assertFalse("A clean run teaches nothing", reflect())
        assertTrue(reflect(status = "partial"))
        assertTrue(reflect(success = false, status = "blocked"))
        assertTrue(reflect(failed = LessonReflector.MIN_FAILED_STEPS))
        assertFalse("A slot that only ran out of budget continues", reflect(success = false, budget = true))
        assertFalse("A parked run is not over", reflect(success = false, parked = true))
        assertFalse("Stopped by the user", reflect(success = false, stopped = true))
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
