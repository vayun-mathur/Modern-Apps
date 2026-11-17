package com.vayunmathur.health.ui

import kotlinx.datetime.format.DateTimeFormat
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.sharedText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.ExerciseData
import com.vayunmathur.health.data.ExerciseLapData
import com.vayunmathur.health.data.ExerciseSegmentData
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.health.sync.exerciseSegmentTypeName
import com.vayunmathur.health.sync.exerciseTypeName
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import java.time.ZoneId

private val dateFormatter: DateTimeFormat<LocalDate> get() = LocalDate.Format {
    dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.FULL)))
    chars(", ")
    monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
    chars(" ")
    day(Padding.NONE)
    chars(", ")
    year()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailsPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val records by remember { viewModel.getAllRecordsOfType(RecordType.Exercise) }.collectAsState(emptyList())

    AppScaffold(
        title = {
            Text(
                stringResource(R.string.metric_exercise_duration),
                modifier = Modifier.sharedText("health-metric-label-EXERCISE"),
            )
        },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (records.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_workouts),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(records, key = { it.primaryKey }) { record ->
                    ExerciseSessionCard(record)
                }
            }
        }
    }
}

@Composable
private fun ExerciseSessionCard(record: Record) {
    val exercise = record.exerciseData
    val exerciseName = exercise?.title
        ?: exercise?.let { exerciseTypeName(it.exerciseType) }
        ?: record.metadata
        ?: "Workout"
    val typeName = exercise?.let { exerciseTypeName(it.exerciseType) } ?: "Workout"

    val startZdt = record.startTime.atZone(ZoneId.systemDefault())
    val endZdt = record.endTime.atZone(ZoneId.systemDefault())
    val is24 = rememberIs24Hour()
    val durationMinutes = record.value.toLong()

    val segments = remember(exercise?.segmentsJson) {
        exercise?.segmentsJson?.let {
            try { Json.decodeFromString<List<ExerciseSegmentData>>(it) } catch (_: Exception) { null }
        }
    }
    val laps = remember(exercise?.lapsJson) {
        exercise?.lapsJson?.let {
            try { Json.decodeFromString<List<ExerciseLapData>>(it) } catch (_: Exception) { null }
        }
    }

    DashboardSection(accentColor = HealthColors.Activity) {
        Column(Modifier.padding(16.dp)) {
            // Header: icon + name + duration
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(HealthColors.Activity.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconDirectionsWalk(
                        modifier = Modifier.size(22.dp),
                        tint = HealthColors.Activity,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        exerciseName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (exerciseName != typeName) {
                        Text(
                            typeName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Text(
                    formatDuration(durationMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = HealthColors.Activity
                )
            }

            Spacer(Modifier.height(8.dp))

            // Date + time range
            Text(
                dateFormatter.format(
                    LocalDate(startZdt.year, startZdt.monthValue, startZdt.dayOfMonth)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${DateString.time(LocalTime(startZdt.hour, startZdt.minute), is24)} – ${DateString.time(LocalTime(endZdt.hour, endZdt.minute), is24)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )

            // Notes
            exercise?.notes?.let { notes ->
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Route indicator
            if (exercise?.hasRoute == true) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconLocationOn(
                        tint = HealthColors.Activity,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.route_recorded),
                        style = MaterialTheme.typography.labelMedium,
                        color = HealthColors.Activity
                    )
                }
            }

            // Segments
            if (!segments.isNullOrEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.segments),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                segments.forEach { segment ->
                    val segDuration = (segment.endTimeMillis - segment.startTimeMillis) / 60000
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                exerciseSegmentTypeName(segment.segmentType),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (segment.repetitions > 0) {
                                Text(
                                    stringResource(R.string.reps, segment.repetitions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Text(
                            formatDuration(segDuration),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Laps
            if (!laps.isNullOrEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.laps),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                laps.forEachIndexed { idx, lap ->
                    val lapDuration = (lap.endTimeMillis - lap.startTimeMillis) / 60000
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.lap, idx + 1),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            lap.lengthMeters?.let { meters ->
                                val display = if (meters >= 1000) {
                                    "%.2f km".format(meters / 1000.0)
                                } else {
                                    "%.0f m".format(meters)
                                }
                                Text(
                                    display,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                formatDuration(lapDuration),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(minutes: Long): String = com.vayunmathur.health.platform.formatDuration(minutes)
