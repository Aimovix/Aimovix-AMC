package com.agent.mobile.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.agent.mobile.ui.settings.components.RuleChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #36: a custom rule can be removed with a full-size touch target and through TalkBack. */
@RunWith(RobolectricTestRunner::class)
class RuleChipTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingTheChipRemovesTheRuleAndTheTargetIsAtLeast48dp() {
        var removed = 0
        compose.setContent { RuleChip(pattern = "^dropdb", isValid = true, invalidColor = Color.Red, onRemove = { removed++ }) }
        val node = compose.onNodeWithContentDescription("Remove rule ^dropdb")
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val minPx = with(compose.density) { 48.dp.toPx() }
        assertTrue("Touch height ${bounds.height} < $minPx", bounds.height >= minPx - 0.5f)
        node.performClick()
        assertEquals(1, removed)
    }
}
