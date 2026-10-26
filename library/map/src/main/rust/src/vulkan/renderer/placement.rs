use super::{
    anchors_for, kind_name,
    placement_reuse::{box_inputs, box_inputs_with_arms, bucket_for, reuse_cached_accept},
    AcceptSet, Overlay, PlacedHit, PlacementKey, Renderer, QUAD_INDICES,
};
use crate::camera::Camera;
use crate::marker::Marker;
use crate::style::{Layer, LayerKind};
use ash::vk;
use std::collections::{HashMap, HashSet};
use std::ops::RangeInclusive;
use std::time::Instant;

/// How long a collision answer may be reused while the camera moves.
///
/// The placer is quadratic in accepted boxes plus per-glyph box projection for every curved
/// label — all of it on the Choreographer callback. During a pan a frame lands every ~16 ms
/// with a slightly moved camera, so re-placing every frame is what the panning jank is made
/// of. Within this window a moved camera reuses the last accept-set (labels hold their
/// relative order; boxes are still re-projected per frame in `refresh_placed`, so what draws
/// tracks the camera and only the *collision outcome* lags). Past it — or on any non-center
/// change (zoom, bearing, pitch, tiles, layers, filter) — the pass re-runs. A tap during the
/// window picks against boxes projected for the current frame, so hit-testing never lags.
/// hit-testing never lags.
///
/// The same window covers small zoom/bearing/pitch drift (see `drift_reuse_ms`): a pinch or
/// rotate that stays within its thresholds reuses the accept-set too, so a gesture does not
/// re-place every frame either. The collision outcome then lags the camera by at most this
/// window (80 ms on a fast zoom-out) — the approved trade-off for holding 60 fps through
/// gestures.
pub(super) const PLACE_REUSE_MS: u128 = 300;

impl Renderer {
    /// Whether any resident tile carries a region-mask piece with this id.
    ///
    /// The place->boundary link is baked at build time, so the id is known the instant a label is
    /// tapped — but the boundary's own tiles may still be loading, in which case there is nothing to
    /// draw the mask from yet. The host retries [`setRegionMask`](crate::bridge::region) each frame
    /// until this turns true. Kept a pure lookup over `RegionBuffers::id`, the same key
    /// [`record_region_mask`](super::Renderer::record_region_mask) filters on.
    pub fn region_resident(&self, id: u64) -> bool {
        self.tiles
            .values()
            .any(|tile| tile.regions.iter().any(|region| region.id == id))
    }

