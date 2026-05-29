package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.data.SleepStage
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.displayString
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailsPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val initialPage = 999
    val pagerState = rememberPagerState(initialPage = initialPage) { 1000 }
    val scope = rememberCoroutineScope()
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_sleep)) },
                navigationIcon = { IconNavigation(backStack) })
        }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Navigation Row (Same as BarChartDetails)
            val selectedDay = remember(pagerState.currentPage) {
                today.minus(initialPage - pagerState.currentPage, DateTimeUnit.DAY)
            }
            val headerLabel = if (selectedDay == today) stringResource(R.string.label_today)
            else selectedDay.displayString()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }) {
                    Icon(
                        painterResource(R.drawable.baseline_arrow_back_24),
                        stringResource(R.string.nav_prev)
                    )
                }

                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(min = 140.dp),
                    textAlign = TextAlign.Center
                )

                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    enabled = pagerState.currentPage < pagerState.pageCount - 1 && selectedDay < today
                ) {
                    Icon(
                        painterResource(R.drawable.outline_arrow_forward_24),
                        stringResource(R.string.nav_next)
                    )
                }
            }

            HorizontalPager(
                state = pagerState, modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) { page ->
                val day = today.minus(initialPage - page, DateTimeUnit.DAY)
                val searchStart = day.atStartOfDayIn(tz).minus(12.hours)
                val searchEnd = day.atStartOfDayIn(tz).plus(24.hours)

                // Fetch records that ended during this day (most likely the sleep that ended this
                // morning)
                val records by remember(searchStart, searchEnd) { viewModel.getAllRecordsInRange(RecordType.Sleep, searchStart, searchEnd) }
                    .collectAsState(emptyList())
                val record = remember(records, day) {
                    records.filter {
                        val endLocal = it.endTime.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        endLocal.year == day.year && 
                        endLocal.monthValue == day.monthNumber && 
                        endLocal.dayOfMonth == day.dayOfMonth
                    }.maxByOrNull { it.endTime }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    record?.let { r ->
                        val sleepData = r.sleepData
                        if (sleepData != null) {
                            item { SleepSummaryHeader(r) }
                            item { SleepStageGraph(r) }
                            item { SleepStageBreakdown(sleepData) }
                        }
                    } ?: item {
                        Box(
                            Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_data_available),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepSummaryHeader(record: Record) {
    val context = LocalContext.current
    val totalMinutes = (record.value * 60).toLong()
    val startT = record.startTime.atZone(java.time.ZoneId.systemDefault())
    val endT = record.endTime.atZone(java.time.ZoneId.systemDefault())

    Column {
        Text(
            text = hoursMinutesString(context, totalMinutes),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light
        )
        Text(
            text = "${startT.hour}:${
                startT.minute.toString().padStart(2, '0')
            } - ${endT.hour}:${endT.minute.toString().padStart(2, '0')}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun SleepStageGraph(record: Record) {
    val stages = remember(record) {
        record.sleepData?.stagesJson?.let { Json.decodeFromString<List<SleepStage>>(it) }
            ?: emptyList()
    }

    if (stages.isEmpty()) return

    val startTime = record.startTime.toEpochMilli()
    val endTime = record.endTime.toEpochMilli()
    val duration = (endTime - startTime).coerceAtLeast(1)

    val awakeColor = Color(0xFFFF9500)
    val remColor = Color(0xFF5AC8FA)
    val coreColor = Color(0xFF007AFF)
    val deepColor = Color(0xFF5856D6)

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.height(160.dp)
            ) {
                Text(
                    stringResource(R.string.label_awake),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.label_rem),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.label_core),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.label_deep),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
                    .padding(start = 8.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val levelHeight = canvasHeight / 4

                // Draw background lines
                for (i in 0..4) {
                    val y = i * levelHeight
                    drawLine(
                        Color.LightGray.copy(alpha = 0.3f),
                        Offset(0f, y),
                        Offset(canvasWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                stages.forEach { stage ->
                    val startX =
                        ((stage.startTimeMillis - startTime).toFloat() / duration) * canvasWidth
                    val endX =
                        ((stage.endTimeMillis - startTime).toFloat() / duration) * canvasWidth

                    val yPos = when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 0f

                        SleepSessionRecord.STAGE_TYPE_REM -> levelHeight
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> levelHeight * 2
                        SleepSessionRecord.STAGE_TYPE_DEEP -> levelHeight * 3
                        else -> levelHeight * 2
                    }

                    val color = when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awakeColor

                        SleepSessionRecord.STAGE_TYPE_REM -> remColor
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> coreColor
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deepColor
                        else -> coreColor
                    }

                    drawRoundRect(
                        color = color, topLeft = Offset(startX, yPos + 4.dp.toPx()), size = Size(
                            (endX - startX).coerceAtLeast(2.dp.toPx()), levelHeight - 8.dp.toPx()
                        ), cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Time labels
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val startT = record.startTime.atZone(java.time.ZoneId.systemDefault())
            val endT = record.endTime.atZone(java.time.ZoneId.systemDefault())

            fun formatHour(hour: Int): String {
                val h = if (hour % 12 == 0) 12 else hour % 12
                val ampm = if (hour < 12) "AM" else "PM"
                return "$h $ampm"
            }

            Text(
                formatHour(startT.hour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                formatHour(endT.hour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun SleepStageBreakdown(data: SleepData) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Stages", style = MaterialTheme.typography.labelLarge)
        Card(
            modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StageRow(
                    stringResource(R.string.label_awake),
                    hoursMinutesString(context, data.awakeDurationMillis / 60000),
                    Color(0xFFFF9500)
                )
                StageRow(
                    stringResource(R.string.label_rem),
                    hoursMinutesString(context, data.remDurationMillis / 60000),
                    Color(0xFF5AC8FA)
                )
                StageRow(
                    stringResource(R.string.label_core),
                    hoursMinutesString(context, data.lightDurationMillis / 60000),
                    Color(0xFF007AFF)
                )
                StageRow(
                    stringResource(R.string.label_deep),
                    hoursMinutesString(context, data.deepDurationMillis / 60000),
                    Color(0xFF5856D6)
                )
            }
        }
    }
}

@Composable
fun StageRow(label: String, duration: String, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(12.dp), shape = MaterialTheme.shapes.extraSmall, color = color) {}
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(duration, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}
