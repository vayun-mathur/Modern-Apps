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
import androidx.compose.ui.res.stringResource
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
        record.sleepData?.stagesJson?.let { Json.decodeFromString<List<SleepStage>>(it) }
            ?: emptyList()
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
            val is24 = rememberIs24Hour()

            Text(
                DateString.hourLabel(startT.hour, is24),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                DateString.hourLabel(endT.hour, is24),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
