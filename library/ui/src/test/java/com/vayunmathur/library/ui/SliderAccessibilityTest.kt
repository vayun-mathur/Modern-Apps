package com.vayunmathur.library.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Accessibility semantics for the shared [Slider] wrapper: a supplied
 * valueFormatter becomes the stateDescription TalkBack reads instead of a
 * bare range percentage, and no formatter leaves the default alone.
 */
@RunWith(RobolectricTestRunner::class)
class SliderAccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun sliderNode() = rule.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
    )

    @Test fun formattedSliderAnnouncesFormattedValue() {
        rule.setContent {
            var value by remember { mutableFloatStateOf(5.0f) }
            Slider(
                value = value,
                onValueChange = { value = it },
                valueRange = 1f..7f,
                valueFormatter = { "Magnitude limit: $it" },
            )
        }
        sliderNode().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Magnitude limit: 5.0"),
        )
        val info = sliderNode().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo(5.0f, 1f..7f, 0), info)
    }

    @Test fun unformattedSliderKeepsTheDefaultRawStateDescription() {
        rule.setContent {
            var value by remember { mutableFloatStateOf(0.5f) }
            Slider(value = value, onValueChange = { value = it })
        }
        // Without a formatter the wrapper adds nothing: Material's own
        // default state description (the raw fraction) is what remains.
        val description = sliderNode().fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]
        assertEquals("0.5", description)
    }
}
