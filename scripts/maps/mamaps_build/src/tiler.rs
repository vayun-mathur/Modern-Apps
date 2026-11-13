//! Stages B, D and E: features in, one `.mamaps` archive out.
//!
//! Zoom by zoom, and within a zoom tile by tile in ascending tile-id order, which is exactly what
//! [`StreamWriter`] requires. That ordering is free rather than arranged: `pmtiles::tile_id` is
//! zoom-major, so every id at z*n* precedes every id at z*n+1*, and sorting within a zoom finishes
//! it.
//!
//! # What is reused
//!
//! All of the geometry, from `tile_build`:
//!
//! * [`geom::project_geometry`] to web-mercator tile units, carrying per-vertex significance.
//! * [`simplify::annotate`] then [`simplify::filter`], which is significance-first simplification:
//!   a vertex's importance is computed **once** on the whole geometry and only then filtered per
//!   zoom, so the same vertex survives or does not regardless of which tile it lands in. Doing it
//!   the other way round is what lets a shared edge simplify differently on each side and open a
//!   seam.
//! * [`subdivide::subdivide`], which walks a feature down the tile quadtree instead of clipping it
//!   in full against every tile it touches. A feature inside one tile is clipped once, exactly as
//!   before; one spanning three states pays one vertex pass per zoom level rather than one per tile.
//! * [`clip`](tile_build::clip)'s Liang-Barsky for lines and Sutherland-Hodgman for rings, against a
//!   tile rect with a buffer so a stroke at the edge has geometry to join to.
//!
//! # Memory
//!
//! One zoom's chunks, **on disk**, through [`crate::tilespill`]. Peak is a function of the thread
//! count and a fixed read budget rather than of the extract: `O(threads)` in-flight features and
//! per-worker chunk maps, plus [`tilespill::READ_BUDGET`] of merge cursors.
//!
//! It was in memory until it could not be. A north-america z14 is 267 M features, 461 M parts and
//! 3.07 G points — about 23 GB of raw arena, and 53 GB resident once `BTreeMap` nodes, `Vec`
//! capacity slack and the duplicated `BodyLayer` header per `(chunk, tile, layer)` are counted. A
//! planet z14 projects to ~244 GB, which is not a tuning problem.
//!
//! The spill is created and dropped **per zoom**, so peak scratch is the largest single zoom rather
//! than the sum: ~23 GB at a north-america z14, ~100 GB at a planet one. The stage-A feature
//! store is a within-build temporary, removed on success; this is a within-zoom temporary, and
//! keeping either would strand gigabytes for nothing.
//!
//! # Parallelism, and why the bytes do not move
//!
//! Tiling was 301 s of a 355 s California build, on one of 64 cores, and z14 alone is 68% of it
//! (180.7 M of 263 M output points) — so the split that matters is *within* a zoom, not across
//! them. A zoom is therefore a map/reduce:
//!
//! 1. **Map.** One reader thread cuts the feature stream into chunks of roughly
//!    [`CHUNK_VERTICES`] input vertices and hands them over a bounded channel to a pool of workers.
//!    Each worker projects, simplifies and clips its own chunk into a private [`Chunk`] map,
//!    touching nothing shared.
//! 2. **Reduce.** The chunks are ordered by the index they were *read* at — never by the order they
//!    finished in — and k-way merged on the tile id.
//! 3. **Encode.** Stage C and body serialisation are pure functions of one tile, so a batch of
//!    merged tiles is encoded in parallel and appended in tile order on one thread.
//!
//! Steps 2 and 3 alternate, and **overlapping them has been tried and made the build slower.** The
//! reduce is single-threaded and the encode is the whole pool, so a merge thread behind a
//! one-batch channel looks like free time: 33 s of a 351 s us-west tiling stage with sixty-three
//! cores idle. Measured, it cost 53 s instead — 351 s to 404 s, byte-identical — and the merge's own
//! busy time went 33 s to 217 s. Two reasons, both about the machine rather than the code. The pool
//! is already one thread per logical CPU, so the merge thread is a sixty-fifth on sixty-four and has
//! to be timesliced against threads that never yield; and the merge is a pointer walk over
//! `BTreeMap` nodes, which was fast because it had L3 to itself and is not once sixty-four encode
//! workers are streaming gigabytes through it. This is the same shape as the parallel spill refill
//! in [`crate::store`], and it is recorded here for the same reason: it reads like an obvious win
//! and it is not one.
//!
//! The reason this is byte-identical is stronger than "the merge is sorted". Concatenating a
//! partition of a sequence in partition order reproduces the sequence, so a tile layer's features
//! land in exactly the order the store yielded them — which makes the archive independent not only
//! of the thread count but of *where the chunk boundaries fall*. Both halves are asserted, in
//! `the_archive_is_identical_at_every_thread_count` and
//! `the_archive_is_identical_however_the_features_are_chunked`.
//!
//! What would break it, and what this module is shaped to avoid:
//!
//! * A `HashMap` anywhere a tile, a layer or a feature is emitted from. Both maps here are
//!   `BTreeMap`s, and the only hash map in the path is the writer's dedup bucket table, which is
//!   probed and never walked.
//! * A reduce that folds partial results as they *complete* — a `reduce`, a `fold`, a channel of
//!   finished work. A layer's feature order would then follow the scheduler, and the damage would
//!   be a different feature *ordering* within a tile rather than a different picture: every pixel
//!   would still be right and every byte would be wrong.
//! * Emitting from the workers. Tile ids must ascend for [`StreamWriter`], so the append stays on
//!   one thread and only the pure work fans out.
//!
//! Memory was what this cost, and the spill is what took it back. A chunk is written out and freed
//! as soon as its worker finishes it, so what is live at the end of the map phase is a handful of
//! [`ChunkRef`]s rather than every chunk's arenas. In flight are at most `2 * threads` chunks of raw
//! features and one chunk map per worker. [`CHUNK_VERTICES`] is still large for the other half of
//! the same reason: smaller chunks balance the pool better and duplicate more `BodyLayer` headers,
//! and now also more entry headers on disk.
//!
//! What the spill costs instead is a decode on the **merge thread**, which is serial, and this
//! module has already been burned once by treating the merge as cheap — see the paragraph above.
//! `merge_ms` is the number to watch; a parallel run-merge cascade is the escape hatch if it is bad.

