//! Tessellate every layer of a tile that could be drawn while it is on screen.
use super::arrows::arrow_meshes;
use super::buildings::extrude_building;
use super::convert::{flatten, widen};
use super::mesh::{BuildingMesh, CarriagewayMesh, LayerMesh, TileMesh};
use super::regions::region_meshes;
use super::split::split_t;
use super::terrain::{drape_vertices, terrain_mesh, tile_ground_width_m};
use super::traffic::{traffic_meshes, traffic_present};
use crate::style::{GatedSummary, KindFilter, Layer, LayerKind, LayerToggles, Toggle, ToggleStamp};
use crate::tess::{fill, ribbon, stroke, subdivide};
use crate::tile::select::ANCESTOR_DEPTH;
use crate::tile::symbol;
use crate::tile::taper;
use tilecodec::mamaps::body::{Body, GEOM_LINE, GEOM_POINT, GEOM_POLYGON};
use tilecodec::mamaps::dict::{LAYER_BUILDINGS, LAYER_JUNCTION};

/// Tessellate every layer of `tile` that could be drawn while this tile is on screen.
///
/// A tile is displayed at its own zoom *and* as a stand-in ancestor for up to
/// [`ANCESTOR_DEPTH`] levels below it, so the camera can be anywhere in `z ..= z +
/// ANCESTOR_DEPTH` while this mesh is resident. Gating on `z` alone — the tile's own zoom —
/// bakes a decision that only holds at the moment of tessellation: an ancestor then carries
/// no `landuse` or `buildings` at all, because those layers' `min_zoom` is above its own,
/// and whole families of geometry appear only once the exact-zoom tiles land rather than
/// the ancestor standing in for them.
///
/// So the gate here is the *widest* it could need to be, and the renderer decides what is
/// actually visible against the camera's own zoom every frame. Layers outside the window
/// are still skipped, which keeps this bounded: a z5 tile does not tessellate buildings.
///
/// Symbol layers shape here zoom-independently (string → advances); per-frame sizing
/// happens in the renderer from `labels`, which carries the shaped candidates while
/// `meshes` carries no symbol vertices (they would be stale the next frame).
pub fn build(
    tile: &Body,
    layers: &[Layer],
    z: u8,
    x: u32,
    y: u32,
    rings_validated: bool,
) -> TileMesh {
    build_toggled(
        tile,
        layers,
        z,
        x,
        y,
        rings_validated,
        LayerToggles::default(),
        &KindFilter::all(),
        0,
    )
}

