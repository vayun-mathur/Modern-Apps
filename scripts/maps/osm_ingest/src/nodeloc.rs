//! Coordinates for a known set of node ids, and the PBF pass that fills them.
//!
//! Split out of [`crate::extract`] so the tile generators can share it: resolving
//! way geometry is the one thing every layer build does, and it is the one place
//! where a careless data structure costs gigabytes.

use std::path::{Path, PathBuf};
use std::sync::atomic;

use crate::geojson::Coord;
use crate::osm::{visit_block, Element, NodeView};
use crate::pbf::{self, KIND_NODES};
use crate::proto::{Error, Result};

/// Sentinel latitude for "this node's location was never seen".
/// Coordinates for a known set of node ids: a compressed id index plus an
/// index-aligned coordinate array.
///
/// **This, and not [`crate::graph_build`]'s bitset.** That module allocates
/// `BITSET_SIZE = 20e9` bits -- 2.5 GB keyed by raw OSM node id, whatever the
/// extract -- and peaks around 10 GB on California. This form costs per *needed*
/// node, so a layer that wants a few hundred thousand nodes pays megabytes. Same
/// pattern as [`crate::poi_build`], which worked it out first.
///
/// # Why the ids are compressed
///
/// A sorted `Vec<i64>` is the obvious id table and it was the first one here. On a California
/// extract it is 154 M ids, so 1.23 GB -- and next to an equally large coordinate array it made this
/// structure about 73% of the whole generator's peak. The ids are sorted and dense enough that the
/// gaps between them are tiny: they are a *subset* of a PBF's node ids, but a large one, so the
/// average gap is a couple of units and encodes in a single byte. [`IdIndex`] stores one full id per
/// block of [`BLOCK`] and varint gaps for the rest, which is about 1.2 bytes per id instead of 8.
///
/// Lookup did not get slower for it, and probably got faster. A binary search over 1.23 GB touches
/// ~27 scattered cache lines and misses on most of them; this searches a 19 MB base array and then
/// scans at most 63 contiguous bytes, which is one or two lines.
pub struct NodeLocations {
    ids: NodeIds,
    locs: Locs,
}

/// The coordinate array, in a memory-mapped file rather than on the heap.
///
/// Eight bytes per needed node is 1.23 GB on a California extract, and unlike the ids it will not
/// compress: latitude and longitude in id order are high-entropy, so there is no structure to exploit.
/// What there is instead is locality — materialisation walks ways in id order and a way's nodes sit
/// close together in a PBF — so a mapped file lets the OS keep the working set and nothing else.
///
/// # Resolved-ness is a bitset, not a sentinel
///
/// A fresh mapping reads as zeroes, and `(0, 0)` is a real coordinate, so zero cannot mean
/// "unresolved". Writing `i32::MIN` across the whole file would work and would also dirty every page
/// of 1.23 GB before the pass that fills it, which is most of the cost this exists to avoid. A bit per
/// node is 19 MB, stays in memory, and leaves the mapping untouched until something is actually
/// written to it.
struct Locs {
    map: Option<memmap2::MmapMut>,
    /// One bit per node, set when a coordinate has been written.
    resolved: Vec<u64>,
    /// Deleted on drop. Scratch, and large enough that leaving it behind would matter.
    path: Option<PathBuf>,
    len: usize,
}

impl Locs {
    fn new(len: usize) -> Result<Locs> {
        if len == 0 {
            return Ok(Locs { map: None, resolved: Vec::new(), path: None, len: 0 });
        }
        let path = std::env::temp_dir().join(format!(
            "osm_nodeloc_{}_{}.bin",
            std::process::id(),
            // Distinct per table, so two in one process cannot share a file.
            NEXT_LOCS.fetch_add(1, atomic::Ordering::Relaxed),
        ));
        let bytes = len as u64 * 8;
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        // Uncompressed on a `compress-force` btrfs: random 8-byte reads through the mapping
        // must not inflate a 128 KiB extent per page fault, and coordinates do not compress
        // anyway. Must precede `set_len`/any data; see `mark_scratch_uncompressed`.
        crate::mem::mark_scratch_uncompressed(&file);
        // Sets the length without writing anything: on both platforms this leaves a sparse or
        // zero-filled file, so the 1.23 GB is not touched until a page is used.
        file.set_len(bytes)
            .map_err(|e| Error(format!("cannot size {}: {e}", path.display())))?;
        // SAFETY: this process owns `path` exclusively — it is named after the pid and a counter, was
        // just created with `truncate`, and is deleted in `Drop`. Nothing else can resize it while the
        // mapping is live, which is the one thing that would make the slice dangle.
        let map = unsafe { memmap2::MmapMut::map_mut(&file) }
            .map_err(|e| Error(format!("cannot map {}: {e}", path.display())))?;
        Ok(Locs {
            map: Some(map),
            resolved: vec![0u64; len.div_ceil(64)],
            path: Some(path),
            len,
        })
    }

