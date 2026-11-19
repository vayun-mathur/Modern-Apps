package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconVisible
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
import com.vayunmathur.weather.domain.metersToMiles
import kotlin.math.roundToInt

/**
 * Port of WeatherMaster's `VisibilityBlock`. Circular surface with the
 * inner cookie-shape drawable as a decorative background tinted with
 * `inversePrimary`. Header top-center, big value centered, unit
 * bottom-center.
 */
@Composable
fun VisibilityBlock(current: Current, useMiles: Boolean = false) {
    val (value, unit) = if (useMiles) {
        metersToMiles(current.visibility).roundToInt() to "mi"
    } else {
        (current.visibility / 1000).roundToInt() to "km"
    }
    CircularStatBlock {
        Image(
            painter = painterResource(R.drawable.visibility_block),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.inversePrimary),
        )
        Box(Modifier.align(Alignment.TopCenter)) {
            BlockHeader(
                icon = { m, c -> IconVisible(m, c) },
                title = stringResource(R.string.metric_visibility),
                topPadding = 36.dp,
            )
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.displayMediumEmphasized,
            modifier = Modifier.align(Alignment.Center).offset(y = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = unit,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-30).dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = true,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
