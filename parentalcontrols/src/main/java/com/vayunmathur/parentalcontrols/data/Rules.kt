package com.vayunmathur.parentalcontrols.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.parentalcontrols.domain.TimeWindow

/**
 * What supervision does to one app.
 *
 * A row exists only for apps the parent has actually chosen; absence means unsupervised, which
 * is why there is no "unrestricted" state here. Both restrictions live on the same row because
 * they are two answers to one question - when may this app be used - and keeping them together
 * is what lets the enforcer resolve them without a join.
 *
 * Order of precedence when both apply is decided in the enforcer, not here: bedtime wins,
 * because a limit that has not been reached should not unblock an app during the night.
 */
@Entity
data class AppRule(
    @PrimaryKey val packageName: String,
    /**
     * Minutes of use permitted per day, or null for no limit.
     *
     * Capped at 24 h by the platform - `PackageUsagePolicy.Builder.performBuild` throws above
     * that, and on a negative value.
     */
    val dailyLimitMinutes: Int? = null,
    /** Whether this app is blocked during the bedtime window. */
    val blockedAtBedtime: Boolean = false,
    /**
     * Whether this app stays usable during downtime and school time.
     *
     * During those windows everything else is blocked, so this is the allow-list. Defaults to
     * false: a newly restricted app is blocked everywhere until the parent approves it.
     */
    val allowedInDowntime: Boolean = false,
)

/**
 * The nightly window during which [AppRule.blockedAtBedtime] apps are unavailable.
 *
 * Stored as minutes past local midnight rather than a timestamp so it survives timezone changes
 * and DST the way a user expects: "9pm" means 9pm wherever the device is.
 *
 * [startMinute] greater than [endMinute] is the normal case, not an error - a window that
 * crosses midnight. [contains] is the only place that asymmetry is interpreted.
 */
@Entity
data class BedtimeSchedule(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    /** Minutes past local midnight at which the window opens. */
    val startMinute: Int = DEFAULT_START_MINUTE,
    /** Minutes past local midnight at which it closes. */
    val endMinute: Int = DEFAULT_END_MINUTE,
    /** Bitmask of days the window applies to, bit 0 = Monday. */
    val daysMask: Int = ALL_DAYS,
) {
    /** Whether [minuteOfDay] on [dayIndex] (0 = Monday) falls inside the window. */
    fun contains(dayIndex: Int, minuteOfDay: Int): Boolean =
        asWindow().contains(dayIndex, minuteOfDay)

    fun isDaySet(dayIndex: Int): Boolean = (daysMask shr dayIndex) and 1 == 1

    private fun asWindow(): TimeWindow =
        TimeWindow(
            enabled = enabled,
            startMinute = startMinute,
            endMinute = endMinute,
            daysMask = daysMask,
        )

    /** Singleton key and default 21:00-07:00 window for [BedtimeSchedule]. */
    companion object {
        /** There is one schedule. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0
        const val DAYS_IN_WEEK = TimeWindow.DAYS_IN_WEEK
        const val ALL_DAYS = TimeWindow.ALL_DAYS
        const val MINUTES_PER_DAY = TimeWindow.MINUTES_PER_DAY

        /** 21:00. */
        const val DEFAULT_START_MINUTE = 21 * 60

        /** 07:00. */
        const val DEFAULT_END_MINUTE = 7 * 60
    }
}

/**
 * The daytime window during which only parent-approved apps are available.
 *
 * Same shape as [BedtimeSchedule] with its own on/off, times and days, so the two windows can
 * overlap or sit apart without interfering. Defaults to off; when on, everything not explicitly
 * allowed is blocked. The allow-list lives on [AppRule]: an app usable during downtime carries
 * `allowedInDowntime = true`.
 */
