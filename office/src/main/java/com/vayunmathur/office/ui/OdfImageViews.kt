package com.vayunmathur.office.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.office.R

@Composable
fun ImageCropDialog(
    image: OdfImage,
    onApply: (Float, Float, Float, Float) -> Unit,
    onDismiss: () -> Unit,
    onRotate: () -> Unit = {},
    onReplace: () -> Unit = {}
) {
    var left by remember { mutableFloatStateOf(image.cropLeftPct) }
    var top by remember { mutableFloatStateOf(image.cropTopPct) }
    var right by remember { mutableFloatStateOf(image.cropRightPct) }
    var bottom by remember { mutableFloatStateOf(image.cropBottomPct) }
    val preview = image.copy(
        cropLeftPct = left,
        cropTopPct = top,
        cropRightPct = right,
        cropBottomPct = bottom,
        width = 0f,
        height = 0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_image)) },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center) { OdfImageView(preview) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onRotate() }) { Text(stringResource(R.string.rotate_90)) }
                    TextButton(onClick = { onReplace(); onDismiss() }) { Text(stringResource(R.string.replace)) }
                }
                CropSlider("Left", left) { left = it }
                CropSlider("Top", top) { top = it }
                CropSlider("Right", right) { right = it }
                CropSlider("Bottom", bottom) { bottom = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(
            left,
            top,
            right,
            bottom); onDismiss() }) { Text(stringResource(UiR.string.apply)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onApply(0f, 0f, 0f, 0f); onDismiss() }) { Text(stringResource(UiR.string.reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
            }
        }
    )
}

@Composable
private fun CropSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        com.vayunmathur.library.ui.Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..0.45f,
            modifier = Modifier.weight(1f))
        Text("${(value * 100).toInt()}%", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
    }
}




