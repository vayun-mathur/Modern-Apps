//! On-disk scratch for one zoom's tile chunks: the missing half of an external sort.
//!
//! [`crate::tiler`]'s map phase produces one `BTreeMap<(tile, layer), BodyLayer>` per chunk and its
//! reduce k-way merges them. Those per-chunk maps already *are* the sorted runs of an external sort
//! -- produced free, as a side effect of clipping -- and `Merged` already *is* its merge phase. The
//! only piece missing was the disk in between, and holding it in memory instead is what put a
//! north-america z14 at 53 GB resident and would put a planet z14 near 244 GB.
//!
//! This module is that disk. A worker hands a finished chunk to [`ChunkSpill::write_chunk`] and gets
//! back a [`ChunkRef`]; the merge opens a [`ChunkReader`] per ref and walks them exactly as it
//! walked the maps. Peak becomes `O(threads) + O(READ_BUDGET)` rather than `O(extract)`.
//!
//! Modelled on `tile_build::spill`, and the conventions are that module's: manual little-endian
//! encode and decode, a zero-filled reserved tail so a field can be added without moving the ones
//! already there, a length that is a pure function of the header so a reader validates before it
//! allocates, counts cross-checked against the file's own length, and `Drop` cleanup so a run that
//! dies mid-planet cannot strand a hundred gigabytes.
//!
//! # The format
//!
//! One chunk is a plain sequential stream of entries in `BTreeMap::into_iter` order -- ascending
//! `(tile_id, layer_id)` -- with **no index**. The merge is strictly forward-only per stream, so an
//! index would be bytes nothing reads.
//!
//! ```text
//! 0..8    u64 tile_id
//! 8..12   u32 features
//! 12..16  u32 parts
//! 16..20  u32 coords
//! 20..21  u8  layer_id
//! 21..24  reserved, zero
//! 24..28  u32 names (label strings in this entry's table)
//! 28..32  u32 ids (feature ids in this entry's table: 0, or one per feature)
//! 32..    packed features (23 B), then parts (10 B), then coords (4 B), then ids (8 B),
//!          then names (u32 length + UTF-8 bytes each)
//! ```
//!
//! The id count took the second reserved word, which is what that word was for. Ids sit inside
//! the fixed arenas rather than after the names so [`payload_bytes`] stays a pure function of the
//! header — a reader still validates the whole fixed run before it allocates any of it.
//!
//! Packed field by field rather than cast wholesale from the in-memory struct. `body::Feature` is
//! `kind`, `kind_detail`, `geom_type`, `flags`, `parts_offset`, `part_count` -- fourteen bytes of
//! payload in a sixteen-byte struct with no `#[repr(C)]`, so a bulk cast would be both unsound and
//! dependent on the host's layout. Fourteen bytes is also less to write.
//!
//! Names ride per entry (not per chunk or per zoom) so the merge never holds more than one
//! entry's strings at a time: a tile's labels are dozens of strings, a zoom's are millions.
//!
//! # Reading with a budget, not a constant
//!
//! The merge holds one cursor per chunk: about 5,700 at a north-america z14 and about 27,000 at a
//! planet one. A fixed per-reader buffer is the wrong shape at both ends -- generous at 5,700, ruin
//! at 27,000. [`read_window_bytes`] divides a total [`READ_BUDGET`] by the stream count instead, so
//! the sum is a flat ceiling until the [`MIN_WINDOW`] floor takes over; the floor exists because a
//! read smaller than it costs more in syscalls than it saves in memory, and at the stream counts a
//! real build reaches the budget is still what binds.
//!
//! One pass, not a cascade. A cascading merge -- merge runs in groups, then merge the results -- is
//! available and order-preserving, and it is the escape hatch if the merge thread's decode turns out
//! to be the cost. It was not taken because it doubles I/O and doubles peak disk to buy memory that
//! [`read_window_bytes`] already bounds.
//!
//! # Random reads
//!
//! Thousands of interleaved forward cursors is a fine access pattern on NVMe and a poor one on
//! spinning media. [`READ_BUDGET`] is the dial: a larger window makes each stream's reads longer and
//! rarer at the cost of resident bytes.

