package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberIs24Hour
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.health.R
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.SleepStage
import com.vayunmathur.health.ui.components.hypnogramColors
import kotlinx.serialization.json.Json

@Composable
fun SleepStageGraph(record: Record) {
    val stages = remember(record) {
        // stagesJson is persisted in Room; a payload written by another build
        // (or otherwise undecodable) must hide the graph, not throw
        // SerializationException during composition — the same guard the
        // exercise segments/laps parsing already uses.
        record.sleepData?.stagesJson?.let {
            runCatching { Json.decodeFromString<List<SleepStage>>(it) }.getOrNull()
        } ?: emptyList()
    }

    if (stages.isEmpty()) return

    val startTime = record.startTime.toEpochMilli()
    val endTime = record.endTime.toEpochMilli()
    val duration = (endTime - startTime).coerceAtLeast(1)

    val colors = hypnogramColors()
    val awakeColor = colors.awake
    val remColor = colors.rem
    val coreColor = colors.core
    val deepColor = colors.deep

    // Screen-reader summary for the canvas below, which otherwise exposes
    // nothing. Durations come from the already-computed SleepData totals;
    // when those are absent (older records) they are summed from the stages
    // using the same stage-to-level mapping the graph draws.
    val context = LocalContext.current
    val is24Hour = rememberIs24Hour()
    val startZone = record.startTime.atZone(java.time.ZoneId.systemDefault())
    val endZone = record.endTime.atZone(java.time.ZoneId.systemDefault())
    fun stageMillis(predicate: (Int) -> Boolean): Long =
        stages.filter { predicate(it.stage) }.sumOf { it.endTimeMillis - it.startTimeMillis }
    val sleepData = record.sleepData
    val awakeMillis = sleepData?.awakeDurationMillis?.takeIf { it > 0 }
        ?: stageMillis { it == SleepSessionRecord.STAGE_TYPE_AWAKE || it == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED }
    val remMillis = sleepData?.remDurationMillis?.takeIf { it > 0 }
        ?: stageMillis { it == SleepSessionRecord.STAGE_TYPE_REM }
    val coreMillis = sleepData?.lightDurationMillis?.takeIf { it > 0 }
        ?: stageMillis { it != SleepSessionRecord.STAGE_TYPE_AWAKE && it != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED && it != SleepSessionRecord.STAGE_TYPE_REM && it != SleepSessionRecord.STAGE_TYPE_DEEP }
    val deepMillis = sleepData?.deepDurationMillis?.takeIf { it > 0 }
        ?: stageMillis { it == SleepSessionRecord.STAGE_TYPE_DEEP }
    val graphDescription = stringResource(
        R.string.sleep_stage_graph_summary,
        DateString.hourLabel(startZone.hour, is24Hour),
        DateString.hourLabel(endZone.hour, is24Hour),
        hoursMinutesString(context, awakeMillis / 60_000),
        hoursMinutesString(context, remMillis / 60_000),
        hoursMinutesString(context, coreMillis / 60_000),
        hoursMinutesString(context, deepMillis / 60_000),
    )

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

            val gridColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
                    .padding(start = 8.dp)
                    .semantics { contentDescription = graphDescription }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val levelHeight = canvasHeight / 4

                // Draw background lines
                for (i in 0..4) {
                    val y = i * levelHeight
                    drawLine(
                        gridColor,
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

                    val (yPos, color) = when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 0f to awakeColor
                        SleepSessionRecord.STAGE_TYPE_REM -> levelHeight to remColor
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> levelHeight * 2 to coreColor
                        SleepSessionRecord.STAGE_TYPE_DEEP -> levelHeight * 3 to deepColor
                        else -> levelHeight * 2 to coreColor
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

            Text(
                DateString.hourLabel(startT.hour, is24Hour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                DateString.hourLabel(endT.hour, is24Hour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
