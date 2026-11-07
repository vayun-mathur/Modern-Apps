package com.vayunmathur.astronomy

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for the app [Navigation] graph and the [Route] serializers the
 * back stack persists: the graph composes the sky map entry, top-bar
 * navigation reaches the search entry, and every route round-trips.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
@RunWith(AndroidJUnit4::class)
class NavigationRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `navigation composes the sky map and reaches search`() {
        val viewModel = AstronomyViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                Navigation(viewModel)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
        // The search entry is now on top; its box takes queries.
        composeRule.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    @Test
    fun `routes round-trip through their serializers`() {
        val json = Json
        val routes: List<Route> = listOf(
            Route.SkyMap,
            Route.Search,
            Route.Settings,
            Route.ObjectDetail("PLANET_MARS"),
            Route.HistoryDatePicker(LocalDate(2026, 1, 15)),
        )
        for (route in routes) {
            val encoded = json.encodeToString(Route.serializer(), route)
            val decoded = json.decodeFromString(Route.serializer(), encoded)
            assertEquals(route, decoded, "round trip failed for $encoded")
        }
        // Distinct routes encode distinctly (the stack can tell them apart).
        assertNotEquals(
            json.encodeToString(Route.serializer(), Route.SkyMap),
            json.encodeToString(Route.serializer(), Route.Search),
        )
        assertEquals(
            "PLANET_MARS",
            (Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), Route.ObjectDetail("PLANET_MARS"))) as Route.ObjectDetail).id,
        )
    }
}