use std::collections::BTreeMap;
use std::fs::File;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use tilecodec::mamaps::body::{
    BuildingAttrs, Carriageway, Feature as BodyFeature, Layer as BodyLayer, Part,
    consts::CARRIAGEWAY_RECORD_LEN,
};
use tilecodec::proto::{err, Error, Result};

use crate::tiler::ChunkEntry;

/// Bytes of fixed header before one entry's arenas.
pub const ENTRY_HEADER_BYTES: usize = 32;

/// Packed width of one [`BodyFeature`] in the spill: kind, kind_detail, geom_type, flags,
/// name_idx, parts_offset, part_count, transit_color, transit_ordinal, transit_lanes,
/// transit_taper, lane_count. Twenty-four, exactly the body's 24-byte record: the fields are
/// the codec's own, so the scratch format never lags the codec by a version.
const FEATURE_BYTES: usize = 24;
/// Packed width of one [`Part`] in the spill: coord_start, point_count, winding. Ten — the
/// body's 12-byte entry carries a reserved half-word the scratch format does not need.
const PART_BYTES: usize = 10;
/// Packed width of one `(i16, i16)` coordinate.
const COORD_BYTES: usize = 4;
/// Packed width of one feature id. Fixed rather than varint for the same reason the body's id
/// table is: unsorted OSM ids have no ordering to delta against.
const ID_BYTES: usize = 8;

/// Packed width of one [`BuildingAttrs`] in the spill: height, min_height, roof_height (u16 each),
/// roof_shape, roof_direction, roof_orientation (u8 each), building_colour, roof_colour (u32
/// each). Seventeen — no alignment padding, unlike the body's 20-byte on-disk record.
const BUILDING_BYTES: usize = 17;

/// An entry longer than this is corruption, not a large tile layer. A tile-layer is capped at 65,535
/// features by the body format, so the largest plausible entry is orders of magnitude below this.
const MAX_ENTRY_BYTES: u64 = 1 << 30;

/// Bytes the whole merge may hold in read windows, divided across its streams.
///
/// Small against the old 256 MiB on purpose: the merge holds one cursor per chunk (~27 k at a
/// planet z14), and each cursor's window is live beside the batch above and the encode workers.
/// 256 MiB of cursors on top of a ~GB batch is what a 512 KiB allocation failure looks like on
/// a box with ~50 GB of commit headroom. 64 MiB still reads in 16 KiB+ windows at 4 k streams
/// (the floor binds long before the budget does at planet scale), so the extra syscalls are
/// noise against the decode.
pub const READ_BUDGET: usize = 64 << 20;
/// Smallest read window. Below this the syscall costs more than the memory saves.
///
/// Halved alongside the budget: at a planet z14's ~27 k streams the window formula divides the
/// budget by the stream count and clamps to this floor, so the floor *is* the total
/// (`streams x MIN_WINDOW`). 8 KiB keeps that total at ~216 MB instead of ~432 MB, still a
/// reasonable read size off NVMe or the page cache, and the window-identity test pins that the
/// size is not observable in the archive.
pub const MIN_WINDOW: usize = 8 << 10;
/// Largest read window. A stream is forward-only, so beyond this a longer window is only read-ahead
/// the page cache would have done anyway.
pub const MAX_WINDOW: usize = 1 << 20;

/// How many bytes each of `streams` concurrent readers may buffer.
///
/// **Not observable in the output.** A reader yields the same entries in the same order whatever its
/// window is, which is what `the_archive_is_identical_however_the_read_window_is_sized` holds this
/// to; the window is a memory/syscall trade and nothing else.
pub fn read_window_bytes(streams: usize) -> usize {
    let forced = WINDOW_OVERRIDE.load(Ordering::Relaxed);
    if forced > 0 {
        return forced;
    }
    window_for(streams)
}

