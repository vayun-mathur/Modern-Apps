package com.vayunmathur.clock.wear.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.domain.watchTimerRemainingMillis

/**
 * After a reboot AlarmManager schedules are gone: re-arm the synced alarms
 * from the persisted snapshot and the watch-native timers from their
 * persisted anchors. A timer that expired while the watch was off is
 * marked finished and rung immediately rather than silently lost.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = WearClockStore.get(context)
        val persisted = store.state.value
        val now = System.currentTimeMillis()
        val (armed, failures) = WatchAlarmScheduler.reconcile(
            context, persisted.snapshot, persisted.armedRecords.map { it.alarmId }, now,
        )
        store.setArming(armed, failures)
        for (timer in persisted.timers) {
            if (!timer.running || timer.finished) continue
            val remaining = watchTimerRemainingMillis(timer, now, SystemClock.elapsedRealtime())
            if (remaining <= 0L) {
                store.upsertTimer(timer.copy(running = false, finished = true, remainingMillis = 0L))
                WatchRingNotifier.show(context, RingKind.TIMER, timer.id, timer.label)
            } else {
                // Re-anchor on the wall base: the pre-boot monotonic anchor
                // is meaningless now, and the scheduler falls back to RTC.
                val reanchored = timer.copy(startElapsedMillis = null)
                store.upsertTimer(reanchored)
                WatchTimerScheduler.schedule(context, reanchored)
            }
        }
    }
}
