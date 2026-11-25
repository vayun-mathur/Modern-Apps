package com.vayunmathur.camera.wear.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.camera.wear.R
import com.vayunmathur.camera.wear.data.WearCameraStore
import com.vayunmathur.camera.wear.domain.WearCameraOp
import com.vayunmathur.camera.wear.domain.WearCameraProtocol
import com.vayunmathur.camera.wear.domain.WearOpKind
import com.vayunmathur.camera.wear.sync.WearLinkBridge

/**
 * The live viewfinder: newest preview frame from the phone camera, the
 * mirrored mode/zoom/recording state, and the shutter/record/mode
 * controls. The stream runs only while this screen is visible. With no
 * phone session there is no viewfinder at all — the watch has no camera.
 */
@Composable
fun WearViewfinderScreen(
    store: WearCameraStore,
    onOpenControls: () -> Unit,
    onOpenLastCapture: () -> Unit,
) {
    val context = LocalContext.current
    val connected by store.linkConnected.collectAsState()
    val state by store.state.collectAsState()
    val previewJpeg by store.previewJpeg.collectAsState()
    val rotation by store.previewRotation.collectAsState()

    DisposableEffect(connected) {
        if (connected) WearLinkBridge.startPreview(context)
        onDispose { WearLinkBridge.stopPreview(context) }
    }
    val decoded = remember(previewJpeg) {
        previewJpeg?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    WearViewfinderContent(connected = connected, state = state, bitmap = decoded, rotation = rotation, onOp = { op -> WearLinkBridge.sendOpAsync(context, op) }, onOpenControls = onOpenControls, onOpenLastCapture = onOpenLastCapture)
}

/**
 * Stateless viewfinder behind [WearViewfinderScreen]: connection/mirrored state and the
 * decoded frame (null while waiting) are parameters, so previews render the waiting
 * state with no phone. Layoutlib cannot render a live phone-camera frame — see MetadataPreviews.
 */
@Composable
internal fun WearViewfinderContent(
    connected: Boolean,
    state: com.vayunmathur.camera.wear.domain.WearCameraState,
    bitmap: android.graphics.Bitmap?,
    rotation: Int,
    onOp: (WearCameraOp) -> Unit,
    onOpenControls: () -> Unit,
    onOpenLastCapture: () -> Unit,
) {
    val context = LocalContext.current

    if (!connected) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.wear_no_phone),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.wear_no_phone_hint),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val videoMode = state.mode in WearCameraProtocol.VIDEO_MODES

    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().rotate(rotation.toFloat()),
            )
        } else {
            Text(
                stringResource(R.string.wear_viewfinder_waiting),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            )
        }

        Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp, start = 24.dp, end = 24.dp)) {
            Text(
                state.mode,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.recording) {
                Text(
                    "REC ${formatRecordingTime(state.recordingSeconds)}",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.timerCountdown > 0) {
                Text(
                    "${state.timerCountdown}",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Bottom controls sit on the vertical centreline with a 24dp lift so
        // the round bezel never clips the shutter — the previous 4dp inset
        // put the circular Button's text in the bezel curve.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyRow {
                items(state.remoteModes) { mode ->
                    Chip(
                        onClick = {
                            onOp(WearCameraOp(WearOpKind.SET_MODE, mode = mode))
                        },
                        label = { Text(mode, maxLines = 1) },
                        colors = if (mode == state.mode) {
                            ChipDefaults.primaryChipColors()
                        } else {
                            ChipDefaults.secondaryChipColors()
                        },
                    )
                }
            }
            Chip(
                onClick = {
                    val op = if (videoMode) {
                        WearCameraOp(WearOpKind.RECORD_TOGGLE)
                    } else {
                        WearCameraOp(WearOpKind.SHUTTER)
                    }
                    onOp(op)
                },
                label = {
                    Text(
                        when {
                            state.recording -> stringResource(R.string.wear_record_stop)
                            videoMode -> stringResource(R.string.wear_record_start)
                            else -> stringResource(R.string.wear_shutter)
                        },
                        maxLines = 1,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow {
                item {
                    Chip(
                        onClick = onOpenControls,
                        label = { Text(stringResource(R.string.wear_controls), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    Chip(
                        onClick = onOpenLastCapture,
                        label = { Text(stringResource(R.string.wear_last_capture), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}

/** mm:ss recording duration for the viewfinder's REC line. */
internal fun formatRecordingTime(totalSeconds: Long): String {
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(minutes, seconds)
}

private const val SECONDS_PER_MINUTE = 60L
