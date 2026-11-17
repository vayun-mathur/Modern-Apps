package com.vayunmathur.auto.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.library.carhost.CarTemplateView

/**
 * One hosted app's screen: renders its latest parsed template full-bleed.
 *
 * The Template→Compose rendering lives in `:library:carhost` ([CarTemplateView])
 * so apps can render the same views in screenshot tests. This wrapper supplies
 * the two things only the head unit owns: the template source
 * ([CarLauncherState.templates]) and the real map [Surface] via [MapSurfaceIsland].
 */
@Composable
fun AppScreen(appId: String, modifier: Modifier = Modifier) {
    val templates by CarLauncherState.templates.collectAsStateWithLifecycle()
    CarTemplateView(
        template = templates[appId],
        modifier = modifier,
        mapContent = { MapSurfaceIsland(it) },
    )
}

/**
 * The hosted map `Surface` as a `TextureView` interop island.
 *
 * The car-app framework hands the host a `SurfaceContainer` the app draws its
 * own map into; Compose cannot host that surface any other way. Lifecycle:
 * available forwards into the session mirror, destroy releases it — matching
 * the old `CarNavCardView` contract. Touches forward into the app's own
 * `SurfaceCallback` (pan/zoom/click in the app itself).
 */
@Composable
private fun MapSurfaceIsland(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            CarTextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        CarLauncherState.mapSurfaceListener?.invoke(Surface(surface), width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        CarLauncherState.mapSurfaceListener?.invoke(null, 0, 0)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                setOnTouchListener { view, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) view.performClick()
                    CarLauncherState.mapTouchForwarder?.invoke(event.action, event.x, event.y) == true
                }
            }
        },
        modifier = modifier,
    )
}

/** Map surface view: forwards touches to the car, so clicks are reported through performClick. */
private class CarTextureView(context: android.content.Context) : TextureView(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