use std::cmp::Reverse;
use std::collections::{BTreeMap, BinaryHeap, HashMap};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;

use rayon::prelude::*;
use tile_build::geom::{self, Geometry, IntGeometry, SigPt, Vertex};
use tile_build::par;
use tile_build::subdivide;
use tile_build::progress::Progress;
use tile_build::simplify;
use tilecodec::mamaps::body::{
    Body, BuildingAttrs, Feature as BodyFeature, Layer as BodyLayer, Part, GEOM_LINE, GEOM_POINT,
    GEOM_POLYGON, WINDING_HOLE, WINDING_OUTER,
};
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::{err, Result};

use crate::extract::Feature;
use crate::store::Store;
use crate::tilespill::{self, ChunkRef, ChunkReader, ChunkSpill};

/// The tile grid, matching MVT's so nothing downstream rescales.
pub const EXTENT: u32 = 4096;

/// How aggressively to simplify, as a multiple of `tile_build`'s per-zoom tolerance.
pub const DEFAULT_SIMPLIFICATION: f64 = 1.0;

/// Extra simplification for the boundary wash: a multiple of the per-zoom policy tolerance,
/// applied in `tolerance_for_layer` beside the buildings floor. 1.5x — boundaries are lines,
/// and lines carry sub-10m wiggles a 2x grid stretch could straighten.
pub const WASH_SIMPLIFICATION: f64 = 1.5;

/// Extra simplification for the landtype wash: natural boundaries are looked
/// at closer than admin ones, so the factor stays at the wash level rather
/// than above it — except at z13, where a tile spans ~5 km and even tripled
/// tolerance is deep sub-pixel. Same mechanism as [`WASH_SIMPLIFICATION`],
/// own constants so the two zooms tune independently.
pub const LANDTYPE_SIMPLIFICATION: f64 = 1.5;

