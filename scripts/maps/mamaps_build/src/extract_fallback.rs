//! Build-time containment fallback for place-label -> boundary links.
//!
//! Tapping a place label should outline its exact admin boundary. The authoritative half of that
//! link is membership: a boundary relation points at its own label node via an `admin_centre` or
//! `label` member, and stage A records that node -> relation pair into `region_links`
//! (`extract_part1.rs`). This module is the second half: for every place label that membership did
//! not link, pick the boundary that contains its centroid.
//!
//! # Matching order: members first, containment second
//!
//! The member map is never overwritten here. Fallback only inserts entries for place tagged-IDs
//! still missing from `region_links`, keyed the same way the tiler looks them up (the place's own
//! tagged node/way/relation id), so the two halves agree and `tiler_part5.rs` needs no change.
//!
//! # Where this runs
//!
//! Called from `main_part1.rs` between `extract()` returning and `tiler::Settings` construction,
//! with the finished [`Store`](crate::store::Store) and the member-built map. The store holds
//! whole lon/lat polygons (pre-tiler, pre-clip), which is the only place a containment test is
//! meaningful — at tiler encode time the geometry is tile-local clipped fragments. Two sequential
//! reads of the spill: one to collect boundary shapes, one to stream places and link them. No new
//! spills, no resident feature table.
//!
//! # Level bands
//!
//! A place kind only matches the admin levels it can actually name:
//!
//! | place kind | admin levels |
//! |---|---|
//! | `country` | 1–2 |
//! | `region` | 3–4 |
//! | `locality` | 7–12 |
//!
//! Levels 5–6 (county) intentionally match nothing. Those boundaries only link via relation
//! members; anything else masks nothing rather than the wrong county. `neighbourhood` and
//! `macrohood` places likewise match nothing — a suburb centroid inside a city boundary must not
//! outline the city.
//!
//! # `REGION_NONE` rules: when in doubt, mask nothing
//!
//! A missing link leaves the tiler's `REGION_NONE` path (mask nothing), so every uncertain case
//! emits nothing: zero in-band candidates, an ambiguous pick (two containers' areas within ~1% of
//! each other), or the place sitting on a ring edge (where ray-casting is a coin flip). Ambiguity
//! favors no-mask over wrong-mask.
//!
//! # Cost cap
//!
//! Boundaries are partitioned by band (a place only ever tests its own band) behind a coarse
//! 4-degree grid, with a bbox reject before any point-in-polygon test, in a single pass per read.
//! Per-place work is then a handful of candidates, not the whole boundary set. If profiling ever
//! shows this dominating, the documented escape hatch is a rank-based skip (only attempt fallback
//! for places above a population-rank floor) — not yet implemented, and the per-kind counts in
//! [`FallbackStats`] are the numbers that would size it.

use std::collections::HashMap;

use osm_ingest::proto::Result;
use osm_ingest::rings::{point_in_ring, ring_area, Polygon};
use rayon::prelude::*;
use tile_build::geom::Geometry;
use tile_build::par;
use tile_build::progress::Progress;
use tilecodec::mamaps::body::ID_NONE;
use tilecodec::mamaps::dict::{KINDS, LAYER_BOUNDARIES, LAYER_PLACES};

/// What a run of the fallback did, for the build report.
#[derive(Debug, Default, Clone)]
pub struct FallbackStats {
    /// Linkable `places` features seen (tagged id, non-empty point, known kind).
    pub places_total: u64,
    /// Of those, already linked by relation members before fallback ran.
    pub member_linked: u64,
    /// Of those, newly linked by containment here.
    pub fallback_linked: u64,
    /// Of those, still unlinked after both halves (`total - member - fallback`).
    pub fallback_missed: u64,
    /// `(kind, total, linked)` per place kind, sorted by kind. `linked` counts both halves;
    /// the ratio is the per-kind hit rate the build log prints.
    pub per_kind: Vec<(String, u64, u64)>,
    /// Country zoom overrides assigned (place tagged id count).
    pub country_zooms: u64,
}

