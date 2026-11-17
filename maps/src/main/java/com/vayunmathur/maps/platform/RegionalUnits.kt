package com.vayunmathur.maps.platform
import com.vayunmathur.maps.data.string

import android.content.Context
import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.text.MeasureFormat
import android.icu.util.LocaleData
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.os.Build
import com.vayunmathur.maps.R
import java.util.Locale
import kotlin.time.Duration

/**
 * Regional (locale-driven) distance/unit formatting for maps. Units follow the
 * device's regional settings — the same mechanism the shared
 * [com.vayunmathur.library.util.formatSpeed] uses (ICU `usage("road")` on API
 * 33+, `LocaleData.getMeasurementSystem` fallback below) — instead of a
 * maps-local units toggle.
 */

/** Metres per mile / feet per metre / metres per kilometre, for the API < 33 fallback. */
private const val METERS_PER_MILE = 1609.34
private const val FEET_PER_METER = 3.28084
private const val METERS_PER_KILOMETER = 1000.0

/** True when [locale]'s regional settings prefer imperial road units (mi/ft). */
fun isImperialUnits(locale: Locale = Locale.getDefault()): Boolean {
    val system = LocaleData.getMeasurementSystem(ULocale.forLocale(locale))
    return system == LocaleData.MeasurementSystem.US ||
        system == LocaleData.MeasurementSystem.UK
}

/**
 * Format a road distance in [meters] as a short user-facing string using
 * [locale]'s regional units (e.g. "800 m" / "2.3 km" or "0.5 mi" / "150 ft").
 */
internal fun formatDistance(meters: Double, locale: Locale = Locale.getDefault()): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return NumberFormatter.with()
            .usage("road")
            .unit(MeasureUnit.METER)
            .locale(locale)
            .unitWidth(NumberFormatter.UnitWidth.SHORT)
            .format(meters)
            .toString()
    }

    // Fallback for API < 33 (no usage("road")): pick the unit by regional system.
    val (unit, value) = if (isImperialUnits(locale)) {
        if (meters >= METERS_PER_MILE) MeasureUnit.MILE to (meters / METERS_PER_MILE)
        else MeasureUnit.FOOT to (meters * FEET_PER_METER)
    } else {
        if (meters >= METERS_PER_KILOMETER) MeasureUnit.KILOMETER to (meters / METERS_PER_KILOMETER)
        else MeasureUnit.METER to meters
    }
    return NumberFormatter.with()
        .unit(unit)
        .locale(locale)
        .unitWidth(NumberFormatter.UnitWidth.SHORT)
        .precision(Precision.maxFraction(1))
        .format(value)
        .toString()
}

/**
 * A duration split into the largest units worth showing: hours and whole minutes.
 *
 * Seconds are dropped, not carried, so this is the shape of "1 h 12 min" rather than
 * a full breakdown. Both fields zero means the duration is under a minute, which is
 * its own rendering — see [isUnderAMinute].
 */
internal data class DurationParts(val hours: Int, val minutes: Int) {
    /** True when there is nothing to show: "0 min" reads as no journey at all. */
    val isUnderAMinute: Boolean get() = hours == 0 && minutes == 0
}

/**
 * Split [duration] for display, truncating rather than rounding.
 *
 * Truncating matches every other time this app shows: a 1 h 12 min 40 s route reads
 * "1 h 12 min", not "1 h 13 min". Kept separate from [formatDuration] because the
 * ICU formatting there needs a device and this arithmetic does not.
 */
internal fun durationParts(duration: Duration): DurationParts {
    val total = duration.inWholeMinutes.coerceAtLeast(0)
    return DurationParts(hours = (total / 60).toInt(), minutes = (total % 60).toInt())
}

/**
 * Format a travel duration as a short user-facing string, largest units only:
 * "1 h 12 min", "45 min", or "less than a minute".
 *
 * Never emits seconds. Localised through ICU rather than a format string so the unit
 * names and their order follow [locale], the same way [formatDistance] does.
 */
internal fun formatDuration(
    context: Context,
    duration: Duration,
    locale: Locale = Locale.getDefault(),
): String {
    val parts = durationParts(duration)
    if (parts.isUnderAMinute) return context.getString(R.string.duration_under_a_minute)
    val measures = buildList {
        if (parts.hours > 0) add(Measure(parts.hours, MeasureUnit.HOUR))
        if (parts.minutes > 0) add(Measure(parts.minutes, MeasureUnit.MINUTE))
    }
    return MeasureFormat
        .getInstance(ULocale.forLocale(locale), MeasureFormat.FormatWidth.SHORT)
        .formatMeasures(*measures.toTypedArray())
}
