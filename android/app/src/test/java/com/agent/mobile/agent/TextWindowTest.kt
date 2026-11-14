package com.agent.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWindowTest {

    private val big = (1..2_000).joinToString("\n") { "line $it: " + "x".repeat(20) }

    @Test
    fun aSmallFileComesWhole() {
        assertEquals("a\nb", TextWindow.lines("a\nb\n", null, null))
    }

    @Test
    fun aLongFileCanBeReadWindowByWindow() {
        val first = TextWindow.lines(big, null, null)
        assertTrue(first.startsWith("[Lines 1-"))
        assertTrue(first.length < TextWindow.WINDOW_CHARS + 200)
        val next = Regex("offset=(\\d+)").find(first)!!.groupValues[1].toInt()

        // Walk the whole file: every line is reachable, nothing is skipped.
        var offset = 1
        val seen = mutableListOf<String>()
        while (true) {
            val w = TextWindow.lines(big, offset, null)
            seen += w.substringAfter("]\n").split('\n')
            val m = Regex("offset=(\\d+)").find(w.substringBefore('\n')) ?: break
            offset = m.groupValues[1].toInt()
        }
        assertTrue(next > 1)
        assertEquals(big.split('\n'), seen)
    }

    @Test
    fun offsetAndLimitSelectExactLines() {
        val w = TextWindow.lines(big, 1500, 3)
        assertTrue(w.startsWith("[Lines 1500-1502 of 2000"))
        assertEquals(listOf("line 1500", "line 1501", "line 1502"), w.substringAfter("]\n").split('\n').map { it.substringBefore(':') })
        assertTrue(TextWindow.lines(big, 3000, null).contains("only 2000 lines"))
    }

    @Test
    fun pagesAreReadByCharacterOffset() {
        val page = "p".repeat(12_000)
        val first = TextWindow.chars(page, null)
        assertTrue(first.startsWith("[Characters 0-5000 of 12000. Next: web_fetch with the same url and offset=5000"))
        val last = TextWindow.chars(page, 10_000)
        assertTrue(last.startsWith("[Characters 10000-12000 of 12000.]"))
        assertEquals("short", TextWindow.chars("short", null))
    }

    @Test
    fun editReplacesOnlyAUniqueMatch() {
        val code = "fun a() = 1\nfun b() = 1\n"
        val ok = TextWindow.replace(code, "fun b() = 1", "fun b() = 2", all = false)
        assertEquals("fun a() = 1\nfun b() = 2\n", ok.text)
        assertEquals(1, ok.count)

        val twice = TextWindow.replace(code, "= 1", "= 3", all = false)
        assertNull(twice.text)
        assertTrue(twice.error!!.contains("occurs 2 times"))
        assertEquals("fun a() = 3\nfun b() = 3\n", TextWindow.replace(code, "= 1", "= 3", all = true).text)

        val missing = TextWindow.replace(code, "fun c()", "x", all = false)
        assertNull(missing.text)
        assertFalse(missing.error.isNullOrBlank())
        // Regex characters are taken literally.
        assertEquals("a.b", TextWindow.replace("a(b)", "(b)", ".b", all = false).text)
    }
}
