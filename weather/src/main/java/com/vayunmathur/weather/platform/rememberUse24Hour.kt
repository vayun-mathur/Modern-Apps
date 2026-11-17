package com.vayunmathur.weather.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.WindUnit
import java.util.Locale

@Composable
fun rememberUse24Hour(): Boolean {
    LocalConfiguration.current
    val context = LocalContext.current
    return remember(LocalConfiguration.current) {
        android.text.format.DateFormat.is24HourFormat(context)
    }
}