    /// The per-frame symbol pre-pass: one collision candidate per shaped label
    /// of every drawing symbol layer, placed once, globally.
    ///
    /// Collision padding widens with zoom-out (see
    /// [`collision_padding_dp`]): at low zoom boxes overlap eagerly so only
    /// the most important places survive, matching MapLibre's ~10 cities at
    /// z6; at street zoom padding relaxes toward the drawn box.
    pub(super) fn place_symbols(
        &self,
        camera: &Camera,
        layers: &[Layer],
        ordered: &[u64],
        extent: vk::Extent2D,
        filter: &crate::style::KindFilter,
    ) -> AcceptSet {
        use crate::tile::placement;
        let globe = crate::camera::globe_active(camera);
        let (bucket_zoom, bucket_bearing, bucket_pitch, bucket_lon, bucket_lat) =
            bucket_for(camera);
        let key = PlacementKey {
            center_lon: camera.center_lon.to_bits(),
            center_lat: camera.center_lat.to_bits(),
            zoom: camera.zoom.to_bits(),
            bearing: camera.bearing_deg.to_bits(),
            pitch: camera.pitch_deg.to_bits(),
            bucket_zoom,
            bucket_bearing,
            bucket_pitch,
            bucket_lon,
            bucket_lat,
            globe,
            width_dp: camera.width_dp.to_bits(),
            height_dp: camera.height_dp.to_bits(),
            density: camera.density.to_bits(),
            extent: (extent.width, extent.height),
            filter: filter.clone(),
            tiles: ordered
                .iter()
                .map(|key| {
                    (
                        *key,
                        self.tiles
                            .get(key)
                            .map(|t| t.uploaded_at)
                            .unwrap_or(0.0)
                            .to_bits(),
                    )
                })
                .collect(),
            layers: layers.len(),
            markers: self.marker_epoch,
        };
        // The reuse prologue lives in `placement_reuse` (file-length split): an exact, drift
        // or bucket hit returns the cached accept-set, a miss falls through to the full pass.
        if let Some(accepted) =
            reuse_cached_accept(&self.placement_cache, &self.step_times, &key, PLACE_REUSE_MS)
        {
            return accepted;
        }
        let mut candidates: Vec<placement::SegmentedCandidate> = Vec::new();
        let camera_z = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        for (index, layer) in layers.iter().enumerate() {
            if layer.kind != LayerKind::Symbol {
                continue;
            }
            if !layer.draws_at_focused(camera_z, layer.focused_by(filter)) {
                continue;
            }
            // Device px, matching `record_symbol`: `extent` below is device px, so a
            // Dp text size here would size every collision box at 1/density and let
            // labels that visibly overlap all survive the placer. The style arms are
            // resolved once per (layer, zoom) below; each label then answers with a
            // float compare, because the size depends on the place's population rank.
            if !layer.text_visible_at(camera.zoom) {
                continue;
            }
            let (base_size, large_size, rank_threshold) = layer.text_size_arms(camera.zoom);
            let (primary, alternate) = anchors_for(layer);
            for key in ordered {
                let Some(tile) = self.tiles.get(key) else {
                    continue;
                };
                // A tile deeper than the camera's own level is a stand-in kept so a zoom-out does
                // not blank the map (`select::DESCENDANT_DEPTH`). It contributes its geometry, but
                // not its labels: a POI's zoom floor is enforced only by which pyramid level
                // carries it, so a retained z14 tile would otherwise draw its bus stops at camera
                // z12. Every other layer is saved by its own camera-zoom floor; the `poi-*` layers
                // declare none, which is why transit stops were the visible symptom.
                if tile.z > camera_z {
                    continue;
                }
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                let tile_span_px = camera.tile_span_px(tile.z);
                let wh = (extent.width, extent.height);
                // Task-9 rank gating: at low UI zoom only high-pop localities
                // Rank gating BEFORE collision: a hamlet must not
                // become a candidate at all - collision alone can't thin
                // hundreds of towns to the major-city set. The camera zoom is
                // the UI zoom now that the tile grid is 512, so selection
                // matches MapLibre's per-zoom set directly.
                let min_pop = placement::locality_min_pop(camera.zoom);
                for (label_idx, label) in tile
                    .labels
                    .iter()
                    .enumerate()
                    .filter(|(_, l)| l.layer_index == index)
                {
                    if label.rank == 2 && label.pop < min_pop {
                        continue;
                    }
                    let inputs = box_inputs_with_arms(
                        layer,
                        label,
                        camera,
                        (base_size, large_size, rank_threshold),
                    );
                    let id = placement::candidate_id(tile.z, tile.x, tile.y, index, label_idx);
                    // A curved label collides as the row of oriented per-glyph boxes it draws; a
                    // point label as one axis-aligned box (plus its variable-anchor alternate). Both
                    // go into one `place_segmented` pass so they collide with each other.
                    // Globe and flat share this switch: the box builders differ
                    // (sphere vs matrix projection), the candidate shape does not.
                    let (boxes, alternate_boxes) = if let Some(centreline) = &label.centreline {
                        let Some(line) = label.lines.first() else {
                            continue;
                        };
                        let ppfu = inputs.text_px / crate::tile::glyph::UP_EM as f32 / tile_span_px;
                        let placed = crate::tess::text::layout_along_line(line, centreline, ppfu);
                        let boxes = if globe {
                            placement::globe_curved_boxes(
                                camera, tile.z, tile.x, tile.y,
                                &placed, tile_clip, wh, inputs.text_px, inputs.pad_px,
                            )
                        } else {
                            placement::curved_boxes(
                                &placed, tile_clip, wh, inputs.text_px, inputs.pad_px,
                            )
                        };
                        if boxes.is_empty() {
                            continue;
                        }
                        (boxes, None)
                    } else {
                        let (primary_rect, alt) = if globe {
                            let Some(primary_rect) = placement::globe_anchored_rect(
                                camera, tile.z, tile.x, tile.y, label.anchor, wh, &inputs, primary,
                            ) else { continue };
                            let alt = alternate.and_then(|second| {
                                placement::globe_anchored_rect(
                                    camera, tile.z, tile.x, tile.y, label.anchor, wh, &inputs, second,
                                )
                                .map(|r| vec![placement::Obb::from_rect(r)])
                            });
                            (primary_rect, alt)
                        } else {
                            let Some(primary_rect) =
                                placement::anchored_rect(label.anchor, tile_clip, wh, &inputs, primary)
                            else {
                                continue;
                            };
                            let alt = alternate.and_then(|second| {
                                placement::anchored_rect(label.anchor, tile_clip, wh, &inputs, second)
                                    .map(|r| vec![placement::Obb::from_rect(r)])
                            });
                            (primary_rect, alt)
                        };
                        let primary_box = placement::Obb::from_rect(primary_rect);
                        (vec![primary_box], alt)
                    };
                    candidates.push(placement::SegmentedCandidate {
                        id,
                        rank: label.rank,
                        pop: label.pop,
                        boxes,
                        alternate: alternate_boxes,
                        feature_id: label.feature_id,
                        layer_index: index,
                    });
                }
            }
        }
        // App-pin blockers (see placement_markers): pin boxes enter the grid before any
        // POI is tested, so a POI that would land on a pin loses instead of drawing under it.
        let (blockers, blocker_ids) = self.marker_blockers(camera, extent);
        candidates.extend(blockers);
        // Viewport cull: drop a candidate before collision only if *all* its boxes are
        // provably outside the viewport expanded by the candidate's own AABB margin.
        // A box whose AABB touches the expanded viewport stays: culling is conservative
        // by construction, and a culled candidate is one that could draw no visible
        // pixel this frame. Rank-0 candidates are never culled — they always draw.
        {
            let (vw, vh) = (extent.width as f32, extent.height as f32);
            candidates.retain(|c| {
                if c.rank == 0 {
                    return true;
                }
                // An empty footprint collides with nothing, so the placer always
                // accepts it: keep, never cull. (Unreachable today — both builders
                // `continue` on empty — but the placer invariant must hold.)
                if c.boxes.is_empty() && c.alternate.as_ref().map_or(true, Vec::is_empty) {
                    return true;
                }
                c.boxes
                    .iter()
                    .chain(c.alternate.iter().flatten())
                    .any(|b| {
                        let (ex, ey) = placement::obb_aabb_half(b);
                        b.cx + ex >= 0.0 && b.cx - ex <= vw && b.cy + ey >= 0.0 && b.cy - ey <= vh
                    })
            });
        }
        // Cross-tile dedup: neighbouring tiles shape the same feature twice (shared
        // borders, overzoomed ancestors), so the candidate list can carry two
        // bit-identical footprints for one label. The placer would accept the first
        // and reject the second on collision — keeping both doubles the quadratic
        // loop's work for nothing. Drop exact duplicates by stable feature identity
        // (feature id + layer). Within a layer candidates run coarsest tile first, so the
        // *last* copy is the finest tile's: keep that one, or a zoom-in keeps drawing the
        // stand-in ancestor's coarser label beside the sharpening geometry. Labels with no
        // feature id (ID_NONE) are never deduped: they carry no identity to compare.
        //
        // `candidate_id` is untouched: it still names the same label in the
        // accept-set `record_symbol` filters by.
        {
            let mut seen: HashSet<(u64, usize)> = HashSet::with_capacity(candidates.len());
            let keep: Vec<bool> = {
                let mut keep = vec![true; candidates.len()];
                for (i, c) in candidates.iter().enumerate().rev() {
                    // Identity unknown: keep, never dedup.
                    if c.feature_id != tilecodec::mamaps::body::ID_NONE
                        && !seen.insert((c.feature_id, c.layer_index))
                    {
                        keep[i] = false;
                    }
                }
                keep
            };
            let mut at = 0;
            candidates.retain(|_| {
                at += 1;
                keep[at - 1]
            });
        }
        // Keyed by candidate id, valued by the anchor the placer settled on and **where in
        // acceptance order it landed**. `place_segmented` returns its winners in priority order and
        // a `HashMap` would throw that away, which is what made `pick_labels`' "topmost first" a
        // claim rather than a fact. Marker blockers are stripped: they are not tile labels, so
        // neither `record_symbol` nor `refresh_placed` (which resolve ids through tiles) may see
        // them — their only effect is the grid footprint they left behind.
        let accepted: AcceptSet = placement::place_segmented(&candidates)
            .into_iter()
            .filter(|(id, _)| !blocker_ids.contains(id))
            .enumerate()
            .map(|(order, (id, flipped))| (id, (flipped, order as u32)))
            .collect();
        // A fresh accept-set is a new placement version: the submit early-out keys on this
        // rather than hashing the set per frame, and reuse hits above leave it alone.
        self.placement_version
            .set(self.placement_version.get().wrapping_add(1));
        self.step_times
            .borrow_mut()
            .record(crate::timing::Step::PlacementHit, 0);
        *self.placement_cache.borrow_mut() = Some((key, accepted.clone(), Instant::now()));
        accepted
    }