/// The z13 landtype factor: forest edges, shorelines and farmland at 3x the
/// per-zoom policy tolerance. z14 stays at 1.5x (crisp parks under streets);
/// z13 is viewed from high, where a ~4 m tolerance is a fraction of a pixel.
pub const LANDTYPE_Z13_SIMPLIFICATION: f64 = 3.0;

/// The simplification floor for the `buildings` layer, in extent units.
///
/// Buildings live only at z14 and up, where [`simplify::tolerance_for`] is 0.0 at max zoom
/// (full detail). Half a unit is ~0.3 m at z14 — sub-pixel on any screen the style draws at —
/// and only drops exactly-collinear midpoints (significance 0) and sub-pixel jaggies. Applied
/// as a floor in [`tolerance_for_layer`], so a coarser global tolerance still wins. The
/// prototype at `analysis/mamaps_building_savings.py` estimates ~2% of building-tile bytes.
pub const BUILDING_TOLERANCE: f64 = 0.5;

/// The simplification floor for washable `roads` at the archive max, in extent units.
///
/// z14 policy tolerance is 0.0 (full detail for carriageway/lane alignment), but only
/// a minority of roads needs it: arterials, and roads carrying lane masks or a
/// carriageway split, whose centre line the renderer places from geometry (see
/// `needs_crisp`). A residential street's centre line has no such reader — a
/// half-unit floor drops only collinear midpoints and sub-pixel jaggies,
/// invisible at z14 where 16 units are a display pixel. Same magnitude as
/// [`BUILDING_TOLERANCE`] for the same reason.
pub const ROAD_DETAIL_FLOOR: f64 = 0.5;

/// True when a road's geometry must stay exactly as digitised: an arterial
/// (motorway through tertiary, mainlines and `_link`s), or any road carrying
/// lane masks, a lane count, or a non-default carriageway split — the only
/// roads whose centre-line placement reads geometry. Everything else
/// (residential, service, track, path…) takes [`ROAD_DETAIL_FLOOR`] at z14:
/// a principled rule about readers, not a cap.
fn needs_crisp(feature: &crate::extract::Feature) -> bool {
    use tilecodec::mamaps::body::FLAG_IS_ONEWAY;
    // Arterials by interned kind_detail id: the HIGHWAYS table's
    // motorway→tertiary rows (schema/roads.rs). Links share the rule: a
    // motorway_link's curve places the same carriageway its mainline does.
    // Built once (read-only after init, so deterministic across threads).
    static ARTERIALS: std::sync::OnceLock<std::collections::HashSet<u16>> =
        std::sync::OnceLock::new();
    let arterials = ARTERIALS.get_or_init(|| {
        [
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link",
        ]
        .into_iter()
        .map(crate::schema::detail)
        .collect()
    });
    if arterials.contains(&feature.class.kind_detail) {
        return true;
    }
    if feature.lane_count > 0 || !feature.turn_fwd.is_empty() || !feature.turn_bwd.is_empty() {
        return true;
    }
    if !feature.carriageway.is_empty() {
        return true;
    }
    // A one-way's digitising direction is its semantics; simplification
    // preserves endpoints but can bow the line, so keep it crisp.
    feature.class.flags & FLAG_IS_ONEWAY != 0
}

/// The orthogonal-snap tolerance for building rings, in extent units.
///
/// Edges within half a unit of axis-aligned are snapped exactly so (see
/// [`snap_building_ring`]), turning hand-digitised near-rectangles into rectangles whose
/// varint deltas encode cheaply. Same magnitude as [`BUILDING_TOLERANCE`]: sub-pixel, visually
/// lossless. Runs after the filter on survivors only, so the significance [`simplify::annotate`]
/// measured is never stale; a pure function of the ring, so the archive stays independent of
/// thread count and chunk boundaries like everything else in [`tile_chunk`].
pub const BUILDING_SNAP: f64 = 0.5;

/// The building vertex grid: coordinates round to multiples of this (extent
/// units) after snapping. 2 units ≈ 1.2 m / ~1/8 display pixel at z14 —
/// invisible natively, and hand-digitised jitter collapses into repeated
/// constants the varint arena and DEFLATE dictionary encode cheaply. Runs
/// inside the snap (same survivors-only pass, significance untouched), so it
/// inherits the determinism property.
pub const BUILDING_GRID: f64 = 2.0;

