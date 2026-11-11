package com.vayunmathur.maps.data

/**
 * Central registry of the maps app's DataStore preference keys and their small
 * enum value types (P6). Kept in one place so the settings screen, the layers
 * sheet, `MainActivity` (theme) and `NavigationService` (voice) all agree on the
 * exact key strings and defaults instead of scattering magic strings.
 */
object MapPreferences {
    const val KEY_VOICE_GUIDANCE = "voice_guidance_enabled"
    const val KEY_THEME_MODE = "theme_mode"
    /**
     * True-3D-sphere globe instead of the flat Mercator map. Maps-only: no other
     * app reads this key or passes `globeEnabled`, so they keep the flat map.
     */
    const val KEY_GLOBE = "globe_enabled"

    // Layer toggles surfaced in the P6 layers sheet.
    const val KEY_LAYER_TRAFFIC = "layer_traffic"
    const val KEY_LAYER_SATELLITE = "layer_satellite"
    const val KEY_LAYER_SAFETY = "layer_safety"
    const val KEY_LAYER_TRANSIT = "layer_transit"

    /**
     * Google services (place details — photos/hours/phone, never reviews — + Street View). OFF by
     * default: nothing contacts Google until the user opts in, via the
     * first-launch consent dialog or the settings toggle. A null (never
     * asked) value is treated as off — the dialog, not the default, decides.
     */
    const val KEY_GOOGLE_SERVICES = "google_services_enabled"
    /** Whether the first-launch Google consent dialog has been answered. */
    const val KEY_GOOGLE_CONSENT_ASKED = "google_consent_asked"

    /**
     * Last known user position (lon/lat doubles). Written on every accepted GPS
     * fix and read back to seed the cold-start camera, so the map opens where
     * the user was. Absent = no fix ever recorded.
     */
    const val KEY_LAST_LON = "last_lon"
    const val KEY_LAST_LAT = "last_lat"

    /**
     * Auto-update map data on unmetered networks. On: [MapOverlayWorker]
     * checks the manifest periodically over WiFi; off: updates only happen
     * from the Settings row.
     */
    const val KEY_MAP_AUTO_UPDATE = "map_auto_update"

    const val DEFAULT_VOICE_GUIDANCE = true
    const val DEFAULT_GLOBE = false
    const val DEFAULT_MAP_AUTO_UPDATE = true
    const val DEFAULT_LAYER_TRAFFIC = true
    const val DEFAULT_LAYER_SATELLITE = false
    const val DEFAULT_LAYER_SAFETY = false
    const val DEFAULT_LAYER_TRANSIT = false
    const val DEFAULT_GOOGLE_SERVICES = false
}

/** App theme selection persisted under [MapPreferences.KEY_THEME_MODE]. */
enum class ThemeMode(val pref: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** null = follow the system (what [com.vayunmathur.library.ui.DynamicTheme] expects). */
    val darkOverride: Boolean?
        get() = when (this) {
            SYSTEM -> null
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun from(pref: String?): ThemeMode = entries.firstOrNull { it.pref == pref } ?: SYSTEM
    }
}
