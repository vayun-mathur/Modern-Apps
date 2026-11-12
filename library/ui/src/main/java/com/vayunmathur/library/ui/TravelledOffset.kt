package com.vayunmathur.library.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset

/**
 * An [Offset] that travels once from [from] to [to] and then calls [onArrived]: a dropped item
 * flying from the finger to its cell, a card returning to the pile it was picked up from.
 *
 * One-shot and keyed rather than target-following like [animatedDp]: the caller owns each trip's
 * identity through [key], so several travellers can be in the air at once, each on its own clock,
 * and recomposing the caller (or adding a sibling traveller) never restarts or jumps one that is
 * already moving. A new [key] starts a new trip from [from]. If [to] moves mid-trip (the
 * destination was re-laid out), the traveller re-aims from wherever it has got to.
 *
 * Returned as a [State] so the reader can defer the read to layout (`Modifier.offset { }`) and a
 * trip does not recompose its caller every frame. Defaults to [Motion.drop], the platform's
 * finger-to-cell landing.
 */
@Composable
fun travelledOffset(
    key: Any,
    from: Offset,
    to: Offset,
    spec: FiniteAnimationSpec<Offset> = Motion.drop(),
    onArrived: () -> Unit = {},
): State<Offset> {
    val position = remember(key) { Animatable(from, Offset.VectorConverter) }
    val arrived = rememberUpdatedState(onArrived)
    LaunchedEffect(key, to) {
        position.animateTo(to, spec)
        arrived.value()
    }
    return position.asState()
}
