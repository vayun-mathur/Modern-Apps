package com.vayunmathur.library.ui

import android.annotation.SuppressLint

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The animated values a screen needs, on the theme's motion scheme.
 *
 * Companion to [Motion], which names durations and curves: these name the *interactions* that were
 * being rebuilt by hand at each call site. Screens reach for these rather than
 * `animate*AsState`/`animateItem` directly, which is also what keeps compose-animation out of the
 * app modules - a spec written at a call site carries no intent, and the ones written with no spec
 * at all silently took Compose's own default rather than the scheme this app is themed with.
 *
 * Every spec here is a scheme spring, which is right for a state change *within* a screen: a spring
 * is interruptible from wherever it has got to, so a second tap does not have to wait out the first.
 * Anything driven by a predictive-back gesture needs a duration instead, and lives in
 * `com.vayunmathur.library.util` with the navigation transitions.
 */

/**
 * A [Dp] that animates to [target] on the scheme's spatial spec - a size, an offset, an elevation.
 *
 * Spatial rather than effects: this is a value that moves something.
 */
@Composable
fun animatedDp(
    target: Dp,
    spec: FiniteAnimationSpec<Dp> = MaterialTheme.motionScheme.defaultSpatialSpec(),
): Dp {
    val value by animateDpAsState(targetValue = target, animationSpec = spec)
    return value
}

/**
 * A [Float] that animates to [target] on the scheme's effects spec - an alpha, a progress fraction.
 *
 * Pass a spatial spec explicitly when the float drives movement rather than an effect: mixing the
 * two up is what makes a fade look like it lags the thing it belongs to.
 */
@Composable
fun animatedFloat(
    target: Float,
    spec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec(),
): Float {
    val value by animateFloatAsState(targetValue = target, animationSpec = spec)
    return value
}

/** A [Color] that animates to [target] - a container tinting as its section opens. */
@Composable
fun animatedColor(
    target: Color,
    spec: FiniteAnimationSpec<Color> = MaterialTheme.motionScheme.defaultEffectsSpec(),
): Color {
    val value by animateColorAsState(targetValue = target, animationSpec = spec)
    return value
}

/**
 * The corner radius under a finger: a round avatar or tile squares off on press and springs back on
 * release.
 *
 * A percentage rather than a [Dp], so it reads the same on a 50dp list avatar and the 100dp one on a
 * detail page, and on the bouncy `fast` spring because a press wants to feel like it gives.
 */
@Composable
fun pressedShape(pressed: Boolean, restingPercent: Int = 50, pressedPercent: Int = 30): Shape {
    val percent by animateIntAsState(
        targetValue = if (pressed) pressedPercent else restingPercent,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    return RoundedCornerShape(percent)
}

/**
 * Lets a lazy item fade in, fade out and slide to its new position as the list changes around it.
 *
 * On the scheme's specs rather than `animateItem`'s own defaults, so a list reordering matches the
 * rest of the app. [placementSpec] overrides just the travel, for a list whose reordering has its
 * own timing - a launcher page rearranging under a drag, say, where [Motion.reorder] is the platform
 * duration for it.
 *
 * Only meaningful on items with a stable `key`; without one the list has no way to tell that an item
 * moved rather than that its content changed.
 */
// ModifierFactoryExtensionFunction is a false positive here by construction:
// Modifier.animateItem only exists as an extension on Modifier *inside*
// LazyItemScope/LazyGridItemScope, so the factory needs the lazy-item receiver
// as well as returning a Modifier; Kotlin has no stable dual-receiver form for
// `Modifier.itemMotion()` that still reaches animateItem.
@SuppressLint("ModifierFactoryExtensionFunction")
@Composable
fun LazyItemScope.itemMotion(placementSpec: FiniteAnimationSpec<IntOffset>? = null): Modifier {
    val scheme = MaterialTheme.motionScheme
    val fade: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
    return Modifier.animateItem(
        fadeInSpec = fade,
        placementSpec = placementSpec ?: scheme.defaultSpatialSpec(),
        fadeOutSpec = fade,
    )
}

/** [itemMotion] for a grid: a tile removed from a grid reflows every tile after it. */
@SuppressLint("ModifierFactoryExtensionFunction")
@Composable
fun LazyGridItemScope.itemMotion(placementSpec: FiniteAnimationSpec<IntOffset>? = null): Modifier {
    val scheme = MaterialTheme.motionScheme
    val fade: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
    return Modifier.animateItem(
        fadeInSpec = fade,
        placementSpec = placementSpec ?: scheme.defaultSpatialSpec(),
        fadeOutSpec = fade,
    )
}

/** How far apart consecutive sections start their entrance. */
private const val STAGGER_STEP_MILLIS = 45L

/** How far a section rises through as it arrives. */
private val StaggerLift = 12.dp

/**
 * A short rise-and-fade so a page of sections reads as assembling under its header rather than
 * appearing all at once. [index] is the section's position in that sequence.
 *
 * [arriving] is captured on first composition rather than observed: a section composed later -
 * scrolled to after the entrance is over - should simply be visible rather than animate in.
 */
@Composable
fun Modifier.staggeredEntrance(index: Int, arriving: Boolean): Modifier {
    var shown by remember { mutableStateOf(!arriving) }
    LaunchedEffect(Unit) {
        if (!shown) {
            delay(index * STAGGER_STEP_MILLIS)
            shown = true
        }
    }
    val lift = animatedDp(if (shown) 0.dp else StaggerLift)
    return this
        .offset { IntOffset(0, lift.roundToPx()) }
        .alpha(animatedFloat(if (shown) 1f else 0f))
}
