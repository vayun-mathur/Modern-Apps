package com.vayunmathur.clock.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.platform.WatchRingNotifier
import com.vayunmathur.clock.wear.sync.WearLinkBridge

/**
 * Watch clock entry: home menu over world clocks (synced), alarms (synced,
 * watch-armed), timers and stopwatch (watch-native). Everything renders
 * from the locally persisted store so it works with the phone absent.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        WatchRingNotifier.ensureChannel(this)
        val store = WearClockStore.get(this)
        WearLinkBridge.get(this)
        WearLinkBridge.publishWatchState(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Home) }
                when (val current = route) {
                    WearRoute.Home -> WearHomeScreen(
                        onOpen = { route = it },
                    )
                    WearRoute.World -> WearWorldClockScreen(
                        store = store, onBack = { route = WearRoute.Home },
                    )
                    WearRoute.Alarms -> WearAlarmsScreen(
                        store = store,
                        onBack = { route = WearRoute.Home },
                        onOpenAlarm = { route = WearRoute.AlarmDetail(it) },
                    )
                    is WearRoute.AlarmDetail -> WearAlarmDetailScreen(
                        store = store,
                        alarmId = current.alarmId,
                        onBack = { route = WearRoute.Alarms },
                    )
                    WearRoute.Timers -> WearTimersScreen(
                        store = store, onBack = { route = WearRoute.Home },
                    )
                    WearRoute.Stopwatch -> WearStopwatchScreen(
                        store = store, onBack = { route = WearRoute.Home },
                    )
                }
            }
        }
    }
}