    fn set(&mut self, index: usize, lat_e7: i32, lon_e7: i32) {
        let Some(map) = self.map.as_mut() else { return };
        let at = index * 8;
        map[at..at + 4].copy_from_slice(&lat_e7.to_le_bytes());
        map[at + 4..at + 8].copy_from_slice(&lon_e7.to_le_bytes());
        self.resolved[index / 64] |= 1 << (index % 64);
    }

    fn is_resolved(&self, index: usize) -> bool {
        index < self.len && self.resolved[index / 64] & (1 << (index % 64)) != 0
    }

    fn get(&self, index: usize) -> Option<(i32, i32)> {
        if index >= self.len || self.resolved[index / 64] & (1 << (index % 64)) == 0 {
            return None;
        }
        let map = self.map.as_ref()?;
        let at = index * 8;
        let lat = i32::from_le_bytes(map[at..at + 4].try_into().expect("four bytes"));
        let lon = i32::from_le_bytes(map[at + 4..at + 8].try_into().expect("four bytes"));
        Some((lat, lon))
    }
}

impl Drop for Locs {
    fn drop(&mut self) {
        // The mapping goes before the file, or Windows refuses the delete.
        self.map = None;
        if let Some(path) = self.path.take() {
            let _ = std::fs::remove_file(path);
        }
    }
}

static NEXT_LOCS: atomic::AtomicU64 = atomic::AtomicU64::new(0);

/// Ids per block of [`IdIndex`].
///
/// The trade is the usual one: a larger block amortises its 16 bytes of base and offset over more
/// ids, and lengthens the scan that follows the binary search. 64 puts the overhead at 0.25 bytes per
/// id and keeps the worst-case scan inside a couple of cache lines.
const BLOCK: usize = 64;

/// A sorted, unique id set as block bases plus varint gaps.
///
/// Searchable through `&self` alone, because [`resolve_nodes`] hands it to every worker in a PBF pass
/// while the sink writes coordinates.
///
/// # When this is used, and when [`RankIndex`] is instead
///
/// This compressed form costs `O(count)` -- ~1.2 bytes per *needed* id -- so it is the right shape
/// for a sub-continent extract, where the needed set is a sparse subset of a 13 G id space and a
/// bitset over that space would dwarf it. [`NodeLocations::new`] builds it. The planet path
/// ([`NodeLocations::from_sorted`], fed by a caller-side bitset) builds a [`RankIndex`] instead: at
/// planet scale the needed set is most of the id space, so a rank-select bitset that costs
/// `O(id space)` -- ~2 GB, bounded -- beats this index's ~12.9 GB, and its `find` is `O(1)` rather
/// than a binary search plus a varint scan. [`NodeIds`] is the switch between the two.
#[derive(Default)]
struct IdIndex {
    /// The first id of each block, ascending. What the binary search runs over.
    bases: Vec<i64>,
    /// Where each block's gaps start in `deltas`, with a sentinel so `offsets[b + 1]` is always the
    /// end of block `b`.
    ///
    /// `u64`, not `u32`. It was `u32` behind a guard that errored when `deltas` outgrew 4.29 GB, and
    /// at ~1.1 bytes per id that guard fires at roughly 3.9 G ids -- reached by a planet extract's
    /// 9.5 G. Widening costs 4 bytes per block of 64 ids, which is 2.38 GB against 1.19 GB at planet
    /// scale and noise against what the bitset in [`crate::extract`]'s caller saves.
    offsets: Vec<u64>,
    /// `BLOCK - 1` varint gaps per block, each the difference from the previous id.
    deltas: Vec<u8>,
    len: usize,
}

