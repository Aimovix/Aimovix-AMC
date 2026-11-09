package com.agent.mobile.phone

import org.junit.Assert.*
import org.junit.Test

class ElementMatcherTest {

    private val screen = Bounds(0, 0, 1080, 2400)
    private fun b(l: Int, t: Int, r: Int, bt: Int) = Bounds(l, t, r, bt)

    private fun snapshot(): ScreenSnapshot = ScreenTreeSerializer.serialize(
        UiNode(
            bounds = screen, children = listOf(
                UiNode(className = "android.widget.TextView", text = "Send feedback", bounds = b(0, 0, 500, 100)),
                UiNode(
                    className = "android.widget.LinearLayout", clickable = true, longClickable = true, bounds = b(0, 200, 1080, 400),
                    children = listOf(UiNode(className = "android.widget.TextView", text = "Anna", bounds = b(200, 220, 600, 280)))
                ),
                UiNode(className = "android.widget.EditText", editable = true, hint = "Search", bounds = b(0, 500, 900, 600), viewId = "app:id/search_box"),
                UiNode(className = "android.widget.ImageButton", contentDescription = "Send", clickable = true, bounds = b(920, 2100, 1060, 2200), viewId = "app:id/send"),
                UiNode(className = "android.widget.Button", text = "Send", clickable = true, bounds = b(0, 2100, 300, 2200))
            )
        ), "app", screen = screen
    )

    @Test
    fun parsesIdsInCommonForms() {
        assertEquals("e12", ElementMatcher.parseId("e12"))
        assertEquals("e12", ElementMatcher.parseId("[e12]"))
        assertEquals("e3", ElementMatcher.parseId(" #E3 "))
        assertNull(ElementMatcher.parseId("Send"))
        assertNull(ElementMatcher.parseId("e12 button"))
    }

    @Test
    fun resolvesById() {
        val s = snapshot()
        assertEquals("Anna", ElementMatcher.resolve(s, "e3")!!.label)
        assertNull(ElementMatcher.resolve(s, "e99"))
    }

    @Test
    fun exactTextBeatsPartialTextAndActionableWins() {
        val s = snapshot()
        val hit = ElementMatcher.findByText(s, "send")!!
        // Exact text "Send" (Button) beats exact description "Send" and partial "Send feedback".
        assertEquals("Button", hit.node.role)
        assertEquals("Send feedback", ElementMatcher.findByText(s, "feedback")!!.label)
    }

    @Test
    fun matchesHintAndViewId() {
        val s = snapshot()
        assertEquals("Search", ElementMatcher.findByText(s, "Search")!!.label)
        assertEquals("app:id/search_box", ElementMatcher.findByText(s, "search_box")!!.node.viewId)
        assertNull(ElementMatcher.findByText(s, "nothing like this"))
    }

    @Test
    fun clickTargetWalksUpToTheClickableRow() {
        val s = snapshot()
        val anna = ElementMatcher.findByText(s, "Anna")!!
        val row = ElementMatcher.clickTarget(s, anna)
        assertEquals(anna.parentId, row.id)
        assertTrue(row.node.clickable)
        assertEquals(row.id, ElementMatcher.clickTarget(s, anna, longPress = true).id)
    }

    @Test
    fun effectiveLabelOfAnUnlabeledRowComesFromItsChild() {
        val s = snapshot()
        val row = ElementMatcher.clickTarget(s, ElementMatcher.findByText(s, "Anna")!!)
        assertEquals("Anna", ElementMatcher.effectiveLabel(s, row))
    }

    @Test
    fun elementAtReturnsTheSmallestElementUnderThePoint() {
        val s = snapshot()
        assertEquals("Anna", ElementMatcher.elementAt(s, 300, 250)!!.label)
        assertTrue(ElementMatcher.elementAt(s, 50, 350)!!.node.clickable)
        assertNull(ElementMatcher.elementAt(s, 1000, 1000))
    }

    @Test
    fun relocateFindsTheSameElementAfterTheScreenMoved() {
        val old = snapshot()
        val send = old.elements.first { it.node.viewId == "app:id/send" }
        val moved = ScreenTreeSerializer.serialize(
            UiNode(
                bounds = screen, children = listOf(
                    UiNode(className = "android.widget.TextView", text = "New banner", bounds = b(0, 0, 500, 100)),
                    UiNode(className = "android.widget.ImageButton", contentDescription = "Send", clickable = true, bounds = b(920, 1500, 1060, 1600), viewId = "app:id/send")
                )
            ), "app", screen = screen
        )
        val found = ElementMatcher.relocate(send, moved)!!
        assertEquals("e2", found.id)
        assertEquals(1500, found.node.bounds.top)
    }

    @Test
    fun relocateReturnsNullWhenTheElementIsGone() {
        val old = snapshot()
        val anna = ElementMatcher.findByText(old, "Anna")!!
        val other = ScreenTreeSerializer.serialize(
            UiNode(bounds = screen, children = listOf(UiNode(className = "android.widget.TextView", text = "Ben", bounds = b(0, 0, 100, 100)))),
            "app", screen = screen
        )
        assertNull(ElementMatcher.relocate(anna, other))
    }
}