fn window_for(streams: usize) -> usize {
    (READ_BUDGET / streams.max(1)).clamp(MIN_WINDOW, MAX_WINDOW)
}

/// A window forced by a test, or zero for the budget. Zero rather than an `Option`, because this is
/// read on the merge's own path and a build never sets it.
static WINDOW_OVERRIDE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// Pin the read window, or release it with zero. Tests only: the window has to be forcible for the
/// claim that it is not observable to be worth anything.
#[cfg(test)]
pub fn set_read_window(bytes: usize) {
    WINDOW_OVERRIDE.store(bytes, Ordering::Relaxed);
}

/// Where one chunk's bytes live in a [`ChunkSpill`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ChunkRef {
    pub at: u64,
    pub len: u64,
    pub entries: u64,
}

/// One zoom's chunks, in one file — or in anonymous memory.
///
/// `&self` throughout, so every worker writes through it and every reader reads through it -- the
/// same discipline as `tile_build::spill::NormalizedChunks`, and for the same reason: one handle,
/// many threads, positional I/O and no shared cursor.
///
/// Anonymous twin: [`create_anon`](Self::create_anon) stages the same chunks in
/// pagefile-backed memory. Same offsets, same bytes, no
/// file. The merge reads through the same [`ChunkReader`] either way.
pub struct ChunkSpill {
    /// `None` only while dropping, where the handle has to close before the file can be unlinked or
    /// Windows refuses the delete. Always `None` on the anon path (no handle at all).
    file: Option<File>,
    anon: Option<std::sync::Mutex<tile_build::anon::AnonStore>>,
    path: PathBuf,
    /// The next free byte, and so also the total reserved. Reserved under the commit lock
    /// ([`Self::write`], always `write` before `at`), so reservation order is completion order
    /// and the file offset advances monotonically — one sequential chunk after another.
    at: Mutex<u64>,
    /// Serialises whole-chunk commits (reserve + write) so the file offset advances
    /// monotonically: reservation order == completion order, one large sequential write
    /// per chunk, no interleaved 1 MB pieces from N workers. Always taken before `at`.
    write: Mutex<()>,
    written: AtomicU64,
    written_entries: AtomicU64,
    read: AtomicU64,
    read_entries: AtomicU64,
    /// Per written chunk (keyed by its start offset and length -- an empty chunk shares its start
    /// with the next one): its tile runs, recorded by `write_chunk` from
    /// the map it encoded. What the merge planner reads instead of skip-walking every chunk back
    /// off disk (twice per zoom: 78 s of the Nov 10 planet build, z14 alone 47 s).
    runs: Mutex<Vec<((u64, u64), Vec<TileRun>)>>,
}

/// One tile's entries within one chunk, as `write_chunk` laid them down. Narrow on purpose: a
/// planet z14 records tens of millions of these, so offsets are relative to the chunk and counts
/// are `u32` (a chunk is a few MB); a chunk that would overflow one is simply not indexed and the
/// planner walks it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TileRun {
    pub tile: u64,
    /// Summed merge cost (features + coordinates) of the run's entries: what
    /// [`ChunkReader::skip_entry`] reports for each.
    pub cost: u32,
    /// Offset of the run's first entry from the chunk's start.
    pub rel_offset: u32,
    /// Entries in the chunk before this run.
    pub before: u32,
}