impl IdIndex {
    /// Build from ids that are already sorted and unique.
    ///
    /// Infallible, and it was not. Two guards used to live here, one on the id count and one on the
    /// delta bytes, because `find` returned a `u32` position and `offsets` held `u32` byte offsets --
    /// and their own doc comment predicted that "both are within reach at planet scale and both fail
    /// silently". They were right and they were reached, so the types were widened instead. Both
    /// ceilings are now `u64`, which no extract of this planet can approach.
    fn build(ids: &[i64]) -> IdIndex {
        IdIndex::build_sorted(ids.iter().copied(), ids.len())
    }

    /// Build from an ascending, unique id **stream** of known length.
    ///
    /// The streaming form exists because a planet extract cannot afford the slice. [`crate::extract`]
    /// gets its ids by walking a bitset over the node id space, and materialising them first would
    /// be 8 bytes an id -- the very vector the bitset replaced.
    ///
    /// `len` sizes the three arenas up front and is not otherwise trusted: the index's own length is
    /// what the stream actually yielded.
    fn build_sorted(ids: impl Iterator<Item = i64>, len: usize) -> IdIndex {
        let blocks = len.div_ceil(BLOCK);
        let mut index = IdIndex {
            bases: Vec::with_capacity(blocks),
            offsets: Vec::with_capacity(blocks + 1),
            // One byte per gap is the common case, so this is the right first guess.
            deltas: Vec::with_capacity(len),
            len: 0,
        };
        let mut in_block = 0usize;
        let mut previous = 0i64;
        for id in ids {
            if in_block == 0 {
                index.bases.push(id);
                index.offsets.push(index.deltas.len() as u64);
            } else {
                // Non-negative because the input is sorted and unique, so the gap is at least one.
                put_uvarint((id - previous) as u64, &mut index.deltas);
            }
            previous = id;
            index.len += 1;
            in_block = (in_block + 1) % BLOCK;
        }
        index.offsets.push(index.deltas.len() as u64);
        index.deltas.shrink_to_fit();
        index
    }

    fn len(&self) -> usize {
        self.len
    }

    /// The position of `id`, or `None` if it is not in the set.
    fn find(&self, id: i64) -> Option<u64> {
        let block = match self.bases.binary_search(&id) {
            // A base is an id, so this is a hit on the first of a block.
            Ok(block) => return Some((block * BLOCK) as u64),
            // Before the first id in the set.
            Err(0) => return None,
            Err(next) => next - 1,
        };
        let mut current = self.bases[block];
        let mut at = self.offsets[block] as usize;
        let end = self.offsets[block + 1] as usize;
        let mut position = 0usize;
        while at < end {
            let (gap, used) = get_uvarint(&self.deltas[at..]);
            at += used;
            current += gap as i64;
            position += 1;
            if current == id {
                return Some((block * BLOCK + position) as u64);
            }
            // The block is ascending, so once it is past `id` the id is absent.
            if current > id {
                return None;
            }
        }
        None
    }

    /// A cursor for a run of **ascending** lookups.
    ///
    /// [`Self::find`] pays a binary search over `bases` per call. That is the right shape for a random
    /// lookup and the wrong one for the node pass, which asks about every node in a PBF block in
    /// ascending order: `bases` grew from 53 MB on a us-west extract to 290 MB on a north-america one,
    /// so each of those searches became about twenty-two cache misses plus the page walks to reach
    /// them. Measured, the pass spent 317.9 s of CPU searching against 5.1 s writing, and went 90x
    /// between those two extracts for 5.4x the nodes -- superlinear, because the array stopped
    /// fitting rather than because there was more work.
    ///
    /// A cursor searches once and then walks, which is one sequential sweep instead of thousands of
    /// scattered probes. Correct for any query order -- a query that goes backwards re-seeks -- so it
    /// does not depend on the PBF actually being sorted, only on it usually being sorted to be fast.
    fn cursor(&self) -> Cursor<'_> {
        Cursor { index: self, block: 0, at: 0, current: 0, position: 0, live: false }
    }
}

