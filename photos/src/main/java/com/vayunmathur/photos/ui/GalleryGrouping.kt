package com.vayunmathur.photos.ui

import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal fun groupPhotosByMonth(
    photos: List<Photo>,
    resources: android.content.res.Resources,
): Map<String, List<Photo>> {
    if (photos.isEmpty()) return emptyMap()
    val monthNames = localizedMonthNames(DateNameStyle.SHORT)
    val zone = TimeZone.currentSystemDefault()
    // Bucket by month-start date. No per-group sort: PhotoDao.getAllFlow
    // already returns rows newest-first (ORDER BY date DESC, served by
    // index_Photo_date) and insertion order within each group preserves
    // that, so re-sorting every month was sorting sorted data.
    //
    // The library arrives date-descending, so consecutive photos usually
    // share a month: reuse the last bucket when the timestamp falls inside
    // its [start, nextStart) range instead of converting every photo
    // through the timezone (an Instant->LocalDateTime per photo was the
    // grouping cost on first paint). A timestamp outside the cached range
    // (unsorted input included) converts exactly as before.
    val byMonth = LinkedHashMap<LocalDate, MutableList<Photo>>()
    var cachedMonth: LocalDate? = null
    var cachedStart = Long.MIN_VALUE
    var cachedEnd = Long.MIN_VALUE
    for (photo in photos) {
        val month = if (photo.date in cachedStart until cachedEnd) {
            cachedMonth!!
        } else {
            val date = Instant.fromEpochMilliseconds(photo.date)
                .toLocalDateTime(zone)
            LocalDate(date.year, date.month, 1).also { start ->
                cachedMonth = start
                cachedStart = start.atStartOfDayIn(zone).toEpochMilliseconds()
                val next = if (start.month.ordinal == 11) {
                    LocalDate(start.year + 1, kotlinx.datetime.Month.JANUARY, 1)
                } else {
                    LocalDate(start.year, kotlinx.datetime.Month.entries[start.month.ordinal + 1], 1)
                }
                cachedEnd = next.atStartOfDayIn(zone).toEpochMilliseconds()
            }
        }
        byMonth.getOrPut(month) { mutableListOf() } += photo
    }
    // Distinct months only (a handful), newest first — sorting these
    // replaces the previous sorted-map over every group operation.
    return byMonth.entries
        .sortedByDescending { it.key }
        .associate { (month, monthPhotos) ->
            resources.getString(
                R.string.month_year_format,
                monthNames[month.month.ordinal],
                month.year,
            ) to monthPhotos
        }
}
