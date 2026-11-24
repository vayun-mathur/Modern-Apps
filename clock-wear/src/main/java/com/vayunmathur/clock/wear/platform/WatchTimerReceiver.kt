package com.vayunmathur.clock.wear.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.clock.wear.data.WearClockStore

/** A watch-native timer expired: mark it finished (persisted) and ring. */
class WatchTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getLongExtra(WatchTimerScheduler.EXTRA_TIMER_ID, -1L)
        if (timerId < 0) return
        val label = intent.getStringExtra(WatchTimerScheduler.EXTRA_LABEL).orEmpty()
        val store = WearClockStore.get(context)
        store.state.value.timers.firstOrNull { it.id == timerId }?.let { timer ->
            store.upsertTimer(
                timer.copy(running = false, finished = true, remainingMillis = 0L),
            )
        }
        WatchRingNotifier.show(context, RingKind.TIMER, timerId, label)
    }
}
