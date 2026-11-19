package com.vayunmathur.photos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TextLayer
import kotlin.math.roundToInt

@Composable
internal fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { onToggleVisibility(!layer.visible) }, modifier = Modifier.size(28.dp)) {
                if (layer.visible) IconVisible()
                else Text("–", fontSize = 16.sp)
            }
            LayerThumbnail(layer)
            Column(modifier = Modifier.weight(1f)) {
                Text(layer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${androidx.compose.ui.res.stringResource(layer.blendMode.labelRes)} · ${(layer.opacity * 100).roundToInt()}%",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (layer.mask != null) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .border(1.dp, androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

@Composable
private fun LayerThumbnail(layer: Layer) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (layer) {
            is PixelLayer -> {
                val bmp = layer.bitmapRef.bitmap
                if (!bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            is AdjustmentLayer -> Text("ƒ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            is TextLayer -> Text("T", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            is DrawingLayer -> Text("✎", fontSize = 16.sp)
        }
    }
}

@Composable
internal fun ActionChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
