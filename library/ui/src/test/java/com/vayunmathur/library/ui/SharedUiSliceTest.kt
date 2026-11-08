package com.vayunmathur.library.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3 (library/ui root files): small shared UI pieces under Robolectric
 * Compose — [ConfirmDialog] buttons/labels, [IconAdd]/[IconDelete] rendering,
 * [SelectionState] toggle/select-all/clear, and [SelectionTopAppBar] count +
 * back/clear behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class SharedUiSliceTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- ConfirmDialog ----

    @Test fun confirmDialogConfirmsAndDismisses() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            ConfirmDialog(
                title = "Delete?",
                confirmLabel = "Delete",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
                message = "Gone forever",
                dismissLabel = "Cancel")
        }
        rule.onNodeWithText("Delete?").assertIsDisplayed()
        rule.onNodeWithText("Gone forever").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        // Confirm dismisses first, then confirms.
        assertEquals(1, dismissed)
        assertEquals(1, confirmed)
    }

    @Test fun confirmDialogDismissButtonOnlyDismisses() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            ConfirmDialog(
                title = "T",
                confirmLabel = "OK",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
                dismissLabel = "Back")
        }
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    // ---- Icons ----

    @Test fun appIconsRenderWithDescriptions() {
        rule.setContent { IconAdd(); IconDelete(); IconSave() }
        rule.onNodeWithContentDescription("Add").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").assertIsDisplayed()
    }

    // ---- SelectionState (pure state, no composition needed) ----

    @Test fun selectionStateToggleSelectClear() {
        val s = androidx.compose.runtime.mutableStateOf(emptySet<Int>())
        val state = SelectionState(s)
        assertFalse(state.isActive)
        assertEquals(0, state.count)
        state.toggle(1)
        assertTrue(state.isSelected(1))
        assertTrue(state.isActive)
        state.toggle(1)
        assertFalse(state.isSelected(1))
        state.select(2)
        state.select(2)
        assertEquals(setOf(2), state.selected)
        state.selectAll(listOf(1, 2, 3))
        assertEquals(3, state.count)
        state.clear()
        assertFalse(state.isActive)
    }

    @Test fun selectionTopAppBarShowsCountAndClears() {
        lateinit var state: SelectionState<String>
        rule.setContent {
            state = rememberSelectionState()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                state.selectAll(listOf("a", "b"))
            }
            if (state.isActive) SelectionTopAppBar(
                state = state,
                countLabel = { n -> "$n selected" })
        }
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        // Tapping the navigation (back) icon clears the selection.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertFalse(state.isActive)
    }
}
