package com.agent.mobile.channel.telegram

import org.junit.Assert.*
import org.junit.Test

class TelegramFormatTest {

    @Test
    fun convertsCommonMarkdownToTelegramHtml() {
        val md = "## Result\n**Battery:** 80% <ok> & charging\n- item `a<b`\n```\nls -la\n```\n[docs](https://example.com/x?a=1&b=2)"
        val html = TelegramFormat.toHtml(md)
        assertTrue(html.contains("<b>Result</b>"))
        assertTrue(html.contains("<b>Battery:</b> 80% &lt;ok&gt; &amp; charging"))
        assertTrue(html.contains("• item <code>a&lt;b</code>"))
        assertTrue(html.contains("<pre>ls -la</pre>"))
        assertTrue(html.contains("<a href=\"https://example.com/x?a=1&amp;b=2\">docs</a>"))
    }

    @Test
    fun codeIsNotFormattedInside() {
        assertEquals("<code>**x**</code>", TelegramFormat.toHtml("`**x**`"))
    }

    @Test
    fun splitsLongTextBelowTheLimit() {
        val text = (1..400).joinToString("\n") { "Line $it with some words to make it longer" }
        val chunks = TelegramFormat.split(text, max = 1_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 1_000 })
        assertEquals(text.replace("\n", ""), chunks.joinToString("") { it }.replace("\n", ""))
    }
}
