package com.vayunmathur.launcher.ui.components
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.launcher.domain.TvRowOrder
import com.vayunmathur.launcher.platform.TvTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rasterises a drawable off-main; null while loading or on failure (caller shows fallback). */
@Composable
fun rememberRaster(drawable: Drawable, width: Int, height: Int): ImageBitmap? {
    val density = LocalDensity.current.density
    val w = (width * density).toInt().coerceAtLeast(1)
    val h = (height * density).toInt().coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, drawable, w, h) {
        value = withContext(Dispatchers.Default) {
            runCatching { drawable.toBitmap(width = w, height = h).asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}
