package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable

/** Circular variant — wind, pressure, cloud cover, visibility. */
@Composable
fun CircularStatBlock(content: @Composable BoxScope.() -> Unit) =
    StatBlock(shape = CircleShape, content = content)
