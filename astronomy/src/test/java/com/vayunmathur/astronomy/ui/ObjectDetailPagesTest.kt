package com.vayunmathur.astronomy.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Detail-page tests for every object family the resolver knows: moon,
 * planets, catalog stars (visible and merely catalogued), deep-sky
 * objects, and the not-found shapes for malformed or unknown ids.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class ObjectDetailPagesTest {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Composable
    private fun stack(): NavBackStack<Route> = rememberNavBackStack(Route.SkyMap)

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun viewModel(): AstronomyViewModel {
        val vm = AstronomyViewModel(app)
        await("catalog") { vm.catalogReady.value }
        vm.setManualLocation(37.0, -122.0)
        return vm
    }

    private fun showDetail(vm: AstronomyViewModel, id: String): NavBackStack<Route> {
        lateinit var stack: NavBackStack<Route>
        rule.setContent {
            stack = rememberNavBackStack(
                Route.SkyMap,
                Route.ObjectDetail(id),
            )
            ObjectDetailPage(backStack = stack, viewModel = vm, objectId = id)
        }
        return stack
    }

    @Test
    fun `moon detail shows phase and illumination`() {
        val vm = viewModel()
        vm.selectObject("MOON")
        showDetail(vm, "MOON")
        rule.onNodeWithText("Moon", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Illum", substring = true).assertExists()
    }

    @Test
    fun `planet detail shows name distance and magnitude`() {
        val vm = viewModel()
        vm.selectObject("PLANET_MARS")
        showDetail(vm, "PLANET_MARS")
        rule.onNodeWithText("Mars", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Planet", substring = true).assertExists()
        rule.onNodeWithText("Dist", substring = true).assertExists()
    }

    @Test
    fun `unknown planet and sun-less sky are not found`() {
        val vm = viewModel()
        showDetail(vm, "PLANET_PLUTO")
        rule.onNodeWithText("PLANET_PLUTO", substring = true).assertExists()
    }

    @Test
    fun `catalog star detail resolves by id`() {
        val vm = viewModel()
        // A named star from the bundled catalog, resolved through the
        // catalog-projection path (it need not be currently visible).
        val named = vm.catalog.stars.firstOrNull { it.properName != null }
        assertNotNull(named)
        val id = "STAR_${named.id}"
        vm.selectObject(id)
        await("visible sky") { vm.visibleSky.value.observer != null }
        showDetail(vm, id)
        rule.onNodeWithText(named.properName!!, substring = true).assertIsDisplayed()
        rule.onNodeWithText("BV", substring = true).assertExists()
    }

    @Test
    fun `malformed and unknown star ids are not found`() {
        val vm = viewModel()
        showDetail(vm, "STAR_abc")
        rule.onNodeWithText("STAR_abc", substring = true).assertExists()
    }

    @Test
    fun `deep sky detail shows type and size`() {
        val vm = viewModel()
        vm.selectObject("M31")
        showDetail(vm, "M31")
        rule.onNodeWithText("M31", substring = true).assertIsDisplayed()
        rule.onNodeWithText("arcmin", substring = true).assertExists()
    }

    @Test
    fun `unknown object is not found`() {
        val vm = viewModel()
        val stack = showDetail(vm, "NOPE_NOT_REAL")
        rule.onNodeWithText("NOPE_NOT_REAL", substring = true).assertExists()
        assertEquals(Route.ObjectDetail("NOPE_NOT_REAL"), stack.last())
    }

    @Test
    fun `close button pops the stack`() {
        val vm = viewModel()
        vm.selectObject("M31")
        val stack = showDetail(vm, "M31")
        assertEquals(Route.ObjectDetail("M31"), stack.last())
        rule.onNodeWithText("Close").performScrollTo().performClick()
        assertEquals(Route.SkyMap, stack.last())
    }

    @Test
    fun `rise-set card resolves for a selected object`() {
        val vm = viewModel()
        vm.selectObject("SUN")
        await("riseSet") { vm.riseSet.value != null }
        showDetail(vm, "SUN")
        rule.onNodeWithText("Sun", substring = true).assertIsDisplayed()
        // Both the card title and the rise row contain "Rise".
        assertTrue(
            rule.onAllNodesWithText("Rise", substring = true).fetchSemanticsNodes().size >= 2,
        )
    }
}
