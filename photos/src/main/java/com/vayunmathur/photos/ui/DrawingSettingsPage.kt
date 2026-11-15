package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.data.DrawingTool
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.vayunmathur.photos.R

@Composable
fun DrawingSettingsPage(
    backStack: NavBackStack<EditRoute>,
    initialTool: DrawingTool,
    initialColor: Int,
    initialThickness: Float,
    initialOpacity: Float
) {
    val tool = initialTool
    var color by remember { mutableIntStateOf(initialColor) }
    var thickness by remember { mutableFloatStateOf(initialThickness) }
    var opacity by remember { mutableFloatStateOf(initialOpacity) }

    val registry = LocalNavResultRegistry.current
    val scope = rememberCoroutineScope()

    fun dispatchAndPop() {
        scope.launch {
            registry.dispatchResult("drawing_settings", DrawingSettingsResult(tool, color, thickness, opacity))
            backStack.pop()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(vertical = 12.dp)
                    .background(
                        if (tool == DrawingTool.Eraser) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) else Color(color).copy(alpha = if (tool == DrawingTool.Highlighter) opacity else 1f),
                        RoundedCornerShape(thickness / 2)
                    )
                    .height(thickness.dp)
            )

            Text(stringResource(R.string.thickness), color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = thickness,
                    onValueChange = { thickness = it },
                    valueRange = if (tool == DrawingTool.Highlighter) 5f..80f else 1f..50f,
                    modifier = Modifier.weight(1f)
                )
                Text(thickness.roundToInt().toString(), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
            }

            if (tool == DrawingTool.Highlighter) {
                Text(stringResource(R.string.opacity), color = MaterialTheme.colorScheme.onSurface)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.1f..1f
                )
            }

            if (tool != DrawingTool.Eraser && tool != DrawingTool.Pointer) {
                Text(stringResource(R.string.color), color = MaterialTheme.colorScheme.onSurface)
                val colors = listOf(
                    Color.Red, Color.Green, Color.Blue, Color.Yellow,
                    Color.Cyan, Color.Magenta, Color.White, Color.Black,
                    Color.Gray, Color.DarkGray, Color.LightGray,
                    Color(0xFFFFA500), Color(0xFF800080), Color(0xFF008080)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.height(100.dp)
                ) {
                    items(colors, key = { it.value.toString() }) { c ->
                        val isSelected = color == c.toArgb()
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(36.dp)
                                .aspectRatio(1f)
                                .background(c, CircleShape)
                                .border(
                                    if (isSelected) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape
                                )
                                .clickable { color = c.toArgb() }
                        )
                    }
                }
            }

            com.vayunmathur.library.ui.Button(
                onClick = { dispatchAndPop() },
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
            ) {
                Text(stringResource(UiR.string.done))
            }
        }
    }
}

/** Immutable value describing drawing settings result. */
data class DrawingSettingsResult(
    val tool: DrawingTool,
    val color: Int,
    val thickness: Float,
    val opacity: Float
)
