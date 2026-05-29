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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1C1E)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(vertical = 12.dp)
                    .background(
                        if (tool == DrawingTool.Eraser) Color.White.copy(alpha = 0.2f) else Color(color).copy(alpha = if (tool == DrawingTool.Highlighter) opacity else 1f),
                        RoundedCornerShape(thickness / 2)
                    )
                    .height(thickness.dp)
            )

            Text("Thickness", color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = thickness,
                    onValueChange = { thickness = it },
                    valueRange = 1f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text(thickness.roundToInt().toString(), color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }

            if (tool == DrawingTool.Highlighter) {
                Text("Opacity", color = Color.White)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0.1f..1f
                )
            }

            if (tool != DrawingTool.Eraser && tool != DrawingTool.Pointer) {
                Text("Color", color = Color.White)
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
                                    Color.White,
                                    CircleShape
                                )
                                .clickable { color = c.toArgb() }
                        )
                    }
                }
            }

            androidx.compose.material3.Button(
                onClick = { dispatchAndPop() },
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
            ) {
                Text("Done")
            }
        }
    }
}

data class DrawingSettingsResult(
    val tool: DrawingTool,
    val color: Int,
    val thickness: Float,
    val opacity: Float
)

private fun Float.roundToInt() = (this + 0.5f).toInt()
