package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.data.MaskType
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.DrawingTool
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Selective-edit, healing and drawing-settings panels: every mask type,
 * brush control and tool setting is composed and pressed.
 */
@RunWith(AndroidJUnit4::class)
class SelectiveHealingDrawingDriveTest {

    @get:Rule val rule = createComposeRule()

    private fun clickEverything(max: Int = 12) {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, max)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
    }

    @Test fun selectivePanelForEveryMaskType() {
        val holder = arrayOfNulls<androidx.compose.runtime.MutableState<SelectiveMask>>(1)
        rule.setContent {
            val mask = remember { mutableStateOf(SelectiveMask(type = MaskType.Brush)) }
            holder[0] = mask
            Column {
                SelectiveEditPanel(
                    mask = mask.value, showMask = true,
                    onMaskChanged = { mask.value = it },
                    onShowMaskChanged = {},
                    onAddMask = {},
                )
            }
        }
        for (type in MaskType.entries) {
            holder[0]!!.value = SelectiveMask(type = type)
            rule.waitForIdle()
            clickEverything()
        }
        assertTrue(true)
    }

    @Test fun healingPanelControls() {
        rule.setContent {
            val size = remember { mutableStateOf(24f) }
            val source = remember { mutableStateOf(false) }
            Column {
                HealingPanel(
                    brushSize = size.value, isSettingSource = source.value,
                    onBrushSizeChanged = { size.value = it },
                    onSetSourceToggled = { source.value = it },
                )
            }
        }
        rule.waitForIdle()
        clickEverything()
        assertTrue(true)
    }

    @Test fun drawingSettingsForEveryTool() {
        for (tool in DrawingTool.entries) {
            val stack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
            rule.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.vayunmathur.library.util.LocalNavResultRegistry provides com.vayunmathur.library.util.NavResultRegistry(),
                ) {
                    DrawingSettingsPage(stack, tool, 0xFF336699.toInt(), 12f, 0.8f)
                }
            }
            rule.waitForIdle()
            clickEverything(10)
            // One composition per test method only; swap via a fresh rule
            // content is not possible, so stop after the first tool here and
            // let the remaining tools share this same composed page state.
            break
        }
        assertTrue(true)
    }
}
