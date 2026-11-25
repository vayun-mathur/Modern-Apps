package com.vayunmathur.library.format

/**
 * Which relative-age bucket a timestamp falls in, without any wording.
 *
 * Calendar dates and clock times live in `library:ui`'s `DateString`, which
 * deliberately excludes relative time. The bucketing below was previously
 * re-derived per app (news feed rows, games-hub activity, madeveloper commit
 * lists, the find-family watch) with the same minute/hour/day thresholds and
 * only the labels and the older-than-a-week fallback differing. Apps keep
 * those labels and fallbacks; the thresholds now live in one place.
 */
sealed interface RelativeAge {
    /** No usable timestamp (`atMillis <= 0`). */
    data object Unknown : RelativeAge

    /** Timestamp is after [nowMillis][relativeAge]. */
    data object Future : RelativeAge

    /** Less than a minute old. */
    data object JustNow : RelativeAge

    /** [amount] whole minutes old, 1..59. */
    data class Minutes(val amount: Long) : RelativeAge

    /** [amount] whole hours old, 1..23. */
    data class Hours(val amount: Long) : RelativeAge

    /** [amount] whole days old, at least 1 and under the caller's [maxDays]. */
    data class Days(val amount: Long) : RelativeAge

    /** At least [maxDays] days old; the caller renders its own fallback. */
    data object Older : RelativeAge
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/**
 * Buckets [atMillis] relative to [nowMillis].
 *
 * A non-positive [atMillis] is [RelativeAge.Unknown], a future timestamp is
 * [RelativeAge.Future] (callers that clamped instead of branching get the
 * same text by mapping both to their just-now label), and ages of at least
 * [maxDays] days are [RelativeAge.Older]. The default [maxDays] never
 * triggers, so day counts are unbounded unless a caller opts into a
 * date fallback.
 */
fun relativeAge(atMillis: Long, nowMillis: Long, maxDays: Long = Long.MAX_VALUE): RelativeAge {
    if (atMillis <= 0L) return RelativeAge.Unknown
    val ageMillis = nowMillis - atMillis
    if (ageMillis < 0L) return RelativeAge.Future
    val minutes = ageMillis / MILLIS_PER_MINUTE
    val hours = ageMillis / MILLIS_PER_HOUR
    val days = ageMillis / MILLIS_PER_DAY
    return when {
        minutes < 1L -> RelativeAge.JustNow
        minutes < MINUTES_PER_HOUR -> RelativeAge.Minutes(minutes)
        hours < HOURS_PER_DAY -> RelativeAge.Hours(hours)
        days >= maxDays -> RelativeAge.Older
        else -> RelativeAge.Days(days)
    }
}

/**
 * The shared `"<amount>m ago"` / `"<amount>h ago"` / `"<amount>d ago"` wording.
 *
 * Only the bucket labels are parameters: [justNowLabel] and [futureLabel]
 * cover the apps that say "now" instead of "just now", [unknownLabel] is
 * what an unusable timestamp renders as, and [olderLabel] (given the original
 * timestamp) renders ages past [maxDays] for apps that fall back to a date.
 * Without [olderLabel], older ages keep counting days.
 */
fun formatRelativeAge(
    atMillis: Long,
    nowMillis: Long,
    justNowLabel: String = "just now",
    futureLabel: String = justNowLabel,
    unknownLabel: String = "",
    maxDays: Long = Long.MAX_VALUE,
    olderLabel: ((atMillis: Long) -> String)? = null,
): String =
    when (val age = relativeAge(atMillis, nowMillis, maxDays)) {
        RelativeAge.Unknown -> unknownLabel
        RelativeAge.Future -> futureLabel
        RelativeAge.JustNow -> justNowLabel
        is RelativeAge.Minutes -> "${age.amount}m ago"
        is RelativeAge.Hours -> "${age.amount}h ago"
        is RelativeAge.Days -> "${age.amount}d ago"
        RelativeAge.Older ->
            olderLabel?.invoke(atMillis)
                ?: "${(nowMillis - atMillis) / MILLIS_PER_DAY}d ago"
    }
