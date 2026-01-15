package com.vayunmathur.library.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

@Composable
fun DynamicTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

@Composable
fun DynamicThemeGlance(
    context: Context,
    content: @Composable () -> Unit
) {
    val colors = ColorProviders(
        light = dynamicLightColorScheme(context),
        dark = dynamicDarkColorScheme(context)
    )

    GlanceTheme(
        colors = colors,
        content = content
    )
}