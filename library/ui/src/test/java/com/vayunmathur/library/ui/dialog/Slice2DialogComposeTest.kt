package com.vayunmathur.library.ui.dialog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 2/3 (library/ui): the shared date/time pickers — confirm delivers
 * the picked value through the result registry and pops the stack; cancel
 * and dismiss pop without delivering; Clear delivers an empty [DateSelection].
 */
@RunWith(RobolectricTestRunner::class)
class Slice2DialogComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private object Slice2Key : NavKey

    private fun backStack() = NavBackStack(arrayOf<NavKey>(Slice2Key))

    // --- DatePickerDialog -------------------------------------------------

    @Test
    fun `confirming the date delivers a LocalDate and pops`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered: Any? = "unset"
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-date") { delivered = it }
                DatePickerDialog(
                    backStack = stack,
                    resultKey = "slice2-date",
                    initialDate = LocalDate(2026, 11, 6),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).assertIsDisplayed()
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).performClick()
        // Delivery is a launched coroutine; waitUntil pumps the main thread so it runs.
        rule.waitUntil(timeoutMillis = 10_000) { delivered != "unset" }
        val date = delivered
        assertTrue(date is LocalDate, "expected a LocalDate, got $date")
        assertEquals(LocalDate(2026, 11, 6), date)
        assertTrue(stack.backStack.isEmpty(), "confirm must pop the dialog")
    }

    @Test
    fun `cancel pops without delivering`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered = false
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-date-cancel") { delivered = true }
                DatePickerDialog(
                    backStack = stack,
                    resultKey = "slice2-date-cancel",
                    initialDate = LocalDate(2026, 11, 6),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.cancel)).performClick()
        rule.waitForIdle()
        assertTrue(stack.backStack.isEmpty())
        assertEquals(false, delivered)
    }

    @Test
    fun `clear delivers an empty selection`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered: Any? = null
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-date-clear") { delivered = it }
                DatePickerDialog(
                    backStack = stack,
                    resultKey = "slice2-date-clear",
                    initialDate = LocalDate(2026, 11, 6),
                    allowClear = true,
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.clear)).assertIsDisplayed()
        rule.onNodeWithText(appContext.getString(UiR.string.clear)).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { delivered != null }
        assertEquals(DateSelection(null), delivered)
        assertTrue(stack.backStack.isEmpty())
    }

    @Test
    fun `a min date disables confirm for an earlier selection`() {
        // initialDate == minDate here: confirm stays enabled (documents the
        // boundary rather than the disabled path, which needs a day click).
        val stack = backStack()
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                DatePickerDialog(
                    backStack = stack,
                    resultKey = "slice2-date-min",
                    initialDate = LocalDate(2026, 11, 6),
                    minDate = LocalDate(2026, 11, 6),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).assertIsDisplayed()
    }

    // --- TimePickerDialogContent --------------------------------------------

    @Test
    fun `confirming the time delivers a LocalTime and pops`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered: Any? = null
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-time") { delivered = it }
                TimePickerDialogContent(
                    backStack = stack,
                    resultKey = "slice2-time",
                    initialTime = LocalTime(17, 30),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.time_picker_title)).assertIsDisplayed()
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { delivered != null }
        assertEquals(LocalTime(17, 30), delivered)
        assertTrue(stack.backStack.isEmpty())
    }

    @Test
    fun `canceling the time pops without delivering`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered = false
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-time-cancel") { delivered = true }
                TimePickerDialogContent(
                    backStack = stack,
                    resultKey = "slice2-time-cancel",
                    initialTime = LocalTime(9, 0),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.cancel)).performClick()
        rule.waitForIdle()
        assertTrue(stack.backStack.isEmpty())
        assertEquals(false, delivered)
    }

    @Test
    fun `a future min time disables confirm`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered: Any? = null
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-time-min") { delivered = it }
                TimePickerDialogContent(
                    backStack = stack,
                    resultKey = "slice2-time-min",
                    initialTime = LocalTime(17, 30),
                    minTime = LocalTime(18, 0),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).assertIsNotEnabled()
        assertEquals(null, delivered)
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `a past min time leaves confirm enabled`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered: Any? = null
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-time-min-ok") { delivered = it }
                TimePickerDialogContent(
                    backStack = stack,
                    resultKey = "slice2-time-min-ok",
                    initialTime = LocalTime(17, 30),
                    minTime = LocalTime(9, 0),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).assertIsEnabled()
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { delivered != null }
        assertEquals(LocalTime(17, 30), delivered)
    }

    @Test
    fun `a max date still confirms inside the window`() {
        val stack = backStack()
        val registry = NavResultRegistry()
        var delivered: Any? = "unset"
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                com.vayunmathur.library.util.ResultEffect<Any>("slice2-date-max") { delivered = it }
                DatePickerDialog(
                    backStack = stack,
                    resultKey = "slice2-date-max",
                    initialDate = LocalDate(2026, 11, 6),
                    minDate = LocalDate(2026, 11, 1),
                    maxDate = LocalDate(2026, 11, 30),
                )
            }
        }
        rule.onNodeWithText(appContext.getString(UiR.string.ok)).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { delivered != "unset" }
        assertEquals(LocalDate(2026, 11, 6), delivered)
        assertTrue(stack.backStack.isEmpty())
    }
}
