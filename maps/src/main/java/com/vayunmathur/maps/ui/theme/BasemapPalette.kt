package com.vayunmathur.maps.ui.theme
import com.vayunmathur.maps.data.string

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.log.Log
import com.vayunmathur.maps.BuildConfig

/**
 * Colours for things drawn on the *tiles* rather than on the app's surfaces.
 *
 * These were hardcoded for a reason, even if an unstated one: `colorScheme.onSurface` is the
 * wrong reference for a road label, because a road label does not sit on a surface — it sits
 * on the basemap, whose colours this file also owns. Deriving them from the wallpaper would
 * let an unlucky accent produce grey-on-grey terrain, and nothing in the scheme expresses
 * "legible against whatever the terrain happens to be".
 *
 * So the basemap gets its own closed palette, contrast-checked against itself. Only the dark
 * palette exists: light mode uses the Protomaps style's own paint verbatim, and the runtime
 * recolour is what dark mode is.
 *
 * Values are `Color`, not hex strings, so they can be contrast-tested. They cross into the
 * style JSON through [toStyleHex] at a single boundary.
 */
object BasemapPalette {

    /** What a basemap layer *is*, independent of what the style happens to call it. */
    enum class Fill {
        Background,
        Water,
        Waterway,
        Landcover,
        Park,
        UrbanGreen,
        Hospital,
        Industrial,
        School,
        Beach,
        Zoo,
        Aerodrome,
        Airstrip,
        Pedestrian,
        Pier,
        Orchard,
        Vineyard,
        Quarry,
        SwimmingPool,
        Residential,
        Commercial,
        Buildings,
        Boundaries,
        Rail,
        RoadCasing,
        RoadTunnel,
        RoadMajor,
        RoadMinor,
        Other,
    }

    /** What a label layer is. Each carries a text colour and its halo. */
    enum class Label {
        Locality,
        Country,
        Region,
        Subplace,
        Islands,
        Water,
        Shields,
        Road,
        Other,
    }

    /** Immutable value describing label colors; carries text, halo. */
    @Immutable
    data class LabelColors(val text: Color, val halo: Color)

    private val darkFill: Map<Fill, Color> = mapOf(
        Fill.Background to Color(0xFF1B1D22),
        Fill.Water to Color(0xFF0D1B2A),
        Fill.Waterway to Color(0xFF24455F),
        Fill.Landcover to Color(0xFF1F2A22),
        Fill.Park to Color(0xFF1E2B20),
        Fill.UrbanGreen to Color(0xFF23362A),
        Fill.Hospital to Color(0xFF2B2528),
        Fill.Industrial to Color(0xFF20262B),
        Fill.School to Color(0xFF282520),
        Fill.Beach to Color(0xFF2C2A22),
        Fill.Zoo to Color(0xFF213030),
        Fill.Aerodrome to Color(0xFF212228),
        Fill.Airstrip to Color(0xFF2B2D33),
        Fill.Pedestrian to Color(0xFF242229),
        Fill.Pier to Color(0xFF202225),
        Fill.Orchard to Color(0xFF232B22),
        Fill.Vineyard to Color(0xFF222A20),
        Fill.Quarry to Color(0xFF2A2723),
        Fill.SwimmingPool to Color(0xFF0D1B2A),
        Fill.Residential to Color(0xFF26252A),
        Fill.Commercial to Color(0xFF27232B),
        Fill.Buildings to Color(0xFF3C4048),
        Fill.Boundaries to Color(0xFF4A4F57),
        Fill.Rail to Color(0xFF3A3E45),
        Fill.RoadCasing to Color(0xFF111318),
        Fill.RoadTunnel to Color(0xFF2B2E35),
        Fill.RoadMajor to Color(0xFF464B54),
        Fill.RoadMinor to Color(0xFF34383F),
        Fill.Other to Color(0xFF26282E),
    )

    private val darkHalo = Color(0xFF101216)

    private val darkLabel: Map<Label, LabelColors> = mapOf(
        Label.Locality to LabelColors(Color(0xFFE4E8EE), darkHalo),
        Label.Country to LabelColors(Color(0xFF9AA0AA), darkHalo),
        Label.Region to LabelColors(Color(0xFF80868F), darkHalo),
        Label.Subplace to LabelColors(Color(0xFFB0B6C0), darkHalo),
        Label.Islands to LabelColors(Color(0xFF9AA0AA), darkHalo),
        // Blue-tinted so a lake name reads as belonging to the water it sits on.
        Label.Water to LabelColors(Color(0xFF6F8FCE), Color(0xFF0D1B2A)),
        Label.Shields to LabelColors(Color(0xFFC8CCD4), darkHalo),
        Label.Road to LabelColors(Color(0xFFB8BDC6), darkHalo),
        Label.Other to LabelColors(Color(0xFFC9CED6), darkHalo),
    )

