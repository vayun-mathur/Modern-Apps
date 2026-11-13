package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Engine load phases, mirroring engine_lifecycle.rs. */
object LoadPhase {
    const val IDLE = 0
    const val GENERATING = 1
    const val MESHING = 2
    const val READY = 3
}

/**
 * Covers the world while the engine generates chunks and meshes the area around the player on its
 * own thread, so the UI stays responsive (no main-thread freeze, no ANR) and nobody taps into a
 * half-built world. Swallows touches.
 */
@Composable
fun LoadingOverlay(phase: Int, progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Color(0xFF0E1A0F))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val label = if (phase == LoadPhase.MESHING) R.string.loading_building else R.string.loading_generating
            Text(stringResource(label), color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (phase == LoadPhase.MESHING) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.width(240.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.width(240.dp))
            }
        }
    }
}
