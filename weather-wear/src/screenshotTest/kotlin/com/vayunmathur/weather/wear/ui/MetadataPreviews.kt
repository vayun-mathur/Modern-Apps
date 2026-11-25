package com.vayunmathur.weather.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.weather.wear.domain.WearWeatherCurrent
import com.vayunmathur.weather.wear.domain.WearWeatherLocation
import com.vayunmathur.weather.wear.domain.WearWeatherSnapshot

/** Round watch, 454x454px class (227dp at 320dpi) — Pixel Watch / Galaxy Watch size. */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/**
 * Store listing images for `:weather:wear`, rendered from Compose previews instead
 * of from an instrumented test on a device.
 *
 * `./gradlew :weather:wear:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/weather-wear/wear/` — the wear module's own bucket, not the
 * phone Weather app's folder — where the release build picks them up.
 *
 * Rules (same as every other module's MetadataPreviews): order comes from the
 * function names, each preview needs `@PreviewTest` plus `group = "wear"`, previews
 * are class members, and everything is a literal fixture snapshot. Layoutlib renders
 * a rectangular canvas, so round previews clip to a circle.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-locations-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1LocationsRound() {
        RoundPreview {
            WearLocationsContent(
                snapshot = WearWeatherSnapshot(
                    generatedAtMillis = 0L,
                    locations = listOf(
                        WearWeatherLocation(
                            locationId = 1, name = "Berlin", country = "Germany", isPrimary = true,
                            current = WearWeatherCurrent(temperatureC = 9.0, apparentTemperatureC = 7.0, humidityPercent = 71, weatherCode = 3),
                        ),
                        WearWeatherLocation(
                            locationId = 2, name = "Lisbon", country = "Portugal",
                            current = WearWeatherCurrent(temperatureC = 19.0, apparentTemperatureC = 19.0, humidityPercent = 55, weatherCode = 0),
                        ),
                        WearWeatherLocation(
                            locationId = 3, name = "Reykjavik", country = "Iceland",
                            current = WearWeatherCurrent(temperatureC = 3.0, apparentTemperatureC = 0.0, humidityPercent = 80, weatherCode = 71),
                        ),
                    ),
                ),
                onOpen = {},
            )
        }
    }
}

/** Round-watch framing: wear screens are round-safe by design; clip so the render is too. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background),
        ) { content() }
    }
}
