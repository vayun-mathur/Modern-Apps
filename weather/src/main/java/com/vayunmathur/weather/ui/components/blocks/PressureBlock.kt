package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconPressure
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.hpaToInHg
import java.util.Locale

/**
 * Port of WeatherMaster's `PressureBlock`. Circular surface with two
 * stacked Images: the full progress container ring (tinted
 * `surfaceContainerHigh`) and the active arc (tinted `primary`) chosen by
 * the hPa bucket. Big inHg value centered, unit bottom-center.
 */
@Composable
fun PressureBlock(current: Current, pressureUnit: PressureUnit) {
    val inHg = hpaToInHg(current.pressureMsl)
    val pressureHpa = current.pressureMsl.toInt()
    val (valueText, unitText) = when (pressureUnit) {
        PressureUnit.InHg -> String.format(Locale.US, "%.2f", inHg) to "inHg"
        PressureUnit.Hpa -> pressureHpa.toString() to "hPa"
    }
    val progressDrawable = when (pressureUnit) {
        PressureUnit.InHg -> when {
            inHg < 29.0 -> R.drawable.pressure_progress_low
            inHg <= 29.7 -> R.drawable.pressure_progress_medium
            inHg <= 30.2 -> R.drawable.pressure_progress_low_medium
            inHg <= 31.0 -> R.drawable.pressure_progress_high
            else -> R.drawable.pressure_progress_very_high
        }
        PressureUnit.Hpa -> when {
            pressureHpa < 980 -> R.drawable.pressure_progress_low
            pressureHpa <= 1005 -> R.drawable.pressure_progress_medium
            pressureHpa <= 1020 -> R.drawable.pressure_progress_low_medium
            pressureHpa <= 1035 -> R.drawable.pressure_progress_high
            else -> R.drawable.pressure_progress_very_high
        }
    }

    CircularStatBlock {
        Image(
            painter = painterResource(R.drawable.pressure_progress_container),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Image(
            painter = painterResource(progressDrawable),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Box(Modifier.align(Alignment.TopCenter)) {
            BlockHeader(
                icon = { m, c -> IconPressure(m, c) },
                title = stringResource(R.string.metric_pressure),
                topPadding = 38.dp,
            )
        }
        Text(
            text = valueText,
            style = MaterialTheme.typography.displaySmallEmphasized,
            modifier = Modifier.align(Alignment.Center).offset(y = 10.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = unitText,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-24).dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = true,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
