package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 15: disabled list item slots + sheet nested-scroll growth. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round15Test {

    @get:Rule val rule = createComposeRule()

    @Test fun listItemDisabledRendersAllSlots() {
        rule.setContent {
            ListItem(
                enabled = false,
                overlineContent = { Text("item-overline") },
                supportingContent = { Text("item-supporting") },
                leadingContent = { Text("item-leading") },
                trailingContent = { Text("item-trailing") },
            ) { Text("item-headline") }
        }
        rule.onNodeWithText("item-overline").assertIsDisplayed()
        rule.onNodeWithText("item-headline").assertIsDisplayed()
        rule.onNodeWithText("item-supporting").assertIsDisplayed()
        rule.onNodeWithText("item-leading").assertIsDisplayed()
        rule.onNodeWithText("item-trailing").assertIsDisplayed()
    }

    @Test fun listItemEnabledWithoutOptionalSlotsRenders() {
        rule.setContent {
            ListItem { Text("bare-headline") }
        }
        rule.onNodeWithText("bare-headline").assertIsDisplayed()
    }

    @Test fun sheetHeaderDragGrowsSheet() {
        // Pre-measure the state: under Robolectric the scaffold's own peek
        // measurement lands at 0 (header/insets do not measure like a device),
        // which would leave the sheet hidden and its content undraggable.
        val state = FreeHeightSheetState(androidx.compose.material3.SheetValue.PartiallyExpanded)
        kotlinx.coroutines.runBlocking { state.onMeasured(100f, 600f) }
        rule.setContent {
            FreeHeightBottomSheetScaffold(
                state = state,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                sheetHeader = { Text("sheet-head") },
                sheetContent = {
                    LazyColumn {
                        items((0 until 30).toList()) { Text("sheet-item-$it") }
                    }
                },
            ) {
                Text("host-body")
            }
        }
        rule.waitForIdle()
        val peek = state.offsetPx
        assertTrue(
            peek > 0f,
            "offset after idle=$peek expanded=${state.expandedHeightPx}",
        )
        assertTrue(peek > 0f, "sheet should rest at a measured peek, was $peek")
        assertTrue(peek < state.expandedHeightPx, "peek below expanded: $peek vs ${state.expandedHeightPx}")

        // Dragging the header (the sheet's own drag surface) grows the sheet
        // via the whole-sheet draggable. (The nested-scroll handover from the
        // LazyColumn inside needs real scroll physics; see the round report.)
        repeat(3) {
            rule.onNodeWithText("sheet-head").performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        assertTrue(
            state.offsetPx > peek,
            "dragging the sheet up should grow it: peek=$peek now=${state.offsetPx}",
        )
    }
}
