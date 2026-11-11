package com.vayunmathur.library.map

/**
 * Which optional map layers to draw. Both are off by default.
 *
 * Every `.mamaps` archive already carries POI and transit data — the tiler has emitted both
 * for some time — but nothing drew either, so every archive paid storage, bandwidth and
 * decode cost for bytes that were discarded. Turning them on is opt-in rather than opt-out
 * because most consumers (findfamily, photos, weather, taxi, fooddelivery) show a map as a
 * backdrop for their own pins, and neither layer helps that: POI icons compete with the pin,
 * and rail lines are noise outside a transit app. `maps` opts into transit, driven by its
 * own layers toggle.
 *
 * # What turning one on costs
 *
 * Unlike the light/dark switch, this is not free. Both layers are gated at **tessellation**
 * time precisely so that leaving them off costs nothing — with [poi] off no label is shaped
 * and no collision candidate is built for any resident tile. The price is that flipping
 * either one invalidates the meshes already on the GPU, so the renderer re-tessellates the
 * resident tiles from the archive it already holds. No tile is refetched and none is
 * evicted; the existing meshes keep drawing until their replacements land, so the map does
 * not blank. Expect a brief burst of worker activity, not a reload.
 *
 * Setting the same values again is a no-op, so it is safe to pass a freshly-constructed
 * instance on every recomposition.
 */
data class LayerOptions(
    /**
     * Points of interest: an icon plus a wrapped label per feature, at z17 and deeper.
     *
     * Matches the reference basemap's `pois` layer — the same sprite sheet, the same six-way
     * colour split by kind, and the same left/right anchor flip at the screen edge. Forty
     * kinds: the reference's thirty-six plus `fuel`, `hotel`, `bank` and `atm`, which it
     * draws none of. Density is governed by the archive, which drops each kind below its
     * own minimum zoom, so this stays empty at world zoom whatever the flag says.
     */
    val poi: Boolean = false,
    /**
     * Narrow [poi] to these archive kind names (`cafe`, `fuel`, `hotel`, …). Empty draws
     * every kind the style has, which is the default.
     *
     * This is what a category-chip row filters with. It is a *sub-layer* filter — the six
     * `poi-*` layers hold forty kinds between them and a chip selects a handful — so it
     * cannot be expressed by turning layers off. Basemap layers are never narrowed by it:
     * selecting "Coffee" hides other POIs, not the roads.
     *
     * A name the archive schema does not know is ignored rather than treated as an error.
     *
     * Costs what [poi] costs: the filter is applied at tessellation time, so changing it
     * re-tessellates the resident set. Order does not matter and duplicates are fine —
     * equality is by content, so a freshly-built set each recomposition is a no-op.
     */
    val poiKinds: Set<String> = emptySet(),
    /**
     * Rail transit lines, coloured per route.
     *
     * Non-bus routes only. Each line takes the operator's own `colour=` tag from OSM, so
     * these are brand colours and are deliberately **not** shifted by the dark palette —
     * a line that is red on the network map stays red on a dark basemap.
     */
    val transit: Boolean = false,
    /**
     * Live traffic: a per-component overlay coloured red/amber/green by live speeds.
     *
     * The geometry is a line layer baked into the archive (one segment per graph component
     * edge); the colours are not authored but pushed at runtime through
     * [SurfaceMapRenderer.setTrafficSpeeds] as a `component_id → ARGB` table. Gated at
     * tessellation like [poi]/[transit], so leaving it off costs nothing — only a navigation
     * view wants the whole drivable network drawn a second time. A segment with no pushed
     * colour draws nothing, so the basemap road shows through as the no-data look.
     *
     * **Draws nothing on planet3/planet4:** the archive writer stopped emitting `traffic`
     * features (mamaps_build "NA diet"; the layer stays in the dictionary, empty), so there is
     * no geometry to colour. Route ETAs still use live speeds from the first-party
     * `api.maproj.org/maps/traffic` feed; only this map overlay is dark until a rebuild brings
     * the layer back (the real-archive test pins its absence).
     */
    val traffic: Boolean = false,
    /**
     * Road furniture: speed and red-light cameras, licence-plate readers, surveillance
     * cameras, stop signs and traffic signals, drawn as icons from z16. Gated at tessellation
     * like the others. An archive built before the `safety` layer simply draws none.
     */
    val safety: Boolean = false,
) {
    companion object {
        /** The default: basemap only. */
        val None = LayerOptions()

        /** Both optional layers on. */
        val All = LayerOptions(poi = true, transit = true)
    }
}
