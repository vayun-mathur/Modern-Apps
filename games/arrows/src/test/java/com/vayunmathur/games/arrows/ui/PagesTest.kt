package com.vayunmathur.games.arrows.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.arrows.Navigation
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.Route
import com.vayunmathur.games.arrows.platform.ArrowsViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PagesTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun loadedVm(): ArrowsViewModel {
        val vm = ArrowsViewModel(app)
        await("vm loads") { vm.uiState.value.game != null && !vm.uiState.value.generating }
        return vm
    }

    @Test
    fun gamePageBindsViewModelAndShowsBoard() {
        val vm = loadedVm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Game))
        rule.setContent { ArrowsGamePage(backStack, vm) }
        rule.onNodeWithText(str(R.string.how_to_play)).assertExists()
        // Restart a fresh board goes straight through the ViewModel (no confirm dialog)
        rule.onNodeWithText("Restart").performClick()
        await("still loaded after restart") { vm.uiState.value.game != null }
        assertEquals(listOf<Route>(Route.Game), backStack.backStack.toList())
    }

    @Test
    fun settingsPageBindsViewModelAndTogglesRoutes() {
        val vm = loadedVm()
        var back = 0
        rule.setContent { SettingsPage(vm, onBack = { back++ }) }
        rule.onNodeWithText(str(R.string.show_routes)).assertExists()
        val before = vm.uiState.value.showRoutes
        rule.onNodeWithText(str(R.string.show_routes)).performClick()
        await("showRoutes toggled via page") { vm.uiState.value.showRoutes == !before }
        // Toggle back to the original value
        rule.onNodeWithText(str(R.string.show_routes)).performClick()
        await("showRoutes restored via page") { vm.uiState.value.showRoutes == before }
        assertEquals(0, back)

        // Reminder toggle and time picker go through the page's SettingsActions wrapper.
        rule.onNodeWithText("Daily puzzle reminder").performClick()
        await("reminder enabled via page") { vm.reminderEnabled.value }
        rule.onNodeWithText("Daily puzzle reminder").performClick()
        await("reminder disabled via page") { !vm.reminderEnabled.value }
    }

    @Test
    fun navigationShowsGamePage() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(str(R.string.how_to_play)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(str(R.string.how_to_play)).assertExists()
    }
}
