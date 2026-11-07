package com.vayunmathur.weather.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.Route
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
class MapPageTest {

    @get:Rule val rule = createComposeRule()

    @Test fun weatherMapPageRenders() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent {
            WeatherMapPage(backStack, 52.5, 13.4, "2026-07-15T14:00", "Temperature")
        }
        rule.waitForIdle()
    }

    @Test fun weatherMapPageBadMetricRenders() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent {
            WeatherMapPage(backStack, 0.0, 0.0, null, "bogus")
        }
        rule.waitForIdle()
    }
}
