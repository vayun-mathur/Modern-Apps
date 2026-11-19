package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconRain
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import com.vayunmathur.weather.domain.mmToInches
import java.util.Locale

/**
 * Precipitation amount for the resolved period (today / a selected day's
 * total, or a selected hour's amount) plus an optional short-range nowcast
 * subtitle derived from the 15-minute series. Square `extraLarge` surface to
 * match the other blocks.
 */
@Composable
fun PrecipitationBlock(
    amountMm: Double?,
    useInches: Boolean,
    nowcast: String?,
) {
    val (value, unit) = if (useInches) {
        val inches = mmToInches(amountMm ?: 0.0)
        String.format(Locale.US, "%.2f", inches) to "in"
    } else {
        val mm = amountMm ?: 0.0
        (if (mm < 10) String.format(Locale.US, "%.1f", mm) else mm.toInt().toString()) to "mm"
    }

    SquareBlock {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth()) {
                BlockHeader(icon = { m, c -> IconRain(m, c) }, title = stringResource(R.string.metric_precipitation))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    softWrap = true,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                if (nowcast != null) {
                    Text(
                        text = nowcast,
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