impl ChunkSpill {
    /// Create the scratch file, truncating whatever a killed run left at the path.
    ///
    /// Read *and* write on one handle, because that is the whole shape of this module: the workers
    /// write positionally through it and the merge reads positionally through it, and a second
    /// handle would be a second file description with nothing to gain.
    pub fn create(path: impl Into<PathBuf>) -> Result<ChunkSpill> {
        let path = path.into();
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        // Uncompressed on a `compress-force` btrfs: every zoom's clipped geometry passes through
        // here (hundreds of GB over a planet build), written once, read back by the merge.
        osm_ingest::mem::mark_scratch_uncompressed(&file);
        Ok(ChunkSpill {
            file: Some(file),
            anon: None,
            path,
            at: Mutex::new(0),
            write: Mutex::new(()),
            written: AtomicU64::new(0),
            written_entries: AtomicU64::new(0),
            read: AtomicU64::new(0),
            read_entries: AtomicU64::new(0),
            runs: Mutex::new(Vec::new()),
        })
    }

    /// Anonymous twin

    /// Anonymous twin of [`create`](Self::create): same chunks and offsets in
    /// pagefile-backed memory, no file. `path` names nothing — it only rides
    /// along for error messages and the `check_books` accounting.
    pub fn create_anon(path: impl Into<PathBuf>) -> Result<ChunkSpill> {
        Self::create_planned(path, osm_ingest::mem::SpillPlan::Anon)
    }

    /// Create with an explicit staging plan. See [`osm_ingest::mem::SpillPlan::decide`]: the
    /// budget gate picks `File` when anon commit does not fit, and `File` stages through the
    /// scratch path (truncated like [`create`](Self::create), removed on drop). Same chunks,
    /// same offsets, same bytes -- the backend never changes a hash.
    pub fn create_planned(path: impl Into<PathBuf>, plan: osm_ingest::mem::SpillPlan) -> Result<ChunkSpill> {
        match plan {
            osm_ingest::mem::SpillPlan::Anon => Ok(ChunkSpill {
                file: None,
                anon: Some(std::sync::Mutex::new(tile_build::anon::AnonStore::new())),
                path: path.into(),
                at: Mutex::new(0),
                write: Mutex::new(()),
                written: AtomicU64::new(0),
                written_entries: AtomicU64::new(0),
                read: AtomicU64::new(0),
                read_entries: AtomicU64::new(0),
                runs: Mutex::new(Vec::new()),
            }),
            osm_ingest::mem::SpillPlan::File => Self::create(path),
        }
    }

    /// Whether this spill stages in anonymous memory rather than a file.
    pub fn is_anon(&self) -> bool {
        self.anon.is_some()
    }

    fn file(&self) -> &File {
        self.file.as_ref().expect("the tile chunk spill outlives its readers and writers")
    }

