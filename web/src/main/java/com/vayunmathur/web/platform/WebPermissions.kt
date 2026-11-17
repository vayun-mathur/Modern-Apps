package com.vayunmathur.web.platform

import android.os.Build

/** Runtime permissions for the Web module (mirrors AndroidManifest.xml). */
object WebPermissions {
    /**
     * Local Network Protections. Without it the OS refuses every socket to a
     * private-range address, and unlike camera or geolocation WebView has no callback for it:
     * the connection just fails.
     *
     * The permission name is a stable platform string; it is read as a literal
     * because the `Manifest.permission` constant for it was introduced at API 37,
     * above this module's minSdk, so referencing the field would be inlined
     * without any runtime guard on older devices. Below 37 the value is null
     * (the manifest entry itself stays unguarded, per repo convention).
     */
    val LOCAL_NETWORK: String? =
        if (Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else null
}
