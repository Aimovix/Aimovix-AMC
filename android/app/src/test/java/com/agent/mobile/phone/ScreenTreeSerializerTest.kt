package com.agent.mobile.phone

import org.junit.Assert.*
import org.junit.Test

class ScreenTreeSerializerTest {

    private val screen = Bounds(0, 0, 1080, 2400)

    private fun b(l: Int, t: Int, r: Int, bt: Int) = Bounds(l, t, r, bt)

    private fun chatScreen() = UiNode(
        className = "android.widget.FrameLayout", bounds = screen, children = listOf(
            UiNode(className = "android.widget.TextView", text = "Chats", bounds = b(40, 100, 300, 160)),
            UiNode(
                className = "androidx.recyclerview.widget.RecyclerView", scrollable = true, bounds = b(0, 200, 1080, 2000),
                children = listOf(
                    UiNode(
                        className = "android.widget.LinearLayout", clickable = true, bounds = b(0, 200, 1080, 400),
                        children = listOf(
                            UiNode(className = "android.widget.TextView", text = "Anna", bounds = b(200, 220, 600, 280)),
                            UiNode(className = "android.widget.TextView", text = "bin gleich da", bounds = b(200, 300, 900, 360))
                        )
                    )
                )
            ),
            UiNode(className = "android.widget.EditText", editable = true, hint = "Message", bounds = b(20, 2100, 900, 2200), focused = true),
            UiNode(className = "android.widget.ImageButton", contentDescription = "Send", clickable = true, bounds = b(920, 2100, 1060, 2200), viewId = "com.whatsapp:id/send")
        )
    )

    @Test
    fun listsInformativeElementsWithIdsRolesAndFlags() {
        val snap = ScreenTreeSerializer.serialize(chatScreen(), "com.whatsapp", "WhatsApp", screen)
        val text = snap.text
        assertTrue(text.startsWith("Screen: WhatsApp (com.whatsapp) · 1080x2400 · 7 elements"))
        assertTrue(text.contains("[e1] Text \"Chats\""))
        assertTrue(text.contains("[e2] List (scroll)"))
        assertTrue(text.contains("  [e3] Item (click) @540,300"))
        assertTrue("children are indented under their row", text.contains("    [e4] Text \"Anna\""))
        assertTrue(text.contains("[e6] Field hint=\"Message\" (edit, focused) @460,2150"))
        assertTrue(text.contains("[e7] IconButton \"Send\" (click) @990,2150"))
        assertEquals(7, snap.elements.size)
        assertEquals("e3", snap.byId("e4")!!.parentId)
        assertFalse(snap.truncated)
    }

    @Test
    fun layoutContainersWithoutContentAreLeftOut() {
        val snap = ScreenTreeSerializer.serialize(chatScreen(), "com.whatsapp", "WhatsApp", screen)
        assertFalse(snap.text.contains("FrameLayout"))
        assertTrue(snap.elements.none { it.node.className == "android.widget.FrameLayout" })
    }

    @Test
    fun invisibleAndOffScreenNodesAreSkipped() {
        val root = UiNode(
            bounds = screen, children = listOf(
                UiNode(className = "android.widget.TextView", text = "hidden", visible = false, bounds = b(0, 0, 100, 100)),
                UiNode(className = "android.widget.TextView", text = "below the screen", bounds = b(0, 3000, 100, 3100)),
                UiNode(className = "android.widget.TextView", text = "zero size", bounds = b(10, 10, 10, 10)),
                UiNode(className = "android.widget.TextView", text = "shown", bounds = b(0, 0, 100, 100))
            )
        )
        val snap = ScreenTreeSerializer.serialize(root, "p", screen = screen)
        assertEquals(listOf("shown"), snap.elements.map { it.label })
    }