/// Input vertices per chunk handed to one worker.
///
/// By **vertex** rather than by feature, because feature cost spans four orders of magnitude in the
/// same file: a building is five points and a state boundary is hundreds of thousands, so a chunk of
/// *n* features is not a unit of work at all. A feature bigger than this on its own becomes its own
/// chunk, which is the right answer for a coastline.
///
/// The size trades two costs against each other. Smaller chunks balance the pool better and give the
/// reader less to get through before a worker can start; larger chunks duplicate fewer `BodyLayer`
/// headers across chunks that touch the same tile, which is the whole of this design's memory
/// overhead.
///
/// **Raised from 64 Ki after tracing where the cost actually is.** An RSS trace of a California build
/// put stage A at 15 s under 1.6 GB and the real peak at t+256 s — 2.45 GB, deep in z13/z14 and
/// plateaued across the whole of it. That plateau is this duplication. Raising the chunk to 512 Ki
/// took the build from 325 s to 181 s and the peak from 2.45 GB to 2.19 GB: at 64 Ki there were ~3 300
/// chunks per zoom, and the allocation and merging of that many per-chunk maps dominated both `map`
/// and `encode`. 512 Ki still leaves hundreds of chunks at California z14, which is several per core
/// on a 64-core box, so the pool stays fed.
///
/// 2 Mi was measured too and was not better — 182 s and 2.21 GB, inside the noise — so this is close
/// to the floor of what the constant alone can buy.
const CHUNK_VERTICES: usize = 512 * 1024;

/// Worker stack, matching [`par`]'s pool and what the toolchain gives `main` on Windows.
///
/// The geometry called below is iterative, so 2 MiB would very likely do — but "very likely" is not
/// what you want from a stack, and getting it wrong aborts partway through a build instead of
/// returning an error.
const WORKER_STACK: usize = 8 * 1024 * 1024;

/// Set only by tests, which build the same archive at several chunk sizes to show the output does
/// not depend on where the boundaries fall. Zero means [`CHUNK_VERTICES`].
static CHUNK_OVERRIDE: AtomicUsize = AtomicUsize::new(0);

fn chunk_vertices() -> usize {
    match CHUNK_OVERRIDE.load(Ordering::Relaxed) {
        0 => CHUNK_VERTICES,
        n => n,
    }
}

#[cfg(test)]
fn set_chunk_vertices(n: usize) {
    CHUNK_OVERRIDE.store(n, Ordering::Relaxed);
}

/// Adopt `RAYON_NUM_THREADS` when nothing else has set the thread budget.
///
/// [`par::threads`] answers to `--threads` and then `MAPS_THREADS`, spelled that way so that one
/// export governs a whole multi-tool build, and it deliberately ignores rayon's own variable because
/// it builds its own pool. But `RAYON_NUM_THREADS` is the knob anyone reaches for to pin a Rust
/// program to one thread — including whoever is checking that this archive is identical at one
/// thread and at sixty-four — and ignoring it would make that check assert nothing. So it is
/// honoured, below `MAPS_THREADS` rather than above it, once per process.
fn adopt_thread_budget() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        if std::env::var_os("MAPS_THREADS").is_some() {
            return;
        }
        let wanted = std::env::var("RAYON_NUM_THREADS")
            .ok()
            .and_then(|raw| raw.trim().parse::<usize>().ok())
            .filter(|n| *n > 0);
        if let Some(n) = wanted {
            par::set_threads(n);
        }
    });
}

/// TEMP-PROFILE: reader-thread wall stash for the mapprofile line. The
/// reader runs on its own thread inside `map_zoom`'s scope; the tally fold
/// (which prints) runs after the scope joins. A process-wide cell carries
/// the wall across. Removed with the probes.
static READER_WALL_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// TEMP-PROFILE: stash the reader wall (millis). Removed with the probes.
pub(crate) fn stash_reader_wall(_z: u8, wall: std::time::Duration) {
    READER_WALL_MS.store(wall.as_millis() as u64, std::sync::atomic::Ordering::Relaxed);
}

