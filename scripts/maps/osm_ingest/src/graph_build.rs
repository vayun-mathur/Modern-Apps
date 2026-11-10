//! Road-graph build: `.osm.pbf` -> `metadata.bin` / `nodes.bin` / `edges.bin` /
//! `lanes.bin` / `intermediate.bin` / `road_names.bin` (+ optional `elevation.bin`).
//!
//! A port of the former `scripts/maps/generator.cpp`, preserving the on-disk
//! contract byte for byte. The reader is `maps/src/main/rust/src/graph.rs`, which
//! is the authority for every layout decision here:
//!
//! | File | Layout |
//! |---|---|
//! | `metadata.bin` | `u32 magic "MARG"`, `u32 version`, `u64 node_count`, `u64 edge_count`, `u64 escape_count`, `u64 named_edges` (40 B) |
//! | `nodes.bin` | `NodeRec[node_count + 1]`, **12 bytes each**: `i32 lat_e7, i32 lon_e7, u32 edge_ptr`; the trailing sentinel's `edge_ptr` is `edge_count` |
//! | `edges.bin` | `EdgeRec[edge_count]`, **7 bytes each**: `i16 target_delta, u24 dist_mm, u8 type, u8 speed_limit`; then padding, `u32 escape_first[E.div_ceil(1024) + 1]`, `(u32 edge_idx, u32 target, u32 dist_mm) x escape_count`, padding, and the sparse name table — a presence bitmap with a rank index plus `u32 name_off[named_edges]`. See [`EdgeFile`] |
//! | `lanes.bin` | `u32 n`, then `(u32 edge_idx, u32 blob_byte_off) x (n + 1)` ascending by `edge_idx`, then the packed `u16` mask blob — **sparse**, only lane-bearing edges appear |
//! | `intermediate.bin` | the delta-encoded polyline blob ([`crate::geom`]) at offset 0, then a trailer: `u64 rank[..]`, `u8 present[..]` (one bit per directed edge), `u64 coarse[..]`, `u16 within[..]` over the *geometry* edges, and `u64 G`. See [`GeomFile`] |
//! | `road_names.bin` | deduped NUL-terminated string pool |
//! | `elevation.bin` | **optional** `i16[node_count]` of per-node ground elevation in metres (WS-G), written only with `--dem`; a build without it is still valid and the device reads every node's elevation as 0 |
//!
//! Every file's length is now an exact function of `metadata.bin`'s counts, and
//! the reader checks each one. `road_names.bin` is the exception: it is a byte pool
//! with no count of its own, and reads into it are bounds-checked instead.
//!
//! Three behavioural notes where this differs from the C++:
//!
//! * It is **deterministic**. The C++ filled its node array via
//!   `idx.fetch_add(1)` from concurrent workers and interned names in
//!   thread-scheduling order, so its node order, `name_offset` values and
//!   therefore all of `road_names.bin`/`edges.bin`/`nodes.bin` differed between
//!   two runs of itself. Here every parallel stage merges per-chunk results in
//!   chunk order, so the output is a pure function of the input.
//! * The C++ kept two bitsets, `useful_nodes_mask` and `road_nodes_mask`, but
//!   set and cleared them in exactly the same places, so they always held
//!   identical contents. One bitset does the same job for half the memory.
//! * **Degree-2 chains are collapsed** ([`crate::compact`]) and the geometry they
//!   carried moves into `intermediate.bin`. Pass 3 therefore builds *undirected*
//!   segments and defers the split into directed edges until after compaction,
//!   because pairing a `u -> v` edge with its `v -> u` twin — which
//!   `REVERSE_GEOMETRY_FLAG` depends on — is only unambiguous while the two are
//!   still one object.
//!
//! # Passes, and why there are four
//!
//! 1. **Refs + stops** (nodes and ways): mark the node bitset. Nothing about a
//!    way is retained.
//! 2. **Rank index**: `popcount` below an id becomes that node's address, so the
//!    node array is implicitly ordered by OSM id. That deletes the sorted
//!    `(osm_id, local_id)` table the edge pass used to binary-search, the
//!    per-node `osm_id`, and the per-node spatial key.
//! 3. **Node scan**: scatter coordinates into their dense slots and record which
//!    slots a blob actually filled.
//! 4. **Ways** (way blobs only): re-read them and emit segments directly.
//!
//! The order is not the obvious one. Pass 1 marks *every* ref of every routable
//! way, including refs to nodes no blob in the file defines — an extract clipped
//! at a boundary is full of them. Under dense addressing those refs own slots, so
//! coordinates must be scattered, and their arrival recorded, *before* anything
//! reads the node array; otherwise a dangling ref reads as `(0, 0)`.