    fun fill(role: Fill): Color = darkFill.getValue(role)

    fun label(role: Label): LabelColors = darkLabel.getValue(role)

    /**
     * Every layer id in `assets/style.json`, mapped to its role.
     *
     * Explicit rather than prefix-matched. The prefix rules this replaced were order-dependent
     * and silent: `roads_highway_casing_early` contains both "casing" and "highway", so casing
     * had to be tested first, and any layer the style renamed would quietly fall through to
     * [Fill.Other] and be recoloured wrong with nothing to indicate it. A missing id here is at
     * least visible — see [fillRole].
     */
    private val fillRoles: Map<String, Fill> = buildMap {
        put("background", Fill.Background)
        put("earth", Fill.Background)
        put("landcover", Fill.Landcover)
        put("water", Fill.Water)
        put("water_stream", Fill.Waterway)
        put("water_river", Fill.Waterway)
        put("buildings", Fill.Buildings)

        put("landuse_park", Fill.Park)
        put("landuse_urban_green", Fill.UrbanGreen)
        put("landuse_hospital", Fill.Hospital)
        put("landuse_industrial", Fill.Industrial)
        put("landuse_school", Fill.School)
        put("landuse_beach", Fill.Beach)
        put("landuse_zoo", Fill.Zoo)
        put("landuse_aerodrome", Fill.Aerodrome)
        put("landuse_pedestrian", Fill.Pedestrian)
        put("landuse_pier", Fill.Pier)
        put("landuse_orchard", Fill.Orchard)
        put("landuse_vineyard", Fill.Vineyard)
        put("landuse_quarry", Fill.Quarry)
        put("landuse_swimming_pool", Fill.SwimmingPool)
        put("landuse_residential", Fill.Residential)
        put("landuse_commercial", Fill.Commercial)

        // Runways and taxiways are paved surfaces, not roads: same grey either way.
        put("landuse_runway", Fill.Airstrip)
        put("roads_runway", Fill.Airstrip)
        put("roads_taxiway", Fill.Airstrip)

        put("roads_rail", Fill.Rail)
        put("roads_pier", Fill.Pier)

        // Casings are the dark outline under a road, so they are darker than any road surface.
        // Listed before the surfaces they sit under only for readability; the map has no order.
        for (id in listOf(
            "roads_minor_service_casing", "roads_minor_casing", "roads_link_casing",
            "roads_major_casing_late", "roads_highway_casing_late",
            "roads_major_casing_early", "roads_highway_casing_early",
            "roads_tunnels_other_casing", "roads_tunnels_minor_casing",
            "roads_tunnels_link_casing", "roads_tunnels_major_casing",
            "roads_tunnels_highway_casing",
            "roads_bridges_other_casing", "roads_bridges_link_casing",
            "roads_bridges_minor_casing", "roads_bridges_major_casing",
            "roads_bridges_highway_casing",
        )) put(id, Fill.RoadCasing)

        // Tunnels read as recessed, so they are dimmer than the same road at grade.
        for (id in listOf(
            "roads_tunnels_other", "roads_tunnels_minor", "roads_tunnels_link",
            "roads_tunnels_major", "roads_tunnels_highway",
        )) put(id, Fill.RoadTunnel)

        // Bridges are at grade visually; they only differ by having a casing.
        for (id in listOf(
            "roads_link", "roads_major", "roads_highway",
            "roads_bridges_link", "roads_bridges_major", "roads_bridges_highway",
        )) put(id, Fill.RoadMajor)

        for (id in listOf(
            "roads_other", "roads_minor", "roads_minor_service", "roads_bridges_other",
            "roads_bridges_minor",
        )) put(id, Fill.RoadMinor)

        put("boundaries", Fill.Boundaries)
        put("boundaries_country", Fill.Boundaries)

        // Symbol layers carry no fill; they are here so they resolve rather than warn.
        for (id in listOf(
            "address_label", "water_waterway_label", "roads_oneway", "roads_labels_minor",
            "water_label_ocean", "earth_label_islands", "water_label_lakes", "roads_shields",
            "roads_labels_major", "pois", "places_subplace", "places_region",
            "places_locality", "places_country",
        )) put(id, Fill.Other)
    }

    private val labelRoles: Map<String, Label> = mapOf(
        "places_locality" to Label.Locality,
        "places_country" to Label.Country,
        "places_region" to Label.Region,
        "places_subplace" to Label.Subplace,
        "earth_label_islands" to Label.Islands,
        "water_waterway_label" to Label.Water,
        "water_label_ocean" to Label.Water,
        "water_label_lakes" to Label.Water,
        "roads_shields" to Label.Shields,
        "roads_labels_major" to Label.Road,
        "roads_labels_minor" to Label.Road,
        "address_label" to Label.Road,
    )

    /** Layer ids the palette knows about. Used by the test that keeps it in step with the asset. */
    val knownLayerIds: Set<String> get() = fillRoles.keys

