package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconDrizzle
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.formatTemperatureCompact
import androidx.compose.ui.res.stringResource

/**
 * Port of WeatherMaster's `HumidityBlock`. Square `extraLarge` Surface.
 * Water-level wave drawable (one of 5 variants based on humidity) tinted
 * with `inversePrimary` fills the bottom of the card. Header top-start,
 * big % value center-start, dew-point pill bottom-start.
 */
@Composable
fun HumidityBlock(current: Current, tempUnit: TemperatureUnit) {
    val humidity = current.relativeHumidity
    val waveDrawable = when (humidity) {
        in 0..30 -> R.drawable.humidity_seven_percent
        in 30..50 -> R.drawable.humidity_thirty_precent
        in 50..70 -> R.drawable.humidity_fifty_percent
        in 70..90 -> R.drawable.humidity_seventy_percent
        else -> R.drawable.humidity_ninety_percent
    }
    val tint = MaterialTheme.colorScheme.inversePrimary

    SquareBlock {
        Image(
            painter = painterResource(id = waveDrawable),
            contentDescription = null,
            modifier = Modifier.matchParentSize().align(Alignment.BottomCenter),
            alignment = Alignment.BottomCenter,
            colorFilter = ColorFilter.tint(tint),
        )
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconDrizzle(m, c) }, title = stringResource(R.string.metric_humidity))
        }
        Text(
            text = "${humidity}%",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.displayMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(Modifier.align(Alignment.BottomStart)) {
            DewPointRow(dewPointCelsius = current.dewPoint, tempUnit = tempUnit)
        }
    }
}

@Composable
private fun DewPointRow(dewPointCelsius: Double, tempUnit: TemperatureUnit) {
    Row(
        modifier = Modifier.padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                Text(
                    formatTemperatureCompact(dewPointCelsius, tempUnit),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = true,
                    color = MaterialTheme.colorScheme.onPrimary,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(R.string.dew_point),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            softWrap = true,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