use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;

use crate::compact::{self, Seg, REMOVED};
use crate::chains;
use crate::geom;
use crate::names::{LocalNames, NamePool, NO_NAME};
use crate::osm::{visit_block, Element};
use crate::par;
use crate::pbf::{self, KIND_NODES, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::spatial::{accurate_dist_mm, spatial_from_e7};
use rayon::prelude::*;
use crate::tags;

/// Node ids at or above this are skipped, exactly as in the C++. The bitset that
/// tracks "is this node part of the graph" is sized from it (2.5 GB).
pub const BITSET_SIZE: u64 = 20_000_000_000;

/// `"MARG"` little-endian — `GRAPH_MAGIC` in `graph.rs`.
const GRAPH_MAGIC: u32 = 0x4752_414D;
/// Version 5 narrows an `edges.bin` record from 14 bytes to 11: `target` becomes an
/// `i16` delta from the edge's source and `dist_mm` a `u24`, with one escape table
/// behind the record array for the 0.3% that do not fit. Planet goes ~29.0 -> ~25.8
/// GB, and every value stays *exact*.
/// Version 6 moves `name_offset` out of the record into a sparse side table — two
/// thirds of edges have no name — taking the record to 7 bytes and planet to ~23.3 GB.
/// Version 7 blocked-compresses `nodes.bin`, `edges.bin` records and
/// `elevation.bin` (ZBLK sections: zstd frames over 4096-record blocks behind
/// a `u64` offset table). Same records, same order — only the container
/// changes. Readers accept 6 and 7; writers emit 7.
const GRAPH_VERSION: u32 = 7;
/// Oldest graph version accepted: v6 raw tables still decode.
const GRAPH_VERSION_MIN: u32 = 6;

/// Geometry edges per coarse block in `intermediate.bin`'s two-level offset table
/// — `INTERMEDIATE_BLOCK` in `graph.rs`, and the two must agree.
///
/// A per-edge blob is at most 1016 bytes ([`geom::MAX_POINTS`] points, of which
/// only the interior is stored, 4 bytes each), so a block spans at most 32,512
/// bytes and the within-block half fits a `u16`. [`GeomFile::push_entry`] asserts
/// it. The table is indexed by an edge's *rank in the presence bitmap*, not by its
/// edge index, so every entry in a block describes a real blob.
const INTERMEDIATE_BLOCK: u64 = 32;

/// Smallest connected component that counts as a real road network for the
/// purpose of [`reconnect_isolated_stops`].
///
/// A stop already attached to a component this size is left alone; anything
/// smaller is treated as a mapping artefact and reconnected. The threshold is
/// capped at the largest component that actually exists, so a tiny graph — every
/// fixture in this file — still reconnects against its own road network.
const MIN_ROUTABLE_COMPONENT: u32 = 1000;

/// Synthetic edges that reattach an isolated transit stop to the routable graph.
const RECONNECT_TYPE: u8 = 12;
const RECONNECT_SPEED: u8 = 5;

/// Sentinel in a `CachedWay`/`TmpEdge` lane offset meaning "no lane data".
const NO_LANES: u32 = 0xFFFF_FFFF;

/// Sentinel in `TmpEdge::chain` meaning "this edge came from nowhere in
/// particular" — currently only the synthetic transit-stop connectors, which are
/// straight lines and so need no stored geometry.
const NO_CHAIN: u32 = 0xFFFF_FFFF;

/// `REVERSE_GEOMETRY_FLAG` in `graph.rs`: set in an edge's `type_` byte to mean
/// "my geometry is my twin's, read backwards". Only one edge of a bidirectional
/// pair stores the polyline, which halves the blob.
///
/// The reader locates the twin by scanning the *target's* edge range for an edge
/// pointing back at the source, and takes the first match — so this may only be
/// set when exactly one such edge exists. See [`twin_is_unique`].
const REVERSE_GEOMETRY_FLAG: u8 = 0x40;

/// Fallback stop code for a transit stop node with no `name`.
const UNNAMED_STOP: &[u8] = b"OSM_STOP";

/// Sentinel in a narrowed `target` delta meaning "this record's `target` and
/// `dist_mm` both live in the escape table".
///
/// An `edges.bin` record stores `target` as an `i16` delta from the edge's own
/// source rather than absolutely, which is only possible because the nodes are
/// sorted by a space-filling curve and an edge is short: [`Census`] measures how
/// often that fails. `i16::MIN` is reserved rather than used, so the representable
/// range is symmetric at ±32767.
const TARGET_DELTA_ESCAPE: i16 = i16::MIN;

/// Sentinel in a narrowed `u24 dist_mm` meaning the same thing.
///
/// 0xFFFFFF mm is 16.78 km, which a collapsed degree-2 chain can exceed in empty
/// country. One sentinel shared with [`TARGET_DELTA_ESCAPE`] means a single escape
/// table serves both fields, so a `dist_mm` outlier costs no extra branch and no
/// extra table.
const DIST_MM_ESCAPE: u32 = 0xFF_FFFF;

/// Bytes per `edges.bin` record — `EdgeRec` in `graph.rs`, and an on-disk contract
/// with it: `i16 target_delta, u24 dist_mm, u8 type_, u8 speed_limit`. `name_offset`
/// is not a field; it is a sparse side table behind the record array.
const EDGE_REC_BYTES: u64 = 7;

/// Directed edges per entry of `edges.bin`'s escape block index — `ESCAPE_BLOCK` in
/// `graph.rs`, and the two must agree.
const ESCAPE_BLOCK: u64 = 1024;

/// Alignment each section of a multi-section file starts on — `SECTION_ALIGN` in
/// `graph.rs`.
const SECTION_ALIGN: u64 = 8;

/// Round `n` up to the next multiple of `align`, which must be a power of two.
const fn align_up(n: u64, align: u64) -> u64 {
    (n + align - 1) & !(align - 1)
}

/// `target - source` when it fits the `i16` a record stores, `None` when the edge
/// has to escape.
///
/// This is *the* escape predicate: [`Census`] counts with it and [`EdgeFile::push`]
/// narrows with it, so the two cannot disagree by one and produce a pack that loads
/// while reading the wrong node.
#[inline]
fn target_delta(source: u32, target: u32) -> Option<i16> {
    let d = i64::from(target) - i64::from(source);
    i16::try_from(d).ok().filter(|d| *d != TARGET_DELTA_ESCAPE)
}

/// True when either field of this edge is unrepresentable inline.
#[inline]
fn edge_escapes(source: u32, target: u32, dist_mm: u32) -> bool {
    target_delta(source, target).is_none() || dist_mm >= DIST_MM_ESCAPE
}

/// One bucket per possible bit width of `|target - source|`, from a zero delta up
/// to a full `u32`. A named type only because arrays this long do not derive
/// `Default`.
pub struct DeltaBits([u64; 33]);

impl Default for DeltaBits {
    fn default() -> DeltaBits {
        DeltaBits([0u64; 33])
    }
}

impl std::ops::Deref for DeltaBits {
    type Target = [u64; 33];
    fn deref(&self) -> &[u64; 33] {
        &self.0
    }
}

impl std::ops::DerefMut for DeltaBits {
    fn deref_mut(&mut self) -> &mut [u64; 33] {
        &mut self.0
    }
}

/// What a narrower `edges.bin` record would cost, measured by the encoder itself
/// rather than by a script that might disagree with it by one.
///
/// Reported by `road_graph --stats`. Everything here is a *property of one
/// extract*, and the design that consumes it extrapolates to a planet 22x larger,
/// so the histogram matters more than any single rate: for nodes ordered by a
/// space-filling curve the tail should decay as `P(|delta| > n) ~ c * n^(-1/2)`,
/// and `c` is what transfers between extracts, not the escape rate itself.
#[derive(Default)]
pub struct Census {
    /// `delta_bits[b]` counts edges needing exactly `b` bits to be exceeded, i.e.
    /// for which `b` is the number of `k` with `2^k < |target - source|`. Suffix
    /// sums of it are the tail counts; see [`Census::delta_tail`].
    pub delta_bits: DeltaBits,
    /// Edges whose `|target - source|` will not fit an `i16`.
    pub delta_escapes: u64,
    /// Edges whose `dist_mm` reaches [`DIST_MM_ESCAPE`].
    pub dist_escapes: u64,
    /// Edges either sentinel forces into the escape table. Not the sum of the two
    /// above: an edge can fail both tests and still be one row.
    pub escapes: u64,
    pub max_delta: u32,
    pub max_dist_mm: u32,
    /// Largest out-degree of any node, which bounds a per-node `u8 degree` field.
    pub max_degree: u32,
    /// Nodes with no outgoing edge. These make several nodes share an `edge_ptr`,
    /// which is what makes `find_node_idx_for_edge`'s tie-break observable.
    pub degree_zero_nodes: u64,
    pub named_edges: u64,
}

impl Census {
    /// Edges whose `|target - source|` exceeds `1 << k`, for `k` in `0..32`.
    pub fn delta_tail(&self, k: u32) -> u64 {
        self.delta_bits[(k as usize + 1).min(32)..].iter().sum()
    }

    /// Record one directed edge.
    #[inline]
    fn edge(&mut self, source: u32, target: u32, dist_mm: u32, named: bool) {
        let delta = source.abs_diff(target);
        // The bucket is the count of `k` with `2^k < delta`, so a plain suffix sum
        // turns the histogram into the tail without a per-edge loop.
        let bits = if delta == 0 { 0 } else { 32 - (delta - 1).leading_zeros() };
        self.delta_bits[bits as usize] += 1;
        self.max_delta = self.max_delta.max(delta);
        self.max_dist_mm = self.max_dist_mm.max(dist_mm);
        if target_delta(source, target).is_none() {
            self.delta_escapes += 1;
        }
        if dist_mm >= DIST_MM_ESCAPE {
            self.dist_escapes += 1;
        }
        if edge_escapes(source, target, dist_mm) {
            self.escapes += 1;
        }
        if named {
            self.named_edges += 1;
        }
    }

    #[inline]
    fn node(&mut self, degree: u32) {
        self.max_degree = self.max_degree.max(degree);
        if degree == 0 {
            self.degree_zero_nodes += 1;
        }
    }
}

pub struct Stats {
    pub node_count: u64,
    pub edge_count: u64,
    pub unique_names: usize,
    pub name_bytes: u32,
    pub lcc_size: u64,
    /// Stops left alone because their own component is already routable.
    pub stops_already_connected: usize,
    pub reconnected_stops: usize,
    /// Stops that needed reconnecting and found no routable node in range.
    pub stops_unreachable: usize,
    /// Nodes before compaction, i.e. one per OSM geometry vertex.
    pub raw_node_count: u64,
    /// Directed edges an uncompacted build would have produced.
    pub raw_edge_count: u64,
    /// Chains cut because their geometry would not fit a single edge.
    pub chain_splits: usize,
    /// Edges carrying a stored polyline.
    pub geometry_edges: u64,
    /// Edges deferring to their twin's polyline.
    pub reversed_edges: u64,
    pub intermediate_bytes: u64,
    pub edges_bytes: u64,
    /// Edges whose `target` or `dist_mm` needed the escape table.
    pub escape_count: u64,
    /// Edges with a name at all, i.e. the length of the sparse name table.
    pub named_edges: u64,
    /// What a narrower record would cost. Always collected — it is a handful of
    /// adds per edge — and reported by `--stats`.
    pub census: Census,
}

/// Bytes per rank block. 64 bytes is 512 bits and one cache line, so a rank
/// costs one `u64` load plus at most eight `popcount`s while the index itself is
/// only 1/64th of the bitset it indexes.
///
/// Both the in-memory [`Bitset`] and `intermediate.bin`'s on-disk presence bitmap
/// use it, and for the latter it is an on-disk contract with `graph.rs`.
const RANK_BLOCK_BYTES: usize = 64;

/// Bits \u2014 for `intermediate.bin`, directed edges \u2014 one rank block covers.
const RANK_BLOCK_BITS: u64 = RANK_BLOCK_BYTES as u64 * 8;

/// A plain (non-atomic) bitset, optionally carrying a rank index.
///
/// Bits are set from a single thread during the merge and only read afterwards,
/// so no atomics are needed.
///
/// [`Bitset::build_rank`] turns it into a *dense addressing scheme*: the node
/// array becomes implicitly ordered by OSM id, with a node's index being the
/// number of set bits before its own. That is what removes the sorted
/// `(osm_id, local_id)` side table the edge pass used to binary-search, and with
/// it the need to store an `osm_id` per node at all.
pub(crate) struct Bitset {
    bits: Vec<u8>,
    /// Set bits before each [`RANK_BLOCK_BYTES`] block, with a total appended.
    /// Empty until [`Bitset::build_rank`].
    rank: Vec<u64>,
}

impl Bitset {
    pub(crate) fn new(size_bits: u64) -> Bitset {
        Bitset {
            bits: vec![0u8; (size_bits / 8 + 1) as usize],
            rank: Vec::new(),
        }
    }

    /// Returns true when this call is what set the bit.
    pub(crate) fn set(&mut self, idx: u64) -> bool {
        let byte = &mut self.bits[(idx / 8) as usize];
        let mask = 1u8 << (idx % 8);
        let was = *byte & mask != 0;
        *byte |= mask;
        !was
    }

    pub(crate) fn get(&self, idx: u64) -> bool {
        self.bits[(idx / 8) as usize] & (1u8 << (idx % 8)) != 0
    }

    /// A shared, atomic view of the bits for marking from many threads at once.
    ///
    /// Setting bits is a set union, which is commutative and idempotent, so the
    /// final bits — and the count of bits that went from clear to set, which
    /// [`AtomicBits::set`] reports exactly once per bit — are independent of
    /// which thread got there first. That is what lets the PBF passes mark from
    /// their decode workers instead of a single-threaded sink.
    pub(crate) fn atomic(&mut self) -> AtomicBits<'_> {
        let bytes: &mut [u8] = &mut self.bits;
        // SAFETY: `AtomicU8` has the size, alignment and bit validity of `u8`,
        // and the exclusive borrow of `self.bits` is held for the view's
        // lifetime, so no plain access can race with the atomic ones.
        AtomicBits(unsafe { &*(bytes as *mut [u8] as *const [std::sync::atomic::AtomicU8]) })
    }

    /// Index the blocks covering ids up to and including `max_id`, returning the
    /// total number of set bits.
    ///
    /// Sized from the largest id actually seen rather than from the bitset's
    /// nominal width: `BITSET_SIZE` is 20 G ids, and indexing all of it would
    /// cost 313 MB to describe blocks no id reaches. `bits` itself is left at
    /// full width, because [`Bitset::get`] is still asked about ids past the
    /// largest marked one.
    fn build_rank(&mut self, max_id: u64) -> u64 {
        let used = (max_id / 8 + 1) as usize;
        let blocks = used.div_ceil(RANK_BLOCK_BYTES);
        // Per-block popcounts on the pool, then one serial prefix sum: the
        // counts are integers, so the result cannot depend on the split.
        let bits = &self.bits[..used];
        let mut rank: Vec<u64> = par::install(|| {
            bits.par_chunks(RANK_BLOCK_BYTES)
                .map(|c| c.iter().map(|b| u64::from(b.count_ones())).sum::<u64>())
                .collect()
        });
        debug_assert_eq!(rank.len(), blocks);
        let mut total = 0u64;
        for r in rank.iter_mut() {
            let n = *r;
            *r = total;
            total += n;
        }
        rank.push(total);
        self.rank = rank;
        total
    }

    /// How many set bits come before `idx`. For a *set* bit that is its position
    /// in the sequence of set bits, i.e. its dense id.
    ///
    /// Meaningless for a clear bit: it returns the id of whichever node comes
    /// next, so callers must test [`Bitset::get`] first or they will silently
    /// alias two nodes together.
    #[inline]
    pub(crate) fn dense(&self, idx: u64) -> u32 {
        let byte = (idx / 8) as usize;
        let block = byte / RANK_BLOCK_BYTES;
        let start = block * RANK_BLOCK_BYTES;
        let whole: u32 = self.bits[start..byte].iter().map(|b| b.count_ones()).sum();
        let below = (self.bits[byte] & ((1u8 << (idx % 8)) - 1)).count_ones();
        let n = self.rank[block] + u64::from(whole) + u64::from(below);
        debug_assert!(n <= u64::from(u32::MAX), "dense id {n} does not fit a u32");
        n as u32
    }
}