    /** The [Fill] roles that are a road surface or the casing under one. */
    private val ROAD_SURFACE_FILLS =
        setOf(Fill.RoadCasing, Fill.RoadTunnel, Fill.RoadMajor, Fill.RoadMinor)

    /**
     * Which half of the road network a base road layer draws.
     *
     * `StylePatcher` stops each base road layer at the zoom our own `roads` overlay starts
     * drawing the same roads, and `RoadsLayer` does not start every class at once — the through
     * network comes in at the handover zoom and minor streets later, because drawing every
     * service road and footway in a metro at z11 is what the staggering exists to avoid. Two
     * families is exactly what the base style can express, since it groups its road layers the
     * same way, and it is what keeps the two halves from leaving a zoom where NEITHER draws a
     * road.
     */
    enum class RoadFamily {
        /** motorway, trunk, primary, secondary, tertiary, and their `*_link` ramps. */
        Through,

        /** unclassified, residential, service, living_street, pedestrian, tracks and paths. */
        Minor,
    }

    private val roadFamilies: Map<String, RoadFamily> = buildMap {
        for (id in listOf(
            "roads_highway", "roads_major", "roads_link",
            "roads_bridges_highway", "roads_bridges_major", "roads_bridges_link",
            "roads_tunnels_highway", "roads_tunnels_major", "roads_tunnels_link",
            "roads_highway_casing_early", "roads_highway_casing_late",
            "roads_major_casing_early", "roads_major_casing_late",
            "roads_link_casing",
            "roads_bridges_highway_casing", "roads_bridges_major_casing",
            "roads_bridges_link_casing",
            "roads_tunnels_highway_casing", "roads_tunnels_major_casing",
            "roads_tunnels_link_casing",
        )) put(id, RoadFamily.Through)

        for (id in listOf(
            "roads_minor", "roads_minor_service", "roads_other",
            "roads_bridges_minor", "roads_bridges_other",
            "roads_tunnels_minor", "roads_tunnels_other",
            "roads_minor_casing", "roads_minor_service_casing",
            "roads_bridges_minor_casing", "roads_bridges_other_casing",
            "roads_tunnels_minor_casing", "roads_tunnels_other_casing",
        )) put(id, RoadFamily.Minor)
    }

    /**
     * Every base style layer that draws a road surface or its casing, and which family it draws.
     *
     * The membership is derived from [fillRoles] rather than listed again, because a second copy
     * of thirty-odd ids is a second copy to forget: `StylePatcher` uses this to stop the base
     * drawing roads above the zoom where our own `roads` overlay takes over, and a layer missing
     * from the set would draw underneath ours forever with nothing to indicate it. A test pins
     * that every derived id has a family.
     *
     * Surfaces and casings only. Road LABELS, shields, oneway arrows and address points stay
     * with the base at every zoom — the `roads` overlay carries no `name`, so suppressing them
     * would leave an unlabelled city. `RoadsLayer` anchors itself below them for the same reason.
     */
    val roadSurfaceFamilies: Map<String, RoadFamily> get() =
        fillRoles.filterValues { it in ROAD_SURFACE_FILLS }
            .mapValues { (id, _) -> roadFamilies[id] ?: RoadFamily.Through }

    /**
     * Which [Fill] a style layer id denotes.
     *
     * An unknown id falls back to [Fill.Other] so a style update cannot crash the map, but says
     * so on a debug build: falling back silently is how a renamed layer ends up mis-coloured
     * with no way to notice short of looking at the map.
     */
    fun fillRole(id: String): Fill = fillRoles[id] ?: run {
        warnUnmatched(id)
        Fill.Other
    }

    /**
     * Which [Label] a style layer id denotes.
     *
     * No warning here: most layers are not label layers, so "not found" is the common case.
     */
    fun labelRole(id: String): Label = labelRoles[id] ?: Label.Other

    /** Dark fill/line/background colour for a base style layer, as a style string. */
    fun darkFillHex(id: String): String = fill(fillRole(id)).toStyleHex()

    /** Dark `(text-color, text-halo-color)` for a label layer, as style strings. */
    fun darkLabelHex(id: String): Pair<String, String> =
        label(labelRole(id)).let { it.text.toStyleHex() to it.halo.toStyleHex() }

    /**
     * Complain once per unknown id on a debug build.
     *
     * Once, because this runs per layer per theme flip and a repeating log is a log nobody
     * reads. Debug-only, because a release build shipping an updated style should degrade
     * rather than spam.
     */
    private val warned = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun warnUnmatched(id: String) {
        if (!BuildConfig.DEBUG) return
        if (warned.add(id)) {
            Log.status(
                "BasemapPalette",
                "no role for style layer '$id'; it will be recoloured as Other. " +
                    "Add it to BasemapPalette.fillRoles.",
            )
        }
    }
}