    /// Write one finished chunk and say where it went.
    ///
    /// Takes the map **by value** and encodes out of `into_iter`, so the map shrinks as it is
    /// written instead of being copied whole into a buffer beside itself.
    ///
    /// Encode happens outside any lock, in parallel across workers. The commit — reserving the
    /// byte range and writing it — holds the `write` lock for one sequential write per chunk.
    /// Reservation order is therefore completion order and the file offset advances
    /// monotonically: no interleaved 1 MB pieces from N workers sharing one file offset space,
    /// which is what turned N parallel positional fills into random-write interleaving on
    /// spinning disks (and page-cache churn everywhere). The merge sorts chunks by their read
    /// index, never by file position, so the layout change is not observable in the archive.
    pub fn write_chunk(&self, map: BTreeMap<(u64, u8), ChunkEntry>) -> Result<ChunkRef> {
        let entries = map.len() as u64;
        let mut len = 0u64;
        // The chunk's tile runs, offsets relative to the chunk start. `None` once a field would
        // not fit its `u32` (never in practice), which leaves the chunk unindexed.
        let mut runs: Option<Vec<TileRun>> = Some(Vec::new());
        for (n, ((tile, layer_id), entry)) in map.iter().enumerate() {
            let cost = entry.layer.features.len() as u64 + entry.layer.coords.len() as u64;
            if let Some(list) = runs.as_mut() {
                let fits = |v: u64| u32::try_from(v).ok();
                let ok = match list.last_mut() {
                    Some(run) if run.tile == *tile => {
                        fits(run.cost as u64 + cost).map(|c| run.cost = c).is_some()
                    }
                    _ => match (fits(cost), fits(len), fits(n as u64)) {
                        (Some(cost), Some(rel_offset), Some(before)) => {
                            list.push(TileRun { tile: *tile, cost, rel_offset, before });
                            true
                        }
                        _ => false,
                    },
                };
                if !ok {
                    runs = None;
                }
            }
            if *layer_id != entry.layer.layer_id {
                // The merge keys its heap on the map key and reads `layer_id` off the layer it
                // yields, so the two disagreeing would mean the round trip had to preserve both. It
                // is one field on disk because in this generator they are one field.
                return err(format!(
                    "a tile chunk entry is keyed on layer {layer_id} but carries layer {}",
                    entry.layer.layer_id
                ));
            }
            len += entry_bytes(entry)?;
        }

        // Encoded before the commit lock, so N workers still clip and encode in parallel and
        // only the disk write itself is serial. Sized exactly: `len` above is the encoded
        // length, so this is one allocation per chunk rather than a growing buffer.
        let mut buf: Vec<u8> = Vec::with_capacity(len as usize);
        for ((tile, layer_id), layer) in map {
            encode_entry(tile, layer_id, &layer, &mut buf);
        }
        debug_assert_eq!(buf.len() as u64, len, "the encoded chunk must match its reservation");

        // One commit per chunk: reserve and write under the same lock, so the file sees whole
        // sequential chunks in reservation order. Always `write` before `at`.
        let _commit = self.write.lock().expect("the tile chunk spill's commit lock");
        let at = {
            let mut next = self.at.lock().expect("the tile chunk spill's cursor");
            let at = *next;
            *next += len;
            at
        };

        let cursor = at + self.flush(&mut buf, at)?;

        if cursor != at + len {
            return err(format!(
                "a tile chunk reserved {len} byte(s) and wrote {}",
                cursor - at
            ));
        }
        self.written.fetch_add(len, Ordering::Relaxed);
        self.written_entries.fetch_add(entries, Ordering::Relaxed);
        drop(_commit);
        // An empty chunk is never indexed: it has nothing to plan, and its start is the next
        // chunk's start, so a run list keyed on the offset alone could be handed to the wrong one
        // (which is what sent US West's z14 merge reading past chunk ends). The key carries the
        // length too, so even a zero-length key cannot meet a real chunk's.
        if let (Some(runs), true) = (runs, entries > 0) {
            self.runs.lock().expect("the tile chunk spill's run index").push(((at, len), runs));
        }
        Ok(ChunkRef { at, len, entries })
    }

    /// Write `buf` at `offset` and clear it. Returns what it wrote.
    fn flush(&self, buf: &mut Vec<u8>, offset: u64) -> Result<u64> {
        if buf.is_empty() {
            return Ok(0);
        }
        match (&self.file, &self.anon) {
            (Some(f), None) => write_all_at(f, buf, offset)
                .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?,
            (None, Some(a)) => {
                let mut store = a.lock().expect("the anon tile chunk spill");
                // Positional: the cursor reservation above hands each chunk a
                // back-to-back range, flushed here in pieces. `write_at`
                // refuses gaps (a lost chunk) but accepts piece-wise fills.
                store.write_at(offset, buf).map_err(|e| {
                    Error(format!("staging anon tile chunk: {e}"))
                })?;
            }
            _ => return err("a tile chunk spill has no backend".to_string()),
        }
        let n = buf.len() as u64;
        buf.clear();
        Ok(n)
    }

    /// The tile runs `write_chunk` recorded for every chunk, keyed by chunk start offset. Empty
    /// for a chunk written some other way (tests building chunks by hand), which the planner
    /// answers by skip-walking that chunk as it always did.
    pub fn take_runs(&self) -> std::collections::HashMap<(u64, u64), Vec<TileRun>> {
        std::mem::take(&mut *self.runs.lock().expect("the tile chunk spill's run index"))
            .into_iter()
            .collect()
    }