/// [`build`] with the optional layers the host has turned on.
///
/// The production entry point. [`build`] is the basemap-only shorthand the probes and
/// tests use, so adding an optional layer does not touch a dozen call sites that have no
/// opinion about POI.
///
/// `generation` is stamped onto the result unchanged; the caller reads it, the toggles and
/// `kinds` from the same [`crate::style::SharedToggles`] snapshot so the three cannot disagree.
#[allow(clippy::too_many_arguments)]
pub fn build_toggled(
    tile: &Body,
    layers: &[Layer],
    z: u8,
    x: u32,
    y: u32,
    rings_validated: bool,
    toggles: LayerToggles,
    kinds: &KindFilter,
    generation: u32,
) -> TileMesh {
    let mut meshes = Vec::with_capacity(layers.len());
    let mut labels = Vec::new();
    // The tile's road surfaces, accumulated across every carriageway layer's features. Tile-wide
    // (not per layer) because they draw in their own pass, not the flat layer loop.
    let mut carriageways: Vec<CarriagewayMesh> = Vec::new();
    // Which way traffic drives here, which is what says whether the forward lanes sit on the +1
    // or the -1 side of the road. Absent on every archive that predates the convention byte, and
    // the default — right-hand traffic, white markings — is what most of the world does.
    let left_hand = tile.convention.is_some_and(|c| c.left_hand);
    // The tile's 3D buildings, accumulated across the buildings layer's features into one mesh.
    // Tile-wide (not per layer) because it draws in its own depth-tested pass, not the layer loop.
    let mut building_vertices: Vec<f32> = Vec::new();
    let mut building_indices: Vec<u32> = Vec::new();
    let ground_width_m = tile_ground_width_m(z, y);
    let deepest = z.saturating_add(ANCESTOR_DEPTH);
    // Every point in this tile where the carriageway changes width. Scanned once, tile-wide,
    // because the step it removes is *between* two features — `coalesce`'s merge key includes
    // the lane count, so a road whose count changes arrives as two — and no per-feature pass
    // can see a pair.
    //
    // Only when a carriageway will actually be built from it: with `style::LANE_RENDERING` off
    // the style carries no carriageway layer at all, and outside its zoom window none draws, so
    // otherwise this is a walk over every road feature in the tile for a result nothing reads.
    // An empty scan tapers nothing, so getting this condition wrong costs a step, not a blank map.
    // Written as the layer loop's own zoom test rather than a helper, so the two cannot drift.
    let carriageway_here = layers
        .iter()
        .any(|l| l.carriageway && l.min_zoom <= deepest && l.max_zoom >= z);
    let transitions = if carriageway_here {
        taper::Nodes::scan(tile)
    } else {
        taper::Nodes::default()
    };
    let taper_run = taper::taper_run(ground_width_m);
    let extent = tile.extent as u32;

    // The toggle-gated source summary for kinds-aware re-tessellation (see
    // [`GatedSummary`]): what this tile *could* emit behind each gate, independent of
    // whether the gate is open now. Computed from the source before the toggle gate
    // below, so a tile built with POI off still records the POI kinds it holds.
    //
    // Only toggle-gated layers are scanned: basemap content is never gated, so no
    // summary could excuse it, and the scan is `matches_feature` integer compares over
    // the gated layers' features — not shaping — so it costs a fraction of the labels
    // it saves. The zoom window below mirrors the layer loop's own (a layer outside
    // its window emits nothing either way), and the match is the same
    // `matches_feature` the loop uses, so the two cannot disagree about what counts.
    let mut gated = GatedSummary::default();
    for layer in layers.iter().filter(|l| l.toggle.is_some()) {
        if layer.min_zoom > deepest || layer.max_zoom < z {
            continue;
        }
        let Some(source) = tile.layer(layer.source_layer_id) else {
            continue;
        };
        for feature in &source.features {
            if !layer.matches_feature(feature) {
                continue;
            }
            match layer.toggle {
                Some(Toggle::Poi) => gated.poi_kinds.push(feature.kind),
                Some(Toggle::Transit) => gated.has_transit = true,
                // No style layer carries the traffic toggle today (traffic geometry comes
                // from `traffic_meshes`, probed below) — but if one ever does, its
                // matched features are traffic-gated content all the same.
                Some(Toggle::Traffic) => gated.has_traffic = true,
                Some(Toggle::Safety) => gated.has_safety = true,
                None => {}
            }
        }
    }
    gated.poi_kinds.sort_unstable();
    gated.poi_kinds.dedup();
    gated.has_traffic |= traffic_present(tile, z);

    for (index, layer) in layers.iter().enumerate() {
        // Before the zoom window and before the layer lookup: an optional layer that is
        // off must cost nothing at all. This is the whole reason the gate is here rather
        // than in the renderer — with POI off, no label is shaped for any resident tile.
        if !toggles.enabled(layer.toggle) {
            continue;
        }
        if layer.min_zoom > deepest || layer.max_zoom < z {
            continue;
        }
        let Some(source) = tile.layer(layer.source_layer_id) else {
            continue;
        };

        let mut vertices: Vec<f32> = Vec::new();
        let mut indices: Vec<u32> = Vec::new();
        // Sub-meshes for features that carry their own colour and lane inputs, in
        // first-seen feature order so the archive's determinism carries through to draw
        // order. Stays empty for every layer but transit, and the `transit_color == 0` fast
        // path below keeps it off the hot road path entirely.
        #[allow(clippy::type_complexity)]
        let mut coloured: Vec<((u32, u8, u8, u8), Vec<f32>, Vec<u32>)> = Vec::new();

        for (feature_index, feature) in source.features.iter().enumerate() {
            // Kind, then the road flag/detail filters: one call, so a surface layer never
            // draws the ramps, bridges, tunnels and service streets its `kind` alone would
            // admit. This used to be a `String` allocation and a property-map lookup per
            // feature per tile; now it is integer compares against sorted slices.
            if !layer.matches_feature(feature) {
                continue;
            }
            // The app's category chips, when it has narrowed the map to a few POI kinds. A
            // second sorted-slice test beside the layer's own, and an empty filter admits
            // everything, so the common case is one branch.
            if !kinds.admits(layer, feature.kind) {
                continue;
            }
            let parts = source.parts_of(feature);
            match layer.kind {
                LayerKind::Fill => {
                    if feature.geom_type != GEOM_POLYGON {
                        continue;
                    }
                    // A feature's parts are exactly one exterior and its holes, which is what the
                    // tessellator takes: the encoder splits a multipolygon into one feature per
                    // ring group rather than making every consumer regroup them.
                    let rings: Vec<Vec<(i32, i32)>> = parts
                        .iter()
                        .map(|part| widen(source.points(part)))
                        .collect();
                    if layer.source_layer_id == LAYER_BUILDINGS {
                        // Buildings extrude into 3D instead of drawing a flat footprint: the
                        // side-table attrs (height, roof shape, colours) plus this tile's ground
                        // scale become walls and a roof cap. At pitch 0 the mesh still reads as the
                        // footprint, so the flat map is unchanged; the flat fill is skipped so the
                        // two do not double up.
                        extrude_building(
                            tile,
                            feature_index,
                            &rings,
                            extent,
                            rings_validated,
                            ground_width_m,
                            &mut building_vertices,
                            &mut building_indices,
                        );
                    } else {
                        // The flat `earth` fill is always kept, even on a relief tile: the
                        // terrain grid draws first depth-tested and this fill paints over its
                        // cracks via depth-off layer order, so gaps show earth colour rather
                        // than the water-blue clear colour.
                        fill::tessellate(
                            &rings,
                            extent,
                            rings_validated,
                            &mut vertices,
                            &mut indices,
                        );
                    }
                }
                LayerKind::Line => {
                    // Polygons contribute their outlines too: a lake's shoreline and an
                    // administrative boundary are both lines drawn over an area feature.
                    if !matches!(feature.geom_type, GEOM_LINE | GEOM_POLYGON) {
                        continue;
                    }
                    let gapped = layer.gapped();
                    if layer.carriageway {
                        // The road's own surface, with its lane markings painted on by the
                        // fragment shader rather than drawn. A polygon outline has no
                        // carriageway, so only real line features take this path.
                        if feature.geom_type != GEOM_LINE {
                            continue;
                        }
                        // A lane connector is one lane of traffic through a junction, by
                        // construction: the tiler emits one feature per connector, already
                        // sampled. So its shape is not read off the feature the way a road's is.
                        // One-way because a single stream has no opposing direction to be
                        // separated from, which is what suppresses the centre line; the split is
                        // then meaningless, exactly as it is on a one-way road.
                        let (lanes, oneway, split) = if layer.source_layer_id == LAYER_JUNCTION {
                            (1, true, 0.0)
                        } else {
                            let oneway = feature.is_oneway();
                            let lanes = taper::carriageway_lanes(feature.lane_count, oneway);
                            let split = split_t(tile, layer, feature_index, lanes, left_hand);
                            (lanes, oneway, split)
                        };
                        let at = match carriageways.iter().position(|m| {
                            m.layer_index == index
                                && m.lanes == lanes
                                && m.oneway == oneway
                                && m.split.to_bits() == split.to_bits()
                        }) {
                            Some(at) => at,
                            None => {
                                carriageways.push(CarriagewayMesh {
                                    layer_index: index,
                                    lanes,
                                    split,
                                    oneway,
                                    vertices: Vec::new(),
                                    indices: Vec::new(),
                                });
                                carriageways.len() - 1
                            }
                        };
                        let Some(mesh) = carriageways.get_mut(at) else {
                            continue;
                        };
                        for part in parts {
                            let flat = flatten(source.points(part));
                            // Where this section abuts one of a different lane count, ramp to
                            // the width the two share rather than stepping to it. Per part, and
                            // carried in the vertices rather than in a push constant — which is
                            // why it does not join the mesh key above, and why parts with
                            // different tapers can still share one draw.
                            let taper = if layer.source_layer_id == LAYER_JUNCTION {
                                ribbon::Taper::NONE
                            } else {
                                transitions.taper(&flat, lanes, taper_run)
                            };
                            ribbon::ribbon_tapered(
                                &flat,
                                extent,
                                taper,
                                &mut mesh.vertices,
                                &mut mesh.indices,
                            );
                        }
                    } else if feature.transit_color != 0 {
                        // A transit line carries its own colour: split into one sub-mesh per
                        // distinct (colour, ordinal, lanes, taper), because two routes of one
                        // colour on different ordinals draw as two parallel lines and one mesh
                        // can only take one lateral offset.
                        let key = (
                            feature.transit_color,
                            feature.transit_ordinal,
                            feature.transit_lanes,
                            feature.transit_taper,
                        );
                        let at = match coloured.iter().position(|(k, _, _)| *k == key) {
                            Some(at) => at,
                            None => {
                                coloured.push((key, Vec::new(), Vec::new()));
                                coloured.len() - 1
                            }
                        };
                        let Some((_, v, i)) = coloured.get_mut(at) else {
                            continue;
                        };
                        for part in parts {
                            let flat = flatten(source.points(part));
                            stroke::stroke(&flat, extent, gapped, v, i);
                        }
                    } else {
                        // Every ordinary road and boundary: one mesh for the whole layer.
                        for part in parts {
                            let flat = flatten(source.points(part));
                            stroke::stroke(&flat, extent, gapped, &mut vertices, &mut indices);
                        }
                    }
                }
                LayerKind::Symbol => {
                    // A named point shapes one billboarded block; a named line (road/river)
                    // shapes a curved label laid along its centreline. Both shape here,
                    // zoom-independently — the renderer emits quads per frame at the frame's
                    // text size, straight from the anchor or along the polyline.
                    // Safety furniture carries no name and draws as a bare icon.
                    let icon_only =
                        layer.toggle == Some(Toggle::Safety) && feature.geom_type == GEOM_POINT;
                    let name = tile.name(feature.name_idx).unwrap_or("");
                    if name.is_empty() && !icon_only {
                        continue;
                    }
                    match feature.geom_type {
                        GEOM_POINT => {
                            if let Some(label) = symbol::shape_label(
                                layer,
                                tile,
                                feature,
                                name,
                                extent,
                                index,
                                feature_index,
                            ) {
                                labels.push(label);
                            }
                        }
                        GEOM_LINE => {
                            // The feature's whole centreline in tile-local 0..1 (its parts joined
                            // in order; a coalesced road is usually one part) — the same
                            // extraction `arrow_meshes` uses to place turn arrows.
                            let scale = extent.max(1) as f32;
                            let mut centreline: Vec<(f32, f32)> = Vec::new();
                            for part in parts {
                                for &(px, py) in source.points(part) {
                                    centreline.push((px as f32 / scale, py as f32 / scale));
                                }
                            }
                            if let Some(label) = symbol::shape_line_label(
                                layer,
                                tile,
                                feature,
                                name,
                                index,
                                feature_index,
                                centreline,
                            ) {
                                labels.push(label);
                            }
                        }
                        _ => continue,
                    }
                }
            }
        }

        // Globe zooms: the globe shaders bend only vertices, so long edges must be split or
        // they cut chords through the ball. Before draping, so new vertices get their own height.
        if z <= crate::camera::GLOBE_FLAT_THRESHOLD as u8 && layer.kind != LayerKind::Symbol {
            let edge = subdivide::globe_max_edge(z);
            let fpv = if layer.kind == LayerKind::Fill { fill::FLOATS_PER_VERTEX } else { stroke::FLOATS_PER_VERTEX };
            subdivide::subdivide(&mut vertices, &mut indices, fpv, edge);
            for (_, v, i) in coloured.iter_mut() {
                subdivide::subdivide(v, i, stroke::FLOATS_PER_VERTEX, edge);
            }
        }
        // Drape the layer's flat geometry onto the relief where the tile carries a
        // heightmap; without one the tessellators' z=0 stands and output is unchanged.
        // Done once per mesh, not per feature, so accumulated vertices are walked once.
        // The kind says which vertex format (and trailing z slot) this mesh carries.
        if tile.heightmap.is_some() {
            let (fpv, z) = match layer.kind {
                LayerKind::Fill => (fill::FLOATS_PER_VERTEX, 2),
                LayerKind::Line => (stroke::FLOATS_PER_VERTEX, 7),
                LayerKind::Symbol => (0, 0),
            };
            if fpv > 0 {
                drape_vertices(&mut vertices, fpv, z, &tile.heightmap, ground_width_m);
                for (_, v, _) in coloured.iter_mut() {
                    drape_vertices(
                        v,
                        stroke::FLOATS_PER_VERTEX,
                        7,
                        &tile.heightmap,
                        ground_width_m,
                    );
                }
            }
        }
        if !indices.is_empty() {
            meshes.push(LayerMesh {
                layer_index: index,
                kind: layer.kind,
                vertices,
                indices,
                color_override: None,
                lane: (0, 0, 0),
            });
        }
        for ((color, ordinal, lanes, taper), vertices, indices) in coloured {
            if indices.is_empty() {
                continue;
            }
            // `transit_color` is `0xRRGGBB`; the renderer's colours are ARGB, and a
            // transit line is never translucent.
            meshes.push(LayerMesh {
                layer_index: index,
                kind: layer.kind,
                vertices,
                indices,
                color_override: Some(0xFF00_0000 | color),
                lane: (ordinal, lanes, taper),
            });
        }
    }

    // A road whose every part was degenerate would otherwise cost an empty draw.
    carriageways.retain(|m| !m.indices.is_empty());
    // Drape the carriageway surfaces onto the relief where the tile carries a heightmap;
    // without one the tessellator's z=0 stands and output is unchanged.
    if tile.heightmap.is_some() {
        for m in carriageways.iter_mut() {
            drape_vertices(
                &mut m.vertices,
                crate::tess::ribbon::FLOATS_PER_VERTEX,
                6,
                &tile.heightmap,
                ground_width_m,
            );
        }
    }

    TileMesh {
        z,
        x,
        y,
        meshes,
        buildings: BuildingMesh {
            vertices: building_vertices,
            indices: building_indices,
        },
        terrain: terrain_mesh(tile, ground_width_m),
        labels,
        regions: region_meshes(tile, extent, rings_validated, ground_width_m),
        traffic: traffic_meshes(tile, extent, z, toggles, ground_width_m),
        carriageways,
        yellow_centre: tile.convention.is_some_and(|c| c.yellow_centre),
        arrows: arrow_meshes(tile, z, left_hand),
        generation,
        toggle_stamp: ToggleStamp {
            toggles,
            kinds: kinds.clone(),
            gated,
        },
        heightmap: tile.heightmap.clone(),
    }
}
