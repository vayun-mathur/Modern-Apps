package com.vayunmathur.astronomy.domain.projection

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.astronomy.domain.engine.AltAz
import kotlin.math.*

class StereographicProjection(private val viewState: ViewState) : SkyProjection {
    private val sinCenterAlt = sin(viewState.centerAltRad)
    private val cosCenterAlt = cos(viewState.centerAltRad)
    private val centerAz = viewState.centerAzRad
    private val fovRad = viewState.fovRad.coerceIn(Math.toRadians(1.0), Math.toRadians(150.0))
    private val scale: Double = run {
        val rhoEdge = 2.0 * tan(fovRad / 2.0 / 2.0)
        if (rhoEdge < 1e-9) 1.0 else (viewState.screenW * 0.5) / rhoEdge
    }
    // Cull to the disk that circumscribes the whole screen rectangle so the sky
    // fills the entire viewport (no circular cutout / vignette). Since
    // rho(theta) = 2*tan(theta/2)*scale, invert at the screen-corner radius to
    // get the limiting angular distance that still reaches the corners.
    private val halfFov = run {
        val w = viewState.screenW.toDouble()
        val h = viewState.screenH.toDouble()
        val cornerRho = 0.5 * sqrt(w * w + h * h)
        val thetaCorner = 2.0 * atan((cornerRho / scale) / 2.0)
        (thetaCorner + Math.toRadians(2.0)).coerceIn(Math.toRadians(1.0), Math.toRadians(179.0))
    }

    // Full sphere: always include below-horizon for grid + user request to keep below-horizon objects visible
    override fun isVisible(altAz: AltAz): Boolean {
        val theta = angularDist(altAz)
        return theta <= halfFov && theta.isFinite()
    }

    override fun project(altAz: AltAz): Offset? {
        val theta = angularDist(altAz)
        if (theta > halfFov) return null
        return projectCore(altAz, theta)
    }

    // Like [project] but without the on-screen FOV cull, so constellation-art mesh
    // vertices stay continuous even where they run past the visible disk. Only the
    // antipode (where stereographic projection diverges) is rejected.
    fun projectMesh(altAz: AltAz): Offset? {
        val theta = angularDist(altAz)
        if (theta > Math.toRadians(175.0)) return null
        return projectCore(altAz, theta)
    }

    private fun projectCore(altAz: AltAz, theta: Double): Offset {
        if (theta < 1e-9) return Offset(viewState.screenW / 2, viewState.screenH / 2)
        val rho = 2.0 * tan(theta / 2.0) * scale
        val sinAlt = sin(altAz.altRad); val cosAlt = cos(altAz.altRad)
        val dAz = (altAz.azRad - centerAz).let { ((it + PI) % (2*PI)) - PI }
        val sinDaz = sin(dAz); val cosDaz = cos(dAz)
        val phi = atan2(sinDaz * cosAlt, cosCenterAlt * sinAlt - sinCenterAlt * cosAlt * cosDaz)
        val x = rho * sin(phi); val y = rho * cos(phi) // inversion fix: move up shows up
        val cosR = cos(viewState.rotationRad); val sinR = sin(viewState.rotationRad)
        val xr = x * cosR - y * sinR; val yr = x * sinR + y * cosR
        return Offset((viewState.screenW / 2 + xr).toFloat(), (viewState.screenH / 2 + yr).toFloat())
    }

    override fun unproject(screen: Offset): AltAz? {
        val cx = viewState.screenW / 2; val cy = viewState.screenH / 2
        var x = (screen.x - cx).toDouble(); var y = (screen.y - cy).toDouble()
        val cosR = cos(-viewState.rotationRad); val sinR = sin(-viewState.rotationRad)
        val xr = x * cosR - y * sinR; val yr = x * sinR + y * cosR; x = xr; y = yr
        val rho = sqrt(x*x + y*y); if (rho < 1e-12) return AltAz(centerAz, viewState.centerAltRad)
        val rhoNorm = rho / scale; val theta = 2 * atan(rhoNorm / 2.0)
        val phi = atan2(x, -y)
        val sinAlt = sinCenterAlt * cos(theta) + cosCenterAlt * sin(theta) * cos(phi)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val sinDaz = sin(theta) * sin(phi)
        val cosDaz = cosCenterAlt * cos(theta) - sinCenterAlt * sin(theta) * cos(phi)
        val dAz = atan2(sinDaz, cosDaz)
        var az = centerAz + dAz; az %= (2*PI); if (az < 0) az += 2*PI
        return AltAz(az, alt)
    }

    private fun angularDist(altAz: AltAz): Double {
        val sinAlt = sin(altAz.altRad); val cosAlt = cos(altAz.altRad)
        val dAz = (altAz.azRad - centerAz).let { ((it + 3*PI) % (2*PI)) - PI }
        val cosTheta = sinCenterAlt * sinAlt + cosCenterAlt * cosAlt * cos(dAz)
        return acos(cosTheta.coerceIn(-1.0, 1.0))
    }
}
