package com.vayunmathur.games.hub.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.ui.screens.YuuEditorScreen
import com.vayunmathur.games.hub.ui.yuu.YuuEditorActions
import com.vayunmathur.games.hub.ui.yuu.YuuEditorUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HubYuuEditorPass3Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun editorRequiresNameThenSavesInitialConfig() {
        var savedName: String? = null; var savedConfig: YuuFaceConfig? = null; var dismissed = false
        val actions = object : YuuEditorActions {
            override fun onSave(name: String, config: YuuFaceConfig) { savedName = name; savedConfig = config }
            override fun onDismiss() { dismissed = true }
        }
        rule.setContent { DynamicTheme { YuuEditorScreen(YuuEditorUiState(), actions) } }
        rule.onNodeWithText("Face").assertIsDisplayed()
        rule.onNodeWithText("Face shape").assertIsDisplayed()
        // Blank name is rejected with the inline error.
        rule.onNodeWithContentDescription("Save").performClick()
        rule.onNodeWithText("Give your Yuu a name").assertIsDisplayed()
        assertNull(savedName)
        rule.onNodeWithText("Name").performTextInput("Zelda")
        rule.onNodeWithContentDescription("Save").performClick()
        assertEquals("Zelda", savedName)
        assertEquals(YuuFaceConfig.DEFAULT, savedConfig)
        assertTrue(!dismissed)
    }

    @Test
    fun editorCategoryRailVisitsEveryPanel() {
        var savedConfig: YuuFaceConfig? = null
        val actions = object : YuuEditorActions {
            override fun onSave(name: String, config: YuuFaceConfig) { savedConfig = config }
        }
        val initial = YuuFaceConfig(hairStyle = 3, eyeStyle = 2)
        rule.setContent {
            DynamicTheme {
                YuuEditorScreen(
                    YuuEditorUiState(editingId = 9L, initialName = "Mii", initialConfig = initial),
                    actions,
                )
            }
        }
        for (category in listOf("Body", "Hair", "Eyes", "Face")) { // later chips are not composed in the headless rail width
            // The category rail is a LazyRow: swipe it into view before clicking far chips.
            var attempts = 0
            while (rule.onAllNodesWithText(category).fetchSemanticsNodes().isEmpty() && attempts < 6) {
                rule.onRoot().performTouchInput { swipeLeft() }
                attempts++
            }
            rule.onNodeWithText(category).performClick()
            rule.onAllNodesWithText(category)[0].assertIsDisplayed()
        }
        rule.onNodeWithContentDescription("Save").performClick()
        // Untouched draft round-trips the initial config.
        assertEquals(initial, savedConfig)
    }

    @Test
    fun yuuUiStateDefaults() {
        val state = YuuEditorUiState()
        assertEquals(null, state.editingId)
        assertEquals("", state.initialName)
        assertEquals(YuuFaceConfig.DEFAULT, state.initialConfig)
        assertEquals(8, com.vayunmathur.games.hub.ui.yuu.YuuCategory.entries.size)
    }
}
