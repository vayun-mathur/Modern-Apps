//! Whole-world routing graph: mmap loader, on-disk packed structs, derived
//! cost tables, spatial (Morton) indexing and delta-decoded edge geometry.
//!
//! Faithful port of the data model in the old `native-lib.cpp` (`NodeMaster`,
//! `Edge`, the `m_file` mmap loader in `init`, and the geometry helpers around
//! it). The graph is loaded once and is read-only afterwards, so it is shared
//! behind an `Arc` (see `lib.rs`).

#[cfg(unix)]
use std::ffi::CString;
use std::ptr;

// --- Travel modes (must match Kotlin RouteService.TravelMode.ordinal) ---
pub const DRIVING: i32 = 0;
pub const PUBLIC_TRANSIT: i32 = 1;
pub const WALK: i32 = 2;
pub const BICYCLE: i32 = 3;

// --- OSM-derived road types ---
pub const MOTORWAY: u8 = 1;
pub const LIVING_STREET: u8 = 9;
pub const STEPS: u8 = 15;

/// Set in an [`Edge::type_`] byte to mean "this edge stores no geometry of its
/// own; decode its twin's and read it backwards".
///
/// `type_` is therefore a bitfield, not a plain road class:
/// `[bits 0-5 road class][bit 6 reverse geometry][bit 7 reserved]`. Anything
/// interpreting it as a road class must mask with
/// [`crate::geometry::ROAD_TYPE_MASK`] first.
pub const REVERSE_GEOMETRY_FLAG: u8 = 0x40;

// --- OSM turn:lanes indication bits (one u16 mask per lane) ---
// Emitted per directed edge by `scripts/maps/osm_ingest` (`road_graph`) into
// `lanes.bin` and decoded here into per-lane turn-direction sets. A lane with no
// marking ("none"/empty) is stored as `LANE_NONE`. These bits are an on-disk
// contract with the generator; keep the two in sync.
pub const LANE_NONE: u16 = 1 << 0;
pub const LANE_THROUGH: u16 = 1 << 1;
pub const LANE_LEFT: u16 = 1 << 2;
pub const LANE_SLIGHT_LEFT: u16 = 1 << 3;
pub const LANE_SHARP_LEFT: u16 = 1 << 4;
pub const LANE_RIGHT: u16 = 1 << 5;
pub const LANE_SLIGHT_RIGHT: u16 = 1 << 6;
pub const LANE_SHARP_RIGHT: u16 = 1 << 7;
pub const LANE_REVERSE: u16 = 1 << 8;
pub const LANE_MERGE_TO_LEFT: u16 = 1 << 9;
pub const LANE_MERGE_TO_RIGHT: u16 = 1 << 10;

/// Sentinel for "no edge" where a u64 global edge index is expected.
pub const INVALID_EDGE: u64 = 0xFFFF_FFFF_FFFF_FFFF;

pub const WALK_SPEED_M_S: f64 = 4.5 / 3.6;
pub const BICYCLE_SPEED_M_S: f64 = 16.0 / 3.6;
/// How many times slower a motorway counts when walking or cycling. A penalty
/// rather than a ban keeps the overlay connected where a freeway shoulder is the
/// only way through, and slowing an edge never breaks the A* heuristic.
pub const ACTIVE_MOTORWAY_PENALTY: f64 = 5.0;
/// Upper bound on any driving edge's effective speed (km/h). The A* heuristic is
/// scaled to this speed AND every edge's effective speed is clamped to it in
/// [`crate::geometry::get_edge_time_10ms`], which together keep the heuristic
/// CONSISTENT (never overestimates a single edge). This is required for the
/// monotonic radix heap: if an edge could be faster than the heuristic assumes,
/// a relaxed node's f-value can fall below the last popped key, the heap
/// mis-buckets it, and A* terminates on a suboptimal path (the "detours the
/// wrong way / no route" bug). 130 km/h ≈ 80 mph covers every real US limit.
pub const MAX_DRIVING_KMH: f64 = 130.0;
pub const DEG_TO_RAD: f64 = std::f64::consts::PI / 180.0;