/// A country's label zoom from its ground footprint and headcount.
///
/// Pure area buries Vatican City, Singapore and Monaco until deep zoom; pure
/// population buries Russia, Canada and Mongolia under their own emptiness. The
/// score multiplies the two so a place must be big *or* populous to surface
/// early, and tiny *and* empty to wait:
///
/// `score = log10(area_m2) + AOI_COVERAGE_WEIGHT * log10(population + 1)`
///
/// The weight (2.0) is what keeps Singapore (6 M people, 725 km²) and Switzerland
/// off the backstop: with equal weights a microstate's area term (~1e8 → 8)
/// caps its score near 15 no matter the headcount, so population could never
/// lift one. Weighting population double counts headcount the way a reader
/// does — people are the point of a label — while area still separates Russia
/// (20.5) from Nauru (10.7). The `+1` keeps unpopulated Antarctica finite.
///
/// Bucket edges calibrated against real countries (see
/// `country_zoom_scores_area_times_headcount` — every edge asserted):
///
/// | score | start zoom | example |
/// |---|---|---|
/// | ≥ 26 | 0 | Russia, USA, France, Germany, Japan, Egypt |
/// | ≥ 23.5 | 1 | Switzerland |
/// | ≥ 20 | 2 | Singapore |
/// | ≥ 14.5 | 3 | Liechtenstein, Nauru, Tuvalu — everything on by z3 |
/// | < 14.5 | 4 | Vatican City (tiny and near-empty) |
///
/// z3 shows every country whose label cannot collide with another country's:
/// at z3 a tile spans 45° and a country label spans a fraction of a tile, so
/// neighbours are tiles apart. z4 is the backstop for the tail, never later —
/// a country is never street-level detail.
///
/// Why z0 holds France as well as Russia: at z0 the world is four tiles and a
/// label is a few dozen pixels — France and Russia never share a tile, so they
/// cannot collide. Gating France out would empty the tile, not declutter it.
///
/// Ground area from planar deg² via equirectangular projection at the shape's
/// own mid-latitude: exact enough for bucketing (adjacent buckets differ ~4x),
/// cheap enough to run over every country once per build.
pub fn country_zoom(area_deg2: f64, mid_lat_deg: f64, population: u64) -> u8 {
    const M_PER_DEG: f64 = 111_320.0;
    /// How much a doubling of headcount moves the score relative to a doubling
    /// of ground area. 2.0: people are the point of a label, and without it no
    /// microstate could ever leave the backstop on headcount alone.
    const AOI_COVERAGE_WEIGHT: f64 = 2.0;
    let area_m2 = area_deg2.max(0.0) * M_PER_DEG * M_PER_DEG * mid_lat_deg.to_radians().cos().max(0.05);
    let score = area_m2.log10() + AOI_COVERAGE_WEIGHT * (population as f64 + 1.0).log10();
    if score >= 26.0 {
        0
    } else if score >= 23.5 {
        1
    } else if score >= 20.0 {
        2
    } else if score >= 14.5 {
        3
    } else {
        4
    }
}

/// Which admin-level band a place or a boundary belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Band {
    Country,
    Region,
    Locality,
}

/// The admin levels a place kind may link to. `None` means the kind never links by containment
/// (county levels and sub-locality places — see the module docs).
fn band_for_place_kind(kind: &str) -> Option<Band> {
    match kind {
        "country" => Some(Band::Country),
        "region" => Some(Band::Region),
        "locality" => Some(Band::Locality),
        _ => None,
    }
}

/// The band an admin level belongs to. Levels 5–6 deliberately belong to no band.
fn band_for_level(level: u16) -> Option<Band> {
    match level {
        1..=2 => Some(Band::Country),
        3..=4 => Some(Band::Region),
        7..=12 => Some(Band::Locality),
        _ => None,
    }
}

/// A boundary candidate: one `region_area` shape with everything the selector needs precomputed.
struct Boundary {
    /// Tagged relation id, the link value the tiler stamps.
    id: u64,
    /// `admin_level` from the region shape's class (`kind_detail`).
    level: u16,
    /// Sum of `|ring_area|` over the shape's exterior rings — the same metric `RegionMesh`
    /// compares on device (a consistent planar measure, not ground area; only ever compared).
    area: f64,
    /// `(min_lon, min_lat, max_lon, max_lat)` over the shape, for the prefilter. `None` when the
    /// shape holds no vertices at all.
    bbox: Option<(f64, f64, f64, f64)>,
    /// Assembled polygons, exterior first then holes, lon/lat.
    polygons: Vec<Polygon>,
}

