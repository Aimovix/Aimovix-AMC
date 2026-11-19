package com.agent.mobile.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.agent.mobile.ui.chat.markdown.Markdown
import com.agent.mobile.ui.chat.markdown.MarkdownText
import com.agent.mobile.ui.chat.markdown.MdBlock
import com.agent.mobile.ui.chat.markdown.MdSpan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Teil E, E1: replies are rendered as Markdown; code blocks have their own copy button. */
@RunWith(RobolectricTestRunner::class)
class MarkdownTest {

    @get:Rule
    val compose = createComposeRule()

    private val reply = """
        # Setup
        Install it with **pkg** and check the [docs](https://example.org/docs).

        1. Update
        2. Install
           the package

        - one
        - two
        > Note: needs Wi-Fi.

        ---
        ```bash
        pkg update && pkg install git
        ```
        Done, see `~/app`.
    """.trimIndent()

    @Test
    fun parsesTheBlocksModelsUse() {
        val blocks = Markdown.parse(reply)
        assertEquals(MdBlock.Heading(1, "Setup"), blocks[0])
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals(MdBlock.ListBlock(ordered = true, items = listOf("Update", "Install the package"), start = 1), blocks[2])
        assertEquals(MdBlock.ListBlock(ordered = false, items = listOf("one", "two")), blocks[3])
        assertEquals(MdBlock.Quote("Note: needs Wi-Fi."), blocks[4])
        assertEquals(MdBlock.Rule, blocks[5])
        assertEquals(MdBlock.Code("bash", "pkg update && pkg install git"), blocks[6])
        assertEquals(MdBlock.Paragraph("Done, see `~/app`."), blocks[7])
    }

    @Test
    fun inlineSpansAndPlainText() {
        val spans = Markdown.inline("Use **pkg**, *not* apt; see `x` at [docs](https://example.org) or https://example.com/a.")
        assertTrue(MdSpan.Text("pkg", bold = true) in spans)
        assertTrue(MdSpan.Text("not", italic = true) in spans)
        assertTrue(MdSpan.Code("x") in spans)
        assertTrue(MdSpan.Link("docs", "https://example.org") in spans)
        assertTrue("Trailing dot is not part of a bare link", MdSpan.Link("https://example.com/a", "https://example.com/a") in spans)
        // Arithmetic and file names are not italic.
        assertEquals(listOf(MdSpan.Text("2 * 3 * 4 and my_file_name.txt")), Markdown.inline("2 * 3 * 4 and my_file_name.txt"))
        assertEquals("Setup\n\nInstall it with pkg and check the docs.", Markdown.plain(reply).lines().take(3).joinToString("\n"))
    }

    @Test
    fun anUnclosedFenceWhileStreamingIsStillCode() {
        val blocks = Markdown.parse("Here:\n```kotlin\nfun main() {\n")
        assertEquals(MdBlock.Code("kotlin", "fun main() {"), blocks.last())
    }

    @Test
    fun copyCodeButtonCopiesOnlyTheCode() {
        compose.setContent { MarkdownText(reply) }
        compose.onNodeWithText("Setup").assertExists()
        compose.onNodeWithText("bash").assertExists()
        compose.onNodeWithContentDescription("Copy code").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("pkg update && pkg install git", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