/// How many blocks a cursor will walk past before it gives up and searches instead.
///
/// Without a bound, a worker handed a distant blob would scan from wherever it happened to be, which
/// is the pathological case this whole change is meant to avoid. Sixty-four blocks is 4096 ids: past
/// that a binary search is cheaper than the walk.
const FORWARD_SCAN_BLOCKS: usize = 64;

/// A forward cursor into an [`IdIndex`]. See [`IdIndex::cursor`].
struct Cursor<'a> {
    index: &'a IdIndex,
    /// The block the cursor sits inside.
    block: usize,
    /// Offset into `deltas` just past `current`.
    at: usize,
    /// The id the cursor is on.
    current: i64,
    /// Position of `current` in the set.
    position: usize,
    /// False before the first lookup, when `current` means nothing.
    live: bool,
}

impl Cursor<'_> {
    /// The position of `id`, or `None` if it is not in the set.
    ///
    /// Answers exactly what [`IdIndex::find`] would, which a test asserts over thousands of cases in
    /// both ascending and shuffled order.
    fn find(&mut self, id: i64) -> Option<u64> {
        if !self.live || id < self.current || self.too_far(id) {
            let block = match self.index.bases.binary_search(&id) {
                Ok(block) => block,
                // Before the first id in the set. The cursor is left alone: a miss says nothing about
                // where the next hit will be.
                Err(0) => return None,
                Err(next) => next - 1,
            };
            self.enter(block);
        }
        while self.current < id {
            if !self.step() {
                return None;
            }
        }
        if self.current == id {
            Some(self.position as u64)
        } else {
            None
        }
    }

    /// Whether `id` is far enough ahead that walking to it would cost more than a search.
    fn too_far(&self, id: i64) -> bool {
        let ahead = self.block + FORWARD_SCAN_BLOCKS;
        ahead < self.index.bases.len() && self.index.bases[ahead] <= id
    }

    /// Sit on `block`'s first id.
    fn enter(&mut self, block: usize) {
        self.block = block;
        self.current = self.index.bases[block];
        self.at = self.index.offsets[block] as usize;
        self.position = block * BLOCK;
        self.live = true;
    }

    /// Move to the next id in the set. `false` at the end, leaving the cursor where it was.
    fn step(&mut self) -> bool {
        let end = self.index.offsets[self.block + 1] as usize;
        if self.at < end {
            let (gap, used) = get_uvarint(&self.index.deltas[self.at..]);
            self.at += used;
            self.current += gap as i64;
            self.position += 1;
            return true;
        }
        // The block is spent, and the next block's base is the next id.
        if self.block + 1 >= self.index.bases.len() {
            return false;
        }
        self.enter(self.block + 1);
        true
    }
}

/// LEB128, as the rest of this crate spells varints.
fn put_uvarint(mut value: u64, out: &mut Vec<u8>) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

/// Returns `(value, bytes consumed)`.
fn get_uvarint(bytes: &[u8]) -> (u64, usize) {
    let mut value = 0u64;
    let mut shift = 0u32;
    for (i, &b) in bytes.iter().enumerate() {
        value |= ((b & 0x7f) as u64) << shift;
        if b < 0x80 {
            return (value, i + 1);
        }
        shift += 7;
    }
    (value, bytes.len())
}

impl NodeLocations {
    /// `ids` need not be sorted or unique; both are done here.
    ///
    /// Fallible because the coordinate array is a mapped file, and a temporary directory that is full
    /// or read-only is a real thing to report rather than to panic on.
    pub fn new(mut ids: Vec<i64>) -> Result<NodeLocations> {
        ids.sort_unstable();
        ids.dedup();
        let index = NodeIds::build(&ids);
        // The plain vector goes here rather than being kept alongside: it is 8 bytes per id against
        // the index's ~1.2, and holding both would give back the saving. Freed before the coordinate
        // array is mapped, so the two never peak together.
        drop(ids);
        let locs = Locs::new(index.len())?;
        Ok(NodeLocations { ids: index, locs })
    }

