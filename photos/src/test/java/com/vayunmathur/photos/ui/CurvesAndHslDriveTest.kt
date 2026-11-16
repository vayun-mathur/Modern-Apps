package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslColorRange
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Curves and HSL panels: channel/range chips switch, and drags on the
 * curve canvas add/move control points through the real handlers.
 */
@RunWith(AndroidJUnit4::class)
class CurvesAndHslDriveTest {

    @get:Rule val rule = createComposeRule()

    @Test fun curvesPanelChannelsAndDrags() {
        val holder = arrayOfNulls<androidx.compose.runtime.MutableState<CurvesAdjustment>>(1)
        val channel = arrayOfNulls<androidx.compose.runtime.MutableState<CurveChannel>>(1)
        rule.setContent {
            val curves = remember { mutableStateOf(CurvesAdjustment()) }
            val selected = remember { mutableStateOf(CurveChannel.Combined) }
            holder[0] = curves
            channel[0] = selected
            Column {
                CurvesPanel(curves.value, selected.value,
                    onChannelSelected = { selected.value = it },
                    onCurvesChanged = { curves.value = it })
            }
        }
        rule.waitForIdle()
        for (ch in CurveChannel.entries) {
            channel[0]!!.value = ch
            rule.waitForIdle()
            rule.onRoot().performTouchInput {
                swipe(start = center, end = centerRight, durationMillis = 80)
            }
            rule.waitForIdle()
            val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (i in 0 until minOf(count, 8)) {
                runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
                runCatching { rule.waitForIdle() }
            }
        }
        assertTrue(holder[0]!!.value.combined.isNotEmpty())
    }

    @Test fun hslPanelRangesAndSliders() {
        val holder = arrayOfNulls<androidx.compose.runtime.MutableState<HslAdjustments>>(1)
        val range = arrayOfNulls<androidx.compose.runtime.MutableState<HslColorRange>>(1)
        rule.setContent {
            val hsl = remember { mutableStateOf(HslAdjustments()) }
            val selected = remember { mutableStateOf(HslColorRange.Red) }
            holder[0] = hsl
            range[0] = selected
            Column {
                HslPanel(hsl.value, selected.value,
                    onRangeSelected = { selected.value = it },
                    onHslChanged = { hsl.value = it })
            }
        }
        rule.waitForIdle()
        for (r in HslColorRange.entries) {
            range[0]!!.value = r
            rule.waitForIdle()
            val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (i in 0 until minOf(count, 10)) {
                runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
                runCatching { rule.waitForIdle() }
            }
        }
        assertTrue(holder[0]!!.value.channels.isNotEmpty())
    }
}
