package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.network.AirQualityCurrent
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.ui.components.blocks.AirQualityBlock
import com.vayunmathur.weather.ui.components.blocks.HumidityBlock
import com.vayunmathur.weather.ui.components.blocks.PollenBlock
import com.vayunmathur.weather.ui.components.blocks.PressureBlock
import com.vayunmathur.weather.ui.components.blocks.SunBlock
import com.vayunmathur.weather.ui.components.blocks.UvIndexBlock
import com.vayunmathur.weather.ui.components.blocks.VisibilityBlock
import com.vayunmathur.weather.ui.components.blocks.WindBlock
import com.vayunmathur.weather.util.TemperatureUnit
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
    today: Daily?,
    air: AirQualityCurrent?,
    sunriseEpochSec: Long?,
    sunsetEpochSec: Long?,
    tempUnit: TemperatureUnit,
    windUnit: WindUnit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize().heightIn(max = 1500.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item { HumidityBlock(current = current, tempUnit = tempUnit) }
        item { UvIndexBlock(uvIndex = today?.uvIndexMax?.firstOrNull()) }
        item { WindBlock(current = current, unit = windUnit) }
        item { PressureBlock(current = current) }
        item { VisibilityBlock(current = current, useMiles = windUnit == WindUnit.Mph) }
        item { SunBlock(sunriseEpochSec = sunriseEpochSec, sunsetEpochSec = sunsetEpochSec) }
        item { AirQualityBlock(air = air) }
        item { PollenBlock(air = air) }
    }
}
