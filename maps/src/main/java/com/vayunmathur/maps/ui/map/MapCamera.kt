package com.vayunmathur.maps.ui.map
import kotlin.time.Duration.Companion.milliseconds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.maps.domain.NavigationProgress

/** How long the follow animation takes. Matched to the ~1s GPS cadence so it never catches up
 *  to itself and stutters. */
private val FOLLOW_ANIMATION_MS = 800.milliseconds.inWholeMilliseconds.toInt()

/**
 * A camera move within this window of our own `animateTo` is assumed to be that animation.
 *
 * library:map's [CameraState] has no `isCameraMoving`, so the pan-away detection the MapLibre
 * version had is gone with it (GAP: renderer owns the gesture API — see the task-7 report).
 * `autoFollow` therefore stays on until the user toggles it via the recenter control, and this
 * timestamp is kept so that detection can be re-added without re-threading the state.
 */
private const val PROGRAMMATIC_MOVE_WINDOW_MS = 1_200

/** Zoom the camera adopts while following the puck. */
private const val FOLLOW_ZOOM = 17.0

/**
 * Follow the puck while navigating.
 *
 * Heading-up when the chrome is not north-up: drives `bearing = course` alongside the
 * position/zoom follow so the map rotates with travel direction; north-up when it is
 * (or when no course is known), leaving the bearing at whatever the twist gesture set.
 */
@Composable
fun NavigationCameraFollow(
    camera: CameraState,
    chrome: MapChromeState,
    navProgress: NavigationProgress?,
    isNavigating: Boolean,
) {
    LaunchedEffect(navProgress, chrome.autoFollow, chrome.northUp, isNavigating) {
        if (!isNavigating) return@LaunchedEffect
        val progress = navProgress ?: return@LaunchedEffect
        if (!chrome.autoFollow) return@LaunchedEffect
        chrome.lastProgrammaticMoveMs = System.currentTimeMillis()
        camera.animateTo(
            camera.position.copy(
                target = progress.snappedPosition,
                zoom = FOLLOW_ZOOM,
                bearing = if (!chrome.northUp) progress.courseOverGround.toDouble() else camera.position.bearing,
            ),
            FOLLOW_ANIMATION_MS,
        )
    }
}
