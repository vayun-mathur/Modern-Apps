package com.vayunmathur.calendar.wear.ui

/** Watch navigation destinations: detail is a route carrying the event key, never selection state. */
sealed interface WearRoute {
    /** Today's agenda + upcoming list. */
    data object Agenda : WearRoute

    /** One occurrence's detail, identified by phone event id + occurrence start. */
    data class Event(val eventId: Long, val startMillis: Long) : WearRoute
}
