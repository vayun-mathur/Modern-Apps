package com.vayunmathur.astronomy.domain

import com.vayunmathur.library.log.NativeLoader

/**
 * JNI bridge to the native `astronomy_engine` Rust library: the per-tick /
 * per-frame hot paths (batch RaDec -> AltAz, batch stereographic projection).
 *
 * The fully-qualified name of this object MUST stay
 * `com.vayunmathur.astronomy.domain.AstronomyNative` so the JNI symbol mangling
 * (`Java_com_vayunmathur_astronomy_domain_AstronomyNative_*`) matches.
 */
object AstronomyNative {
    /**
     * Whether the `.so` loaded. It always does on a device; Compose previews render on
     * the host JVM through Layoutlib, where there is no native library to load, so the
     * callers that have a Kotlin equivalent (see `projectAll`) check this and fall back
     * rather than dying with an [UnsatisfiedLinkError].
     */
    val available: Boolean = NativeLoader.load("astronomy_engine", "AstronomyNative")

    /**
     * Batch RaDec -> AltAz. [radec] is interleaved `[ra0,dec0,ra1,dec1,...]` in
     * radians; returns interleaved `[az0,alt0,az1,alt1,...]` in radians.
     */
    external fun batchRaDecToAltAz(radec: DoubleArray, lstRad: Double, latRad: Double): DoubleArray

    /**
     * Batch stereographic projection. [altaz] is interleaved `[az0,alt0,...]`;
     * returns interleaved `[x0,y0,x1,y1,...]` in screen pixels, with `Float.NaN`
     * for both components of any culled point.
     */
    external fun batchProject(
        altaz: DoubleArray,
        centerAzRad: Double,
        centerAltRad: Double,
        fovDeg: Double,
        screenW: Double,
        screenH: Double,
        rotationRad: Double,
    ): FloatArray
}
