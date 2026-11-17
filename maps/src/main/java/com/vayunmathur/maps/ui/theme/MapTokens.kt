package com.vayunmathur.maps.ui.theme
import com.vayunmathur.maps.data.string

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Map colours that must NOT follow the wallpaper.
 *
 * Everything drawn on the app's own surfaces belongs to `MaterialTheme.colorScheme`. These
 * do not: each one encodes a convention the user already knows from outside the app, and
 * re-hueing it from an arbitrary accent would change what it means. A green traffic jam and
 * a blue speed-limit sign are not restyled UI, they are wrong.
 *
 * They still come in light and dark variants — a fixed hue is not the same as a fixed
 * lightness, and the light-mode values are chosen against a light basemap.
 *
 * Resolve with [mapTokens], passing the map's own dark flag. Plain data rather than a
 * composition local because the map's light/dark state is a setting threaded explicitly (it
 * overrides the system one), and because the route-colour producers that need these are
 * ordinary functions building GeoJSON, not composables.
 */
@Immutable
data class MapTokens(
    val traffic: TrafficColors,
    val transitMode: TransitModeColors,
    val speedSign: SpeedSignColors,
    val roads: RoadColors,
    /** Walking and cycling routes, which carry no congestion data to colour by. */
    val routeInert: Color,
    /** A transit line that reported no colour of its own. */
    val routeTransitFallback: Color,
)

/**
 * Road surfaces, for the roads we draw ourselves from the baked `roads` layer.
 *
 * Unlike everything else here these are not a convention being preserved -- they are basemap
 * cartography, and they exist because above the handover zoom the base style's own road layers
 * are suppressed (see `StylePatcher`) and there is nothing else drawing a road. The dark values
 * come from [BasemapPalette] rather than being chosen again, so a road looks the same whether
 * the base drew it below the handover or we drew it above.
 *
 * Four buckets, not fifteen classes: motorway/trunk, the primary-to-tertiary network, the minor
 * streets, and unpaved or foot-only ways. That is the granularity a width and a fill can
 * actually distinguish at the zooms this layer covers.
 */
@Immutable
data class RoadColors(
    val casing: Color,
    val motorway: Color,
    val major: Color,
    val minor: Color,
    val path: Color,
)

/**
 * The driving-route congestion ramp.
 *
 * Red/amber/green is the convention every traffic map uses; the mapping to `speedRatio`
 * lives in the caller. [traveled] greys out the part of the route already driven, so it has
 * to read as "spent" against both basemaps rather than as a fourth congestion level.
 */
@Immutable
data class TrafficColors(
    val jam: Color,
    val slow: Color,
    val free: Color,
    val traveled: Color,
)

/**
 * Rail-like transit modes, used only as the fallback when a line reports no `colour` of its
 * own. Riders read these off system maps, so the hues are conventional rather than derived.
 */
@Immutable
data class TransitModeColors(
    val rail: Color,
    val subway: Color,
    val lightRail: Color,
    val tram: Color,
    val monorail: Color,
    val train: Color,
)

/**
 * The posted-speed-limit sign: white disc, red ring, near-black numerals.
 *
 * There is deliberately only ONE of these, shared by both palettes. It is a reproduction of a
 * legal road sign, and a dark-mode speed limit sign does not exist — having a single value is
 * how that is stated so nobody adds a variant later. The dark-mode complaint this addresses
 * was never these colours; it was that the card behind them was white too. Theme containers,
 * leave the sign alone.
 */
val SpeedSign = SpeedSignColors(
    disc = Color(0xFFFFFFFF),
    ring = Color(0xFFD32F2F),
    ink = Color(0xFF111111),
)

/** Immutable value describing speed sign colors. */
@Immutable
data class SpeedSignColors(
    val disc: Color,
    val ring: Color,
    val ink: Color,
)

private val LightMapTokens = MapTokens(
    traffic = TrafficColors(
        jam = Color(0xFFF44336),
        slow = Color(0xFFFFC107),
        free = Color(0xFF4CAF50),
        traveled = Color(0xFF9E9E9E),
    ),
    transitMode = TransitModeColors(
        rail = Color(0xFF616161),
        subway = Color(0xFF0055A4),
        lightRail = Color(0xFF00843D),
        tram = Color(0xFFE4002B),
        monorail = Color(0xFF6A1B9A),
        train = Color(0xFF455A64),
    ),
    speedSign = SpeedSign,
    // A light basemap draws its roads as white ribbons on a grey casing, with the motorway
    // network warmed so it reads as the through route at a glance.
    roads = RoadColors(
        casing = Color(0xFFD5D7DB),
        motorway = Color(0xFFFFCE8A),
        major = Color(0xFFFFFFFF),
        minor = Color(0xFFFFFFFF),
        path = Color(0xFFCFC8BE),
    ),
    routeInert = Color(0xFF1710F1),
    routeTransitFallback = Color(0xFFFF0000),
)

/**
 * Same hues, lifted for a dark basemap.
 *
 * The light values are mid-tone Material 500s, which sit close to the dark basemap's own
 * lightness and lose their edges on it. These are the 300/400 steps of the same hues, so
 * the convention survives while the contrast does.
 */
private val DarkMapTokens = MapTokens(
    traffic = TrafficColors(
        jam = Color(0xFFEF5350),
        slow = Color(0xFFFFCA28),
        free = Color(0xFF66BB6A),
        traveled = Color(0xFF757575),
    ),
    transitMode = TransitModeColors(
        rail = Color(0xFF9E9E9E),
        subway = Color(0xFF5B8DEF),
        lightRail = Color(0xFF4CAF50),
        tram = Color(0xFFFF6B6B),
        monorail = Color(0xFFBA68C8),
        train = Color(0xFF90A4AE),
    ),
    // Unchanged: a road sign does not have a dark variant.
    speedSign = SpeedSign,
    roads = RoadColors(
        casing = BasemapPalette.fill(BasemapPalette.Fill.RoadCasing),
        // One step lighter than RoadMajor, which is the only distinction the dark palette has
        // room for: the motorway must separate from the primary network without becoming a
        // label-bright line across the whole viewport.
        motorway = Color(0xFF5A6068),
        major = BasemapPalette.fill(BasemapPalette.Fill.RoadMajor),
        minor = BasemapPalette.fill(BasemapPalette.Fill.RoadMinor),
        path = Color(0xFF3E434B),
    ),
    routeInert = Color(0xFF7C8CFF),
    routeTransitFallback = Color(0xFFFF6E6E),
)

/** The token set for [isDark]. Safe to call off the main thread. */
fun mapTokens(isDark: Boolean): MapTokens = if (isDark) DarkMapTokens else LightMapTokens

/**
 * Render as a style colour string.
 *
 * Parses as CSS colour syntax, so this emits `#rrggbb`, or `rgba()` when there is
 * alpha — `#aarrggbb` is an Android convention and is **not** valid CSS, which is the trap
 * this exists to close. Needed wherever a colour has to survive a round-trip through a
 * GeoJSON feature property (e.g. the Canvas-drawn route's `route-color`) rather than
 * staying a typed value.
 */
fun Color.toStyleHex(): String {
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    if (alpha >= 1f) return "#%02x%02x%02x".format(r, g, b)
    // Two decimals, and locale-independent: alpha is stored quantized to 8 bits, so asking
    // for more precision just surfaces the quantization (0.5f comes back as 0.502). Trailing
    // zeros trimmed so the output is stable enough to assert on.
    val a = String.format(java.util.Locale.ROOT, "%.2f", alpha).trimEnd('0').trimEnd('.')
    return "rgba($r,$g,$b,$a)"
}
