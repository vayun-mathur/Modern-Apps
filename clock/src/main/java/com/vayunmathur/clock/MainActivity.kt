package com.vayunmathur.clock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.clock.ui.AlarmPage
import com.vayunmathur.clock.ui.ClockPage
import com.vayunmathur.clock.ui.StopwatchPage
import com.vayunmathur.clock.ui.TimerPage
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation()
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Alarm: Route
    @Serializable
    data object Clock: Route
    @Serializable
    data object Timer: Route
    @Serializable
    data object Stopwatch: Route
}

val MAIN_PAGES = listOf(
    BottomBarItem("Alarm", Route.Alarm, R.drawable.baseline_access_alarm_24),
    BottomBarItem("Clock", Route.Clock, R.drawable.baseline_access_time_24),
    BottomBarItem("Timer", Route.Timer, R.drawable.baseline_hourglass_bottom_24),
    BottomBarItem("Stopwatch", Route.Stopwatch, R.drawable.outline_timer_24)
)

@Composable
fun Navigation() {
    val backStack = rememberNavBackStack<Route>(Route.Alarm)
    MainNavigation(backStack) {
        entry<Route.Alarm> {
            AlarmPage(backStack)
        }
        entry<Route.Clock> {
            ClockPage(backStack)
        }
        entry<Route.Timer> {
            TimerPage(backStack)
        }
        entry<Route.Stopwatch> {
            StopwatchPage(backStack)
        }
    }
}