    /// The same table from an **ascending, unique** id stream of known length.
    ///
    /// [`Self::new`] takes a vector because most callers have one. A continental or planet extract
    /// does not want one: [`crate::extract`]-shaped callers hold the needed ids as a bitset over the
    /// node id space, which is a fixed ~1.7 GB for any extract, and turning that back into 8 bytes an
    /// id to hand it over would be the allocation the bitset exists to avoid.
    ///
    /// **Sorted and unique is a precondition, not a request.** It is what makes every gap positive,
    /// and an out-of-order id would encode as a ten-byte varint and quietly corrupt the index. A
    /// bitset walked low to high satisfies it by construction, which is the only reason this is safe
    /// to expose.
    pub fn from_sorted(ids: impl Iterator<Item = i64>, len: usize) -> Result<NodeLocations> {
        let index = NodeIds::build_sorted(ids, len);
        let locs = Locs::new(index.len())?;
        Ok(NodeLocations { ids: index, locs })
    }

    /// The same table from an already-populated bitset over the id space, taking ownership of its
    /// words: bit `i` set iff id `i` is needed, `len` the number of set bits.
    ///
    /// [`Self::from_sorted`] walks the caller's bitset into ids and re-sets them here, holding two
    /// words arrays at once. This moves the words across and builds the rank directory in place, so
    /// the peak is one bitset rather than two. The only production caller is the planet ref
    /// collector; the vector path ([`Self::new`]) is untouched.
    pub fn from_bitset(words: Vec<u64>, len: usize) -> Result<NodeLocations> {
        let index = NodeIds::from_words(words, len);
        let locs = Locs::new(index.len())?;
        Ok(NodeLocations { ids: index, locs })
    }

    pub fn len(&self) -> usize {
        self.ids.len()
    }

    pub fn is_empty(&self) -> bool {
        self.ids.len() == 0
    }

    fn index_of(&self, id: i64) -> Option<u64> {
        self.ids.find(id)
    }

    /// Whether `id` is one of the ids this table was built for.
    ///
    /// Distinct from [`Self::get`] returning `None`, which also means "in the set but the node pass
    /// never found it" -- the legitimate case at an extract's cut edges.
    pub fn contains(&self, id: i64) -> bool {
        self.index_of(id).is_some()
    }

    pub fn get(&self, id: i64) -> Option<(i32, i32)> {
        self.locs.get(self.index_of(id)? as usize)
    }

    /// Every ref's table index into `out` (cleared first), `false` as soon as one ref has no
    /// coordinate -- exactly the refs [`Self::line`] would drop. Reads only the in-memory id index
    /// and resolved bits, never the coordinate array: a caller that needs only some of a way's
    /// coordinates (its ends, or a prefix) can then read just those with [`Self::coord_at`].
    pub fn indices_if_complete(&self, refs: &[i64], out: &mut Vec<usize>) -> bool {
        out.clear();
        for &id in refs {
            match self.index_of(id) {
                Some(i) if self.locs.is_resolved(i as usize) => out.push(i as usize),
                _ => return false,
            }
        }
        true
    }

    /// The coordinate at a table index [`Self::indices_if_complete`] returned, in the same
    /// `(lon, lat)` degrees [`Self::line`] produces.
    pub fn coord_at(&self, index: usize) -> Coord {
        let (lat_e7, lon_e7) = self.locs.get(index).expect("a resolved index");
        (lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7)
    }

    /// A way's geometry in lon/lat order, with unresolved refs skipped.
    ///
    /// A ref can be unresolved for a legitimate reason: an extract cut through the
    /// way, so some of its nodes are simply not in the file. Skipping the gap keeps
    /// the rest of the line rather than discarding the whole way, which is what
    /// `osmium extract`'s `complete_ways` produces for the same input.
    pub fn line(&self, refs: &[i64]) -> Vec<Coord> {
        refs.iter()
            .filter_map(|id| self.get(*id))
            .map(|(lat_e7, lon_e7)| (lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
            .collect()
    }
}

include!("nodeloc_part1.rs");
include!("nodeloc_part2.rs");