package com.agent.mobile.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.agent.mobile.data.model.ChatMessage
import com.agent.mobile.data.model.MessageRole
import com.agent.mobile.ui.chat.components.MessageActionsSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Teil E, E4/E5: the long-press menu offers edit for own messages and read aloud for replies.
 * The bottom sheet lives in its own window, so the actions are invoked through semantics.
 */
@RunWith(RobolectricTestRunner::class)
class MessageActionsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ownMessagesCanBeEditedAndSentAgain() {
        var edited = false
        compose.setContent {
            MessageActionsSheet(
                message = ChatMessage(role = MessageRole.USER, text = "Build the app"),
                onResend = {},
                onDismiss = {},
                onEdit = { edited = true }
            )
        }
        compose.onNodeWithText("Send again").assertExists()
        compose.onNodeWithText("Read aloud").assertDoesNotExist()
        compose.onNodeWithText("Edit and send").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(edited)
    }

    @Test
    fun repliesCanBeReadAloudAndStopped() {
        var read = 0
        compose.setContent {
            MessageActionsSheet(
                message = ChatMessage(role = MessageRole.ASSISTANT, text = "Done."),
                onResend = null,
                onDismiss = {},
                onReadAloud = { read++ }
            )
        }
        compose.onNodeWithText("Edit and send").assertDoesNotExist()
        compose.onNodeWithText("Read aloud").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, read)
    }

    @Test
    fun whileReadingTheMenuOffersStop() {
        var stopped = false
        compose.setContent {
            MessageActionsSheet(
                message = ChatMessage(role = MessageRole.ASSISTANT, text = "Done."),
                onResend = null,
                onDismiss = {},
                onReadAloud = {},
                onStopReading = { stopped = true }
            )
        }
        compose.onNodeWithText("Read aloud").assertDoesNotExist()
        compose.onNodeWithText("Stop reading").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(stopped)
    }
}
