package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared shell for every weather grid block: a `surface`-colored [Surface]
 * with a 2.dp shadow and the given [shape], wrapping a square
 * (`aspectRatio(1f)`) [Box] that fills it. Blocks lay out their content with
 * the usual [BoxScope] alignment modifiers.
 */
@Composable
fun StatBlock(
    shape: Shape,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().aspectRatio(1f), content = content)
    }
}
