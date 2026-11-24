package com.vayunmathur.clock.wear.ui

/** Watch navigation destinations: detail is a route carrying the alarm id, never selection state. */
sealed interface WearRoute {
    data object Home : WearRoute
    data object World : WearRoute
    data object Alarms : WearRoute
    data object Timers : WearRoute
    data object Stopwatch : WearRoute

    /** One synced alarm's honest armed/display-only state, by phone alarm id. */
    data class AlarmDetail(val alarmId: Long) : WearRoute
}