    /// Task-17 pick: the placed labels of the last frame whose screen boxes
    /// contain the query box (device px), in placement order (topmost first).
    /// A linear scan — hundreds of labels, no index needed. Called from the
    /// JNI pick path with Dp already converted to device px by the caller.
    pub fn pick_labels(&self, query: (f32, f32, f32, f32)) -> Vec<PlacedHit> {
        let (qx0, qy0, qx1, qy1) = query;
        self.placed
            .borrow()
            .iter()
            .filter(|h| h.rect.0 <= qx1 && h.rect.2 >= qx0 && h.rect.1 <= qy1 && h.rect.3 >= qy0)
            .cloned()
            .collect()
    }

    /// The marker id under the device-pixel `(x, y)`, or `0` when the tap hit no marker.
    ///
    /// The GPU-picking counterpart of [`pick_labels`](Self::pick_labels): where labels are picked
    /// against the CPU snapshot of the last placed frame, markers are picked by rendering their
    /// ids into an offscreen `R32_UINT` buffer with the last frame's camera and reading back the
    /// tapped pixel (see [`crate::vulkan::pick`]). That is what keeps a pin tappable under tilt,
    /// where its screen box is no longer a plain projection of its lon/lat.
    ///
    /// Returns the marker's own id (the value the host set on it), so the host maps the tap back to
    /// its feature without matching on position. `0` covers "no marker here", "no frame drawn yet",
    /// and a pick that could not run — all of which the host treats as "fall through to the next
    /// probe", exactly as an empty [`pick_labels`](Self::pick_labels) result is treated.
    pub fn pick_at(&mut self, x: u32, y: u32) -> u64 {
        let Some(camera) = self.last_camera else {
            return 0;
        };
        // The markers this frame would draw, in draw order, so the topmost pin wins the pixel.
        let markers: Vec<Marker> = self
            .overlays
            .iter()
            .filter_map(|overlay| match overlay {
                Overlay::Markers(m, _) => Some(m.iter().copied()),
                _ => None,
            })
            .flatten()
            .collect();
        if markers.is_empty() {
            return 0;
        }
        let extent = self.swapchain.extent;
        let quad_indices = QUAD_INDICES.len() as u32;
        match unsafe {
            self.pick.at(
                &self.context,
                self.command_pool,
                self.quad.vertices.buffer,
                self.quad.indices.buffer,
                quad_indices,
                extent,
                &camera,
                &markers,
                x,
                y,
            )
        } {
            Ok(id) => id,
            Err(e) => {
                eprintln!("pick_at failed: {e}");
                0
            }
        }
    }

