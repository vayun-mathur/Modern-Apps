package com.vayunmathur.office.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.office.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FullScreenImage(image: OdfImage, onCrop: (() -> Unit)? = null, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current.density
            val reqWidthPx = if (maxWidth == Dp.Infinity) 1024 else (maxWidth.value * density).toInt().coerceAtLeast(1)
            val reqHeightPx = if (maxHeight == Dp.Infinity) reqWidthPx else (maxHeight.value * density).toInt().coerceAtLeast(1)
            val bitmap by produceState<Bitmap?>(
                null, image.path, image.imageData.size, reqWidthPx, reqHeightPx) {
                value = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(image.imageData, reqWidthPx, reqHeightPx)
                }
            }
            // No image while decoding or for undecodable data, as before.
            bitmap?.let { decoded ->
                Image(
                    bitmap = decoded.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentScale = ContentScale.Fit)
            }
        }
        if (onCrop != null) {
            TextButton(onClick = onCrop, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconCrop(tint = Color.White)
                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.crop), color = Color.White)
            }
        }
    }
}
