package com.vayunmathur.clock.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.ui.dialogs.NewAlarmDialog
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.clock.ui.dialogs.SelectTimeZonesDialog
import com.vayunmathur.library.ui.IconAccessTime
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconHourglass
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage

/**
 * The four bottom-nav tabs as sibling [Route] destinations: the route-backed
 * [TabbedPagerScaffold] owns the chrome and resets the stack on tab select,
 * while [MainNavigation] hosted in its content slot renders the tab on top
 * (and any dialog/settings destination pushed over it, with no tab selected).
 */
@Composable
fun ClockTabs(
    backStack: NavBackStack<Route>,
    ds: DataStoreUtils,
    clockViewModel: ClockViewModel,
) {
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Alarm, stringResource(R.string.label_alarm)) { IconAlarm() },
        RouteTab(Route.Clock, stringResource(R.string.app_name)) { IconAccessTime() },
        RouteTab(Route.Timer, stringResource(R.string.label_timer)) { IconHourglass() },
        RouteTab(Route.Stopwatch, stringResource(R.string.channel_stopwatch_name)) { IconTimer() },
    )
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
            entry<Route.Alarm>(metadata = SiblingPage()) {
                AlarmPage(backStack, clockViewModel)
            }
            entry<Route.Clock>(metadata = SiblingPage()) {
                ClockPage(backStack, ds, clockViewModel)
            }
            entry<Route.Timer>(metadata = SiblingPage()) {
                TimerPage(clockViewModel)
            }
            entry<Route.Stopwatch>(metadata = SiblingPage()) {
                StopwatchPage(clockViewModel)
            }
            entry<Route.AlarmSettings> {
                AlarmSettingsPage(backStack, ds)
            }
            entry<Route.SelectTimeZonesDialog>(metadata = DialogPage()) {
                SelectTimeZonesDialog(backStack, ds, clockViewModel)
            }
            entry<Route.NewTimerDialog>(metadata = DialogPage()) { key ->
                NewTimerDialog(backStack, clockViewModel, key.lengthSeconds, key.message)
            }
            entry<Route.NewAlarmDialog>(metadata = DialogPage()) { key ->
                NewAlarmDialog(
                    backStack = backStack,
                    clockViewModel = clockViewModel,
                    initialHour = key.hour,
                    initialMinutes = key.minutes,
                    initialMessage = key.message,
                    initialDays = key.days,
                )
            }
            entry<Route.AlarmSetTimeDialog>(metadata = DialogPage()) {
                TimePickerDialogContent(backStack, "alarm_set_time_${it.id}", it.time)
            }
        }
    }
}
