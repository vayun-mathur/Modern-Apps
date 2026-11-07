package com.vayunmathur.communicate.rcs

import android.net.ConnectivityManager
import android.net.NetworkRequest
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager

/**
 * Makes IMS network requests fail immediately (onUnavailable) instead of
 * Robolectric's default of never calling back, which would otherwise park
 * every IMS lookup for its full 15 s timeout. The production fallback paths
 * (plain sockets, fail-closed sends) are what these tests exercise.
 */
@Implements(ConnectivityManager::class)
class RcsSlice3Pass2ConnectivityShadow : ShadowConnectivityManager() {
    @Implementation
    override fun requestNetwork(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
        callback.onUnavailable()
    }
}
