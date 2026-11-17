package com.vayunmathur.office.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Typography
import com.vayunmathur.library.ui.dynamicDarkColorScheme
import com.vayunmathur.library.ui.dynamicLightColorScheme

// Public so `src/screenshotTest` can wrap a document exactly the way DocumentScreen does;
// without it the listing images would show the paper in the wrong scheme.
@Composable
fun OfficeLightTheme(content: @Composable () -> Unit) {
    // Light scheme — used only for the rendered document (the "paper"), which stays light in dark mode.
    val colorScheme = dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

private val DocumentDarkModeColorMatrix = ColorMatrix(
    floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/**
 * Renders the content into an offscreen layer and paints it back through a
 * luminance-inverting color filter, so the displayed document appears dark
 * without altering the underlying document colors. View-only (#479).
 */
fun Modifier.documentDarkModeInvert(): Modifier = this.drawWithContent {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(DocumentDarkModeColorMatrix)
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
}

@Composable
internal fun OfficeAppTheme(content: @Composable () -> Unit) {
    // The app chrome (menus, toolbars, home) is dark; the document content re-wraps in the light theme.
    val colorScheme = dynamicDarkColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}