@Entity
data class DowntimeSchedule(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    /** Minutes past local midnight at which the window opens. */
    val startMinute: Int = DEFAULT_START_MINUTE,
    /** Minutes past local midnight at which it closes. */
    val endMinute: Int = DEFAULT_END_MINUTE,
    /** Bitmask of days the window applies to, bit 0 = Monday. */
    val daysMask: Int = TimeWindow.ALL_DAYS,
) {
    /** Whether [minuteOfDay] on [dayIndex] (0 = Monday) falls inside the window. */
    fun contains(dayIndex: Int, minuteOfDay: Int): Boolean =
        asWindow().contains(dayIndex, minuteOfDay)

    private fun asWindow(): TimeWindow =
        TimeWindow(
            enabled = enabled,
            startMinute = startMinute,
            endMinute = endMinute,
            daysMask = daysMask,
        )

    /** Singleton key and default 12:00-14:00 window for [DowntimeSchedule]. */
    companion object {
        /** There is one schedule. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0

        /** 12:00. */
        const val DEFAULT_START_MINUTE = 12 * 60

        /** 14:00. */
        const val DEFAULT_END_MINUTE = 14 * 60
    }
}

/**
 * The school-hours window during which only learning apps are available.
 *
 * Same shape as [BedtimeSchedule] with its own on/off, times and days. Defaults to 08:00-15:00
 * on weekdays, off until the parent enables it. The allow-list is shared with downtime: an app
 * usable during school carries `allowedInDowntime = true`.
 */
@Entity
data class SchoolTimeSchedule(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    /** Minutes past local midnight at which the window opens. */
    val startMinute: Int = DEFAULT_START_MINUTE,
    /** Minutes past local midnight at which it closes. */
    val endMinute: Int = DEFAULT_END_MINUTE,
    /** Bitmask of days the window applies to, bit 0 = Monday. */
    val daysMask: Int = TimeWindow.WEEKDAYS,
) {
    /** Whether [minuteOfDay] on [dayIndex] (0 = Monday) falls inside the window. */
    fun contains(dayIndex: Int, minuteOfDay: Int): Boolean =
        asWindow().contains(dayIndex, minuteOfDay)

    private fun asWindow(): TimeWindow =
        TimeWindow(
            enabled = enabled,
            startMinute = startMinute,
            endMinute = endMinute,
            daysMask = daysMask,
        )

    /** Singleton key and default 08:00-15:00 window for [SchoolTimeSchedule]. */
    companion object {
        /** There is one schedule. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0

        /** 08:00. */
        const val DEFAULT_START_MINUTE = 8 * 60

        /** 15:00. */
        const val DEFAULT_END_MINUTE = 15 * 60
    }
}

/**
 * The device-wide daily screen-time budget, mirroring Family Link's daily limit.
 *
 * Null means no device-wide cap; per-app caps still live on [AppRule.dailyLimitMinutes]. When
 * the budget is spent, everything except calls and parent-approved apps locks until midnight,
 * with the same lock-screen UX as an app limit. Stored as minutes so the UI offers the same
 * fixed choices as per-app limits.
 */
@Entity
data class DailyLimit(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Minutes of screen time permitted per day, or null for no device-wide limit. */
    val dailyLimitMinutes: Int? = null,
) {
    /** Singleton key for the one device-wide [DailyLimit] row. */
    companion object {
        /** There is one limit. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0
    }
}

/**
 * A one-shot grant of extra minutes, given on-device by the parent from a lock screen.
 *
 * Family Link's bonus time, without the cloud: the child taps "ask for more time", the parent
 * enters the PIN on the same device, and the grant extends today's budget. Rows are stamped
 * with the day they belong to so a grant can never leak into tomorrow; the enforcer prunes
 * foreign-day rows on reconcile.
 */
@Entity
data class BonusGrant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Package the grant applies to, or null for a device-wide (daily-limit) grant. */
    val packageName: String? = null,
    /** Extra minutes added to today's budget for [packageName]. */
    val bonusMinutes: Int = 0,
    /** Local date the grant belongs to, ISO-8601 (yyyy-MM-dd). */
    val day: String = "",
)
