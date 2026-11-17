package com.vayunmathur.weather.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.WindUnit
import java.util.Locale

@Composable
fun rememberPressureUnit(): PressureUnit {
    LocalConfiguration.current
    return remember(LocalConfiguration.current) {
        if (isImperialMeasurementSystem(Locale.getDefault())) PressureUnit.InHg else PressureUnit.Hpa
    }
}
