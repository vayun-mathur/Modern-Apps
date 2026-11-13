package com.vayunmathur.games.voxels.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.domain.HoldInfo
import com.vayunmathur.games.voxels.domain.WorldTouch
import com.vayunmathur.games.voxels.domain.WorldTouchTracker
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.sqrt

/** How often a held finger repeats its action (mine / attack / eat / throw). */
private const val ACT_REPEAT_MS = 300L

// Fullscreen world-interaction layer + floating look stick. Gesture rules live in the pure
// domain/WorldTouchTracker: a quick still tap places/uses, holding still mines/attacks and repeats
// (with a progress ring), a drag that starts on the right half becomes the look stick and never
// mines, and every finger is tracked separately so one can look while another acts. The move
// joystick and buttons sit above this and take their own touches.
@Composable
fun FloatingLookJoystick(
    modifier: Modifier,
    onLookRate: (Float, Float) -> Unit,
    onPlace: (Offset) -> Unit,
    onBreak: (Offset) -> Unit,
    // Swipe-to-look (default): the camera follows the finger, reported as pixel deltas. Off: the
    // classic floating rate stick that keeps turning while held off-centre.
    swipeLook: Boolean = true,
    onLookDelta: (dx: Float, dy: Float) -> Unit = { _, _ -> },
) {
    val look by rememberUpdatedState(onLookRate)
    val lookDelta by rememberUpdatedState(onLookDelta)
    val swipe by rememberUpdatedState(swipeLook)
    val place by rememberUpdatedState(onPlace)
    val act by rememberUpdatedState(onBreak)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf<HoldInfo?>(null) }
    Box(modifier
        .pointerInput(Unit) {
            val maxR = 70.dp.toPx()
            val tracker = WorldTouchTracker(
                slopPx = 4.dp.toPx(), longPressMs = viewConfiguration.longPressTimeoutMillis, repeatMs = ACT_REPEAT_MS,
            )
            fun dispatch(events: List<WorldTouch>) {
                for (e in events) when (e) {
                    is WorldTouch.Tap -> place(Offset(e.x, e.y))
                    is WorldTouch.Act -> act(Offset(e.x, e.y))
                    WorldTouch.ActEnd -> hold = null
                    is WorldTouch.LookStart -> {
                        origin = Offset(e.x, e.y); knob = Offset.Zero; active = !swipe; look(0f, 0f)
                    }
                    is WorldTouch.LookMove -> if (swipe) lookDelta(e.dx, e.dy) else {
                        val nk = Offset(e.x, e.y) - origin
                        val dist = hypot(nk.x, nk.y)
                        knob = if (dist > maxR && dist > 0.001f) nk * (maxR / dist) else nk
                        val nx = knob.x / maxR
                        val ny = knob.y / maxR
                        if (sqrt(nx * nx + ny * ny) < 0.1f) look(0f, 0f) else look(lookCurve(-nx), lookCurve(ny))
                    }
                    WorldTouch.LookEnd -> { active = false; knob = Offset.Zero; look(0f, 0f) }
                }
            }
            coroutineScope {
                // Long presses and repeats are time-driven, not event-driven.
                launch {
                    while (isActive) {
                        delay(16)
                        val now = SystemClock.uptimeMillis()
                        dispatch(tracker.tick(now))
                        hold = tracker.hold(now)
                    }
                }
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            for (c in ev.changes) {
                                val id = c.id.value
                                when {
                                    c.changedToDownIgnoreConsumed() -> dispatch(
                                        tracker.down(id, c.position.x, c.position.y, c.uptimeMillis, lookSide = c.position.x > size.width / 2f)
                                    )
                                    c.changedToUpIgnoreConsumed() -> dispatch(tracker.up(id))
                                    c.positionChanged() -> dispatch(tracker.move(id, c.position.x, c.position.y))
                                }
                                if (tracker.owns(id)) c.consume()
                            }
                        }
                    }
                } finally {
                    dispatch(tracker.cancel())
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Crosshair: the Use button and the camera aim here.
            val c = Offset(size.width / 2f, size.height / 2f)
            val arm = 7.dp.toPx()
            for ((col, w) in listOf(Color.Black.copy(alpha = 0.55f) to 3.5.dp.toPx(), Color.White.copy(alpha = 0.85f) to 1.5.dp.toPx())) {
                drawLine(col, c - Offset(arm, 0f), c + Offset(arm, 0f), strokeWidth = w)
                drawLine(col, c - Offset(0f, arm), c + Offset(0f, arm), strokeWidth = w)
            }
            if (active) {
                drawStickBase(origin, 70.dp.toPx())
                drawStickKnob(origin + knob, 30.dp.toPx(), active = true)
            }
            hold?.let { h ->
                // Progress ring around the held finger: fills toward the next mine/attack.
                val r = 34.dp.toPx()
                val tl = Offset(h.x - r, h.y - r)
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = r, center = Offset(h.x, h.y), style = Stroke(6.dp.toPx()))
                drawArc(
                    Color.White.copy(alpha = 0.9f), startAngle = -90f, sweepAngle = 360f * h.progress, useCenter = false,
                    topLeft = tl, size = Size(r * 2, r * 2), style = Stroke(4.dp.toPx()),
                )
            }
        }
    }
}