/// TEMP-PROFILE: take the stashed reader wall as a Duration. Removed with
/// the probes.
pub(crate) fn take_reader_wall() -> std::time::Duration {
    std::time::Duration::from_millis(READER_WALL_MS.load(std::sync::atomic::Ordering::Relaxed))
}

/// Per-zoom counts for the build report.
#[derive(Debug, Default, Clone, Copy)]
pub struct ZoomStats {
    pub zoom: u8,
    pub tiles: u64,
    pub features: u64,
    pub points: u64,
    /// Features dropped because simplification left nothing, or the clip did.
    pub dropped: u64,
    pub bytes: u64,
    /// What stage C had to correct.
    pub rings: crate::rings::Stats,
    /// What coalescing merged away: an OSM way is an editing unit, not a rendering one.
    pub lines: crate::coalesce::Stats,
    /// Milliseconds in each phase of this zoom: map, merge, encode, append.
    ///
    /// Kept because guessing which one dominates has already cost two wrong optimisations. The map
    /// and encode phases run on the pool and the other two are serial, so the split is what says
    /// whether more cores would help at all or whether Amdahl has already won.
    pub map_ms: u64,
    pub merge_ms: u64,
    pub encode_ms: u64,
    pub append_ms: u64,
}

/// Planet-size prototype knobs (exp/planet-size). Every field's default reproduces today's
/// archive byte for byte; a non-default value is folded into the build id (see
/// `derive_build_id`). Set once by `main` before tiling, read everywhere after.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SizeOpts {
    /// `--body-zstd LEVEL`: zstd bodies (header compression 2, which the shipping reader already
    /// decodes with `ruzstd`) instead of DEFLATE-9. 0 = DEFLATE (today).
    pub zstd_level: i32,
    /// `--dem-max-error M`: error-bounded adaptive heightmap grids (coarsest aligned sub-grid +
    /// elevation step whose reconstruction stays within M metres of the full grid). 0 = today's
    /// relief-gated 5x5 coarsening.
    pub dem_max_error_m: f64,
    /// `--dem-px K`: zoom-adaptive DEM bound — the error allowed on a tile is K times its ground
    /// resolution (metres per 256-px-tile pixel at the tile's latitude), floored at
    /// `dem_max_error_m` (or 1 m). 0 = off.
    pub dem_px: f64,
    /// `--simplify-scale S`: multiply every below-max-zoom simplification tolerance. 1 = today.
    pub simplify_scale: f64,
    /// `--z14-tolerance T`: extent-unit DP floor at z14 for landtype, boundaries, buildings and
    /// washable roads (crisp roads keep full detail). 0 = today's floors.
    pub z14_tolerance: f64,
    /// `--coord-quant`: snap tile coordinates below z14 to a zoom-scaled grid (2 units at z13,
    /// 4 at z<=12; points and the junction layer untouched). false = today.
    pub coord_quant: bool,
}

impl SizeOpts {
    pub const DEFAULT: SizeOpts = SizeOpts {
        zstd_level: 0,
        dem_max_error_m: 0.0,
        dem_px: 0.0,
        simplify_scale: 1.0,
        z14_tolerance: 0.0,
        coord_quant: false,
    };
    pub fn is_default(&self) -> bool {
        *self == SizeOpts::DEFAULT
    }
    /// Stable text for the build id (only hashed when non-default).
    pub fn tag(&self) -> String {
        format!(
            "size/zstd={};dem={};dempx={};simp={};z14={};q={}",
            self.zstd_level, self.dem_max_error_m, self.dem_px, self.simplify_scale, self.z14_tolerance, self.coord_quant
        )
    }
    /// The coordinate grid step for zoom `z` under `--coord-quant` (1 = untouched).
    pub fn quant_step(&self, z: u8, layer: u8) -> i32 {
        if !self.coord_quant || layer == tilecodec::mamaps::dict::LAYER_JUNCTION {
            return 1;
        }
        match z {
            0..=12 => 4,
            13 => 2,
            _ => 1,
        }
    }
}

