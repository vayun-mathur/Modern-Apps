package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.network.AirQualityCurrent
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.ui.components.blocks.AirQualityBlock
import com.vayunmathur.weather.ui.components.blocks.CloudCoverBlock
import com.vayunmathur.weather.ui.components.blocks.HumidityBlock
import com.vayunmathur.weather.ui.components.blocks.PollenBlock
import com.vayunmathur.weather.ui.components.blocks.PrecipitationBlock
import com.vayunmathur.weather.ui.components.blocks.PressureBlock
import com.vayunmathur.weather.ui.components.blocks.SunBlock
import com.vayunmathur.weather.ui.components.blocks.UvIndexBlock
import com.vayunmathur.weather.ui.components.blocks.VisibilityBlock
import com.vayunmathur.weather.ui.components.blocks.WindBlock
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.PressureUnit
import com.vayunmathur.weather.util.WeatherMetric
import com.vayunmathur.weather.util.WindUnit

/**
 * Port of WeatherMaster's `WeatherBlocks` grid (simplified): adaptive 140 dp
 * cells, 14 dp spacing, `userScrollEnabled = false`, `heightIn(max =
 * 1500.dp)` so it plays well nested in a vertically-scrollable parent.
 *
 * Order matches WeatherMaster's default for the conditions we have data
 * for. No drag-to-reorder, no per-block visibility rules — fixed order,
 * static set.
 */
@Composable
fun WeatherBlocks(
    current: Current,
    uvIndex: Double?,
    air: AirQualityCurrent?,
    sunriseEpochSec: Long?,
    sunsetEpochSec: Long?,
    precipitationMm: Double?,
    precipitationNowcast: String?,
    daylightDurationSec: Double?,
    onMetricSelected: (WeatherMetric) -> Unit,
    tempUnit: TemperatureUnit,
    windUnit: WindUnit,
    pressureUnit: PressureUnit,
    use24Hour: Boolean,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize().heightIn(max = 1500.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item { Graphable({ onMetricSelected(WeatherMetric.Humidity) }) { HumidityBlock(current = current, tempUnit = tempUnit) } }
        item { Graphable({ onMetricSelected(WeatherMetric.UvIndex) }) { UvIndexBlock(uvIndex = uvIndex) } }
        item {
            Graphable({ onMetricSelected(WeatherMetric.Precipitation) }) {
                PrecipitationBlock(
                    amountMm = precipitationMm,
                    useInches = windUnit == WindUnit.Mph,
                    nowcast = precipitationNowcast,
                )
            }
        }
        item { Graphable({ onMetricSelected(WeatherMetric.WindSpeed) }) { WindBlock(current = current, unit = windUnit) } }
        item { Graphable({ onMetricSelected(WeatherMetric.CloudCover) }) { CloudCoverBlock(current = current) } }
        item { Graphable({ onMetricSelected(WeatherMetric.Pressure) }) { PressureBlock(current = current, pressureUnit = pressureUnit) } }
        item { Graphable({ onMetricSelected(WeatherMetric.Visibility) }) { VisibilityBlock(current = current, useMiles = windUnit == WindUnit.Mph) } }
        item { SunBlock(sunriseEpochSec = sunriseEpochSec, sunsetEpochSec = sunsetEpochSec, use24Hour = use24Hour, daylightDurationSec = daylightDurationSec) }
        item { AirQualityBlock(air = air) }
        item { PollenBlock(air = air) }
    }
}

@Composable
private fun Graphable(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        content()
    }
}
