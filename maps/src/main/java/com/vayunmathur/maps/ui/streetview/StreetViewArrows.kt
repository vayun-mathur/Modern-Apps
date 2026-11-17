package com.vayunmathur.maps.ui.streetview
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconStraight
import com.vayunmathur.library.ui.PanoramaCameraState
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.StreetViewLink
import com.vayunmathur.maps.data.google.StreetViewPano
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Eye height above the road, metres — how far below the horizon arrows sit. */
private const val EYE_HEIGHT_M = 2.5f

/** Ground distance the arrow is drawn at, and the point it aims toward. */
private const val ANCHOR_M = 8f
private const val TIP_M = 14f

private val ARROW_SIZE = 56.dp

/**
 * Navigation arrows drawn into the panorama: one per walkable neighbour, lying on
 * the ground plane pointing down its road, tappable to travel there.
 *
 * Each arrow is pinned to a compass bearing rather than to the screen, so it stays
 * glued to its road while the user looks around. That is the whole point of the
 * control, and it is why this reads [PanoramaCameraState] and re-projects rather
 * than laying out against the viewport.
 *
 * Drawn in Compose over the GL surface, not as scene geometry, so the shared
 * renderer stays free of any navigation concept — it only publishes where the
 * camera points. Hit testing then comes for free: each arrow is a real composable
 * at a projected position, so Compose resolves the tap and everything outside an
 * arrow falls through to the surface underneath and still pans the view.
 */
@Composable
fun BoxScope.StreetViewArrows(
    pano: StreetViewPano,
    camera: PanoramaCameraState,
    modifier: Modifier = Modifier,
    onTravel: (StreetViewLink) -> Unit,
) {
    BoxWithConstraints(modifier.matchParentSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        val yaw = camera.yaw
        val pitch = camera.pitch
        val fov = camera.fovDeg
        val half = with(LocalDensity.current) { (ARROW_SIZE / 2).toPx() }

        for (link in pano.neighbors) {
            val azimuth = panoYawForBearing(pano.headingDeg, link.bearingDeg)
            val anchor = project(azimuth, groundElevation(ANCHOR_M), yaw, pitch, fov, width, height)
                ?: continue
            val tip = project(azimuth, groundElevation(TIP_M), yaw, pitch, fov, width, height)
                ?: continue
            // Past the frustum edge the projection diverges, so drop anything that
            // lands well outside rather than placing a composable at ±10000 px.
            if (anchor.x < -width || anchor.x > width * 2f) continue

            key(link.panoId) {
                ArrowGlyph(
                    offset = IntOffset((anchor.x - half).toInt(), (anchor.y - half).toInt()),
                    // IconStraight points up the screen, which is atan2(-1, 0) = -90°.
                    rotationDeg = Math.toDegrees(
                        atan2(tip.y - anchor.y, tip.x - anchor.x).toDouble()
                    ).toFloat() + 90f,
                    label = stringResource(R.string.street_view_move, compassPoint(link.bearingDeg)),
                    onClick = { onTravel(link) },
                )
            }
        }
    }
}

@Composable
private fun ArrowGlyph(
    offset: IntOffset,
    rotationDeg: Float,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .offset { offset }
            .size(ARROW_SIZE)
            .semantics { contentDescription = label },
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.85f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IconStraight(
                Modifier.graphicsLayer { rotationZ = rotationDeg },
                tint = Color.Black.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * Sphere yaw (radians) that faces true-north [bearingDeg] in a pano captured at
 * [headingDeg].
 *
 * Two steps, both established by measurement against real captures — see
 * `analysis/streetview/FINDINGS.md`. The source equirect puts image column
 * `u = ((bearing - heading + 180) / 360)`, i.e. the capture heading sits at the
 * image centre and `u` increases clockwise. The mesh then places column `u` at
 * azimuth `-u * 2pi`, because the sphere is textured on the inside.
 */
internal fun panoYawForBearing(headingDeg: Double, bearingDeg: Double): Float {
    val u = (((bearingDeg - headingDeg + 180.0) % 360.0) + 360.0) % 360.0 / 360.0
    return (-u * 2.0 * PI).toFloat()
}

/** The inverse: the true-north bearing the viewer faces at [yaw]. */
internal fun panoBearingForYaw(headingDeg: Double, yaw: Float): Double {
    val u = ((-yaw / (2.0 * PI)) % 1.0 + 1.0) % 1.0
    return ((headingDeg - 180.0 + u * 360.0) % 360.0 + 360.0) % 360.0
}

/** Elevation of a point on the road [distanceM] ahead, radians (negative = down). */
private fun groundElevation(distanceM: Float): Float = -atan2(EYE_HEIGHT_M, distanceM)

/**
 * Project a direction onto the viewport, matching the renderer's own camera.
 *
 * Mirrors `setLookAtM` + `perspectiveM`: screen-right is `s = f x up`, and the
 * perspective divide is by the forward depth. Null when the point is behind the
 * camera, which is what culls arrows the user has turned away from.
 */
private fun project(
    azimuth: Float,
    elevation: Float,
    yaw: Float,
    pitch: Float,
    fovDeg: Float,
    width: Float,
    height: Float,
): Offset? {
    val ce = cos(elevation)
    val px = ce * sin(azimuth)
    val py = sin(elevation)
    val pz = ce * cos(azimuth)

    val cp = cos(pitch)
    val fx = cp * sin(yaw)
    val fy = sin(pitch)
    val fz = cp * cos(yaw)

    val forward = fx * px + fy * py + fz * pz
    if (forward <= 0.01f) return null

    val sx = -cos(yaw)
    val sz = sin(yaw)
    val ux = -sin(yaw) * sin(pitch)
    val uz = -cos(yaw) * sin(pitch)

    val xView = sx * px + sz * pz
    val yView = ux * px + cp * py + uz * pz

    val tanHalf = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
    val xNdc = xView / (width / height * tanHalf * forward)
    val yNdc = yView / (tanHalf * forward)

    return Offset((xNdc * 0.5f + 0.5f) * width, (0.5f - yNdc * 0.5f) * height)
}

/** Compass point (8-wind) for a bearing in degrees. */
internal fun compassPoint(bearingDeg: Double): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((bearingDeg % 360 + 360) % 360) / 45.0).toInt() % 8]
}
