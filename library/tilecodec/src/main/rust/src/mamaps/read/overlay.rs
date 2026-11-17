//! Patch-first tile lookup over a base archive plus overlay segments.
//!
//! Phase 3 reader core: the base `.mamaps` is immutable and overlay segments
//! are small self-contained `.mamaps` files (same header/index/body format,
//! overlay-relative offsets — see `scripts/maps/tile_build/src/overlay.rs`).
//! Lookup checks each segment newest-first, then the base:
//!
//! * segment hit (incl. a body that decodes) wins;
//! * a tombstoned tile (deleted between builds) answers absent without
//!   consulting the base;
//! * a miss everywhere answers absent, exactly as a single archive does.
//!
//! [`TileSource`] is the seam the renderer serves through: the existing
//! single-archive path implements it by delegation (zero behaviour change),
//! and [`OverlayArchive`] implements it patch-first. Segments are just
//! [`MamapsArchive`] views — no format change, no new index code, and no
//! cross-file offset arithmetic anywhere (each archive addresses only its
//! own header extents).

use std::sync::Arc;

use super::archive::MamapsArchive;
use crate::mamaps::body::Body;
use crate::mamaps::header::Header;
use crate::pmtiles::tile_id;
use crate::proto::Result;
use crate::stream::RangeReader;

/// What a tile worker serves through: one tile, many tiles, header facts,
///
/// and a durability flush. Implemented by [`MamapsArchive`] (delegation —
/// the single-archive path is byte-for-byte today's behaviour) and by
/// [`OverlayArchive`] (patch-first). The flush is a no-op for file readers
/// and orders the disk cache for network readers, exactly as before.
pub trait TileSource {
    /// The decoded body for a tile, or `None` when no archive holds it.
    fn tile(&self, z: u8, x: u32, y: u32) -> Result<Option<Body>>;
    /// The decoded bodies for many tiles at once, in request order.
    fn tiles(&self, tiles: &[(u8, u32, u32)]) -> Vec<Result<Option<Body>>>;
    /// The base archive's header (zoom range, build id, validation flags).
    ///
    /// Segments carry their own headers but never override the base's range
    /// or identity: the overlay refines tiles, not coverage.
    fn header(&self) -> &Header;
    /// Order recently written ranges on disk, batched (see
    /// [`RangeReader::flush_pending`]).
    fn flush_pending(&self) {}
}

impl<R: RangeReader> TileSource for MamapsArchive<R> {
    fn tile(&self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        MamapsArchive::tile(self, z, x, y)
    }

    fn tiles(&self, tiles: &[(u8, u32, u32)]) -> Vec<Result<Option<Body>>> {
        MamapsArchive::tiles(self, tiles)
    }

    fn header(&self) -> &Header {
        &self.header
    }

    fn flush_pending(&self) {
        self.reader().flush_pending();
    }
}

/// A base archive plus zero or more overlay segments, newest first.
///
/// With no segments this answers exactly as the base alone (the worker keeps
/// opening the single-archive path directly, so this is only constructed
/// when segments exist). Tombstones are tile ids deleted between builds:
/// they answer absent even though the base still holds the old body.
///
/// Each archive must be opened over its OWN leaf cache (see [`LeafCache`]:
/// offsets collide across archives). The caller keeps one cache per archive;
/// views of the same archive share it as before.
pub struct OverlayArchive<R: RangeReader> {
    base: MamapsArchive<R>,
    /// Newest segment first (later overlays shadow earlier ones).
    segments: Vec<MamapsArchive<R>>,
    /// Deleted tile ids. Sorted, for binary search.
    tombstones: Vec<u64>,
    /// The shared leaf cache every view reads through (one per surface).
    _leaves: Arc<super::leaf_cache::LeafCache>,
}

impl<R: RangeReader> OverlayArchive<R> {
    /// Base plus segments (newest first) plus tombstones. The tombstone
    /// order does not matter; they are sorted here.
    pub fn new(
        base: MamapsArchive<R>,
        segments: Vec<MamapsArchive<R>>,
        tombstones: Vec<u64>,
        leaves: Arc<super::leaf_cache::LeafCache>,
    ) -> OverlayArchive<R> {
        let mut tombstones = tombstones;
        tombstones.sort_unstable();
        tombstones.dedup();
        // A tombstone the base does not hold is harmless (the base would
        // answer absent anyway); a tombstone a segment *re-adds* is a
        // publisher error, and the tombstone wins — fail closed to absent
        // rather than serve a tile the manifest says is gone. Segments are
        // still consulted first so a re-added tile's absence is explicit.
        let _ = &leaves;
        OverlayArchive { base, segments, tombstones, _leaves: leaves }
    }

    /// Whether `id` was deleted between builds.
    fn is_tombstoned(&self, id: u64) -> bool {
        self.tombstones.binary_search(&id).is_ok()
    }
}

impl<R: RangeReader> TileSource for OverlayArchive<R> {
    fn tile(&self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        if z >= 32 {
            return Ok(None);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(None);
        }
        let id = tile_id(z, x as u64, y as u64);
        if self.is_tombstoned(id) {
            return Ok(None);
        }
        for segment in &self.segments {
            if let some @ Some(_) = segment.tile(z, x, y)? { return Ok(some) }
        }
        self.base.tile(z, x, y)
    }

