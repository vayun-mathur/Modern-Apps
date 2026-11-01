package com.vayunmathur.travel.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconKeyboardArrowLeft
import com.vayunmathur.library.ui.IconKeyboardArrowRight
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.domain.nextRange
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// Sizes mirror the Material 3 DatePicker so the range calendar reads as the
// same component: 120dp header, 56dp month row, 48dp cells, 40dp day circles.
private val HeaderMinHeight = 120.dp
private val HeaderTitlePadding = PaddingValues(start = 24.dp, end = 12.dp, top = 16.dp)
private val HeaderHeadlinePadding = PaddingValues(start = 24.dp, end = 12.dp, bottom = 12.dp)
private val MonthRowHeight = 56.dp
private val HorizontalPadding = 12.dp
private val CellSize = 48.dp
private val DaySize = 40.dp
private const val CalendarRows = 6
private const val MonthsAhead = 24L

/**
 * A start/end date calendar that looks and behaves like the single Material
 * DatePicker (same header, month navigation, weekday row and day cells), with
 * the days between start and end joined by a range band.
 *
 * Tapping a day starts a new range; the next tap at or after it ends the range
 * (a tap before it restarts there). [allowSameDay] = false requires end > start.
 */
@Composable
internal fun RangeCalendar(
    title: String,
    startLabel: String,
    endLabel: String,
    start: LocalDate?,
    end: LocalDate?,
    onChange: (start: LocalDate?, end: LocalDate?) -> Unit,
    minDate: LocalDate,
    allowSameDay: Boolean,
    modifier: Modifier = Modifier,
) {
    val locale: Locale = LocalConfiguration.current.locales[0]
    val firstMonth = remember(minDate) { YearMonth.from(minDate) }
    val initialPage = remember {
        val shown = YearMonth.from(start ?: minDate)
        (shown.year - firstMonth.year) * 12 + (shown.monthValue - firstMonth.monthValue)
    }.coerceIn(0, MonthsAhead.toInt() - 1)
    val pager = rememberPagerState(initialPage = initialPage) { MonthsAhead.toInt() }
    val scope = rememberCoroutineScope()
    val shownMonth = firstMonth.plusMonths(pager.currentPage.toLong())

    Column(modifier.fillMaxWidth()) {
        // Header: title + headline, as in DatePicker's DateEntryContainer.
        Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = HeaderMinHeight), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                title,
                modifier = Modifier.padding(HeaderTitlePadding),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${start?.let { formatHeadlineDate(it, locale) } ?: startLabel} – ${end?.let { formatHeadlineDate(it, locale) } ?: endLabel}",
                modifier = Modifier.padding(HeaderHeadlinePadding),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        HorizontalDivider()

        // Month navigation: "November 2026" with previous/next arrows.
        Row(
            Modifier.fillMaxWidth().requiredHeight(MonthRowHeight).padding(horizontal = HorizontalPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shownMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = shownMonth.toString() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                IconButton(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                    enabled = pager.currentPage > 0,
                ) { IconKeyboardArrowLeft(tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    enabled = pager.currentPage < MonthsAhead - 1,
                ) { IconKeyboardArrowRight(tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        Column(Modifier.padding(horizontal = HorizontalPadding)) {
            val weekDays = remember(locale) { orderedWeekDays(locale) }
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = CellSize), verticalAlignment = Alignment.CenterVertically) {
                weekDays.forEach { day ->
                    Text(
                        day.getDisplayName(TextStyle.NARROW, locale),
                        modifier = Modifier.weight(1f).semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                MonthGrid(
                    month = firstMonth.plusMonths(page.toLong()),
                    weekDays = weekDays,
                    start = start,
                    end = end,
                    minDate = minDate,
                    onDay = { tapped ->
                        val (newStart, newEnd) = nextRange(start, end, tapped, allowSameDay)
                        onChange(newStart, newEnd)
                    },
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    weekDays: List<DayOfWeek>,
    start: LocalDate?,
    end: LocalDate?,
    minDate: LocalDate,
    onDay: (LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val leading = weekDays.indexOf(month.atDay(1).dayOfWeek)
    Column(Modifier.fillMaxWidth().requiredHeight(CellSize * CalendarRows), verticalArrangement = Arrangement.SpaceEvenly) {
        for (row in 0 until CalendarRows) {
            Row(Modifier.fillMaxWidth().height(CellSize)) {
                for (col in 0 until 7) {
                    val dayNumber = row * 7 + col - leading + 1
                    if (dayNumber < 1 || dayNumber > month.lengthOfMonth()) {
                        Box(Modifier.weight(1f))
                    } else {
                        val date = month.atDay(dayNumber)
                        DayCell(
                            date = date,
                            start = start,
                            end = end,
                            today = date == today,
                            enabled = !date.isBefore(minDate),
                            onClick = { onDay(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    start: LocalDate?,
    end: LocalDate?,
    today: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isStart = date == start
    val isEnd = date == end
    val selected = isStart || isEnd
    val hasRange = start != null && end != null && end.isAfter(start)
    val inRange = hasRange && date.isAfter(start) && date.isBefore(end)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        // Range band (40dp tall, like the state layer): full width between the
        // ends, half width on the start/end cells so the ends stay round.
        if (hasRange && (inRange || isStart || isEnd)) {
            val band = colors.secondaryContainer
            Row(Modifier.fillMaxWidth().height(DaySize)) {
                val leftFilled = inRange || (if (rtl) isStart else isEnd)
                val rightFilled = inRange || (if (rtl) isEnd else isStart)
                Box(Modifier.weight(1f).fillMaxHeight().background(if (leftFilled) band else Color.Transparent))
                Box(Modifier.weight(1f).fillMaxHeight().background(if (rightFilled) band else Color.Transparent))
            }
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (selected) colors.primary else Color.Transparent,
            border = if (today && !selected) BorderStroke(1.dp, colors.primary) else null,
            modifier = Modifier.size(DaySize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        selected -> colors.onPrimary
                        !enabled -> colors.onSurface.copy(alpha = 0.38f)
                        inRange -> colors.onSecondaryContainer
                        today -> colors.primary
                        else -> colors.onSurface
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun orderedWeekDays(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

/** "Nov 30" in the locale's own order (e.g. "30 Nov"). */
private fun formatHeadlineDate(date: LocalDate, locale: Locale): String =
    date.format(
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    )
