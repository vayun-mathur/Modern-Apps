package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.BoxScope
import com.vayunmathur.library.ui.MaterialTheme
import androidx.compose.runtime.Composable

/** Square (rounded `extraLarge`) variant — humidity, precipitation, air quality, sun, pollen. */
@Composable
fun SquareBlock(content: @Composable BoxScope.() -> Unit) =
    StatBlock(shape = MaterialTheme.shapes.extraLarge, content = content)
