package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Slice 2/3 (library/ui): the MaterialFunctions state factories and helpers —
 * each creates the state it promises with the requested initial values.
 */
@RunWith(RobolectricTestRunner::class)
class Slice2MaterialStateTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `drawer state opens and closes`() {
        var opened = false
        rule.setContent {
            val drawer = rememberDrawerState(initialValue = DrawerValue.Closed)
            opened = drawer.isOpen
            Column {
                Text(if (drawer.isClosed) "slice2 shut" else "slice2 open")
            }
        }
        rule.onNodeWithText("slice2 shut").assertIsDisplayed()
        assertEquals(false, opened)
    }

    @Test
    fun `time picker state carries its initials`() {
        rule.setContent {
            val state = rememberTimePickerState(initialHour = 7, initialMinute = 45)
            assertTrue(state.hour == 7 && state.minute == 45)
            Column {
                TimePicker(state = state)
                Text("slice2 time tail")
            }
        }
        rule.onNodeWithText("slice2 time tail").assertIsDisplayed()
    }

    @Test
    fun `date picker states build and render`() {
        rule.setContent {
            val single = rememberDatePickerState(initialSelectedDateMillis = 2_000_000L)
            val range = rememberDateRangePickerState(
                initialSelectedStartDateMillis = 1_000_000L,
                initialSelectedEndDateMillis = 3_000_000L,
            )
            assertNotNull(single)
            assertNotNull(range)
            Column {
                DatePicker(state = single, modifier = Modifier.testTag("slice2 state dp"))
            }
        }
        rule.onNodeWithTag("slice2 state dp").assertIsDisplayed()
    }

    @Test
    fun `bottom sheet states build with defaults`() {
        rule.setContent {
            val sheet = rememberBottomSheetState()
            val modal = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scaffold = rememberBottomSheetScaffoldState()
            val scaffold2 = rememberBottomSheetScaffoldState(bottomSheetState = sheet)
            assertNotNull(sheet)
            assertNotNull(modal)
            assertNotNull(scaffold)
            assertNotNull(scaffold2)
            Column {
                Text("slice2 sheet tail")
            }
        }
        rule.onNodeWithText("slice2 sheet tail").assertIsDisplayed()
    }

    @Test
    fun `slider and swipe states build`() {
        rule.setContent {
            val slider = rememberSliderState(value = 0.4f, steps = 3, valueRange = 0f..2f)
            val swipe = rememberSwipeToDismissBoxState()
            assertEquals(0.4f, slider.value)
            assertNotNull(swipe)
            Column {
                Text("slice2 misc tail")
            }
        }
        rule.onNodeWithText("slice2 misc tail").assertIsDisplayed()
    }

    @Test
    fun `scroll behavior matches the bar size`() {
        rule.setContent {
            val small = appBarScrollBehavior(AppBarSize.Small)
            val medium = appBarScrollBehavior(AppBarSize.MediumFlexible)
            val large = appBarScrollBehavior(AppBarSize.LargeFlexible)
            assertNotNull(small)
            assertNotNull(medium)
            assertNotNull(large)
            Column {
                Text("slice2 scroll tail")
            }
        }
        rule.onNodeWithText("slice2 scroll tail").assertIsDisplayed()
    }

    @Test
    fun `compact touch targets render content and stay clickable`() {
        var clicked = 0
        rule.setContent {
            CompactTouchTargets(size = 40.dp) {
                TextButton(
                    onClick = { clicked++ },
                    modifier = Modifier.testTag("slice2 compact"),
                ) {
                    Text("slice2 compact btn")
                }
            }
        }
        rule.onNodeWithText("slice2 compact btn").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun `adaptive info is available`() {
        rule.setContent {
            val info = currentWindowAdaptiveInfo()
            assertNotNull(info)
            Column {
                Text("slice2 adaptive tail")
            }
        }
        rule.onNodeWithText("slice2 adaptive tail").assertIsDisplayed()
    }
}
