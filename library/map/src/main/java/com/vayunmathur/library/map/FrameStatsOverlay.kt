package com.vayunmathur.library.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The native step names in [MapNative.lastFrameStepTimesNanos] order.
 *
 * A mirror of the `Step::ALL` short names in `timing.rs` — adding a step there means
 * appending here. Counted rather than zipped: on drift ([times] shorter or longer) the
 * table shows `n/a` rows rather than mislabelling them.
 *
 * The last three rows are counts, not timings (see `Step::is_count`): skips, placement
 * hits and culled draws print as integers.
 */
private val STEP_NAMES = listOf(
    "jni", "upl", "sel", "fen", "ret", "acq", "rec", "plc",
    "ter", "flt", "car", "bld", "sym", "trf", "arw", "msk",
    "ral", "ovl", "sub", "prs", "eout", "phit", "cull", "bind",
    "fcan", "uall",
)
private val COUNT_STEPS = setOf("eout", "phit", "cull", "bind", "fcan", "uall")

/**
 * The per-step frame-time table for the debug overlay: one monospace row per native step,
 * last frame's ms, polled from [MapNative.lastFrameStepTimesNanos].
 *
 * Off by default ([enabled]) so release pays nothing: no poll, no composition, no JNI. The
 * poll runs at ~3 Hz through [renderer]'s render thread and never touches the frame loop — it
 * reads the native copy rather than asking for a frame, so the overlay cannot keep the map
 * awake or change its pacing.
 *
 * @param renderer the live renderer to poll; a zero handle (no surface yet) reads as `n/a`.
 */
@Composable
fun FrameStatsOverlay(
    renderer: SurfaceMapRenderer,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
) {
    if (!enabled) return
    var times by remember { mutableStateOf(LongArray(0)) }
    LaunchedEffect(renderer) {
        while (isActive) {
            // Native state is read on the render thread; the answer lands back on main.
            renderer.queryStepTimes { times = it }
            delay(POLL_MILLIS)
        }
    }
    Box(
        modifier
            .background(Color(OVERLAY_SCRIM))
            .padding(6.dp),
    ) {
        Column {
            // Strings first, composables second: the rows are plain data, emitted below.
            for (i in STEP_NAMES.indices) {
                val name = STEP_NAMES[i]
                val raw = times.getOrNull(i)
                // Count rows print as integers; timing rows as ms with one decimal, fixed
                // width-ish so the table does not jitter while panning.
                val text = when {
                    raw == null -> "$name  n/a"
                    name in COUNT_STEPS -> "$name %5d".format(raw)
                    else -> "$name %5.1f".format(raw / NANOS_PER_MILLI)
                }
                BasicText(text, style = TextStyle(color = Color.White, fontSize = 10.sp))
            }
        }
    }
}

/** How often the overlay polls the native copy: ~3 Hz, slow enough to cost nothing. */
private const val POLL_MILLIS = 333L
private const val NANOS_PER_MILLI = 1_000_000.0
private const val OVERLAY_SCRIM = 0xC0000000
