package com.vayunmathur.todo.notifications

import android.app.AlarmManager
import android.app.PendingIntent

/** Exact when allowed, inexact otherwise — including if access is revoked mid-call. */
internal fun AlarmManager.arm(at: Long, pi: PendingIntent) {
    if (!canScheduleExactAlarms()) {
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return
    }
    try {
        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    } catch (_: SecurityException) {
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
