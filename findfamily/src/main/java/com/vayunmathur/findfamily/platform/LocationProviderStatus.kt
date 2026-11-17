package com.vayunmathur.findfamily.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global signal describing which location provider the
 * [LocationTrackingService] is currently relying on.
 *
 * Some devices (no Play Services / MicroG, GrapheneOS without network
 * location) have no `NETWORK_PROVIDER`. When that happens the service falls
 * back to GPS-only tracking, which is functional but noticeably heavier on
 * battery. This flag lets the UI surface a warning so the user understands why
 * their battery is draining faster.
 *
 * Mirrors the singleton-StateFlow pattern of [UwbSessionManager].
 */
object LocationProviderStatus {

    private val _usingGpsFallback = MutableStateFlow(false)

    /** True iff the network provider is unavailable and GPS-only fallback is active. */
    val usingGpsFallback: StateFlow<Boolean> = _usingGpsFallback.asStateFlow()

    fun setUsingGpsFallback(value: Boolean) {
        _usingGpsFallback.value = value
    }
}