pub static SIZE_OPTS: std::sync::OnceLock<SizeOpts> = std::sync::OnceLock::new();

pub fn size_opts() -> SizeOpts {
    *SIZE_OPTS.get().unwrap_or(&SizeOpts::DEFAULT)
}

/// `--v8-bodies`: encode dense v8 bodies (set once by `main` before tiling).
pub static V8_BODIES: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub struct Settings {
    pub build_id: u64,
    /// Where one zoom's chunks go while they wait for the merge. See [`crate::tilespill`].
    ///
    /// Beside the output archive, as `<out>.tilechunks`, matching where the feature spill is placed.
    /// Truncated at the start of every zoom and removed at the end of each, so it holds one zoom.
    pub scratch: PathBuf,
    /// Force the tile-chunk spill through the scratch *file* even when the budget gate would pick
    /// anonymous pagefile-backed memory. Set from `MAPS_ANON_SPILL` (unset, or any value but
    /// `1`/`true`/`yes`, forces files): on a box whose commit headroom is tens of GB against a
    /// ~900 GB estimate, anon staging pages the merge to death -- files page too, but against
    /// the scratch volume rather than the commit limit that aborts the build. Same chunks, same
    /// offsets, same bytes; the merge reads through the same `ChunkReader` either way.
    pub force_chunk_spill_file: bool,
    /// The DEM heightmap dataset to sample one grid per output tile from.
    /// See [`crate::dem::Dem`]. A tile with no DEM under it carries no heightmap
    /// section (stays 16-byte).
    pub dem: crate::dem::Dem,
    /// Place-label id (tagged node) -> the tagged relation id of the admin boundary it names, from
    /// stage A. The tiler stamps each `places` label whose id is a key here with the linked id in
    /// the body's region-link table, so a tap on the label outlines that exact region. Empty when no
    /// links were found (the section is then omitted from every tile).
    pub region_links: std::collections::HashMap<u64, u64>,
    /// Place-label id (tagged, `country` places only) -> the label's start zoom, from
    /// footprint × headcount (see `extract_fallback::country_zoom`). The tiler drops the
    /// label below its start zoom — same shape as `region_links`, probe-only so the
    /// archive stays deterministic. Absent ids keep the schema floor (z0): a missing
    /// score must never hide a country.
    pub country_zooms: std::collections::HashMap<u64, u8>,
}

/// One chunk's share of a zoom, keyed on `(tile id, layer id)`.
///
/// Keyed on the **tile id**, not on `(x, y)`. Those are different orders: `tile_id` walks a Hilbert
/// curve, so row-major `(x, y)` ascends through it out of sequence and the writer rejects the
/// archive. Cheap to get wrong and caught only by an ordering check, which is why the writer has
/// one.
///
/// Flat rather than `tile -> layer -> layer`. A nested map pays a whole inner `BTreeMap` node —
/// eleven `BodyLayer` slots, most of a kilobyte — for every tile, and nearly every tile carries one
/// or two layers; with a chunk map per chunk that overhead is multiplied by the chunk count.
/// Flattening puts eleven *entries* in a node instead, and changes no order: `(tile, layer)`
/// ascending is tile-major with layers in id order inside it, which is what the nested form yielded
/// and what the writer needs.
type Chunk = BTreeMap<(u64, u8), ChunkEntry>;