impl Boundary {
    fn new(id: u64, level: u16, polygons: Vec<Polygon>) -> Boundary {
        let area: f64 =
            polygons.iter().filter_map(|poly| poly.first()).map(|ext| ring_area(ext).abs()).sum();
        let mut bbox: Option<(f64, f64, f64, f64)> = None;
        for ring in polygons.iter().flatten() {
            for &(lon, lat) in ring {
                bbox = Some(match bbox {
                    None => (lon, lat, lon, lat),
                    Some((x0, y0, x1, y1)) => {
                        (x0.min(lon), y0.min(lat), x1.max(lon), y1.max(lat))
                    }
                });
            }
        }
        Boundary { id, level, area, bbox, polygons }
    }
}

/// A place kind name from its interned `Class.kind` (1-based into [`KINDS`]).
fn place_kind_name(kind: u16) -> Option<&'static str> {
    if kind == 0 {
        return None;
    }
    KINDS.get(kind as usize - 1).copied()
}

/// Grid cells are 4 degrees square: 90 over lon, 45 over lat.
const GRID_NX: usize = 90;
const GRID_NY: usize = 45;
/// A boundary covering more cells than this goes on the overflow list, tested for every place.
/// 4-degree cells make even Russia (~250 cells) indexable; this only catches degenerate bboxes.
const MAX_CELLS_PER_BOUNDARY: usize = 1024;

/// Boundaries partitioned spatially so one place tests a cellful, not the planet.
struct Grid {
    cells: Vec<Vec<usize>>,
    overflow: Vec<usize>,
}

fn cell_of(lon: f64, lat: f64) -> usize {
    let ix = (((lon + 180.0) / 4.0).floor() as i32).clamp(0, GRID_NX as i32 - 1) as usize;
    let iy = (((lat + 90.0) / 4.0).floor() as i32).clamp(0, GRID_NY as i32 - 1) as usize;
    iy * GRID_NX + ix
}

impl Grid {
    fn build(boundaries: &[Boundary]) -> Grid {
        let mut grid = Grid { cells: vec![Vec::new(); GRID_NX * GRID_NY], overflow: Vec::new() };
        for (i, b) in boundaries.iter().enumerate() {
            let Some((x0, y0, x1, y1)) = b.bbox else {
                continue;
            };
            let (cx0, cy0) = (
                (((x0 + 180.0) / 4.0).floor() as i32).clamp(0, GRID_NX as i32 - 1),
                (((y0 + 90.0) / 4.0).floor() as i32).clamp(0, GRID_NY as i32 - 1),
            );
            let (cx1, cy1) = (
                (((x1 + 180.0) / 4.0).floor() as i32).clamp(0, GRID_NX as i32 - 1),
                (((y1 + 90.0) / 4.0).floor() as i32).clamp(0, GRID_NY as i32 - 1),
            );
            let count = (cx1 - cx0 + 1) as usize * (cy1 - cy0 + 1) as usize;
            if count > MAX_CELLS_PER_BOUNDARY {
                grid.overflow.push(i);
                continue;
            }
            for cy in cy0..=cy1 {
                for cx in cx0..=cx1 {
                    grid.cells[cy as usize * GRID_NX + cx as usize].push(i);
                }
            }
        }
        grid
    }

    /// Boundary indices that may contain `point`: its cell plus the overflow list.
    fn candidates_for(&self, point: (f64, f64)) -> impl Iterator<Item = usize> + '_ {
        self.cells[cell_of(point.0, point.1)]
            .iter()
            .copied()
            .chain(self.overflow.iter().copied())
    }
}

/// Is `point` inside `polygons`? Inside some exterior and in none of its holes.
fn point_in_boundary(point: (f64, f64), polygons: &[Polygon]) -> bool {
    polygons.iter().any(|poly| {
        let Some((exterior, holes)) = poly.split_first() else {
            return false;
        };
        point_in_ring(point, exterior) && !holes.iter().any(|hole| point_in_ring(point, hole))
    })
}

