package com.vayunmathur.maps.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point (P12).
 *
 * Declared in the manifest with the `androidx.car.app.CarAppService` action and
 * the `androidx.car.app.category.NAVIGATION` category, so the Android Auto host
 * treats this as a turn-by-turn navigation app (map surface + NavigationTemplate).
 * All routing, the navigation session/progress, and voice guidance are the
 * app's existing phone stack — see [MapsSession].
 */
class MapsCarAppService : CarAppService() {

    /** Shared constants and helpers for MapsCarAppService. */
    companion object {
        /** Car API level for per-display sessions (MAIN + CLUSTER). */
        private const val CAR_API_SESSIONS = 6
        /** Car API level for the brand-styling theme source. */
        private const val CAR_API_THEME_SOURCE = 9
    }

    // Dev posture: accept any host. Before shipping this must be tightened to a
    // real allow-list (Android Auto / Automotive OS signatures) via
    // HostValidator.Builder + the car-app allowlist — that is part of the
    // on-device (DHU/car) verification follow-up, not compile-time.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MapsSession()

    // API 6+: per-display sessions (MAIN + CLUSTER). Cluster sessions still get
    // a MapsSession — NavMapScreen renders NavigationTemplate, the only
    // template the cluster allows.
    @RequiresCarApi(CAR_API_SESSIONS)
    override fun onCreateSession(sessionInfo: SessionInfo): Session = MapsSession()

    // API 9 (experimental): keep MA brand styling instead of adopting OEM
    // system styling. Only honored by hosts negotiating API 9+.
    @RequiresCarApi(CAR_API_THEME_SOURCE)
    @ExperimentalCarApi
    override fun getCarAppThemeSource(): Int =
        CarAppService.THEME_SOURCE_APP
}
