//! Covered-stand-in suppression: which resident tiles are fully covered.
//!
//! The renderer draws *every* resident tile, and a tile is resident at its own
//! zoom and as a stand-in for up to
//! [`ANCESTOR_DEPTH`](super::resident::ANCESTOR_DEPTH) levels below it (plus
//! already-resident descendants on zoom-out) — so the same ground is routinely
//! resident at several LODs at once. At sea level the copies coincide and the
//! overdraw is invisible; off sea level each LOD drapes the road from its own
//! DEM, and the copies separate into stacked roads.
//!
//! This is the per-frame half of the fix. [`cover`](super::cover) is the pure
//! coverage predicate (host-testable, no GPU state); this turns the resident
//! set into a per-tile suppression map the record passes consult, and versions
//! it so the submit early-out sees the change.
//!
//! Suppression is per draw group, not per tile: a z15 tile carries roads but no
//! `landtype` wash, so its z14 ancestor's road draws are redundant while its
//! earth fill must keep drawing. Only *settled* tiles cover and only settled
//! tiles are suppressed — a tile mid-fade is the backdrop its ancestor fades
//! over, so suppressing either side of an live fade would hole or pop the map.
//!
//! Recomputed only when it can have changed: a residency change, or a fade
//! still in flight. Otherwise the cached map stands and this costs one pass
//! over the resident set.

use super::{Renderer, SuppressedDraws};
use std::collections::{HashMap, HashSet};

impl Renderer {
    /// Refresh the suppression map when residency or fade state moved.
    ///
    /// `now` is the frame clock ([`Camera::time_seconds`](crate::camera::Camera::time_seconds)),
    /// the same epoch the LOD fade is evaluated against. Bumps
    /// [`suppression_epoch`](super::Renderer::suppression_epoch) when the map
    /// changes, which is what makes the [`FrameVersion`](super::FrameVersion)
    /// differ on the fade-completion frame so it actually submits.
    pub(super) fn refresh_suppression(&mut self, now: f32) {
        use crate::tile::select::{cover, tile_lod_alpha, LOD_FADE_SECONDS};
        let residency = self.residency_epoch.get();
        // Fast path, checked BEFORE allocating the resident/settled sets below:
        // residency unchanged since the last refresh and that refresh saw no
        // fade in flight means the map cannot have changed. Settled-ness is
        // monotonic in `now` (alpha ramps 0->1, wrap-safe via rem_euclid in
        // lod_fade_alpha) and the tile set / uploaded_at stamps only change
        // with a residency bump (upload / evict), so a fully-settled set stays
        // fully settled. Previously this frame paid two HashSet builds plus a
        // per-tile ancestor scan only to discover exactly this and return.
        if crate::tile::select::suppression_can_skip(residency, self.suppression_residency, self.suppression_had_unsettled) {
            return;
        }
        let resident: HashSet<u64> = self.tiles.keys().copied().collect();
        let settled: HashSet<u64> = resident
            .iter()
            .copied()
            .filter(|key| {
                self.tiles.get(key).is_some_and(|tile| {
                    tile_lod_alpha(
                        *key,
                        tile.uploaded_at,
                        now,
                        LOD_FADE_SECONDS,
                        &resident,
                    ) >= 1.0
                })
            })
            .collect();
        let unsettled_now = settled.len() != resident.len();
        if residency == self.suppression_residency
            && !self.suppression_had_unsettled
            && !unsettled_now
        {
            return;
        }
        let relevant = cover::relevant_keys(&resident);
        let mut map: HashMap<u64, SuppressedDraws> = HashMap::new();
        // One flat layer's draws are redundant where settled finer tiles carry
        // that same layer's mesh — per layer, so the z14 wash survives where
        // z15 tiles carry roads but no earth.
        let mut layer_indices: Vec<usize> = {
            let mut seen = HashSet::new();
            self.tiles
                .values()
                .flat_map(|tile| tile.layers.iter().map(|mesh| mesh.layer_index))
                .filter(|index| seen.insert(*index))
                .collect()
        };
        layer_indices.sort_unstable();
        for index in layer_indices {
            let redundant = self.suppress_group(&resident, &settled, &relevant, &|key| {
                self.tiles.get(&key).is_some_and(|tile| {
                    tile.layers.iter().any(|mesh| mesh.layer_index == index)
                })
            });
            for key in redundant {
                map.entry(key).or_default().layers.push(index);
            }
        }
        for key in self.suppress_group(&resident, &settled, &relevant, &|key| {
            self.tiles
                .get(&key)
                .is_some_and(|tile| !tile.carriageways.is_empty())
        }) {
            map.entry(key).or_default().carriageways = true;
        }
        for key in self.suppress_group(&resident, &settled, &relevant, &|key| {
            self.tiles.get(&key).is_some_and(|tile| tile.terrain.is_some())
        }) {
            map.entry(key).or_default().terrain = true;
        }
        for key in self.suppress_group(&resident, &settled, &relevant, &|key| {
            self.tiles.get(&key).is_some_and(|tile| tile.buildings.is_some())
        }) {
            map.entry(key).or_default().buildings = true;
        }
        for key in self.suppress_group(&resident, &settled, &relevant, &|key| {
            self.tiles
                .get(&key)
                .is_some_and(|tile| !tile.traffic.is_empty())
        }) {
            map.entry(key).or_default().traffic = true;
        }
        for key in self.suppress_group(&resident, &settled, &relevant, &|key| {
            self.tiles
                .get(&key)
                .is_some_and(|tile| !tile.arrows.is_empty())
        }) {
            map.entry(key).or_default().arrows = true;
        }
        for suppressed in map.values_mut() {
            suppressed.layers.sort_unstable();
        }
        if map != self.suppression {
            self.suppression = map;
            self.suppression_epoch
                .set(self.suppression_epoch.get().wrapping_add(1));
        }
        self.suppression_residency = residency;
        self.suppression_had_unsettled = unsettled_now;
    }

    /// This group's redundant tiles, through the shared coverage predicate.
    fn suppress_group(
        &self,
        resident: &HashSet<u64>,
        settled: &HashSet<u64>,
        relevant: &HashSet<u64>,
        has_content: &dyn Fn(u64) -> bool,
    ) -> HashSet<u64> {
        crate::tile::select::cover::redundant_tiles(resident, settled, relevant, has_content)
    }

    /// This tile's suppression entry, if any.
    ///
    /// The per-pass consult for passes that iterate `self.tiles.values()` and
    /// hold no key: builds it from the tile's coordinates. Passes that already
    /// hold the key (`record`, `record_carriageways`) read
    /// [`suppression`](Self::suppression) directly instead.
    pub(super) fn suppressed_tile(&self, z: u8, x: u32, y: u32) -> Option<&SuppressedDraws> {
        let key = crate::tile::select::TileId { z, x, y }.key();
        self.suppression.get(&key)
    }

    /// How many draws of one tile's traffic overlay actually issue.
    ///
    /// Only segments with a pushed colour draw (see `record_traffic`), so only
    /// those count toward the cull tally — suppressed or off-screen alike.
    pub(super) fn traffic_draws(&self, tile: &super::ResidentTile) -> usize {
        tile.traffic
            .iter()
            .filter(|segment| self.traffic_colors.contains_key(&segment.id))
            .count()
    }
}