/// Point-to-segment distance at or below one e7 grid step (~1 cm) counts as on the edge.
///
/// e7-quantised coordinates snap to a 1e-7 grid, so anything closer than that is
/// indistinguishable from exactly on the line — and ray-casting there is a coin flip.
fn point_on_segment(point: (f64, f64), a: (f64, f64), b: (f64, f64)) -> bool {
    const EPS: f64 = 1e-7;
    let (px, py) = point;
    let dx = b.0 - a.0;
    let dy = b.1 - a.1;
    let len2 = dx * dx + dy * dy;
    if len2 == 0.0 {
        return (px - a.0).abs() <= EPS && (py - a.1).abs() <= EPS;
    }
    // Perpendicular distance, scale-aware: |cross| / |segment|. Compared
    // without the square root (|cross|^2 <= EPS^2 * len2, all sides
    // non-negative) — same predicate, no sqrt per segment on the hottest
    // inner loop of the link pass.
    let cross = dx * (py - a.1) - dy * (px - a.0);
    if cross * cross > EPS * EPS * len2 {
        return false;
    }
    px >= a.0.min(b.0) - EPS
        && px <= a.0.max(b.0) + EPS
        && py >= a.1.min(b.1) - EPS
        && py <= a.1.max(b.1) + EPS
}

/// Is `point` on any ring edge of `polygons`? Rings are explicitly closed, but the wrap edge is
/// checked too so an unclosed ring cannot hide one.
fn point_on_boundary_edge(point: (f64, f64), polygons: &[Polygon]) -> bool {
    polygons.iter().flat_map(|poly| poly.iter()).any(|ring| {
        if ring.len() < 2 {
            return false;
        }
        (0..ring.len()).any(|i| point_on_segment(point, ring[i], ring[(i + 1) % ring.len()]))
    })
}

/// Pick the link for one place: smallest in-band container wins, ties and edges link nothing.
fn select_link(point: (f64, f64), band: Band, candidates: &[&Boundary]) -> Option<u64> {
    // In-band, bbox-passing candidates only — the bbox is what keeps this off the PIP path.
    let mut passing: Vec<&Boundary> = Vec::new();
    for b in candidates {
        if band_for_level(b.level) != Some(band) {
            continue;
        }
        let Some((x0, y0, x1, y1)) = b.bbox else {
            continue;
        };
        if point.0 < x0 || point.0 > x1 || point.1 < y0 || point.1 > y1 {
            continue;
        }
        passing.push(*b);
    }
    // On any in-band edge the ray cast cannot be trusted — mask nothing.
    if passing.iter().any(|b| point_on_boundary_edge(point, &b.polygons)) {
        return None;
    }
    let mut best: Option<&Boundary> = None;
    let mut second = f64::INFINITY;
    for b in passing {
        if b.area <= 0.0 || !point_in_boundary(point, &b.polygons) {
            continue;
        }
        match best {
            None => best = Some(b),
            Some(cur) if b.area < cur.area => {
                second = cur.area;
                best = Some(b);
            }
            Some(_) => {
                second = second.min(b.area);
            }
        }
    }
    let best = best?;
    // Two containers within ~1% of each other is a coin flip (overlapping claims, duplicate
    // relations) — mask nothing rather than the wrong one.
    if second.is_finite() && (second - best.area) / best.area < 0.01 {
        return None;
    }
    Some(best.id)
}

/// Extend `region_links` with containment fallback links for member-less place labels.
///
/// Reads the finished store twice (boundaries, then places), inserts only keys still missing so
/// member links always win, and returns the counts the build log reports. The tiler reads the
/// extended map unchanged.
pub fn extend_region_links(
    store: &crate::store::Store,
    region_links: &mut HashMap<u64, u64>,
) -> Result<FallbackStats> {
    extend_region_links_with(store, region_links, &mut HashMap::new(), &HashMap::new())
}

