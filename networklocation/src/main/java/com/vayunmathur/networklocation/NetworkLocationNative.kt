package com.vayunmathur.networklocation

// PACKAGE STRUCTURE EXCEPTION (JNI)

/**
 * JNI bridge to the native `networklocation` Rust library: device-position
 * estimation from the beacon fixes (cell towers + WiFi APs) resolved against the
 * offline WPSDB stores and cached locally.
 *
 * The fully-qualified name of this object MUST stay
 * `com.vayunmathur.networklocation.NetworkLocationNative` so the JNI symbol
 * mangling (`Java_com_vayunmathur_networklocation_NetworkLocationNative_*`)
 * matches.
 */
object NetworkLocationNative {
    /** Whether the `.so` loaded. Always true on device; guarded so host/unit
     * contexts without the native library degrade gracefully. */
    val available: Boolean = runCatching { System.loadLibrary("networklocation") }.isSuccess

    /**
     * Estimate the device position from beacon fixes.
     *
     * [beacons] is interleaved `[lat0,lon0,acc0,lat1,lon1,acc1,...]` — latitude
     * and longitude in degrees, accuracy radius in metres. Returns a 3-element
     * `[lat, lon, accuracyMeters]`, or null when there are no usable beacons.
     */
    external fun estimatePosition(beacons: DoubleArray): DoubleArray?
}
