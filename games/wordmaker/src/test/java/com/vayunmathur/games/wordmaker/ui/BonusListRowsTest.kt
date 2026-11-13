package com.vayunmathur.games.wordmaker.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.ui.dialogs.BonusWordsDialog
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BonusListRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun bonusWordRowsAreFullSizeTouchTargetsThatOpenTheDefinition() {
        rule.setContent {
            DynamicTheme {
                BonusWordsDialog(bonusWords = setOf("OWL", "COW"), getDefinition = { listOf("def of $it") }, onDismiss = {})
            }
        }
        val row = rule.onNodeWithText("OWL")
        row.assertHasClickAction()
        val height = row.fetchSemanticsNode().boundsInRoot.height
        val min = with(rule.density) { 48.dp.toPx() }
        assertTrue(height >= min, "bonus row is $height px tall, want at least $min")
        row.performClick()
        rule.onNodeWithText("def of OWL").assertIsDisplayed()
    }
}
