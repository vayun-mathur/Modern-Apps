package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.Text
import kotlin.time.Duration

@Composable
fun LapRow(number: Int, color: Color, split: Duration, total: Duration) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.lap_number_format, number), color = color, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(formatDuration(context, split), color = color, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(formatDuration(context, total), color = color, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

fun formatDuration(context: android.content.Context, d: Duration): String =
    // Hours component included so a lap past one hour rolls over to
    // H:MM:SS.cs instead of accumulating total minutes, matching the
    // timer card's formatTimerDuration shape.
    d.toComponents { hours, minutes, seconds, nanoseconds ->
        val ms = nanoseconds / 10_000_000
        when {
            hours > 0L -> context.getString(R.string.duration_h_m_s_ms_format, hours, minutes, seconds, ms)
            minutes == 0 -> context.getString(R.string.duration_ms_format, seconds, ms)
            else -> context.getString(R.string.duration_m_s_ms_format, minutes, seconds, ms)
        }
    }