// --- metadata.bin header ---
/// `"MARG"` (Modern-Apps Road Graph), little-endian. Added when the duplicated
/// per-OSM-node transit nodes were removed: that changed `node_count` and
/// `edge_count`, so a pack directory holding files from two vintages reads
/// garbage. The magic + version make that a clean rejection instead.
pub use tilecodec::mamaps::archive::GRAPH_MAGIC;
/// Version 2 narrowed `nodes.bin` to 12-byte records, made `lanes.bin` a sparse
/// index and gave `intermediate.bin` two-level offsets — 50.8 GB of planet became
/// 34 GB. Version 3 gave `intermediate.bin` a presence bitmap and dropped both
/// endpoints of every stored polyline, taking it to ~29 GB. Version 4 changes no
/// polyline. Version 4 changes no
/// layout at all: it adds `edge_count` to `metadata.bin` so that nothing is
/// derived from a file's *length*, which is what the next round of record
/// narrowing would silently invalidate. Version 5 is that narrowing: an
/// `edges.bin` record is 11 bytes rather than 14, taking planet to ~25.8 GB.
/// Version 6 moves `name_offset` out of the record into a sparse side table, since
/// two thirds of edges are unnamed, taking the record to 7 bytes and planet to
/// ~23.3 GB. There is no dual-path reader: an older pack is rejected.
/// Version 7 blocked-compresses `nodes.bin`, `edges.bin` records and
/// `elevation.bin` (ZBLK sections); readers accept 6 and 7, inflating v7
/// sections once on first use.
///
/// Re-exported from tilecodec (with [`GRAPH_VERSION_MIN`], v6: raw tables still read
/// directly) so the app, the archive validator and the writer share one range.
pub use tilecodec::mamaps::archive::{GRAPH_VERSION, GRAPH_VERSION_MIN};

/// Sentinel `name_offset` meaning "this edge has no name".
///
/// `NO_NAME` in the generator, and an on-disk contract with it. Callers should
/// prefer [`Graph::edge_name_offset`], which spells absence as `None`; this exists
/// for the places that have to put a `u32` in a struct crossing the JNI boundary.
pub const NO_NAME: u32 = 0xFFFF_FFFF;

/// Sentinel in an [`EdgeRec`]'s `target_delta` meaning "this edge's `target` and
/// `dist_mm` are both in the escape table".
///
/// Reserved rather than used, so the representable delta range is symmetric at
/// ±32767.
const TARGET_DELTA_ESCAPE: i16 = i16::MIN;

/// Sentinel in an [`EdgeRec`]'s `u24 dist_mm` meaning the same thing.
///
/// # Why one escape table and not two
///
/// Either sentinel means *both* fields come from the escape row, so one branch in
/// the A* relaxation covers both. Two sentinels with two tables would cost two
/// branches, two metadata fields and two sets of off-by-one risk to keep a 39 MB
/// table apart from an 85 KB one.
///
/// Merging also makes the `dist_mm` escape rate irrelevant to the design, which
/// matters because it is the *least* transferable number available: California has
/// 166 edges past 16.78 km, and collapsed chains in Siberia or the Australian
/// Outback will be far longer. A hundredfold miss just rides along in the same
/// table.
const DIST_MM_ESCAPE: u32 = 0xFF_FFFF;

/// Directed edges per entry of `edges.bin`'s escape block index.
///
/// # Why a block index and not a binary search
///
/// The inline sentinel already *is* the presence bit — it is in a register that was
/// just loaded — so a v3-style presence bitmap would spend 134 MB duplicating it.
/// All that is missing is edge index → row, and `u32 escape_first[E/1024 + 1]` is
/// 4.2 MB at planet scale: one load from a largely cache-resident array, then a scan
/// of the ~3 rows a block holds at a 0.3% escape rate, usually one cache line. A
/// binary search over 3.24 M rows would be 12–15 misses instead.
///
/// `lanes.bin` does binary-search its sparse index, and its doc comment justifies
/// that by being "called once per maneuver rather than per edge". `target` is read
/// on every relaxation, so that precedent does not transfer.
pub const ESCAPE_BLOCK: u64 = 1024;

