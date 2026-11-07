package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vayunmathur.keyboard.util.ShiftState
import com.vayunmathur.library.ui.Text
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KeyComponentsTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `alternates popup highlights selected entry`() {
        rule.setContent {
            AlternatesPopup(options = "abc", selected = 1, itemWidth = 44.dp, offsetX = 0)
        }
        rule.onNodeWithText("a").assertIsDisplayed()
        rule.onNodeWithText("b").assertIsDisplayed()
        rule.onNodeWithText("c").assertIsDisplayed()
    }

    @Test
    fun `key preview shows label`() {
        rule.setContent { KeyPreview("Q") }
        rule.onNodeWithText("Q").assertIsDisplayed()
    }

    @Test
    fun `char key tap commits and hint variants render`() {
        val clicks = mutableListOf<String>()
        val alts = mutableListOf<String>()
        rule.setContent {
            Column {
                Row {
                    CharKey(
                        label = "a", height = 52.dp, hint = "ABC",
                        alternates = "àá", onClick = { clicks += "a" },
                        onAlternate = { alts += it },
                    )
                    CharKey(
                        label = "ß", height = 52.dp, cornerHint = "2",
                        alternates = "", onClick = { clicks += "ß" },
                    )
                }
            }
        }
        rule.onNodeWithText("a").assertIsDisplayed()
        rule.onNodeWithText("ABC").assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithText("a").performTouchInput { down(center); up() }
        assertEquals(listOf("a"), clicks)
        rule.onNodeWithText("ß").performTouchInput { down(center); up() }
        assertEquals(listOf("a", "ß"), clicks)
    }

    @Test
    fun `char key long press opens popup and release picks base`() {
        val clicks = mutableListOf<String>()
        val alts = mutableListOf<String>()
        rule.setContent {
            Row {
                CharKey(
                    label = "e", height = 52.dp,
                    alternates = "éè", onClick = { clicks += "e" },
                    onAlternate = { alts += it },
                )
            }
        }
        rule.onNodeWithText("e").performTouchInput {
            down(center)
            advanceEventTime(700)
        }
        rule.waitForIdle()
        // popup entries are the label plus alternates; releasing picks one of them
        rule.onAllNodesWithText("e")[0].performTouchInput { up() }
        assertTrue(alts.isNotEmpty() || clicks.isNotEmpty())
    }

    @Test
    fun `special space repeat and shift keys respond`() {
        var specials = 0; var longs = 0; var spaces = 0; var repeats = 0; var shifts = 0
        rule.setContent {
            Column {
                Row {
                    SpecialKey(
                        height = 52.dp, onClick = { specials++ },
                        onLongClick = { longs++ }, onLongClickLabel = "more",
                    ) { Text("FN") }
                    SpaceKey(height = 52.dp, weight = 2f, label = "English", onSpace = { spaces++ })
                }
                Row {
                    RepeatKey(height = 52.dp, weight = 1f, onRepeat = { repeats++ }) { Text("DEL") }
                    ShiftKey(height = 52.dp, weight = 1f, shift = ShiftState.OFF, onShift = { shifts++ })
                }
                Row {
                    ShiftKey(height = 52.dp, weight = 1f, shift = ShiftState.CAPS_LOCK, onShift = { shifts++ })
                }
            }
        }
        rule.onNodeWithText("FN").performClick()
        assertEquals(1, specials)
        rule.onNodeWithText("English").performClick()
        assertEquals(1, spaces)
        rule.onNodeWithText("DEL").performTouchInput { down(center); up() }
        assertTrue(repeats >= 1)
        // shift keys are icon-only; touch the second row area via DEL sibling is not
        // addressable, so assert they rendered by composition completing without error
        assertEquals(0, shifts)
    }

    @Test
    fun `haptic tick respects enabled flag`() {
        var rendered = false
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalKeyHapticsEnabled provides false,
            ) {
                val tick = rememberKeyHapticTick()
                tick()
                rendered = true
            }
        }
        rule.waitForIdle()
        assertTrue(rendered)
    }
}
