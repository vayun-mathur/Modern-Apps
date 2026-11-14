package com.vayunmathur.music.service.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.validation.HostValidator

/**
 * Car App Library entry point for music.
 *
 * Declared in the manifest with the `androidx.car.app.CarAppService` action
 * and the `androidx.car.app.category.MEDIA` category, so car hosts (MA
 * Auto's launcher, Android Auto) list music in the dock. Playback itself
 * stays in [com.vayunmathur.music.service.PlaybackService] — the car
 * session drives it through a `MediaController` on the same session, and
 * browse reuses [com.vayunmathur.music.service.MusicLibraryTree] IDs, so
 * nothing here forks the player or the library.
 */
class MusicCarAppService : CarAppService() {

    /** Shared constants and helpers for MusicCarAppService. */
    companion object {
        const val MIN_API_MULTI_DISPLAY = 6
        const val MIN_API_THEMING = 9
    }

    // Dev posture: accept any host. Before shipping this must be tightened to a
    // real allow-list (Android Auto / Automotive OS signatures) via
    // HostValidator.Builder + the car-app allowlist — same follow-up as maps.
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MusicCarSession()

    // API 6+: per-display sessions. Cluster sessions still browse — the host
    // picks which templates it allows.
    @RequiresCarApi(MIN_API_MULTI_DISPLAY)
    override fun onCreateSession(sessionInfo: SessionInfo): Session = MusicCarSession()

    // API 9 (experimental): keep MA brand styling on hosts that offer it.
    @RequiresCarApi(MIN_API_THEMING)
    @ExperimentalCarApi
    override fun getCarAppThemeSource(): Int =
        CarAppService.THEME_SOURCE_APP
}
