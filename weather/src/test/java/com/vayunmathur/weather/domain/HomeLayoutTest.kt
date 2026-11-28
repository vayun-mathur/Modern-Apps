package com.vayunmathur.weather.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLayoutTest {

    @Test fun breakpointsMatchWindowSizeClasses() {
        assertEquals(HomeLayout.Compact, homeLayoutForWidthDp(0))
        assertEquals(HomeLayout.Compact, homeLayoutForWidthDp(411))
        assertEquals(HomeLayout.Compact, homeLayoutForWidthDp(599))
        assertEquals(HomeLayout.Medium, homeLayoutForWidthDp(600))
        assertEquals(HomeLayout.Medium, homeLayoutForWidthDp(839))
        assertEquals(HomeLayout.Expanded, homeLayoutForWidthDp(840))
        assertEquals(HomeLayout.Expanded, homeLayoutForWidthDp(1280))
    }

    @Test fun onlyExpandedPinsLocationsPanel() {
        assertFalse(homeUsesPermanentLocationsPanel(HomeLayout.Compact))
        assertFalse(homeUsesPermanentLocationsPanel(HomeLayout.Medium))
        assertTrue(homeUsesPermanentLocationsPanel(HomeLayout.Expanded))
    }

    @Test fun wideLayoutsShowTwoForecastColumns() {
        assertEquals(1, forecastColumnCount(HomeLayout.Compact))
        assertEquals(2, forecastColumnCount(HomeLayout.Medium))
        assertEquals(2, forecastColumnCount(HomeLayout.Expanded))
    }

    @Test fun panelIsBoundedAndForecastKeepsMajority() {
        // Panel clamps to 280-360dp however wide the window gets.
        assertEquals(280, locationsPanelWidthDp(840))
        assertEquals(300, locationsPanelWidthDp(1000))
        assertEquals(360, locationsPanelWidthDp(1280))
        assertEquals(360, locationsPanelWidthDp(2560))
        // Regression for the landscape squeeze: the forecast beside the
        // panel is never the narrow (~1/3) column — it keeps >= 60%.
        for (width in listOf(840, 985, 1280, 1920)) {
            val layout = homeLayoutForWidthDp(width)
            val forecast = forecastWidthDp(width, layout)
            assertTrue(forecast * 5 >= width * 3, "forecast $forecast of $width")
            assertEquals(width - locationsPanelWidthDp(width), forecast)
        }
    }

    @Test fun nonExpandedForecastUsesFullWidth() {
        assertEquals(411, forecastWidthDp(411, HomeLayout.Compact))
        // A landscape phone (medium) gets the whole width, not a pane of it.
        assertEquals(800, forecastWidthDp(800, HomeLayout.Medium))
    }
}