    fn tiles(&self, tiles: &[(u8, u32, u32)]) -> Vec<Result<Option<Body>>> {
        if self.segments.is_empty() && self.tombstones.is_empty() {
            return self.base.tiles(tiles);
        }
        // Per-tile patch-first. The batch wins of the single-archive path
        // (shared leaf waves) apply within each archive's own `tiles` call;
        // segments are small (changed tiles only), so a viewport's overlay
        // hits concentrate in few leaves. A future pass can fuse the waves
        // across archives if profiling says the per-archive wave framing
        // costs real frames.
        if tiles.len() < 2 {
            return tiles.iter().map(|&(z, x, y)| self.tile(z, x, y)).collect();
        }
        // Tombstoned slots answer absent; the rest fan out per archive and
        // first-hit-wins in segment order, base last.
        let mut tombstoned = vec![false; tiles.len()];
        for (slot, &(z, x, y)) in tiles.iter().enumerate() {
            if z < 32 {
                let n = 1u64 << z;
                if (x as u64) < n && (y as u64) < n && self.is_tombstoned(tile_id(z, x as u64, y as u64)) {
                    tombstoned[slot] = true;
                }
            }
        }
        let live: Vec<(usize, (u8, u32, u32))> = tiles
            .iter()
            .enumerate()
            .filter(|(slot, _)| !tombstoned[*slot])
            .map(|(slot, &coord)| (slot, coord))
            .collect();
        let coords: Vec<(u8, u32, u32)> = live.iter().map(|(_, c)| *c).collect();
        // One `tiles` wave per archive (segments newest-first, base last);
        // slots resolve on first hit.
        let mut out: Vec<Option<Result<Option<Body>>>> = Vec::with_capacity(tiles.len());
        for _ in tiles {
            out.push(None);
        }
        // Query order: segments newest-first, then base. A slot keeps its
        // first `Some` hit; `None`s fall through; errors fail the slot.
        let mut pending: Vec<usize> = live.iter().map(|(slot, _)| *slot).collect();
        let mut pending_coords: Vec<(u8, u32, u32)> = coords;
        let mut archives: Vec<&MamapsArchive<R>> = Vec::with_capacity(self.segments.len() + 1);
        for segment in &self.segments {
            archives.push(segment);
        }
        archives.push(&self.base);
        for archive in archives {
            if pending.is_empty() {
                break;
            }
            let mut got = archive.tiles(&pending_coords);
            let mut still_pending = Vec::new();
            let mut still_coords = Vec::new();
            for (n, slot) in pending.iter().enumerate() {
                let slot = *slot;
                // Take ownership out of the wave result: each slot resolves once.
                let take = if n < got.len() {
                    Some(std::mem::replace(
                        &mut got[n],
                        crate::proto::err("moved".to_string()),
                    ))
                } else {
                    None
                };
                match take {
                    Some(Ok(Some(_))) => {
                        out[slot] = take;
                    }
                    Some(Ok(None)) => {
                        still_pending.push(slot);
                        still_coords.push(pending_coords[n]);
                    }
                    Some(Err(_)) => {
                        out[slot] = take;
                    }
                    None => {
                        out[slot] = Some(crate::proto::err(
                            "an overlay batch left a tile unresolved".to_string(),
                        ));
                    }
                }
            }
            pending = still_pending;
            pending_coords = still_coords;
        }
        for slot in pending {
            out[slot] = Some(Ok(None));
        }
        for (slot, is_dead) in tombstoned.iter().enumerate() {
            if *is_dead {
                out[slot] = Some(Ok(None));
            }
        }
        out.into_iter()
            .map(|slot| slot.unwrap_or_else(|| crate::proto::err("an overlay batch left a tile unresolved".to_string())))
            .collect()
    }

    fn header(&self) -> &Header {
        self.base.header()
    }

    fn flush_pending(&self) {
        self.base.flush_pending();
        for segment in &self.segments {
            segment.flush_pending();
        }
    }
}

/// Build ids equal: the overlay applies to this base, or it refuses.
pub fn base_matches(
    source: &impl TileSource,
    base_build_id: u64,
) -> bool {
    source.header().build_id == base_build_id
}

/// Silence the unused-set lint for the tombstone pre-check above: tombstones
/// the base does not hold are pruned at open so the hot path never searches
/// dead ids. Returns the pruned list.
pub fn prune_tombstones<R: RangeReader>(
    base: &MamapsArchive<R>,
    tombstones: Vec<u64>,
) -> Vec<u64> {
    // Cheap membership probe without decoding: `tile_bytes` answers None for
    // absent tiles. Tombstones number in the hundreds-to-thousands for a real
    // month-over-month (coverage-edge storms mean the wrong pair), so one
    // probe each is fine at open.
    tombstones
        .into_iter()
        .filter(|id| {
            let (z, x, y) = crate::pmtiles::tile_zxy(*id);
            if z >= 32 {
                return false;
            }
            let n = 1u64 << z;
            if x >= n || y >= n {
                return false;
            }
            matches!(base.tile_bytes(z, x as u32, y as u32), Ok(Some(_)))
        })
        .collect()
}