    /// A forward cursor over one chunk, buffering `window` bytes at a time.
    pub fn reader(&self, chunk: &ChunkRef, window: usize) -> ChunkReader<'_> {
        ChunkReader {
            spill: self,
            at: chunk.at,
            end: chunk.at + chunk.len,
            left: chunk.entries,
            buf: Vec::new(),
            used: 0,
            window: window.max(ENTRY_HEADER_BYTES),
            spare: None,
            exact_end: true,
        }
    }

    /// Reserved, written, on disk and read back must all agree.
    ///
    /// `tile_build::spill::BucketSet::seal`'s triple entry, for the same reason: a chunk that
    /// quietly lost entries would produce an archive with holes in it and nothing downstream could
    /// tell. Called once the merge has drained every reader. On the anon path "on disk" is the
    /// store's committed length — same check, no file.
    pub fn check_books(&self) -> Result<()> {
        let reserved = *self.at.lock().expect("the tile chunk spill's cursor");
        let written = self.written.load(Ordering::Relaxed);
        if reserved != written {
            return err(format!(
                "{} reserved {reserved} byte(s) and wrote {written}",
                self.path.display()
            ));
        }
        let staged = match (&self.file, &self.anon) {
            (Some(_), None) => std::fs::metadata(&self.path)
                .map_err(|e| Error(format!("cannot stat {}: {e}", self.path.display())))?
                .len(),
            (None, Some(a)) => a.lock().expect("the anon tile chunk spill").len(),
            _ => return err("a tile chunk spill has no backend".to_string()),
        };
        if staged != written {
            return err(format!(
                "{} stages {staged} byte(s) but {written} were written to it",
                self.path.display()
            ));
        }
        let read = self.read.load(Ordering::Relaxed);
        if read != written {
            return err(format!(
                "{} wrote {written} byte(s) and read back {read}",
                self.path.display()
            ));
        }
        let pushed = self.written_entries.load(Ordering::Relaxed);
        let pulled = self.read_entries.load(Ordering::Relaxed);
        if pushed != pulled {
            return err(format!(
                "{} wrote {pushed} entr(ies) and read back {pulled}",
                self.path.display()
            ));
        }
        Ok(())
    }
}

impl Drop for ChunkSpill {
    fn drop(&mut self) {
        // The handle first, or Windows refuses the delete. Anon path holds no
        // handle and no file: nothing to do, the mapping frees with the store.
        self.file = None;
        if self.anon.is_none() {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

/// A forward-only cursor over one chunk's byte range.
///
/// Reads through `&ChunkSpill`, so every cursor in a merge shares one file handle.
pub struct ChunkReader<'a> {
    spill: &'a ChunkSpill,
    /// Disk offset of the first byte not yet in `buf`.
    at: u64,
    /// One past the chunk's last byte.
    end: u64,
    /// Entries the header said were written and this cursor has not yielded.
    left: u64,
    buf: Vec<u8>,
    /// How much of `buf` has been yielded.
    used: usize,
    window: usize,
    /// A recycled entry's emptied arenas, handed back by the merge after it drains an entry into
    /// its accumulator. `decode` reuses these buffers instead of allocating four fresh `Vec`s per
    /// entry — the decode is on the serial merge thread, so its allocator churn is Amdahl time.
    /// `None` until the merge returns one; a decoded entry is byte-identical whether it was built
    /// from a recycled arena or a fresh one, because the contents are written the same way.
    spare: Option<ChunkEntry>,
    /// Whether this cursor runs to the chunk's last byte. A range worker's
    /// segment ends mid-chunk (the next range owns what follows), so only a
    /// cursor that does reach the end enforces the no-trailing-bytes check —
    /// any other segment legitimately stops with unread bytes after it.
    exact_end: bool,
}

include!("tilespill_part1.rs");
include!("tilespill_part2.rs");
include!("tilespill_part3.rs");
include!("tilespill_part4.rs");