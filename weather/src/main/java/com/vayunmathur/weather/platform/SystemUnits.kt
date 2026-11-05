package com.vayunmathur.weather.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.text.util.LocalePreferences
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.WindUnit
import java.util.Locale

/**
 * Resolve the display units from the device's regional preferences (set
 * via Android 14's *Settings → System → Languages → Regional preferences*,
 * and inferred from the active locale on earlier versions). Reading via
 * Compose's `LocalConfiguration` ensures we re-resolve on configuration
 * changes when the user flips a setting at runtime.
 *
 * Temperature comes from [LocalePreferences.getTemperatureUnit].
 *
 * Wind speed (and any other distance-based units) comes from the
 * **measurement system** preference, which Android writes to the active
 * locale as the Unicode extension `-u-ms-{metric|ussystem|uksystem}`. We
 * read it directly via [Locale.getUnicodeLocaleType] since the androidx
 * helper doesn't expose it yet — falling back to the locale's country code
 * (US/LR/MM → US system) for devices where the extension is absent.
 */
@Composable
fun rememberTempUnit(): TemperatureUnit {
    LocalConfiguration.current
    return remember(LocalConfiguration.current) { systemTempUnit() }
}

fun systemTempUnit(): TemperatureUnit = when (LocalePreferences.getTemperatureUnit()) {
    LocalePreferences.TemperatureUnit.FAHRENHEIT -> TemperatureUnit.Fahrenheit
    else -> TemperatureUnit.Celsius
}

@Composable
fun rememberWindUnit(): WindUnit {
    LocalConfiguration.current
    return remember(LocalConfiguration.current) {
        if (isImperialMeasurementSystem(Locale.getDefault())) WindUnit.Mph else WindUnit.KmH
    }
}

@Composable
fun rememberPressureUnit(): PressureUnit {
    LocalConfiguration.current
    return remember(LocalConfiguration.current) {
        if (isImperialMeasurementSystem(Locale.getDefault())) PressureUnit.InHg else PressureUnit.Hpa
    }
}

@Composable
fun rememberUse24Hour(): Boolean {
    LocalConfiguration.current
    val context = LocalContext.current
    return remember(LocalConfiguration.current) {
        android.text.format.DateFormat.is24HourFormat(context)
    }
}

/**
 * True when the locale opts into a non-metric measurement system, either
 * via the explicit `-u-ms-…` Unicode extension that Android 14's regional
 * preference sets, or by virtue of being one of the three remaining
 * predominantly-imperial country codes.
 */
private fun isImperialMeasurementSystem(locale: Locale): Boolean {
    val extension = locale.getUnicodeLocaleType("ms")
    if (extension != null) {
        return extension == "ussystem" || extension == "uksystem"
    }
    return locale.country in setOf("US", "LR", "MM")
}
