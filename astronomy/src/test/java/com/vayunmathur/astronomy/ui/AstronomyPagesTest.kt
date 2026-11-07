package com.vayunmathur.astronomy.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AstronomyPagesTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Composable
    private fun stack(): NavBackStack<Route> = rememberNavBackStack(Route.SkyMap)

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) { if (cond()) return; Thread.sleep(50) }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun `object detail page renders the sun`() {
        val vm = AstronomyViewModel(app)
        await("catalog") { vm.catalogReady.value }
        vm.setManualLocation(37.0, -122.0)
        vm.selectObject("SUN")
        rule.setContent { ObjectDetailPage(backStack = stack(), viewModel = vm, objectId = "SUN") }
        rule.onNodeWithText("Sun", substring = true).assertExists()
    }

    @Test fun `settings page renders through the view model`() {
        val vm = AstronomyViewModel(app)
        await("catalog") { vm.catalogReady.value }
        rule.setContent { SettingsPage(backStack = stack(), viewModel = vm) }
        rule.onNodeWithText("Settings", substring = true).assertExists()
    }

    @Test fun `search page renders through the view model`() {
        val vm = AstronomyViewModel(app)
        await("catalog") { vm.catalogReady.value }
        rule.setContent { SearchPage(backStack = stack(), viewModel = vm) }
        // Search box composes; results require typing, covered in AstronomyScreensTest
        assertTrue(vm.catalog.stars.isNotEmpty())
    }
}