    /// Task-17 pick snapshot: rebuild [`placed`](Self::placed) from the
    /// frame's accept-set. Boxes are recomputed with the same inputs the
    /// pre-pass used (same text size, same padding) so pick boxes match drawn
    /// boxes; anchor lon/lat comes from unprojecting the tile-local anchor
    /// through the tile's world position at the camera zoom.
    ///
    /// File-length split: the per-label resolve lives in
    /// `refresh_placed_label` (`placement_refresh`), this loop only walks.
    pub(super) fn refresh_placed(
        &self,
        camera: &Camera,
        layers: &[Layer],
        accepted: &HashMap<u64, (bool, u32)>,
        extent: vk::Extent2D,
    ) {
        use crate::tile::placement;
        let globe = crate::camera::globe_active(camera);
        let mut placed: Vec<(u32, PlacedHit)> = Vec::new();
        for tile in self.tiles.values() {
            // Tile origin in world px at the camera zoom: tile (x,y) at ITS
            // OWN zoom would misplace overzoomed ancestors, but every
            // resident tile here is at the selected zoom (see select), so
            // tile_span at tile.z is the tile's own screen size and the
            // anchor offsets within it directly.
            let span_dp = camera.tile_span_dp(tile.z);
            let tile_wx = tile.x as f64 * span_dp;
            let tile_wy = tile.y as f64 * span_dp;
            for (label_idx, label) in tile.labels.iter().enumerate() {
                let id =
                    placement::candidate_id(tile.z, tile.x, tile.y, label.layer_index, label_idx);
                let Some(&(flipped, order)) = accepted.get(&id) else {
                    continue;
                };
                let Some(layer) = layers.get(label.layer_index) else {
                    continue;
                };
                // Device px, as in `place_symbols` — this rebuilds the same boxes for
                // the pick path, so it has to agree with them, including which anchor
                // the placer settled on.
                let text_px = layer.text_size_for(camera.zoom, label.pop) * camera.density;
                if text_px <= 0.0 {
                    continue;
                }
                let (primary, alternate) = anchors_for(layer);
                let anchor = if flipped {
                    alternate.unwrap_or(primary)
                } else {
                    primary
                };
                let Some((rect, lon, lat)) = self.refresh_placed_label(
                    camera, layer, label, tile, text_px,
                    primary, alternate, anchor, extent, globe, tile_wx, tile_wy, span_dp,
                ) else {
                    continue;
                };
                placed.push((
                    order,
                    PlacedHit {
                        rect,
                        layer_index: label.layer_index,
                        name: label.name.clone(),
                        // The feature's own kind, not the layer's first whitelist entry — that
                        // reported `restaurant` for every `poi-food` hit, `stadium` for every
                        // civic one, and so on for all four multi-kind layers.
                        kind: kind_name(label.kind),
                        feature_id: label.feature_id,
                        region_id: label.region_id,
                        lon,
                        lat,
                    },
                ));
            }
        }
        // `self.tiles` is a `HashMap`, so the walk above visits tiles in arbitrary order. Sorted
        // back into the placer's acceptance order here, which is what makes "topmost first" true
        // of what `pick_labels` returns — and therefore what makes a tap resolve to the label
        // actually drawn on top rather than to whichever tile the hasher happened to yield first.
        placed.sort_by_key(|(order, _)| *order);
        *self.placed.borrow_mut() = placed.into_iter().map(|(_, hit)| hit).collect();
    }
}

/// Screen-space quad matrix for a globe overlay anchor: the lon/lat's screen
/// point (Dp, via [`Camera::globe_anchor_to_screen`]) centred in a `radius_dp`
/// quad, in the same shape as [`Camera::screen_quad_to_clip`] so the puck and
/// vehicle-ring draws share one contract.
///
/// `pub(crate)`: the offscreen pick pass (`vulkan::pick`) resolves the same
/// quad for its tap box, so the pick lands where the pin draws. Re-exported
/// through the renderer root for that caller.
pub fn globe_screen_quad(camera: &Camera, sx_dp: f64, sy_dp: f64, radius_dp: f64) -> [f32; 16] {
    let kx = 2.0 / camera.width_dp as f64;
    let ky = 2.0 / camera.height_dp as f64;
    let cx = sx_dp * kx - 1.0;
    let cy = sy_dp * ky - 1.0;
    [
        (kx * radius_dp) as f32,
        0.0,
        0.0,
        0.0, //
        0.0,
        (ky * radius_dp) as f32,
        0.0,
        0.0, //
        0.0,
        0.0,
        1.0,
        0.0, //
        cx as f32,
        cy as f32,
        0.0,
        1.0,
    ]
}