/// [`extend_region_links`], also filling `country_zooms`: place tagged id → label start
/// zoom for `country` places, from each country's ground footprint and headcount (see
/// [`country_zoom`]). `populations` maps place tagged id → `population` tag headcount
/// (relation places; node/way countries are absent and score as unpopulated — the area
/// term still separates Russia from Nauru). The caller hands both maps to the tiler
/// beside `region_links`.
pub fn extend_region_links_with(
    store: &crate::store::Store,
    region_links: &mut HashMap<u64, u64>,
    country_zooms: &mut HashMap<u64, u8>,
    populations: &HashMap<u64, u64>,
) -> Result<FallbackStats> {
    // Pass 1: boundary shapes AND places, in one scan. Only `region_area`
    // areas (the mask's own shapes, carrying the admin level in `kind_detail`);
    // border lines enclose nothing. Places are positional, so node,
    // way-centroided and relation-centroided labels all link by the tagged id
    // they already carry.
    //
    // One scan, not two: the store reader is one sequential cursor over tens
    // of GB of spill, and both passes filtered it by layer. Collecting both
    // vectors in a single streaming pass deletes a full decode. Peak is
    // unchanged — boundaries already lived through place collection.
    struct PlacePoint {
        id: u64,
        point: (f64, f64),
        kind: Band,
        kind_name: String,
    }
    enum Kept {
        Boundary(Boundary),
        Place(PlacePoint),
    }
    // The scan decodes the whole store across the pool (`Store::par_scan`, file order kept), and
    // the per-feature filter -- including `Boundary::new`'s area and bbox walk -- runs there too.
    // The split into the two vectors below is in file order, so both are exactly what the
    // one-cursor loop built.
    // Pre-filter on the packed class (no geometry decoded): only an id-carrying boundary area
    // or place can pass the filter below.
    let kept = store.par_scan_where(
        |class, has_id| {
            has_id
                && ((class.layer == LAYER_BOUNDARIES && class.area) || class.layer == LAYER_PLACES)
        },
        |feature| {
        if feature.id == ID_NONE {
            return None;
        }
        if feature.class.layer == LAYER_BOUNDARIES && feature.class.area {
            let Geometry::Polygons(polygons) = feature.geometry else {
                return None;
            };
            if polygons.is_empty() {
                return None;
            }
            Some(Kept::Boundary(Boundary::new(feature.id, feature.class.kind_detail, polygons)))
        } else if feature.class.layer == LAYER_PLACES {
            let Geometry::Points(points) = feature.geometry else {
                return None;
            };
            let &point = points.first()?;
            let kind_name = place_kind_name(feature.class.kind)?;
            let band = band_for_place_kind(kind_name)?;
            Some(Kept::Place(PlacePoint {
                id: feature.id,
                point,
                kind: band,
                kind_name: kind_name.to_string(),
            }))
        } else {
            None
        }
    })?;
    let mut boundaries: Vec<Boundary> = Vec::new();
    let mut places: Vec<PlacePoint> = Vec::new();
    for item in kept {
        match item {
            Kept::Boundary(b) => boundaries.push(b),
            Kept::Place(p) => places.push(p),
        }
    }
    boundaries.retain(|b| b.bbox.is_some() && b.area > 0.0);
    let grid = Grid::build(&boundaries);
    // The `country_zoom` lookup below used to walk `boundaries` linearly per
    // linked country; indexed by id so it is a hash lookup instead. Built
    // once — the vec never moves again after this.
    let boundary_by_id: HashMap<u64, usize> =
        boundaries.iter().enumerate().map(|(i, b)| (b.id, i)).collect();

    // Pass 2: places. Positional, so node, way-centroided and relation-centroided labels all
    // link by the tagged id they already carry — the only path that can link non-node places,
    // since the member map is node-keyed. Country labels also gain their area+population
    // start zoom here (see `country_zoom`): the linked boundary's footprint is in hand,
    // so no third pass is needed. The headcount comes from `populations` (relation
    // places, filled by the caller while tags were in hand); absent entries score as
    // unpopulated — Antarctica's case — never as missing.
    //
    // Collected serially first — the store reader is one sequential cursor —
    // then linked on the pool: `select_link` is pure per-place work over the
    // shared grid, and the drain inserts in collection order with member
    // links winning, exactly as the serial loop did. `select_link` returns
    // at most one target per place, so the drain cannot double-insert and
    // the map bytes are identical.
    //
    // NOTE: `places` now arrives from the fused pass-1 scan above (same
    // reader, same filter, same spill order), not from a second scan. What
    // follows is unchanged: drain order — and therefore every inserted key,
    // value, stat and country zoom — is identical.
    let mut stats = FallbackStats::default();
    let mut per_kind: HashMap<String, (u64, u64)> = HashMap::new();
    // Member links always win: counted exactly as the serial loop did,
    // and skipped in the link pass so nothing contends on the map.
    for place in &places {
        stats.places_total += 1;
        let entry = per_kind.entry(place.kind_name.clone()).or_insert((0, 0));
        entry.0 += 1;
        if region_links.contains_key(&place.id) {
            stats.member_linked += 1;
            entry.1 += 1;
        }
    }
    // Parallel link: one candidate-set build + `select_link` per
    // member-less place, on the pool; the drain inserts in collection
    // order, so the map — and the stats — are identical at every thread
    // count. `select_link` returns at most one target per place, so the
    // drain cannot double-insert. The bar ticks in the drain per place, so
    // it spans the whole pass even though the expensive half runs on the
    // pool.
    let mut bar =
        Progress::new("Fallback: places".to_string(), places.len(), "place(s)", true);
    const FALLBACK_BATCH: usize = 16 * 1024;
    let mut batch: Vec<usize> = Vec::with_capacity(FALLBACK_BATCH);
    let mut built: Vec<Option<u64>> = Vec::with_capacity(FALLBACK_BATCH);
    let mut at = 0usize;
    while at < places.len() {
        batch.clear();
        while at < places.len() && batch.len() < FALLBACK_BATCH {
            if region_links.contains_key(&places[at].id) {
                // Member-linked: counted above, nothing to compute. The tick
                // keeps the bar spanning every place, not just computed ones.
                bar.tick("place(s)");
                at += 1;
                continue;
            }
            batch.push(at);
            at += 1;
        }
        if batch.is_empty() {
            continue;
        }
        built.clear();
        par::install(|| {
            batch
                .par_iter()
                .map(|&pi| {
                    let place = &places[pi];
                    let candidates: Vec<&Boundary> = grid
                        .candidates_for(place.point)
                        .map(|i| &boundaries[i])
                        .collect();
                    select_link(place.point, place.kind, &candidates)
                })
                .collect_into_vec(&mut built)
        });
        for (&pi, target) in batch.iter().zip(built.drain(..)) {
            let place = &places[pi];
            if let Some(target) = target {
                region_links.insert(place.id, target);
                stats.fallback_linked += 1;
                if let Some(entry) = per_kind.get_mut(&place.kind_name) {
                    entry.1 += 1;
                }
            }
            // A country label starts at the zoom its footprint earns. The
            // linked boundary (member half inserted before this pass,
            // fallback half just above — both visible in `region_links`
            // now) carries the ground area; the headcount comes from
            // `populations` (relation places, filled by the caller while
            // tags were in hand); absent entries score as unpopulated —
            // Antarctica's case — never as missing. Unlinked countries keep
            // the schema floor (z0): a missing link must never hide a
            // country.
            if place.kind_name == "country" {
                if let Some(target) = region_links.get(&place.id) {
                    if let Some(&bi) = boundary_by_id.get(target) {
                        let boundary = &boundaries[bi];
                        let mid_lat =
                            boundary.bbox.map(|(_, y0, _, y1)| (y0 + y1) / 2.0).unwrap_or(0.0);
                        let population = populations.get(&place.id).copied().unwrap_or(0);
                        country_zooms.insert(
                            place.id,
                            country_zoom(boundary.area, mid_lat, population),
                        );
                        stats.country_zooms += 1;
                    }
                }
            }
            bar.tick("place(s)");
        }
    }
    bar.finish("place(s)");
    stats.fallback_missed =
        stats.places_total.saturating_sub(stats.member_linked + stats.fallback_linked);
    let mut kinds: Vec<(String, u64, u64)> = per_kind
        .into_iter()
        .map(|(kind, (total, linked))| (kind, total, linked))
        .collect();
    kinds.sort_by(|a, b| a.0.cmp(&b.0));
    stats.per_kind = kinds;
    Ok(stats)
}

include!("extract_fallback_part1.rs");
