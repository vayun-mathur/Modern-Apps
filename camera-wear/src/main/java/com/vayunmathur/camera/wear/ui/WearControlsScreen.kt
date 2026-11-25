package com.vayunmathur.camera.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
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
 * Remote controls executed on the phone: zoom presets, a zoom scrub
 * (coarse steps over the mirrored range), flash/torch, lens flip and the
 * shutter timer. Every value shown is the phone's mirrored state — a tap
 * sends one op and the watch believes the next state publish, not the tap.
 */
@Composable
fun WearControlsScreen(store: WearCameraStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val listState = rememberScalingLazyListState()

    fun send(op: WearCameraOp) = WearLinkBridge.sendOpAsync(context, op)
    WearControlsContent(state = state, onOp = ::send, onBack = onBack)
}

/** Stateless controls behind [WearControlsScreen]: mirrored state/op sink are parameters for previews. */
@Composable
internal fun WearControlsContent(state: com.vayunmathur.camera.wear.domain.WearCameraState, onOp: (WearCameraOp) -> Unit, onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    fun send(op: WearCameraOp) = onOp(op)

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_controls),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.wear_zoom, formatZoomLabel(state.zoomRatio)),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        itemsIndexed(state.zoomPresets) { index, preset ->
            Chip(
                onClick = { send(WearCameraOp(WearOpKind.ZOOM_PRESET, zoomPresetIndex = index)) },
                label = { Text(formatZoomLabel(preset), maxLines = 1) },
                colors = if (preset == state.zoomRatio) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = { send(WearCameraOp(WearOpKind.SET_ZOOM, zoomRatio = scrubZoom(state.zoomRatio, state.zoomMin, state.zoomMax, up = false))) },
                label = { Text("−", maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = { send(WearCameraOp(WearOpKind.SET_ZOOM, zoomRatio = scrubZoom(state.zoomRatio, state.zoomMin, state.zoomMax, up = true))) },
                label = { Text("+", maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (state.hasFlashUnit) {
            item {
                Chip(
                    onClick = { send(WearCameraOp(WearOpKind.SET_FLASH, flash = nextFlash(state.flash))) },
                    label = { Text(stringResource(R.string.wear_flash, state.flash), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = { send(WearCameraOp(WearOpKind.SET_TORCH, torch = !state.torch)) },
                    label = {
                        Text(
                            "${stringResource(R.string.wear_torch)}: ${if (state.torch) "ON" else "OFF"}",
                            maxLines = 1,
                        )
                    },
                    colors = if (state.torch) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        item {
            Chip(
                onClick = { send(WearCameraOp(WearOpKind.FLIP_LENS)) },
                label = {
                    Text(
                        "${stringResource(R.string.wear_flip_lens)}: " +
                            stringResource(if (state.frontLens) R.string.wear_front else R.string.wear_back_lens),
                        maxLines = 1,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = { send(WearCameraOp(WearOpKind.SET_TIMER, timerSeconds = nextTimer(state.timerSeconds))) },
                label = {
                    Text(
                        stringResource(R.string.wear_timer, if (state.timerSeconds == 0) "OFF" else "${state.timerSeconds}s"),
                        maxLines = 1,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

/** One scrub step: ×/÷ 1.25 in log space, clamped to the mirrored range. */
internal fun scrubZoom(current: Float, min: Float, max: Float, up: Boolean): Float {
    val stepped = if (up) current * ZOOM_STEP_FACTOR else current / ZOOM_STEP_FACTOR
    return stepped.coerceIn(min, max)
}

/** Next flash value in the OFF -> AUTO -> ON cycle. */
internal fun nextFlash(current: String): String = when (current) {
    "OFF" -> "AUTO"
    "AUTO" -> "ON"
    else -> "OFF"
}

/** Next timer value in the protocol's option set, wrapping to off. */
internal fun nextTimer(currentSeconds: Int): Int {
    val options = WearCameraProtocol.TIMER_OPTIONS_SECONDS
    val index = options.indexOf(currentSeconds)
    return options[(index + 1) % options.size]
}

/** ".5", "1x", "1.5x" — the watch's zoom label (mirrors the phone's format). */
internal fun formatZoomLabel(ratio: Float): String = when {
    ratio < 1f -> ".${(ratio * ZOOM_DECIMAL_SCALE).toInt()}"
    ratio == ratio.toInt().toFloat() -> "${ratio.toInt()}x"
    else -> "%.1fx".format(ratio)
}

private const val ZOOM_STEP_FACTOR = 1.25f
private const val ZOOM_DECIMAL_SCALE = 10f
