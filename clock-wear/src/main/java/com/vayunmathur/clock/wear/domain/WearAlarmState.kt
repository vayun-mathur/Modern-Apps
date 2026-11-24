package com.vayunmathur.clock.wear.domain

import kotlinx.serialization.Serializable

/** Why an enabled alarm is only displayed, never claimed as armed. */
enum class DisplayOnlyReason {
    /** The phone has this alarm turned off. */
    DISABLED_ON_PHONE,

    /** No successful arming has been recorded yet for this snapshot. */
    NOT_YET_ARMED,

    /** AlarmManager refused the schedule (exact-alarm access revoked etc.). */
    SCHEDULING_FAILED,
}

/**
 * Honest per-alarm state on the watch. An alarm is [Armed] only when a
 * record exists proving the watch's own AlarmManager accepted a schedule
 * for it; every other case is [DisplayOnly] with the reason. The UI renders
 * exactly this — it never infers "armed" from the phone's `enabled` flag.
 */
sealed interface WearAlarmDisplayState {
    /** AlarmManager accepted a schedule; it will ring on the watch at [nextTriggerMillis]. */
    data class Armed(
        val nextTriggerMillis: Long,
        /** True for setAlarmClock/exact, false for the inexact while-idle fallback. */
        val exact: Boolean,
    ) : WearAlarmDisplayState

    /** Shown from the snapshot only; nothing will ring for this alarm. */
    data class DisplayOnly(val reason: DisplayOnlyReason) : WearAlarmDisplayState
}

/** Proof of one successful watch-local arming, persisted by the store. */
@Serializable
data class ArmedAlarmRecord(
    val alarmId: Long,
    val nextTriggerMillis: Long,
    val exact: Boolean,
    /** Snapshot generation this arming was reconciled from. */
    val snapshotGeneration: Long = 0L,
)

/** One failed arming attempt, persisted so the UI can name the reason. */
@Serializable
data class ArmFailureRecord(
    val alarmId: Long,
    val reason: DisplayOnlyReason = DisplayOnlyReason.SCHEDULING_FAILED,
)

/** Outcome of one attempt to arm an alarm with the watch's AlarmManager. */
sealed interface ArmAttempt {
    data class Armed(val record: ArmedAlarmRecord) : ArmAttempt
    data class Failed(val reason: DisplayOnlyReason) : ArmAttempt
}

/**
 * The display state for [alarm] given the persisted arming proof. A record
 * for a different alarm, a stale (already-past) trigger, or no record at
 * all can never produce [WearAlarmDisplayState.Armed].
 */
fun resolveWearAlarmDisplay(
    alarm: WearClockAlarm,
    armed: ArmedAlarmRecord?,
    failure: ArmFailureRecord?,
    nowMillis: Long,
): WearAlarmDisplayState {
    if (!alarm.enabled) return WearAlarmDisplayState.DisplayOnly(DisplayOnlyReason.DISABLED_ON_PHONE)
    if (armed != null && armed.alarmId == alarm.alarmId && armed.nextTriggerMillis > nowMillis) {
        return WearAlarmDisplayState.Armed(armed.nextTriggerMillis, armed.exact)
    }
    if (failure != null && failure.alarmId == alarm.alarmId) {
        return WearAlarmDisplayState.DisplayOnly(failure.reason)
    }
    return WearAlarmDisplayState.DisplayOnly(DisplayOnlyReason.NOT_YET_ARMED)
}

/** Counts for the watch-state document: armed vs display-only among enabled alarms. */
fun armedAndDisplayOnlyCounts(
    alarms: List<WearClockAlarm>,
    armedRecords: List<ArmedAlarmRecord>,
    failures: List<ArmFailureRecord>,
    nowMillis: Long,
): Pair<Int, Int> {
    var armedCount = 0
    var displayOnlyCount = 0
    for (alarm in alarms) {
        if (!alarm.enabled) continue
        when (resolveWearAlarmDisplay(
            alarm,
            armedRecords.firstOrNull { it.alarmId == alarm.alarmId },
            failures.firstOrNull { it.alarmId == alarm.alarmId },
            nowMillis,
        )) {
            is WearAlarmDisplayState.Armed -> armedCount++
            is WearAlarmDisplayState.DisplayOnly -> displayOnlyCount++
        }
    }
    return armedCount to displayOnlyCount
}
