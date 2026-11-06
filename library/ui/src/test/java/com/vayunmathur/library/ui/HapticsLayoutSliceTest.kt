package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: haptics facade, adaptive grid cells, free-height sheet state.
 */
@RunWith(RobolectricTestRunner::class)
class HapticsLayoutSliceTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeFeedback : HapticFeedback {
        val fired = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            fired += hapticFeedbackType
        }
    }

    @Test fun hapticsFiresNamedWaveforms() {
        val feedback = FakeFeedback()
        val haptics = Haptics(feedback)
        haptics.longPress()
        haptics.keyPress()
        haptics.tick()
        haptics.confirm()
        haptics.reject()
        assertEquals(
            listOf(
                HapticFeedbackType.LongPress,
                HapticFeedbackType.KeyboardTap,
                HapticFeedbackType.SegmentTick,
                HapticFeedbackType.Confirm,
                HapticFeedbackType.Reject,
            ),
            feedback.fired,
        )
    }

    @Test fun rememberHapticsProvidesFacade() {
        var saw: Haptics? = null
        rule.setContent {
            saw = rememberHaptics()
            Text("x")
        }
        rule.waitForIdle()
        assertTrue(saw != null)
    }

    @Test fun adaptiveGridCellsUsesMinSize() {
        rule.setContent {
            val cells = adaptiveGridCells(minCellSize = 200.dp)
            assertEquals(GridCells.Adaptive(200.dp), cells)
        }
        rule.waitForIdle()
    }

    @Test fun dynamicThemeRendersContent() {
        rule.setContent {
            DynamicTheme(darkTheme = false) {
                Text("Themed")
            }
        }
        rule.onNodeWithText("Themed").assertIsDisplayed()
    }

    @Test fun desktopMaxWidthContainerRendersContent() {
        rule.setContent {
            DesktopMaxWidthContainer {
                Text("Narrow")
            }
        }
        rule.onNodeWithText("Narrow").assertIsDisplayed()
    }
}