/// One tile-layer in a chunk map: the geometry plus the label strings its features name.
///
/// Names ride beside the layer (not inside `BodyLayer`, which is the codec's type) from `push`
/// through the spill and the merge to encode, where per-layer tables fuse into the body's one.
/// A chunk entry's `names` is deduplicated in first-use order, so `name_idx == i` names
/// `names[i - 1]` — the same convention as the body's table, which is what makes the merge's
/// remap a pure index translation.
///
/// `ids` rides the same way and is simpler: it holds values rather than indices, so a merge
/// concatenates it instead of remapping it. It is empty for every layer but `places` and `poi`,
/// and dense-parallel to `layer.features` for those two — `ids[i]` is the OSM element that
/// produced `features[i]`.
#[derive(Debug, Clone, PartialEq)]
pub struct ChunkEntry {
    pub layer: BodyLayer,
    pub names: Vec<String>,
    pub ids: Vec<u64>,
    /// The per-feature turn-lane masks, dense-parallel to `layer.features` on the `roads` layer and
    /// empty on every other. Rides the merge the way `ids` does — concatenated, not remapped — and
    /// is remapped alongside `ids` when `coalesce` and stage C rebuild the feature vector.
    pub turn_lanes: Vec<tilecodec::mamaps::body::LaneTurns>,
    /// The per-feature S3DB attributes, dense-parallel to `layer.features` on the `buildings` layer
    /// and empty on every other. Rides the merge like `ids` (concatenated, not remapped) and is
    /// remapped alongside it when `coalesce` and stage C rebuild the feature vector, so the body's
    /// building side table stays aligned to the features it describes.
    pub buildings: Vec<BuildingAttrs>,
    /// The per-feature directional carriageway split, dense-parallel to `layer.features` on the
    /// `roads` layer and empty on every other. Rides the merge like `ids` (concatenated, not
    /// remapped) and is remapped alongside it when `coalesce` rebuilds the feature vector. Stage C
    /// is not given it: a road is a line, and `rings::normalise` never drops one.
    pub carriageways: Vec<tilecodec::mamaps::body::Carriageway>,
}

impl ChunkEntry {
    pub(crate) fn new(layer_id: u8) -> ChunkEntry {
        ChunkEntry {
            layer: BodyLayer::new(layer_id),
            names: Vec::new(),
            ids: Vec::new(),
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            carriageways: Vec::new(),
        }
    }
}

/// Intern `name` into a first-use-ordered table, returning its 1-based body index.
fn intern_name(names: &mut Vec<String>, name: Option<&str>) -> u16 {
    match name.filter(|n| !n.is_empty()) {
        None => tilecodec::mamaps::body::NAME_NONE,
        Some(name) => match names.iter().position(|n| n == name) {
            Some(at) => at as u16 + 1,
            None => {
                let idx = names.len() as u16 + 1;
                names.push(name.to_string());
                idx
            }
        },
    }
}

/// What one chunk contributed to a zoom's counters. Only ever summed, so the order they are summed
/// in cannot change the answer.
#[derive(Debug, Default, Clone, Copy)]
struct Tally {
    features: u64,
    points: u64,
    dropped: u64,
    // TEMP-PROFILE: per-stage map walls, summed across chunks (durations
    // add; order irrelevant). Removed with the probes. NOT Copy (Duration
    // is, but the explicit add below keeps the accounting visible).
    profile: TallyProfile,
}

/// TEMP-PROFILE: per-stage map-phase walls carried in the tally. Removed
/// with the probes.
#[derive(Debug, Default, Clone, Copy)]
struct TallyProfile {
    project: std::time::Duration,
    annotate: std::time::Duration,
    filter: std::time::Duration,
    subdiv: std::time::Duration,
}

impl Tally {
    fn add(&mut self, other: Tally) {
        self.features += other.features;
        self.points += other.points;
        self.dropped += other.dropped;
        self.profile.project += other.profile.project;
        self.profile.annotate += other.profile.annotate;
        self.profile.filter += other.profile.filter;
        self.profile.subdiv += other.profile.subdiv;
    }

    /// TEMP-PROFILE: attach this chunk's stage walls. Removed with the probes.
    fn profile_add(
        &mut self,
        project: std::time::Duration,
        annotate: std::time::Duration,
        filter: std::time::Duration,
        subdiv: std::time::Duration,
    ) {
        self.profile.project += project;
        self.profile.annotate += annotate;
        self.profile.filter += filter;
        self.profile.subdiv += subdiv;
    }
}

include!("tiler_part1.rs");
include!("tiler_part2.rs");
include!("tiler_part3.rs");
include!("tiler_part5.rs");
include!("tiler_part6.rs");
include!("tiler_part7.rs");
include!("tiler_part8.rs");
include!("tiler_part9.rs");
include!("tiler_part10.rs");
include!("tiler_part11.rs");