/// Alignment every section of a multi-section file starts on.
///
/// Eight rather than four so that a section's first entry is naturally aligned
/// whatever its width, and so that section offsets do not depend on `E mod 8`.
const SECTION_ALIGN: u64 = 8;

/// Round `n` up to the next multiple of `align`, which must be a power of two.
#[inline]
const fn align_up(n: u64, align: u64) -> u64 {
    (n + align - 1) & !(align - 1)
}

/// Geometry edges per coarse block in `intermediate.bin`'s two-level offset table.
///
/// A per-edge blob is at most 1016 bytes (4 per interior point, capped at
/// `MAX_POINTS - 2` = 254 of them by [`Graph::decode_edge_coords`]), so a block
/// spans at most 32 x 1016 = 32,512 bytes and the within-block half fits a `u16`.
/// That bound is the invariant the whole layout rests on; the generator asserts
/// it. The table is indexed by an edge's rank in the presence bitmap, so every
/// entry in a block describes a real blob.
pub const INTERMEDIATE_BLOCK: u64 = 32;

/// Geometry edges per coarse block in a v8 (planet5) `intermediate.bin`. Half of
/// [`INTERMEDIATE_BLOCK`] because a varint blob is variable length: at most 254 interior points
/// x 2 varints x 5 bytes = 2540 bytes, and 16 x 2540 = 40,640 still fits the `u16` within-table.
pub const INTERMEDIATE_BLOCK_V8: u64 = 16;

/// Bytes of presence bitmap one entry of a rank index covers.
///
/// 64 bytes is 512 edges and one cache line, so a rank costs one `u64` load plus at
/// most eight `popcount`s, while the index is a 64th of the bitmap it indexes.
/// `RANK_BLOCK_BYTES` in the generator, and the two must agree.
pub const RANK_BLOCK_BYTES: u64 = 64;

/// A presence bitmap over the directed edges, plus a rank index over it.
///
/// Answers two questions: does edge `idx` have one of these, and if so which one is
/// it. That is what makes a per-edge field sparse — the edges without one own no
/// bytes at all — and it is used twice: for `intermediate.bin`'s stored polylines
/// (70.4% of a planet's edges store none) and for `edges.bin`'s names (62.3% to 68.9%
/// are unnamed, and the fraction *falls* with scale). One type rather than two
/// transcriptions of the same eight lines.
///
/// ```text
/// [ u64 rank[E.div_ceil(512) + 1] ]   set bits before each 64-byte block, total appended
/// [ u8  present[E.div_ceil(8)] ]      one bit per directed edge
/// ```
#[derive(Clone, Copy)]
struct EdgeBitmap {
    rank: *const u8,
    present: *const u8,
}

impl EdgeBitmap {
    /// Byte size of both tables for `edge_count` directed edges. The generator sizes
    /// them the same way.
    const fn bytes(edge_count: u64) -> u64 {
        (edge_count.div_ceil(RANK_BLOCK_BYTES * 8) + 1) * std::mem::size_of::<u64>() as u64
            + edge_count.div_ceil(8)
    }

    /// Offset of `present` within the pair, i.e. the size of `rank` alone.
    const fn present_offset(edge_count: u64) -> u64 {
        (edge_count.div_ceil(RANK_BLOCK_BYTES * 8) + 1) * std::mem::size_of::<u64>() as u64
    }

    /// True when edge `idx` has one.
    #[inline]
    fn contains(&self, idx: u64) -> bool {
        let byte = unsafe { read_at::<u8>(self.present, (idx / 8) as usize) };
        byte & (1u8 << (idx % 8)) != 0
    }

    /// How many edges below `idx` have one, i.e. the side-table index of edge `idx`
    /// itself when its own bit is set.
    ///
    /// Only meaningful for an edge whose bit *is* set, so callers must test
    /// [`EdgeBitmap::contains`] first or they will read another edge's entry.
    #[inline]
    fn rank_of(&self, idx: u64) -> u64 {
        let byte = idx / 8;
        let block = byte / RANK_BLOCK_BYTES;
        let mut n = unsafe { read_at::<u64>(self.rank, block as usize) };
        for b in block * RANK_BLOCK_BYTES..byte {
            n += u64::from(unsafe { read_at::<u8>(self.present, b as usize) }.count_ones());
        }
        let partial = unsafe { read_at::<u8>(self.present, byte as usize) };
        n + u64::from((partial & ((1u8 << (idx % 8)) - 1)).count_ones())
    }