/// See [`Bitset::atomic`].
pub(crate) struct AtomicBits<'a>(&'a [std::sync::atomic::AtomicU8]);

impl AtomicBits<'_> {
    /// Set bit `idx`; true when this call is the one that set it. A plain load
    /// first, so re-marking an already-set bit (most refs: a node is shared by
    /// every way through it) never takes the cache line exclusive.
    #[inline]
    pub(crate) fn set(&self, idx: u64) -> bool {
        use std::sync::atomic::Ordering::Relaxed;
        let byte = &self.0[(idx / 8) as usize];
        let mask = 1u8 << (idx % 8);
        if byte.load(Relaxed) & mask != 0 {
            return false;
        }
        byte.fetch_or(mask, Relaxed) & mask == 0
    }
}

/// Dense addressing for the graph's nodes: which OSM ids are in the graph, and
/// which of those the file actually supplied coordinates for.
///
/// Both tests matter, and for different reasons. The mask is what makes
/// [`Bitset::dense`] meaningful. `present` is what keeps *dangling references*
/// out: pass 1 marks every ref of every routable way, including refs to nodes no
/// blob in the file defines — an extract clipped at a boundary is full of them —
/// and those slots never receive coordinates. Left in, each would read as
/// `(0, 0)`, a point in the Atlantic that corrupts distances, spatial keys and
/// polylines while leaving every count looking plausible.
pub(crate) struct NodeIndex {
    pub(crate) mask: Bitset,
    present: Bitset,
}

