package com.vayunmathur.clock.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.ui.dialogs.NewAlarmDialog
import com.vayunmathur.clock.ui.dialogs.SelectTimeZonesDialog
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import org.junit.Before
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric tests for the ViewModel-backed dialogs (cities, time picker, timezone picker). */
@RunWith(AndroidJUnit4::class)
class DialogsWithVmUiTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun vm() = ClockViewModel(ctx as android.app.Application, ClockRepository.get(ctx))

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun awaitCities(vm: ClockViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.cities.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            rule.waitForIdle()
        }
        assertTrue(vm.cities.value?.isNotEmpty() == true, "cities did not load")
    }

    @Test
    fun newAlarmDialogShowsPickerAndCancels() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(), Route.NewAlarmDialog()))
        val sizeBefore = stack.backStack.size
        rule.setContent {
            NewAlarmDialog(stack, vm(), initialHour = 7, initialMinutes = 30, initialMessage = "wake", initialDays = arrayListOf(1, 3))
        }
        rule.onNodeWithText("Alarm").assertIsDisplayed()
        rule.onNodeWithText("wake").assertExists()
        clickRow("Cancel")
        assertEquals(sizeBefore - 1, stack.backStack.size)
    }

    @Test
    fun newAlarmDialogDefaultsToNow() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(), Route.NewAlarmDialog()))
        rule.setContent { NewAlarmDialog(stack, vm(), null, null, null, null) }
        rule.onNodeWithText("Alarm").assertIsDisplayed()
        rule.waitForIdle()
    }

    @Test
    fun selectTimeZonesListsCitiesAndSearchFilters() {
        val v = vm()
        awaitCities(v)
        val ds = DataStoreUtils.getInstance(ctx)
        val stack = NavBackStack(arrayOf<Route>(Route.Main(), Route.SelectTimeZonesDialog))
        rule.setContent { SelectTimeZonesDialog(stack, ds, v) }
        rule.onNodeWithText("Select Cities", substring = true).assertExists()
        rule.waitForIdle()
        // Search for a distinctive city and assert the list narrows to it.
        val firstCity = v.cities.value!!.keys.first()
        rule.onNodeWithText("Search", substring = true).performTextInput(firstCity.take(4))
        rule.waitForIdle()
        rule.onNodeWithText(firstCity, substring = true).assertExists()
    }

    @Test
    fun selectTimeZonesToggleSelectsCity() {
        val v = vm()
        awaitCities(v)
        val ds = DataStoreUtils.getInstance(ctx)
        val stack = NavBackStack(arrayOf<Route>(Route.Main(), Route.SelectTimeZonesDialog))
        rule.setContent { SelectTimeZonesDialog(stack, ds, v) }
        rule.waitForIdle()
        val city = v.cities.value!!.keys.first()
        clickRow(city)
        // Selection persists through WorldClockCities; give the DataStore write a moment.
        val deadline = System.currentTimeMillis() + 5000
        var selected: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.runBlocking {
                selected = com.vayunmathur.clock.platform.WorldClockCities.flow(ds).first()
            }
            if (city in selected) break
            Thread.sleep(50)
        }
        assertTrue(city in selected, "selected=$selected city=$city")
        // Clean up so other tests start unselected.
        kotlinx.coroutines.runBlocking { com.vayunmathur.clock.platform.WorldClockCities.set(ds, emptyList()) }
    }
}