    @Test
    fun passwordFieldsNeverRevealTheirContent() {
        val root = UiNode(
            bounds = screen, children = listOf(
                UiNode(
                    className = "android.widget.EditText", editable = true, password = true,
                    text = "hunter2", contentDescription = "hunter2", hint = "Password", bounds = b(0, 0, 500, 100),
                    children = listOf(UiNode(className = "android.widget.TextView", text = "hunter2", bounds = b(0, 0, 500, 100)))
                )
            )
        )
        val snap = ScreenTreeSerializer.serialize(root, "p", screen = screen)
        assertFalse(snap.text.contains("hunter2"))
        assertTrue(snap.text.contains("PasswordField \"Password\" [content hidden]"))
        assertEquals(1, snap.elements.size)
        val kept = snap.elements.single()
        assertNull("the secret is not even kept for matching", kept.node.text)
        assertNull(kept.node.contentDescription)
        assertEquals("Password", kept.label)
    }

    @Test
    fun sizeLimitTruncatesTheTextButKeepsAllElementsForMatching() {
        val rows = (1..200).map { UiNode(className = "android.widget.TextView", text = "Row number $it", bounds = b(0, it * 10, 500, it * 10 + 9)) }
        val root = UiNode(bounds = screen, children = rows)
        val snap = ScreenTreeSerializer.serialize(root, "p", screen = screen, maxChars = 1_000)
        assertTrue(snap.truncated)
        assertTrue(snap.text.length <= 1_000 + 120)
        assertTrue(snap.text.contains("more elements not shown"))
        assertEquals(200, snap.elements.size)
        assertEquals("Row number 200", snap.byId("e200")!!.label)
    }

    @Test
    fun elementLimitAlsoTruncates() {
        val rows = (1..50).map { UiNode(className = "android.widget.TextView", text = "R$it", bounds = b(0, it * 10, 500, it * 10 + 9)) }
        val snap = ScreenTreeSerializer.serialize(UiNode(bounds = screen, children = rows), "p", screen = screen, maxElements = 10)
        assertTrue(snap.truncated)
        assertTrue(snap.text.contains("[e10]"))
        assertFalse(snap.text.contains("[e11]"))
        assertTrue(snap.text.contains("40 more elements"))
    }

    @Test
    fun longLabelsAreShortenedAndOnOneLine() {
        val long = "Line one\nline two " + "x".repeat(200)
        val root = UiNode(bounds = screen, children = listOf(UiNode(className = "android.widget.TextView", text = long, bounds = b(0, 0, 100, 100))))
        val line = ScreenTreeSerializer.serialize(root, "p", screen = screen).text.lines()[1]
        assertTrue(line.startsWith("[e1] Text \"Line one line two"))
        assertTrue(line.length < 110)
        assertTrue(line.contains("…"))
    }

    @Test
    fun filterListsOnlyMatchingElementsWithTheirGlobalIds() {
        val snap = ScreenTreeSerializer.serialize(chatScreen(), "com.whatsapp", "WhatsApp", screen, filter = "send")
        val lines = snap.text.lines()
        assertTrue(lines[0].contains("filter \"send\": 1 match"))
        assertEquals("[e7] IconButton \"Send\" (click) @990,2150", lines[1])
        assertEquals(2, lines.size)
    }

    @Test
    fun emptyScreenSaysSo() {
        val snap = ScreenTreeSerializer.serialize(UiNode(bounds = screen), "p", screen = screen)
        assertTrue(snap.text.contains("no readable elements"))
        assertTrue(snap.elements.isEmpty())
    }

    @Test
    fun checkableStateAndDisabledAreShown() {
        val root = UiNode(
            bounds = screen, children = listOf(
                UiNode(className = "android.widget.Switch", text = "Wi-Fi", checkable = true, checked = true, clickable = true, bounds = b(0, 0, 500, 100)),
                UiNode(className = "android.widget.Button", text = "Next", clickable = true, enabled = false, bounds = b(0, 200, 500, 300))
            )
        )
        val text = ScreenTreeSerializer.serialize(root, "p", screen = screen).text
        assertTrue(text.contains("[e1] Switch \"Wi-Fi\" (click, on)"))
        assertTrue(text.contains("[e2] Button \"Next\" (click, disabled)"))
    }
}