    /// The rank index's appended total, which must equal the side table's length.
    /// Cheap, and it ties the index to the table it indexes rather than trusting both
    /// to have come from the same run.
    #[inline]
    fn total(&self, edge_count: u64) -> u64 {
        unsafe { read_at::<u64>(self.rank, edge_count.div_ceil(RANK_BLOCK_BYTES * 8) as usize) }
    }
}

// --- On-disk packed structs ---
// `#[repr(C, packed)]` reproduces the exact byte strides the generator writes
// (notably `EdgeRec` is 14 bytes, not the 16 a naturally-aligned layout would
// give). All reads go through `read_unaligned`, so element pointers need no
// alignment. The `const _` block below asserts every stride at compile time.

/// A `nodes.bin` record: 12 bytes, with `edge_ptr` narrowed to a `u32`.
///
/// Planet has 1.07 G directed edges, which leaves 4x headroom, and the four bytes
/// saved per node are 1.7 GB. [`Graph::node`] widens it into [`NodeMaster`], so
/// nothing above this file sees the narrower field.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct NodeRec {
    lat_e7: i32,
    lon_e7: i32,
    edge_ptr: u32,
}

/// One `lanes.bin` index entry: the edge that carries lanes, and where its masks
/// start in the blob.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct LaneEntry {
    edge_idx: u32,
    blob_off: u32,
}

/// An `edges.bin` record as the generator writes it: 11 bytes.
///
/// Private, so a layout change stays inside this file. [`Graph::edge`] widens it
/// into [`Edge`].
///
/// `target` is stored as a signed delta from the edge's own source rather than
/// absolutely, which is only affordable because the nodes are sorted by a
/// space-filling curve and an edge is short: measured on California, 99.699% of
/// deltas fit an `i16`. `dist_mm` is a `u24`, which is exact for anything under
/// 16.78 km — 99.999% of edges. The rest escape; see [`TARGET_DELTA_ESCAPE`].
///
/// `name_offset` is *not* a field. Two thirds of edges are unnamed — 62.3% of
/// California's, 68.9% of Europe's — so it lives in a sparse side table reached
/// through [`Graph::edge_name_offset`], and the four bytes it used to cost every
/// edge are now four bytes for the third that have one. It is read on the
/// instruction-emission path and never in the A* relaxation, which is what makes
/// three loads instead of one affordable there and not here.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct EdgeRec {
    target_delta: i16,
    /// `dist_mm` as three little-endian bytes. Assembled by [`EdgeRec::dist_mm`]
    /// rather than by loading a `u32` and masking, so the read can never run past
    /// the end of the array.
    dist: [u8; 3],
    type_: u8,
    speed_limit: u8,
}

impl EdgeRec {
    #[inline]
    fn dist_mm(&self) -> u32 {
        let d = self.dist;
        u32::from_le_bytes([d[0], d[1], d[2], 0])
    }

    /// True when `target` and `dist_mm` both live in the escape table.
    #[inline]
    fn escaped(&self) -> bool {
        // Read into a local first: `self.target_delta` is a field of a packed
        // struct, so taking a reference to it is not allowed.
        let delta = self.target_delta;
        delta == TARGET_DELTA_ESCAPE || self.dist_mm() == DIST_MM_ESCAPE
    }
}

/// One row of `edges.bin`'s escape table: the fields an 11-byte record could not
/// hold, for the 0.3% of edges that need them. Ascending by `edge_idx`.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct EscapeRow {
    edge_idx: u32,
    target: u32,
    dist_mm: u32,
}

