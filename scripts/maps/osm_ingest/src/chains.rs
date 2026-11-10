//! Within-way chain building: degree-2 collapsing that never materialises a
//! segment array.
//!
//! [`crate::compact`] does the same job by building every segment, indexing them
//! into a CSR of incidences, and walking chains across way boundaries. That is
//! the better graph and the wrong shape for a planet: the segment array and its
//! incidence index are 114 GB of a 217 GB working set, and no amount of shrinking
//! records closes that on a 94 GB machine.
//!
//! This module gets the same collapse from two much smaller structures:
//!
//! * **one byte of degree per node**, accumulated by a pass that reads the way
//!   blobs and keeps nothing;
//! * **the way's own ref list**, walked positionally as it is decoded.
//!
//! # What it costs
//!
//! Chains may not cross from one OSM way into another. Every segment in a chain
//! then shares its way's type, speed limit, name, one-way-ness and lane masks *by
//! construction*, so the entire attribute-agreement half of
//! `compact::classify` — and the segment records it compared — disappears.
//!
//! The price is real and is an accepted regression: a node where two different
//! ways meet end-to-end with matching tags is collapsed by `compact` and kept
//! here. OSM splits long roads constantly, at every tag change and at the
//! 2000-node way ceiling, so this is not a rare case. Two things soften it: the
//! 256-point budget already forces cuts on long dense roads anyway, and more
//! surviving nodes strictly *improves* snapping.
//!
//! # What it buys beyond memory
//!
//! Two correctness simplifications fall out of walking positions instead of a
//! graph:
//!
//! * the walk cannot loop, because it advances through a finite ref list, so
//!   there is no "used" flag per segment and no anchorless-cycle phase — a ring
//!   way closes on its own first node for free;
//! * within one way every segment runs along ref order, so a one-way chain is
//!   already oriented along its traffic and there is no reorientation step to get
//!   the forward and backward lane masks the wrong way round.
//!
//! Both of those were bugs that had to be fixed once already.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use crate::compact::Chain;
use crate::geom;
use crate::graph_build::{way_attrs, AtomicBits, Bitset, NodeIndex, WayAttrs};
use crate::names::{LocalNames, NamePool, NO_NAME};
use crate::osm::{visit_block, Element};
use crate::pbf::{self, BlobLoc, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::spatial::accurate_dist_mm;
use crate::tags;

/// Is this consecutive pair of way refs a segment of the graph?
///
/// **Both the degree pass and the chain walk must ask this and nothing else.**
/// The degree array is what decides where a chain is cut, so a disagreement
/// between the two would either dead-end a chain at a node the degree array
/// thinks is a pass-through, or walk a chain straight through one it thinks is a
/// junction. Every other invariant here is recoverable; this one is not.
#[inline]
pub(crate) fn segment(index: &NodeIndex, a: i64, b: i64) -> Option<(u32, u32)> {
    let (u, v) = (index.dense(a)?, index.dense(b)?);
    // A way listing the same node twice in a row is a mapping artefact, not a
    // zero-length road. Counting it would give that node two phantom incidences
    // and so promote a genuine pass-through into a junction.
    (u != v).then_some((u, v))
}

/// Nodes with more incidences than a byte can hold are junctions whatever the
/// true count is, so the counter saturates. Only `== 2` changes a decision, and
/// no node with 255 incidences is a pass-through.
///
/// Counted straight into the shared array from the decode workers: a
/// saturating increment commutes (the result is `min(255, incidences)` in any
/// order), so nothing reaches the sink and nothing about the order of chunks
/// can show. The sink used to scatter every endpoint single-threaded, which
/// bounded the whole pass to one core.
#[derive(Default)]
struct DegreePass;

/// Saturating `+1` on a shared degree byte.
#[inline]
fn bump(d: &std::sync::atomic::AtomicU8) {
    use std::sync::atomic::Ordering::Relaxed;
    let _ = d.fetch_update(Relaxed, Relaxed, |v| v.checked_add(1));
}

/// One saturating incidence count per dense node id.
///
/// `filter` drops segments the same way the chain walk does: a way no node of
/// which touches the region contributes no incidence, so its nodes stay degree
/// 0 and never survive. Both this and [`build`] must ask the same question —
/// see [`segment`] — or a chain dead-ends where the degrees say pass-through.
///
/// The region test reads pass 2's `coords`, exactly as [`build`]'s does. It used
/// to resolve the marked nodes' locations again in a side table with an extra
/// node scan; that table held the same coordinates for the same nodes
/// (`coords[d]` is set for exactly the nodes `index.dense` accepts), so the
/// test is unchanged and the scan is gone.
pub(crate) fn count_degrees(
    input: &Path,
    blobs: &[BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    slots: u32,
    filter: crate::graph_build::RegionFilter,
) -> Result<Vec<u8>> {
    let mut degree = vec![0u8; slots as usize];
    {
        let bytes: &mut [u8] = &mut degree;
        // SAFETY: `AtomicU8` has the layout of `u8`, and the exclusive borrow of
        // `degree` is held for as long as this view lives.
        let shared: &[std::sync::atomic::AtomicU8] =
            unsafe { &*(bytes as *mut [u8] as *const [std::sync::atomic::AtomicU8]) };
        let touches = |refs: &[i64]| -> bool {
            let Some(b) = filter.as_ref() else {
                return true;
            };
            refs.iter().any(|r| {
                index.dense(*r).is_some_and(|d| {
                    let (lat, lon) = coords[d as usize];
                    b.contains_e7(lat, lon)
                })
            })
        };
        let _ = pbf::run_pass_sink(
            input,
            blobs,
            Some(blob_kinds),
            KIND_WAYS,
            "Pass 3: node degrees",
            DegreePass::default,
            |_state: &mut DegreePass, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
                    if let Element::Way(w) = el {
                        if tags::get_hw_id(w.tags.get_str("highway")) != 0 && touches(w.refs) {
                            for pair in w.refs.windows(2) {
                                if let Some((u, v)) = segment(index, pair[0], pair[1]) {
                                    bump(&shared[u as usize]);
                                    bump(&shared[v as usize]);
                                }
                            }
                        }
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
            |_chunk| Ok(()),
        )?;
    }
    Ok(degree)
}

/// Every chain in the file, plus the lane pool their offsets point into.
/// What the chain pass leaves behind. The chains themselves are on disk.
pub(crate) struct Chains {
    pub chain_count: u64,
    pub lanes: Vec<u16>,
    /// Dense ids a chain starts or ends at. Together with "degree is not 2" this
    /// is the surviving-node set.
    pub endpoints: Bitset,
    /// Chains cut because their geometry would not fit one edge.
    pub splits: usize,
    /// Directed edges an uncompacted build would have produced. Runs tile every
    /// segment exactly once, so this is exact without keeping the segments.
    pub raw_edge_count: u64,
    /// Which blobs the chain pass actually had to read. The load-bearing
    /// measurement for re-reading the way blobs rather than caching them.
    pub kinds: Vec<u8>,
}

#[derive(Default)]
struct ChainPass {
    /// `chains.hdr` records, pre-encoded on the worker with CHUNK-LOCAL
    /// `pts_start`, name id and lane offsets; the sink rebases those three
    /// fields in place ([`rebase_header`]) and appends the bytes.
    hdr: Vec<u8>,
    /// `chains.pts` bytes for this chunk's chains, coordinates already
    /// resolved — the random `coords` reads now happen on the pool instead of
    /// in the single-threaded sink.
    pts: Vec<u8>,
    /// Points in `pts`, i.e. the next chain's chunk-local `pts_start`.
    points: u64,
    lanes: Vec<u16>,
    names: LocalNames,
    splits: usize,
    raw_edge_count: u64,
}

/// Walk every routable way, cutting chains at nodes that must survive, and stream
/// the result into `spill`.
///
/// `degree` comes from [`count_degrees`] and `stop` marks nodes carrying a
/// transit stop code; both are indexed by dense id, as is `coords`.
///
/// Nothing chain-shaped is retained. Only one chunk's worth exists at a time,
/// which is what keeps this pass's footprint proportional to a chunk rather than
/// to the road network.
///
/// The workers do everything that does not depend on earlier chunks: encode
/// each header record, resolve every point to coordinates, and mark chain
/// endpoints (a set union, straight into a shared bitset). The ordered sink
/// does only what does: intern the chunk's names, rebase the lane pool and the
/// point offsets, and append two byte buffers. Same bytes as encoding each
/// chain in the sink.
#[allow(clippy::too_many_arguments)]
pub(crate) fn build<W: Write + Send>(
    input: &Path,
    blobs: &[BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    degree: &[u8],
    stop: &Bitset,
    slots: u32,
    filter: crate::graph_build::RegionFilter,
    pool: &mut NamePool<W>,
    spill: &Spill,
) -> Result<Chains> {
    let mut endpoints = Bitset::new(u64::from(slots));
    let mut lanes: Vec<u16> = Vec::new();
    let mut splits = 0usize;
    let mut raw_edge_count = 0u64;
    let mut writer = spill.writer()?;
    let kinds = {
        let ends = endpoints.atomic();
        pbf::run_pass_sink(
            input,
            blobs,
            Some(blob_kinds),
            KIND_WAYS,
            "Pass 4: chains",
            ChainPass::default,
            |state: &mut ChainPass, block| {
                chain_blob(state, block, index, coords, degree, stop, filter, &ends)
            },
            |mut chunk| {
                let name_map = chunk
                    .names
                    .flush(pool)
                    .map_err(|e| Error(e.to_string()))?;
                let lane_base = lanes.len() as u32;
                lanes.extend_from_slice(&chunk.lanes);
                crate::graph_build::cap_lane_pool(lanes.len())?;
                let points_base = writer.points;
                for rec in chunk.hdr.chunks_exact_mut(CHAIN_REC_BYTES as usize) {
                    rebase_header(rec, points_base, &name_map, lane_base);
                }
                writer.push_encoded(
                    std::mem::take(&mut chunk.hdr),
                    std::mem::take(&mut chunk.pts),
                    chunk.points,
                )?;
                splits += chunk.splits;
                raw_edge_count += chunk.raw_edge_count;
                Ok(())
            },
        )?
    };
    let chain_count = writer.finish()?;
    Ok(Chains {
        chain_count,
        lanes,
        endpoints,
        splits,
        raw_edge_count,
        kinds,
    })
}

/// Turn a worker-encoded header's chunk-local fields into global ones: exactly
/// what the sink used to do to the `Chain` before encoding it.
#[inline]
fn rebase_header(rec: &mut [u8], points_base: u64, name_map: &[u32], lane_base: u32) {
    let u32_at = |r: &[u8], o: usize| u32::from_le_bytes(r[o..o + 4].try_into().expect("4 bytes"));
    let u16_at = |r: &[u8], o: usize| u16::from_le_bytes(r[o..o + 2].try_into().expect("2 bytes"));
    let start = u64::from_le_bytes(rec[0..8].try_into().expect("8 bytes")) + points_base;
    rec[0..8].copy_from_slice(&start.to_le_bytes());
    let name = u32_at(rec, 24);
    let name = if name == u32::MAX {
        NO_NAME
    } else {
        name_map[name as usize]
    };
    rec[24..28].copy_from_slice(&name.to_le_bytes());
    if u16_at(rec, 36) > 0 {
        let off = u32_at(rec, 28) + lane_base;
        rec[28..32].copy_from_slice(&off.to_le_bytes());
    }
    if u16_at(rec, 38) > 0 {
        let off = u32_at(rec, 32) + lane_base;
        rec[32..36].copy_from_slice(&off.to_le_bytes());
    }
}

#[allow(clippy::too_many_arguments)]
fn chain_blob(
    state: &mut ChainPass,
    block: &pbf::PrimitiveBlock,
    index: &NodeIndex,
    coords: &[geom::Pt],
    degree: &[u8],
    stop: &Bitset,
    filter: crate::graph_build::RegionFilter,
    ends: &AtomicBits,
) -> Result<u8> {
    let mut kinds = 0u8;
    let mut path: Vec<u32> = Vec::new();
    let mut hops: Vec<u32> = Vec::new();
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        let Element::Way(w) = el else {
            return Ok(());
        };
        let type_ = tags::get_hw_id(w.tags.get_str("highway"));
        if type_ == 0 {
            return Ok(());
        }
        // Region filter: keep the way when ANY node touches the box. Coords
        // are resolved by now so this is a direct test, same rule as the
        // reference path's `way_blob`.
        if let Some(b) = filter.as_ref() {
            let mut touches = false;
            for r in w.refs {
                if let Some(d) = index.dense(*r) {
                    let (lat_e7, lon_e7) = coords[d as usize];
                    if b.contains_e7(lat_e7, lon_e7) {
                        touches = true;
                        break;
                    }
                }
            }
            if !touches {
                return Ok(());
            }
        }
        let attrs = way_attrs(&w, type_, &mut state.lanes, &mut state.names);
        path.clear();
        hops.clear();
        for pair in w.refs.windows(2) {
            let Some((u, v)) = segment(index, pair[0], pair[1]) else {
                // Not a segment — a dangling ref, or the way listing one node
                // twice in a row. Skip it and let the next accepted pair decide
                // whether it continues this run.
                continue;
            };
            if path.last() != Some(&u) {
                // The start of the way, the far side of a gap the graph does not
                // bridge, or the node the previous cut ended on.
                flush(state, &path, &hops, &attrs, coords, ends);
                path.clear();
                hops.clear();
                path.push(u);
            }
            let (a, b) = (coords[u as usize], coords[v as usize]);
            hops.push(accurate_dist_mm(a.0, a.1, b.0, b.1));
            path.push(v);
            // `v` must survive when it is a junction, a dead end, or a transit
            // stop the reconnect pass addresses directly. Cut there; the next pair
            // restarts from it by the rule above.
            if degree[v as usize] != 2 || stop.get(u64::from(v)) {
                flush(state, &path, &hops, &attrs, coords, ends);
                path.clear();
                hops.clear();
            }
        }
        // The way's last node is a chain boundary whatever its degree: a chain may
        // not continue into the next way.
        flush(state, &path, &hops, &attrs, coords, ends);
        Ok(())
    })?;
    Ok(kinds)
}

/// Emit `path` as one or more chains, splitting it to fit the geometry budget.
fn flush(
    state: &mut ChainPass,
    path: &[u32],
    hops: &[u32],
    attrs: &WayAttrs,
    coords: &[geom::Pt],
    ends: &AtomicBits,
) {
    if path.len() < 2 {
        return;
    }
    debug_assert_eq!(hops.len(), path.len() - 1);
    let runs = split_runs(path, coords);
    if runs.len() > 1 {
        state.splits += runs.len() - 1;
    }
    for (lo, hi) in runs {
        ends.set(u64::from(path[lo]));
        ends.set(u64::from(path[hi]));
        let ids = &path[lo..=hi];
        // Directed edges an uncompacted build would have emitted for this run:
        // one per segment per permitted direction. Runs tile every segment of the
        // way exactly once, so summing them is exact without keeping the segments.
        state.raw_edge_count += (hi - lo) as u64 * if attrs.oneway { 1 } else { 2 };
        let mut rec = [0u8; CHAIN_REC_BYTES as usize];
        ChainRec {
            // Chunk-local; rebased by the sink.
            pts_start: state.points,
            pts_len: (hi - lo + 1) as u32,
            first: ids[0],
            last: ids[ids.len() - 1],
            dist_mm: hops[lo..hi].iter().fold(0u32, |a, d| a.saturating_add(*d)),
            // Chunk-local name id (or u32::MAX); rebased by the sink.
            name_offset: attrs.name,
            // Forward is along ref order, and so is the chain: no reorientation,
            // so no chance of handing the masks to the wrong end. Chunk-local
            // offsets; rebased by the sink when the count is non-zero.
            fwd_lane_off: attrs.fwd_lane_off,
            bwd_lane_off: attrs.bwd_lane_off,
            fwd_lane_count: attrs.fwd_lane_count,
            bwd_lane_count: attrs.bwd_lane_count,
            type_: attrs.type_,
            speed_limit: attrs.speed_limit,
            oneway: attrs.oneway,
        }
        .encode(&mut rec);
        state.hdr.extend_from_slice(&rec);
        for id in ids {
            let (lat_e7, lon_e7) = coords[*id as usize];
            state.pts.extend_from_slice(&lat_e7.to_le_bytes());
            state.pts.extend_from_slice(&lon_e7.to_le_bytes());
        }
        state.points += ids.len() as u64;
    }
}

/// Cut `path` into runs that each fit one edge's geometry.
///
/// Returns inclusive index ranges. A run of two nodes is always allowed even if
/// its single segment is too long to delta-encode: the caller stores no geometry
/// for it, and the reader's straight-chord fallback is then exactly right,
/// because a two-node run *is* a straight chord.
fn split_runs(path: &[u32], coords: &[geom::Pt]) -> Vec<(usize, usize)> {
    let point = |n: u32| coords[n as usize];
    let mut runs = Vec::new();
    let mut start = 0usize;
    let mut cost = 1u64;
    let mut i = 1usize;
    while i < path.len() {
        let step = geom::segment_points(point(path[i - 1]), point(path[i]));
        if cost + step <= u64::from(geom::MAX_POINTS) {
            cost += step;
            i += 1;
            continue;
        }
        if i - 1 > start {
            // Close the run before this segment and retry it with a fresh budget
            // starting at the shared node.
            runs.push((start, i - 1));
            start = i - 1;
            cost = 1;
        } else {
            // One segment that cannot be encoded at all, even alone.
            runs.push((start, i));
            start = i;
            cost = 1;
            i += 1;
        }
    }
    if start < path.len() - 1 {
        runs.push((start, path.len() - 1));
    }
    runs
}

/// Does dense node `n` survive as a graph node?
///
/// A chain endpoint always does. Anything of degree 1 or 3+ is a junction, a
/// dead end or an anchor the walks must address. Degree 2 is the only
/// collapsible case and is handled by the walk, not here.
///
/// Degree 0 is dropped unless it carries a transit stop: a node with no
/// incident segment is a way the region filter removed (every road node outside
/// the region reads degree 0 once its ways are skipped), dead weight in
/// `nodes.bin` with no edge ever addressing it. An isolated stop is the one
/// exception — the reconnect pass addresses stop nodes directly.
#[inline]
pub(crate) fn survives(endpoints: &Bitset, degree: &[u8], stop: &Bitset, n: u32) -> bool {
    if endpoints.get(u64::from(n)) {
        return true;
    }
    let d = degree[n as usize];
    d != 2 && (d != 0 || stop.get(u64::from(n)))
}

// ---- the spill -----------------------------------------------------------
//
// Chains are the last structure in the build that scales with the road network
// rather than with the node set, and every stage after them is a sequential scan.
// So they go to two flat files and the stages read them back: the CSR build, the
// reconnect BFS and each write round all stream, and none of them needs the chain
// set resident.
//
// Two properties are worth the redundancy in the record:
//
// * **The header is fixed size**, so the chain count is the file's length divided
//   by the record size, and any stage can seek straight to chain *k* and process a
//   contiguous slice of chains without reading the ones before it.
// * **`chains.pts` holds coordinates, not node ids**, so `coords` — the largest
//   array in the build, and the only one indexed by *marked* rather than surviving
//   node — can be freed the moment the spill is written. The cost is that the two
//   endpoint node ids have to be repeated in the header, which is eight bytes a
//   chain against gigabytes of coordinates.

/// Bytes per record in `chains.hdr`. The trailing padding is reserved and written
/// as zero, so the record can grow a field without changing the stride.
pub(crate) const CHAIN_REC_BYTES: u64 = 48;

/// Bytes per point in `chains.pts`: `i32 lat_e7`, `i32 lon_e7`.
const CHAIN_PT_BYTES: u64 = 8;

/// One chain, as `chains.hdr` stores it.
#[derive(Clone, Copy)]
pub(crate) struct ChainRec {
    /// First point's index in `chains.pts`.
    pub pts_start: u64,
    pub pts_len: u32,
    /// Dense id of the first point. Kept even though the point itself is stored,
    /// because the writer needs the *node* to map to a final id and the
    /// coordinates cannot be mapped back to one.
    pub first: u32,
    /// Dense id of the last point.
    pub last: u32,
    pub dist_mm: u32,
    pub name_offset: u32,
    pub fwd_lane_off: u32,
    pub bwd_lane_off: u32,
    pub fwd_lane_count: u16,
    pub bwd_lane_count: u16,
    pub type_: u8,
    pub speed_limit: u8,
    pub oneway: bool,
}

include!("chains_part1.rs");
include!("chains_part2.rs");