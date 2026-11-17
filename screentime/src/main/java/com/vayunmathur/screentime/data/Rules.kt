package com.vayunmathur.screentime.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A self-imposed daily cap on one app.
 *
 * Unlike parental controls, a row here is a promise the user makes to themselves: reaching it
 * pauses the app with a dismissible notice rather than a PIN-locked screen. Absence means no
 * timer.
 */
@Entity
data class AppTimer(
    @PrimaryKey val packageName: String,
    /** Minutes of use permitted per day. */
    val dailyLimitMinutes: Int,
)

/**
 * Which apps the user has paused by hand, per app.
 *
 * One row (singleton): just the paused set. Pausing is unconditional and per-app - there is
 * no global focus session, and nothing here touches Do Not Disturb.
 */
@Entity
data class PausedApps(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Packages currently paused by the user. */
    val pausedPackages: List<String> = emptyList(),
) {
    /** Singleton row identifier for [PausedApps]. */
    companion object {
        /** There is one row. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0
    }
}

/**
 * The overnight wind-down: grayscale plus Do Not Disturb.
 *
 * Self-managed bedtime without enforcement - nothing is blocked, the screen just goes quiet.
 * Same minute-past-midnight storage as the supervision windows so behavior matches user
 * expectations across timezone changes and DST.
 */
@Entity
data class WindDownSchedule(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = false,
    /** Minutes past local midnight at which wind-down starts. */
    val startMinute: Int = DEFAULT_START_MINUTE,
    /** Minutes past local midnight at which wind-down ends. */
    val endMinute: Int = DEFAULT_END_MINUTE,
    /** Bitmask of days wind-down applies to, bit 0 = Monday. */
    val daysMask: Int = ALL_DAYS,
    /** Whether to desaturate the display during wind-down. */
    val grayscale: Boolean = true,
    /** Whether to enter Do Not Disturb during wind-down. */
    val doNotDisturb: Boolean = true,
) {
    /** Singleton identifier and day/default constants for [WindDownSchedule]. */
    companion object {
        /** There is one schedule. The table exists so Room can store it, not to hold many. */
        const val SINGLETON_ID = 0
        const val ALL_DAYS = 0b111_1111

        /** 22:00. */
        const val DEFAULT_START_MINUTE = 22 * 60

        /** 07:00. */
        const val DEFAULT_END_MINUTE = 7 * 60
    }
}
