package com.agent.mobile.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agent.mobile.rememberSelectedTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #35: the selected tab survives a configuration change (rotation, theme, font size). */
@RunWith(RobolectricTestRunner::class)
class MainTabStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedTabSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var tab by rememberSelectedTab()
            Button(onClick = { tab = 3 }) { Text("Tab $tab") }
        }
        compose.onNodeWithText("Tab 0").performClick()
        compose.onNodeWithText("Tab 3").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Tab 3").assertExists()
    }
}