/// The in-memory view of an `edges.bin` record, the same widening
/// [`NodeRec`]/[`NodeMaster`] uses.
///
/// Nothing above [`Graph::edge`] sees [`EdgeRec`], so the on-disk record is free to
/// narrow a field, delta-encode it against the edge's source or move it into a
/// sparse side table without any call site changing. That is the whole reason the
/// two types are separate: the alternative is editing the same dozen call sites
/// once per layout change.
///
/// `name_offset` is deliberately *not* here. It is read on the instruction-emission
/// path and never in the A* relaxation, so it belongs in
/// [`Graph::edge_name_offset`] where a sparse representation costs nothing;
/// including it in the view would put every future reader of that field back on
/// the hot path.
#[derive(Clone, Copy)]
pub struct Edge {
    pub target: u32,
    pub dist_mm: u32,
    pub type_: u8,
    pub speed_limit: u8,
}

/// The in-memory view of a `nodes.bin` record, with `edge_ptr` widened back to a
/// `u64` so nothing above [`Graph::node`] cares that the file stores 32 bits.
#[derive(Clone, Copy)]
pub struct NodeMaster {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub edge_ptr: u64,
}

#[derive(Clone, Copy)]
pub struct LatLon {
    pub lat_e7: i32,
    pub lon_e7: i32,
}

const _: () = {
    // Compile-time assertions that the fixed on-disk record sizes match the
    // writer. `#[repr(C, packed)]` is what makes them what they are; without these
    // a field reordered or widened by one byte changes every stride in the file and
    // still compiles, and the resulting pack loads and routes wrongly. The length
    // validation in `Graph::load` derives from these, so they are also what makes
    // that validation mean anything.
    assert!(std::mem::size_of::<NodeRec>() == 12);
    assert!(std::mem::size_of::<EdgeRec>() == 7);
    assert!(std::mem::size_of::<EscapeRow>() == 12);
    assert!(std::mem::size_of::<LaneEntry>() == 8);
};

/// Owns a read-only `mmap` region and unmaps it on drop.
pub(crate) struct MmapRegion {
    ptr: *mut libc::c_void,
    pub(crate) len: usize,
}

// The graph is only ever read after init, so sharing the raw pointer across
// threads is sound.
unsafe impl Send for MmapRegion {}
unsafe impl Sync for MmapRegion {}

impl MmapRegion {
    /// mmap `path` read-only. Returns `None` for missing/empty/unreadable files,
    /// mirroring the C++ `m_file` lambda.
    #[cfg(unix)]
    pub(crate) fn map(path: &str) -> Option<MmapRegion> {
        let c = CString::new(path).ok()?;
        unsafe {
            let fd = libc::open(c.as_ptr(), libc::O_RDONLY);
            if fd < 0 {
                return None;
            }
            let end = libc::lseek(fd, 0, libc::SEEK_END);
            if end <= 0 {
                libc::close(fd);
                return None;
            }
            let len = end as usize;
            let ptr = libc::mmap(
                ptr::null_mut(),
                len,
                libc::PROT_READ,
                libc::MAP_SHARED,
                fd,
                0,
            );
            libc::close(fd);
            if ptr == libc::MAP_FAILED {
                return None;
            }
            Some(MmapRegion { ptr, len })
        }
    }

    /// This crate ships to Android, so the real loader is Unix-only. The stub
    /// exists purely so a host `cargo test` can build and exercise the parts
    /// that don't need a pack file (the in-memory transit index).
    #[cfg(not(unix))]
    pub(crate) fn map(_path: &str) -> Option<MmapRegion> {
        None
    }

    #[inline]
    pub(crate) fn base(&self) -> *const u8 {
        self.ptr as *const u8
    }
}

#[cfg(unix)]
impl Drop for MmapRegion {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.ptr, self.len);
        }
    }
}

/// Read a `Copy` value of type `T` from `base` at element index `idx`
/// (byte offset `idx * size_of::<T>()`), tolerating any alignment.
#[inline(always)]
pub(crate) unsafe fn read_at<T: Copy>(base: *const u8, idx: usize) -> T {
    (base.add(idx * std::mem::size_of::<T>()) as *const T).read_unaligned()
}

include!("graph_part1.rs");
include!("graph_part2.rs");
include!("graph_part2b.rs");
include!("graph_part3.rs");
include!("graph_part4.rs");
include!("graph_part4b.rs");
include!("graph_part5.rs");
include!("graph_overlay.rs");