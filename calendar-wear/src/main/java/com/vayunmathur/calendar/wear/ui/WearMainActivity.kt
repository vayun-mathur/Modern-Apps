package com.vayunmathur.calendar.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.calendar.wear.data.WearCalendarStore

/**
 * Watch calendar entry: agenda list and event detail, rendered only from the
 * locally stored snapshot so it works standalone (phone absent, cold start).
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WearCalendarStore.get(this)
        store.publishWatchState()
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Agenda) }
                when (val current = route) {
                    WearRoute.Agenda -> WearAgendaScreen(
                        store = store,
                        onOpenEvent = { event ->
                            route = WearRoute.Event(event.eventId, event.startMillis)
                        },
                    )
                    is WearRoute.Event -> WearEventScreen(
                        store = store,
                        eventId = current.eventId,
                        startMillis = current.startMillis,
                        onBack = { route = WearRoute.Agenda },
                    )
                }
            }
        }
    }
}
