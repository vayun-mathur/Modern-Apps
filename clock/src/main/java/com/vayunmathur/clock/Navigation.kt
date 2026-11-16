package com.vayunmathur.clock

import androidx.compose.runtime.Composable
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.ui.ClockTabs
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(
    ds: DataStoreUtils,
    clockViewModel: ClockViewModel,
    initialRoute: Route?,
) {
    // A tab deep link (Alarm/Clock/Timer/Stopwatch) is the start destination
    // itself; any other initial route (dialog/settings) is pushed over the
    // default Alarm tab so back returns to a tab.
    val startTab = when (initialRoute) {
        is Route.Clock -> Route.Clock
        is Route.Timer -> Route.Timer
        is Route.Stopwatch -> Route.Stopwatch
        else -> Route.Alarm
    }
    val pushedRoute = when (initialRoute) {
        is Route.Alarm, is Route.Clock, is Route.Timer, is Route.Stopwatch, null -> null
        else -> initialRoute
    }
    val backStack = rememberNavBackStack<Route>(listOfNotNull(startTab, pushedRoute).distinct())
    ClockTabs(backStack, ds, clockViewModel)
}
