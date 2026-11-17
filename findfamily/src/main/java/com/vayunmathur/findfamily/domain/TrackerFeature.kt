package com.vayunmathur.findfamily.domain

import com.vayunmathur.findfamily.BuildConfig

/**
 * Single on/off gate for the experimental **custom UWB tracker** feature
 * (crowd-finding network + BLE binding + phone-native UWB precision finding).
 *
 * Backed by [BuildConfig.DEV_BUILD], which is `true` for `assembleDev`/`assembleDebug`
 * and `false` for `assembleRelease`. Because it's a compile-time constant, every
 * `if (TrackerFeature.enabled)` branch (and everything it reaches) is stripped from
 * release builds by R8, and the BLE permissions declared only in
 * `src/dev/AndroidManifest.xml` are never requested there.
 *
 * All tracker entry points in the app must be guarded by this flag.
 */
object TrackerFeature {
    val enabled: Boolean get() = BuildConfig.DEV_BUILD
}