impl NodeIndex {
    /// Dense id of a node that is both in the graph and really in the file.
    #[inline]
    pub(crate) fn dense(&self, osm_id: i64) -> Option<u32> {
        if osm_id < 0 || osm_id as u64 >= BITSET_SIZE || !self.mask.get(osm_id as u64) {
            return None;
        }
        let d = self.mask.dense(osm_id as u64);
        self.present.get(u64::from(d)).then_some(d)
    }
}

#[derive(Default)]
struct Pass1 {
    /// Ids this chunk was the first to mark. Refs and stops are marked straight
    /// into the shared mask from the decode workers (a set union, so order
    /// cannot matter); only these two scalars reach the sink. Nothing about a
    /// way is retained, because the ways pass re-reads them.
    marked: u64,
    /// Largest id this chunk marked or found already marked.
    max_id: u64,
}

/// A region bbox filter, or `None` for a world build.
///
/// Passed by value into the block visitors (it is `Copy`): every routable way
/// whose nodes all fall outside is dropped, and a stop outside never enters
/// the graph. Ways are kept whole when any node touches — the same
/// `complete_ways` rule [`crate::bbox`] documents — so a road straddling the
/// border keeps its boundary stub rather than ending mid-segment.
pub type RegionFilter = Option<crate::bbox::BBox>;

include!("graph_build_part1.rs");
include!("graph_build_part2.rs");
include!("graph_build_part3.rs");
include!("graph_build_part4.rs");
include!("graph_build_part5.rs");
include!("graph_build_part11.rs");
#[cfg(test)]
mod tests {
include!("graph_build_part6.rs");
include!("graph_build_part7.rs");
include!("graph_build_part8.rs");
include!("graph_build_part9.rs");
include!("graph_build_part10.rs");}
