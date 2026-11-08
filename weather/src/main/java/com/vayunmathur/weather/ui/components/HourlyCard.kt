package com.vayunmathur.weather.ui.components

import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.isoDayNumber
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.MaterialShapes
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Hourly
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.formatStripHour
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.domain.parseLocalIsoToEpochSec
import com.vayunmathur.weather.domain.weatherConditionForCode
import androidx.compose.ui.unit.sp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Direct port of WeatherMaster's `HourlyCard`. `Surface(shape = extraLarge,
 * color = surface, shadowElevation = 2.dp)`, [CardsHeader] at top, then a
 * `LazyRow` of 120 dp × 45 dp items. First (current-hour) item shows its
 * temperature inside a `MaterialShapes.Cookie4Sided` pill filled with
 * `primary`.
 */
@Composable
fun HourlyCard(
    hourly: Hourly,
    tempUnit: TemperatureUnit,
    utcOffsetSeconds: Int = 0,
    use24Hour: Boolean = false,
    selectedIsoTime: String? = null,
    onHourSelected: (String) -> Unit = {},
    scrollToIsoDate: String? = null,
    /** ISO `yyyy-MM-ddTHH:00` of the current hour in the location's zone; that cell gets an outline. */
    nowIsoHour: String? = null,
    /**
     * "Now" for the strip's day labels and the sun arc. A parameter rather than a
     * direct clock read so a preview can pin it — otherwise fixed sample data ages
     * out and the whole card disappears.
     */
    nowEpochSec: Long = System.currentTimeMillis() / 1000,
) {
    val cells = hourly.time.indices
        .mapNotNull { i ->
            val iso = hourly.time.getOrNull(i) ?: return@mapNotNull null
            val ts = parseLocalIsoToEpochSec(iso, utcOffsetSeconds) ?: return@mapNotNull null
            HourCell(
                iso = iso,
                epochSec = ts,
                temperature = hourly.temperature.getOrNull(i) ?: 0.0,
                weatherCode = hourly.weatherCode.getOrNull(i) ?: 0,
                precip = hourly.precipitationProbability.getOrNull(i) ?: 0,
                isDay = (hourly.isDay.getOrNull(i) ?: 1) == 1,
            )
        }
    if (cells.isEmpty()) return

    // Open scrolled so now is visible with history to its left, unless the
    // user picked a day (which takes precedence and jumps to that date).
    val nowIdx = cells.indexOfFirst { it.iso == nowIsoHour }.takeIf { it >= 0 }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToIsoDate, nowIdx, cells) {
        val target = if (scrollToIsoDate != null) {
            cells.indexOfFirst { it.iso.substringBefore('T') == scrollToIsoDate }.takeIf { it >= 0 }
        } else {
            nowIdx?.minus(1)?.coerceAtLeast(0)
        }
        if (target != null && target >= 0) listState.scrollToItem(target)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            CardsHeader(text = stringResource(R.string.hourly_forecast), icon = { m, c -> IconSchedule(m, c) })
            LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(cells.size, key = { "${cells[it].epochSec}_$it" }) { index ->
                    val cell = cells[index]
                    val isNow = cell.iso == nowIsoHour
                    HourlyItem(
                        time = if (isNow) stringResource(R.string.now) else formatStripHour(cell.epochSec, use24Hour, utcOffsetSeconds),
                        dayLabel = formatDayLabel(cell.epochSec, nowEpochSec, utcOffsetSeconds),
                        precipitationProbability = cell.precip,
                        temperature = cell.temperature,
                        isNow = isNow,
                        isSelected = selectedIsoTime == cell.iso,
                        icon = weatherConditionForCode(cell.weatherCode).iconContent(cell.isDay),
                        tempUnit = tempUnit,
                        onClick = { onHourSelected(cell.iso) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyItem(
    time: String,
    dayLabel: String,
    precipitationProbability: Int,
    temperature: Double,
    isNow: Boolean,
    isSelected: Boolean,
    icon: @Composable (Modifier, Color) -> Unit,
    tempUnit: TemperatureUnit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .heightIn(min = 135.dp)
            .widthIn(min = 45.dp)
            .then(
                if (isNow) {
                    Modifier
                        .padding(1.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(1.dp)
                } else {
                    Modifier.padding(2.dp)
                }
            )
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(5.dp))
        TempWithShape(
            temperature = temperature,
            tempUnit = tempUnit,
            highlighted = isNow || isSelected,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${precipitationProbability}%",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(bottom = 3.dp)
                .alpha(if (precipitationProbability > 0) 1f else 0f),
        )
        WeatherIconBox(icon = icon, size = 28.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            time,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            dayLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TempWithShape(
    temperature: Double,
    tempUnit: TemperatureUnit,
    highlighted: Boolean,
) {
    Surface(
        shape = MaterialShapes.Cookie4Sided.toShape(),
        modifier = Modifier.size(36.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                formatTemperatureCompact(temperature, tempUnit),
                color = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private data class HourCell(
    val iso: String,
    val epochSec: Long,
    val temperature: Double,
    val weatherCode: Int,
    val precip: Int,
    val isDay: Boolean,
)

@Composable
private fun formatDayLabel(epochSec: Long, nowEpochSec: Long, utcOffsetSeconds: Int): String {
    // Same shift-then-read-in-UTC as domain localTimeAt: Instant.toLocalDateTime(UtcOffset)
    // is internal in this kotlinx-datetime version.
    val date = Instant.fromEpochSeconds(epochSec + utcOffsetSeconds).toLocalDateTime(TimeZone.UTC).date
    val today = Instant.fromEpochSeconds(nowEpochSec + utcOffsetSeconds).toLocalDateTime(TimeZone.UTC).date
    val tomorrow = today.plus(1, DateTimeUnit.DAY)
    return when (date) {
        today -> stringResource(R.string.today_short)
        tomorrow -> stringResource(R.string.tomorrow_short)
        else -> {
            val locale = LocalConfiguration.current.locales[0]
            try {
                val isoNum = when (date.dayOfWeek) {
                    kotlinx.datetime.DayOfWeek.MONDAY -> 1
                    kotlinx.datetime.DayOfWeek.TUESDAY -> 2
                    kotlinx.datetime.DayOfWeek.WEDNESDAY -> 3
                    kotlinx.datetime.DayOfWeek.THURSDAY -> 4
                    kotlinx.datetime.DayOfWeek.FRIDAY -> 5
                    kotlinx.datetime.DayOfWeek.SATURDAY -> 6
                    kotlinx.datetime.DayOfWeek.SUNDAY -> 7
                }
                localizedDayOfWeekNames(DateNameStyle.SHORT, locale)[isoNum - 1]
            } catch (_: Exception) {
                localizedDayOfWeekNames(DateNameStyle.SHORT)[date.dayOfWeek.isoDayNumber - 1]
            }
        }
    }
}
