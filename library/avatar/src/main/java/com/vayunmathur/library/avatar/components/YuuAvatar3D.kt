package com.vayunmathur.library.avatar.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vayunmathur.library.avatar.YuuAvatarNative
import kotlinx.coroutines.delay

/**
 * 3D Yuu hero surface: the native avatar engine draws into a [YuuAvatarSurfaceView]
 * layered in a [Box]. Traits + pose cross as pre-encoded JSON; config updates
 * debounce so editor typing doesn't spam JNI.
 *
 * The caller supplies [configJson] (the `YuuFaceConfig` JSON) and an optional
 * [fallback] shown when native is unavailable (no `libyuu_avatar`) and layered
 * underneath the surface (so a failed renderer never shows a black hole).
 *
 * Each instance carries a stable [surfaceId] for the multi-renderer engine;
 * dispose destroys that surface only.
 *
 * When [enableDragRotate] is true (editor preview), drags over the surface
 * orbit the camera: horizontal drag yaws (unlimited), vertical drag pitches
 * (clamped native-side). The avatar stays where left — no snap-back, no
 * auto-spin (auto-rotate is disabled for the surface). Overlays declared by
 * the caller (e.g. stepper buttons) sit above this layer and keep working.
 */
@Composable
fun YuuAvatar3D(
    configJson: String,
    poseId: String?,
    surfaceId: Int,
    modifier: Modifier = Modifier,
    fallback: (@Composable () -> Unit)? = null,
    enableDragRotate: Boolean = false,
) {
    if (!YuuAvatarNative.isAvailable) {
        fallback?.invoke()
        return
    }
    val pose = poseId ?: "idle"
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, surfaceId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> YuuAvatarNative.setPaused(surfaceId, true)
                Lifecycle.Event.ON_RESUME -> YuuAvatarNative.setPaused(surfaceId, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            YuuAvatarNative.surfaceDestroyed(surfaceId)
        }
    }

    // Debounced config push: rapid draft edits coalesce into one JNI call.
    LaunchedEffect(configJson, pose, surfaceId) {
        delay(CONFIG_DEBOUNCE_MS)
        YuuAvatarNative.setConfig(surfaceId, configJson, pose)
    }

    // Editor drag mode owns the camera from first composition.
    LaunchedEffect(surfaceId, enableDragRotate) {
        if (enableDragRotate) YuuAvatarNative.setAutoRotate(surfaceId, false)
    }

    Box(modifier = modifier) {
        // Placeholder underneath: if the surface never draws (both backends
        // failed), the hero still shows something instead of a black hole.
        fallback?.let { fb ->
            Box(Modifier.fillMaxSize()) { fb() }
        }
        AndroidView(
            factory = { ctx -> YuuAvatarSurfaceView(ctx, surfaceId = surfaceId) },
            modifier = Modifier.fillMaxSize(),
        )
        // Drag layer ABOVE the SurfaceView: touches over a SurfaceView go to
        // the native view and bypass ancestor Compose pointer input, so the
        // gesture must live on a transparent overlay. Declared before any
        // caller overlays (e.g. stepper buttons) so those keep working.
        // Grab metaphor: drag-right turns the object right (yaw decreases),
        // drag-down pulls the front down (pitch increases, eye rises).
        if (enableDragRotate) {
            Box(
                Modifier.fillMaxSize().pointerInput(surfaceId) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        YuuAvatarNative.onRotateInput(
                            surfaceId,
                            -dragAmount.x * DRAG_RAD_PER_PX,
                            dragAmount.y * DRAG_RAD_PER_PX,
                        )
                    }
                }
            )
        }
    }
}

/** Radians of orbit per pixel of drag. */
private const val DRAG_RAD_PER_PX = 0.008f

/** Coalescing window for config pushes: rapid draft edits merge into one JNI call. */
private const val CONFIG_DEBOUNCE_MS = 200L
