package com.vayunmathur.camera.wear.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.camera.wear.R
import com.vayunmathur.camera.wear.data.WearCameraStore

/** Last-capture thumbnail pushed from the phone; the media itself stays on the phone. */
@Composable
fun WearLastCaptureScreen(store: WearCameraStore, onBack: () -> Unit) {
    val thumbnailJpeg by store.thumbnailJpeg.collectAsState()
    val state by store.state.collectAsState()
    val bitmap = remember(thumbnailJpeg) {
        thumbnailJpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            stringResource(R.string.wear_last_capture),
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.lastCaptureKind.isNotEmpty()) {
                Text(
                    state.lastCaptureKind,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        } else {
            Text(
                stringResource(R.string.wear_no_capture),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        Chip(
            onClick = onBack,
            label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
