package com.agent.mobile.phone

import org.junit.Assert.*
import org.junit.Test

/** Field search, field relocation, hint handling and the send-button resolver (blueprint 1.2). */
class TextInputLogicTest {

    private val screen = Bounds(0, 0, 1080, 2400)
    private fun b(l: Int, t: Int, r: Int, bt: Int) = Bounds(l, t, r, bt)

    private fun snap(vararg children: UiNode, pkg: String = "com.whatsapp") =
        ScreenTreeSerializer.serialize(UiNode(bounds = screen, children = children.toList()), pkg, screen = screen)

    /** A messenger chat: label row, then a composer container with a nested field and a send button. */
    private fun chat(fieldTop: Int = 2200, fieldText: String? = null, showingHint: Boolean = fieldText == null) = snap(
        UiNode(className = "android.widget.TextView", text = "Anna", bounds = b(0, 0, 1080, 150)),
        UiNode(
            className = "android.widget.LinearLayout", bounds = b(0, fieldTop - 20, 1080, fieldTop + 120), clickable = true,
            contentDescription = "Message composer",
            children = listOf(
                UiNode(
                    className = "android.widget.FrameLayout", bounds = b(0, fieldTop, 900, fieldTop + 100),
                    children = listOf(
                        UiNode(
                            className = "android.widget.EditText", editable = true, hint = "Message", text = fieldText,
                            showingHint = showingHint, viewId = "com.whatsapp:id/entry", bounds = b(20, fieldTop, 880, fieldTop + 100)
                        )
                    )
                ),
                UiNode(
                    className = "android.widget.ImageButton", contentDescription = "Send", clickable = true,
                    viewId = "com.whatsapp:id/send", bounds = b(920, fieldTop, 1060, fieldTop + 100)
                )
            )
        )
    )

    @Test
    fun findInputFieldNeverReturnsTheLabelAndFindsNestedFields() {
        val s = chat()
        val composer = s.elements.first { it.label == "Message composer" }
        val field = ElementMatcher.findInputField(s, composer)!!
        assertTrue(field.node.editable)
        assertEquals("com.whatsapp:id/entry", field.node.viewId)
    }

    @Test
    fun findInputFieldUsesTheFieldBesideOrBelowALabel() {
        val s = snap(
            UiNode(className = "android.widget.TextView", text = "Name", bounds = b(0, 300, 300, 380)),
            UiNode(className = "android.widget.EditText", editable = true, hint = "Your name", bounds = b(0, 400, 1080, 500)),
            UiNode(className = "android.widget.TextView", text = "Email", bounds = b(0, 600, 300, 680)),
            UiNode(className = "android.widget.EditText", editable = true, hint = "you@example.com", bounds = b(0, 700, 1080, 800)),
            pkg = "com.example.form"
        )
        val email = s.elements.first { it.label == "Email" }
        assertEquals("you@example.com", ElementMatcher.findInputField(s, email)!!.node.hint)
        val name = s.elements.first { it.label == "Name" }
        assertEquals("Your name", ElementMatcher.findInputField(s, name)!!.node.hint)
    }

    @Test
    fun findInputFieldReturnsNullWithoutAnyField() {
        val s = snap(UiNode(className = "android.widget.TextView", text = "Hello", bounds = b(0, 0, 500, 100)), pkg = "x")
        assertNull(ElementMatcher.findInputField(s, s.elements.single()))
    }

    @Test
    fun relocateFindsAFieldThatMovedAndGotText() {
        val before = chat(fieldTop = 2200)
        val field = before.elements.first { it.node.editable }
        // The keyboard opened (the field moved up by 900 px) and the text was typed.
        val after = chat(fieldTop = 1300, fieldText = "Hallo Anna", showingHint = false)
        val found = ElementMatcher.relocate(field, after)!!
        assertTrue(found.node.editable)
        assertEquals("Hallo Anna", found.node.text)
    }

    @Test
    fun relocateFieldFallsBackToHintThenFocus() {
        val old = snap(
            UiNode(className = "android.widget.EditText", editable = true, hint = "Search", bounds = b(0, 100, 1080, 200)),
            pkg = "x"
        ).elements.single()
        val fresh = snap(
            UiNode(className = "android.widget.EditText", editable = true, hint = "Other", bounds = b(0, 100, 1080, 200)),
            UiNode(className = "android.widget.EditText", editable = true, hint = "Search", text = "cats", bounds = b(0, 900, 1080, 1000)),
            pkg = "x"
        )
        assertEquals("cats", ElementMatcher.relocate(old, fresh)!!.node.text)
        val focusedOnly = snap(
            UiNode(className = "android.widget.EditText", editable = true, hint = "A", bounds = b(0, 100, 1080, 200)),
            UiNode(className = "android.widget.EditText", editable = true, hint = "B", focused = true, bounds = b(0, 300, 1080, 400)),
            pkg = "x"
        )
        assertEquals("B", ElementMatcher.relocate(old, focusedOnly)!!.node.hint)
    }

    @Test
    fun hintIsShownAsHintNotAsText() {
        val s = chat()
        val line = ScreenTreeSerializer.formatLine(s.elements.first { it.node.editable })
        assertTrue(line, line.contains("hint=\"Message\""))
        assertTrue(line, line.contains("empty"))
        assertFalse(line, line.contains("Field \"Message\""))
    }

    @Test
    fun messengerSubmitTapsTheSendButton() {
        val s = chat(fieldText = "Hi", showingHint = false)
        val field = s.elements.first { it.node.editable }
        val plan = SubmitResolver.plan(s, field, AppKind.MESSAGING)
        assertTrue(plan is SubmitResolver.Plan.TapButton)
        assertEquals("com.whatsapp:id/send", (plan as SubmitResolver.Plan.TapButton).button.node.viewId)
    }

    @Test
    fun otherAppsUseTheKeyboardActionAndMessengersWithoutButtonDoNotGuess() {
        val s = chat(fieldText = "Hi", showingHint = false)
        val field = s.elements.first { it.node.editable }
        assertEquals(SubmitResolver.Plan.ImeAction, SubmitResolver.plan(s, field, AppKind.OTHER))
        val noButton = snap(
            UiNode(className = "android.widget.EditText", editable = true, text = "Hi", bounds = b(0, 2200, 1080, 2300)),
            UiNode(className = "android.widget.Button", text = "Attach", clickable = true, bounds = b(0, 2000, 300, 2100))
        )
        assertTrue(SubmitResolver.plan(noButton, noButton.elements.first { it.node.editable }, AppKind.MESSAGING) is SubmitResolver.Plan.NotFound)
    }

    @Test
    fun fieldClearedDetectsSentMessages() {
        val sent = chat()
        assertTrue(SubmitResolver.fieldCleared(sent.elements.first { it.node.editable }, "Hi"))
        val notSent = chat(fieldText = "Hi Anna", showingHint = false)
        assertFalse(SubmitResolver.fieldCleared(notSent.elements.first { it.node.editable }, "Hi Anna"))
        val hintAsText = snap(UiNode(className = "android.widget.EditText", editable = true, text = "Message", hint = "Message", bounds = b(0, 0, 100, 100)))
        assertTrue(SubmitResolver.fieldCleared(hintAsText.elements.single(), "Hi"))
    }
}
