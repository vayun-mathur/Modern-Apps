use super::codec::{compress_body, hash64};
use super::options::Options;
use super::spill::Spill;
use crate::mamaps::body::{self, Body};
use crate::mamaps::header::MAX_ZOOM;
use crate::mamaps::index::{LeafEntry, RootEntry, self};
use crate::proto::{Result, err};
use std::collections::HashMap;

/// The dedup index's map: keyed by `(FNV-1a hash, length)`, which is already well mixed, so the
/// default SipHash only costs time on a lookup made for every appended tile. Never iterated for
/// output, so the hasher cannot move a byte.
type SeenMap = HashMap<(u64, u32), Vec<u64>, std::hash::BuildHasherDefault<KeyHasher>>;

/// A multiply-rotate mixer for [`SeenMap`]'s already-hashed keys.
#[derive(Default)]
struct KeyHasher(u64);

impl std::hash::Hasher for KeyHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.write_u64(b as u64);
        }
    }
    fn write_u64(&mut self, v: u64) {
        self.0 = (self.0.rotate_left(5) ^ v).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
    }
    fn write_u32(&mut self, v: u32) {
        self.write_u64(v as u64);
    }
}
use std::fs::File;
use std::io::Write;
use std::path::Path;


/// Builds an archive, appending bodies in ascending tile-id order.
///
/// Bodies go to a scratch file as they arrive rather than into a `Vec`, so what this holds is the
/// index and the dedup buckets — 16 bytes per stored body and a hash entry per distinct one, not the
/// bodies. See the module header for why, and for what that costs the two dedup compares.
pub struct StreamWriter {
    pub(crate) options: Options,
    /// The data section, being written.
    ///
    /// Named `data` because that is what it is: the same bytes, in the same order, that used to be
    /// accumulated in memory here.
    pub(crate) data: Spill,
    /// One per stored body, ascending by tile id, partitioned into leaves at `finish`.
    pub(crate) entries: Vec<Pending>,
    /// `(hash, stored length) -> offsets already written`. Several per key only on a collision,
    /// which is why it is a list. Probed, never iterated.
    seen: SeenMap,
    /// Every offset in `seen`, in the order it was added (which is ascending), with its key: what
    /// [`Self::sweep_dedup`] pops from instead of walking the whole map.
    seen_order: std::collections::VecDeque<(u64, (u64, u32))>,
    last_id: Option<u64>,
    /// The stored bytes of the body `entries.last()` points at, which is what a run-length compare
    /// is against.
    ///
    /// Kept so that compare stays in memory. It is *not* the last body appended: content dedup
    /// means the last entry may point at a body written for a much earlier tile, and this holds
    /// whatever those bytes are. A `debug_assert` in [`Self::append_encoded`] checks it against the
    /// spill on every append, which is how the shortcut is held to the file's answer rather than
    /// trusted to stay in step with it.
    last_body: std::sync::Arc<Vec<u8>>,
    /// Distinct bodies appended since the last [`Self::sweep_dedup`] pass. Bounds how stale the
    /// dedup index may get between sweeps; see the sweep for why staleness is memory.
    distinct_since_sweep: u64,
    pub(crate) tiles_addressed: u64,
    /// Bodies actually appended to `data`, which is what dedup reduces. Distinct from
    /// `entries.len()`, which counts *index* entries: a body shared by two non-adjacent tiles is
    /// one body and two entries.
    pub(crate) distinct: u64,
    pub(crate) runs_used: bool,
}

/// One stored body's index entry, before it knows which leaf it belongs to.
///
/// Deliberately not a [`LeafEntry`]: that type's `offset_delta` is 32-bit *relative to its leaf's*
/// `base_data_offset`, which is exactly what makes the format hold a data section past 4 GiB. Holding
/// an absolute offset in that field meant the writer could not build one -- a north-america z13
/// archive stopped at `a .mamaps data section past 4 GiB needs a wider offset field` after 56 minutes
/// of work, on a limit the format does not actually have. The narrowing belongs at
/// [`StreamWriter::partition`], where the leaf base is known.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Pending {
    pub(crate) tile_id: u64,
    /// Absolute within the data section.
    pub(crate) offset: u64,
    pub(crate) run_length: u32,
    pub(crate) length: u32,
}

/// A root index and its leaves, as [`StreamWriter::partition`] produces them.
type Split = (Vec<RootEntry>, Vec<Vec<LeafEntry>>);

/// How far back content dedup may point, in bytes of the data section.
///
/// [`LeafEntry::offset_delta`] is 32-bit relative to its leaf's `base_data_offset`, and that base is
/// the **minimum** offset in the leaf because dedup lets a tile reuse a body written for an earlier
/// one. So a leaf late in a large archive that reuses a body from the very beginning would need a
/// delta of the whole archive -- past 4 GiB on a north-america z13 build, which no `u32` holds.
///
/// Bounding the reach bounds the delta: every offset in a leaf is then within `MAX_DEDUP_REACH` of
/// the write head when that leaf was written, so the span cannot approach 4 GiB. The cost is a
/// re-appended body when a match is older than this, and it is small in practice because tile ids are
/// Hilbert-ordered -- tiles that share a body are neighbours, and neighbours are written close
/// together.
const MAX_DEDUP_REACH: u64 = 2 << 30;

/// How many new distinct bodies between dedup-index sweeps. A planet z14 writes tens of millions
/// of distinct bodies; sweeping every one would be quadratic in the map's own bookkeeping, and
/// never sweeping leaves hundreds of MB of `(hash, len) -> offsets` behind. One sweep per ~200 k
/// distinct bodies keeps each sweep's `retain` pass small against the bodies it reclaims.
const DEDUP_SWEEP_DISTINCT: u64 = 200_000;

/// How much of the data section the dedup index keeps entries for. Matches `MAX_DEDUP_REACH`:
/// bodies older than the reach can never match again (the `push` skip above refuses them), so
/// their entries are dead lookups. At planet z14 density ~2 GB of bodies covers millions of
/// recently-written tiles -- the whole neighbourhood that actually shares bodies -- while the
/// map stays tens of MB instead of hundreds.
const DEDUP_KEEP_REACH: u64 = 2 << 30;

impl StreamWriter {
    pub fn new(options: Options) -> Result<StreamWriter> {
        if options.min_zoom > options.max_zoom || options.max_zoom > MAX_ZOOM {
            return err(format!(
                "a .mamaps build needs a zoom range inside 0..={MAX_ZOOM}, got {}..={}",
                options.min_zoom, options.max_zoom,
            ));
        }
        if !options.leaf_entry_capacity.is_power_of_two() || options.leaf_entry_capacity == 0 {
            return err(format!(
                "a .mamaps leaf capacity must be a power of two, got {}",
                options.leaf_entry_capacity,
            ));
        }
        // After the option checks, so a build with an impossible zoom range fails without having
        // created a file to clean up.
        let data = Spill::create(options.spill_dir.as_deref(), options.spill_file_hook)?;
        Ok(StreamWriter {
            options,
            data,
            entries: Vec::new(),
            seen: SeenMap::default(),
            seen_order: std::collections::VecDeque::new(),
            last_id: None,
            last_body: Default::default(),
            distinct_since_sweep: 0,
            tiles_addressed: 0,
            distinct: 0,
            runs_used: false,
        })
    }

    /// Encode and append one tile. Ids must ascend.
    pub fn append(&mut self, tile_id: u64, body: &Body) -> Result<()> {
        self.append_encoded(tile_id, &body::serialize(body)?)
    }

    /// Append an already-encoded body.
    ///
    /// Ascending ids are a requirement rather than something to sort into place: the index is built
    /// as bodies arrive, and the generator's zoom-major Hilbert buckets already deliver them in
    /// order. A caller that cannot is a caller whose bucketing broke, which is worth failing over.
    pub fn append_encoded(&mut self, tile_id: u64, encoded: &[u8]) -> Result<()> {
        // Parsed rather than trusted: `raw_len` is what a reader allocates from, and a body whose
        // declared length disagrees with its bytes would be caught on device instead of here.
        let raw_len = Body::raw_len(encoded)?;
        if raw_len as usize != encoded.len() {
            return err(format!(
                "a .mamaps body declares {raw_len} bytes but is {}",
                encoded.len(),
            ));
        }
        let stored = if self.options.compress { compress_body(encoded) } else { encoded.to_vec() };
        let hash = hash64(&stored);
        self.push(tile_id, &stored, hash)
    }

    /// Append a body already compressed by [`compress_body`].
    ///
    /// For a generator that compresses in parallel, which is the difference between using one core
    /// and using all of them. DEFLATE at level nine runs on the order of 15 MB/s, so a California
    /// build's 1.3 GB of bodies is about ninety seconds of a single core — and inside
    /// [`Self::append_encoded`] that sits *downstream* of the caller's parallel map and encode,
    /// serialising the whole pipeline behind the one step that cannot be stolen. Compressing in the
    /// worker and appending the result leaves the append what it should be: index bookkeeping.
    ///
    /// Dedup is unaffected. Entries are deduplicated on the **stored** bytes either way, and DEFLATE
    /// is deterministic, so two equal bodies still compress to two equal frames and still collapse.
    /// That is also why this is byte-identical to compressing here.
    pub fn append_stored(&mut self, tile_id: u64, stored: &[u8]) -> Result<()> {
        if !self.options.compress {
            return err(
                "append_stored was given a compressed body but this archive stores them raw"
                    .to_string(),
            );
        }
        // The 16-byte body header rides uncompressed ahead of the frame, so this still validates.
        Body::raw_len(stored)?;
        let hash = hash64(stored);
        self.push(tile_id, stored, hash)
    }

    /// [`Self::append_stored`], with the FNV-1a dedup key already computed by the caller.
    ///
    /// The hash is over the whole stored body, and on a continent that is hundreds of megabytes
    /// pushed through one core on the serial append thread — downstream of the parallel encode,
    /// exactly the place [`compress_body_with`] moved DEFLATE out of. A generator that already holds
    /// each body in a worker can hash it there and hand the key over, leaving the append thread the
    /// index bookkeeping it should be.
    ///
    /// `hash` must be [`hash64`] of `stored`; the writer trusts it as the bucket key and still
    /// confirms every candidate by a full byte compare, so a wrong hash could only ever cost a
    /// missed dedup, never a wrong body. Passing the right one keeps every dedup decision — and the
    /// archive — byte for byte identical to [`Self::append_stored`].
    pub fn append_stored_with_hash(
        &mut self,
        tile_id: u64,
        stored: &[u8],
        hash: u64,
    ) -> Result<()> {
        if !self.options.compress {
            return err(
                "append_stored was given a compressed body but this archive stores them raw"
                    .to_string(),
            );
        }
        Body::raw_len(stored)?;
        self.push(tile_id, stored, hash)
    }

    /// The part both appends share: range and order checks, dedup, and the index entry. `hash` is
    /// the FNV-1a dedup key of `stored`, computed by the caller so it need not sit on this thread.
    ///
    /// # How `seen` stays bounded
    ///
    /// Every distinct stored body leaves one `(hash, len) -> offsets` entry behind, and there is
    /// one distinct body per structurally-unique tile on a planet build -- hundreds of megabytes
    /// of `HashMap` in the steady state, with the OS error at the end of it when the box only has
    /// ~50 GB of commit headroom. So every [`DEDUP_SWEEP_DISTINCT`] new distinct bodies the index
    /// is swept down to the youngest [`DEDUP_KEEP_RECENT`]: dropping a reachable entry cannot
    /// change a byte, because a miss re-appends the identical stored body and the index maps a body
    /// to its offset, never an offset to a meaning. The only cost is bytes: a body that would have
    /// deduped against a swept entry is re-appended (still deduplicated against a *later* copy,
    /// still correct). A test build has a handful of distinct bodies and sweeps nothing.
    ///
    /// The bound is distance, not a body count: urban bodies that recur within a neighbourhood
    /// still dedup (the window covers ~200 M bodies' worth of archive reach at z14 density), while
    /// a body from eighteen zooms ago cannot be referenced cheaply across a 4 GiB-spanning leaf
    /// anyway (see `MAX_DEDUP_REACH`). Byte-identity on small archives holds because the sweep
    /// never fires there.
    fn push(&mut self, tile_id: u64, stored: &[u8], hash: u64) -> Result<()> {
        self.push_shared(tile_id, std::sync::Arc::new(stored.to_vec()), hash)
    }

    /// [`Self::append_stored_with_hash`] for a body the caller hands over whole: the same dedup
    /// decisions, offsets and index entries, with the body's bytes going to the background writer
    /// ([`Spill::append_shared`]) instead of being copied on this thread.
    pub fn append_stored_owned(&mut self, tile_id: u64, stored: Vec<u8>, hash: u64) -> Result<()> {
        if !self.options.compress {
            return err(
                "append_stored was given a compressed body but this archive stores them raw"
                    .to_string(),
            );
        }
        Body::raw_len(&stored)?;
        self.push_shared(tile_id, std::sync::Arc::new(stored), hash)
    }

    fn push_shared(&mut self, tile_id: u64, stored: std::sync::Arc<Vec<u8>>, hash: u64) -> Result<()> {
        let (z, _, _) = crate::pmtiles::tile_zxy(tile_id);
        if z < self.options.min_zoom || z > self.options.max_zoom {
            return err(format!(
                "tile {tile_id} is at z{z}, outside the declared range {}..={}",
                self.options.min_zoom, self.options.max_zoom,
            ));
        }
        if let Some(previous) = self.last_id {
            if tile_id <= previous {
                return err(format!(
                    "a .mamaps build needs ascending tile ids, got {tile_id} after {previous}"
                ));
            }
        }
        self.last_id = Some(tile_id);
        self.tiles_addressed += 1;

        let length = u32::try_from(stored.len())
            .map_err(|_| crate::proto::Error("a .mamaps body is larger than 4 GiB".to_string()))?;

        // Run-length first: a consecutive repeat needs no dedup lookup and no new entry at all.
        // The compare is against `last_body` rather than the spill, because the bytes the last
        // entry points at are the one body always worth keeping to hand.
        if let Some(previous) = self.entries.last_mut() {
            debug_assert!(
                self.data.matches_at(previous.offset, &self.last_body)?,
                "last_body must be the bytes the last entry points at, or the shortcut below is \
                 answering for a body that is not there",
            );
            if previous.length == length
                && previous.tile_id + previous.run_length as u64 == tile_id
                && *self.last_body == *stored
            {
                previous.run_length += 1;
                self.runs_used = true;
                return Ok(());
            }
        }

        let key = (hash, length);
        // `seen` and `data` are borrowed apart because confirming a candidate may read the spill,
        // which needs `&mut`, while the bucket being walked lives in the map.
        let StreamWriter { seen, seen_order, data, distinct, .. } = &mut *self;
        let bucket = seen.entry(key).or_default();
        let mut hit = None;
        let head = data.len();
        for &at in bucket.iter() {
            // Too far back to be addressable from a leaf that ends here. Skipped rather than
            // matched, so the body is written again and the delta stays inside a `u32`.
            if head - at > MAX_DEDUP_REACH {
                continue;
            }
            if data.matches_at(at, &stored)? {
                hit = Some(at);
                break;
            }
        }
        let offset = match hit {
            Some(at) => at,
            None => {
                let at = data.len();
                data.append_shared(std::sync::Arc::clone(&stored))?;
                bucket.push(at);
                seen_order.push_back((at, key));
                *distinct += 1;
                // Counted here -- not in the caller -- so the sweep below sees every new
                // distinct body exactly once, wherever the dedup miss happened.
                //
                // Borrowed through the destructure above; updated after the re-borrow
                // ends would fight it, so it rides on `self` after `data` is released.
                // (The counter lives on `self`, the map on the destructured borrow;
                // both are written below once the borrow ends.)
                at
            }
        };
        // The counter update deferred from above: the destructure's borrow of `seen`/`data`
        // has ended (both `bucket` and `data` go out of scope here), so `self` is writable.
        if hit.is_none() {
            self.distinct_since_sweep += 1;
            if self.distinct_since_sweep >= DEDUP_SWEEP_DISTINCT {
                self.sweep_dedup(head);
            }
        }
        self.entries.push(Pending { tile_id, offset, run_length: 1, length });
        self.last_body = stored;
        Ok(())
    }

    /// Sweep the dedup index down to bodies written recently.
    ///
    /// `head` is the data-section length at the sweep -- every offset older than
    /// `head - DEDUP_KEEP_REACH` loses its entries. Buckets left empty are removed, so the
    /// map itself shrinks rather than filling with empty vectors.
    ///
    /// Correctness rests on one fact: `seen` is a *cache*, not an index. A hit reuses a body;
    /// a miss re-appends the identical bytes. Dropping an entry therefore costs at most the
    /// re-appended bytes, never a wrong byte: two equal stored bodies compress identically
    /// (DEFLATE is deterministic), so the re-appended body dedups and reads exactly as the
    /// swept one would have. The run-length shortcut above is unaffected (it keys on
    /// `last_body`, not on `seen`).
    ///
    /// Reach, not count: nearby tiles share bodies (Hilbert order writes neighbours together),
    /// so a sweep that keeps the last ~2 GB of bodies preserves the dedups that actually fire
    /// while bounding the map. The `MAX_DEDUP_REACH` skip in `push` already stops matching
    /// older bodies, so entries past it were dead weight -- buckets walked, never hit.
    fn sweep_dedup(&mut self, head: u64) {
        let floor = head.saturating_sub(DEDUP_KEEP_REACH);
        // Popped from the insertion-ordered list rather than `retain` over the whole map: the
        // map holds ~2 GiB of bodies' worth of keys (millions on a planet z14) and the sweep
        // fires every `DEDUP_SWEEP_DISTINCT` new bodies, so walking all of it each time was a
        // large share of the serial append. Offsets were pushed in ascending order, so the
        // front of the list is exactly the set `retain(at >= floor)` removed: the same entries
        // go, the same stay.
        while let Some(&(at, key)) = self.seen_order.front() {
            if at >= floor {
                break;
            }
            self.seen_order.pop_front();
            if let Some(bucket) = self.seen.get_mut(&key) {
                bucket.retain(|&b| b != at);
                if bucket.is_empty() {
                    self.seen.remove(&key);
                }
            }
        }
        self.distinct_since_sweep = 0;
    }

    /// Write the whole archive to `path`, never holding more than one section of it.
    ///
    /// This is the finish a real build wants: the prefix is assembled in memory — it is the index,
    /// which is small next to the bodies — and the data section is copied straight from the scratch
    /// file onto the end. Nothing ever holds the archive.
    ///
    /// The header is parsed before the destination is touched, so a build that would not open does
    /// not leave a file behind that looks like it might.
    pub fn finish_to_path(mut self, path: &Path) -> Result<()> {
        let (header, prefix) = self.prefix()?;
        let mut out = File::create(path).map_err(|e| {
            crate::proto::Error(format!("cannot write {}: {e}", path.display()))
        })?;
        out.write_all(&prefix)
            .map_err(|e| crate::proto::Error(format!("cannot write {}: {e}", path.display())))?;
        let copied = self.data.copy_to(&mut out, path)?;
        if copied != header.data_len {
            return err(format!(
                "a .mamaps build declared a {} byte data section and copied {copied}",
                header.data_len,
            ));
        }
        Ok(())
    }

    /// The whole file, in memory.
    ///
    /// Kept for callers small enough not to care — the tests, and the tools that read an archive
    /// back before writing it — and for them the peak is one copy of the archive rather than the two
    /// it used to be. Anything the size of a region should use [`Self::finish_to_path`].
    pub fn finish(mut self) -> Result<Vec<u8>> {
        let (header, prefix) = self.prefix()?;
        // TEMPORARY instrumentation.
        eprintln!(
            "spill: {} confirms, {} from file ({:.2}%), {} bytes read back",
            self.data.confirms,
            self.data.confirms_from_file,
            100.0 * self.data.confirms_from_file as f64 / self.data.confirms.max(1) as f64,
            self.data.bytes_from_file,
        );
        let mut out = Vec::with_capacity(header.file_len as usize);
        out.extend_from_slice(&prefix);
        let copied = self.data.copy_to_vec(&mut out)?;
        if copied != header.data_len {
            return err(format!(
                "a .mamaps build declared a {} byte data section and copied {copied}",
                header.data_len,
            ));
        }
        debug_assert_eq!(out.len() as u64, header.file_len);
        Ok(out)
    }

    /// Chop the entries into leaves

    /// Chop the entries into leaves of at most `capacity`, rebasing each leaf's ids and offsets.
    ///
    /// `None` when some leaf's tile-id span will not fit the `u32` a [`LeafEntry::tile_id_lo`]
    /// carries, which the caller answers by choosing a different capacity. A leaf is also
    /// closed early when its data span would exceed u32::MAX — at planet scale the data
    /// section is >4 GiB and a count-only leaf can span 4294968552 bytes (u32::MAX+1257
    /// in the failed run) which the old `chunks(capacity)` path turned into a hard
    /// `u32::try_from` abort. Splitting by span as well as count bounds every
    /// LeafEntry.offset_delta to u32 without changing the wire format.
    ///
    /// Byte-identity: when no leaf's span nears 4 GiB (NA, us-west), this yields
    /// exactly the same leaf boundaries as `chunks(capacity)` — the span check
    /// only adds *earlier* splits for planet and never alters count-based splits.
    pub(crate) fn partition(&self, capacity: u32) -> Result<Option<Split>> {
        let mut root = Vec::new();
        let mut leaves = Vec::new();
        let cap = capacity as usize;
        let mut start = 0usize;
        let mut leaf_data_offset: u64 = 0; // cumulative leaf section offset (packed, not stride)
        while start < self.entries.len() {
            // Greedily grow this leaf until capacity or 4 GiB span would be exceeded.
            // Base is the *minimum* offset in the leaf, since content dedup lets a
            // later entry point at a body written for an earlier tile (negative delta
            // if based on first entry). Track min/max incrementally O(cap) per leaf.
            let mut end = start + 1;
            let mut cur_min = self.entries[start].offset;
            let mut cur_max = cur_min;
            while end < self.entries.len() && (end - start) < cap {
                let next_off = self.entries[end].offset;
                let new_min = cur_min.min(next_off);
                let new_max = cur_max.max(next_off);
                if new_max - new_min > u32::MAX as u64 {
                    break;
                }
                cur_min = new_min;
                cur_max = new_max;
                end += 1;
            }
            let chunk = &self.entries[start..end];
            let base_data_offset = chunk.iter().map(|e| e.offset).min().unwrap_or(0);
            let base_tile_id = chunk[0].tile_id;
            let last = &chunk[chunk.len() - 1];
            if !index::span_fits(last.tile_id + last.run_length as u64 - 1 - base_tile_id) {
                return Ok(None);
            }
            let mut leaf = Vec::with_capacity(chunk.len());
            for entry in chunk {
                let delta = u32::try_from(entry.offset - base_data_offset).map_err(|_| {
                    crate::proto::Error(format!(
                        "a .mamaps leaf spans {} bytes of the data section, past the {} a leaf entry \
                         addresses",
                        entry.offset - base_data_offset,
                        u32::MAX,
                    ))
                })?;
                leaf.push(LeafEntry {
                    tile_id_lo: (entry.tile_id - base_tile_id) as u32,
                    run_length: entry.run_length,
                    offset_delta: delta,
                    length: entry.length,
                });
            }
            let leaf_len_before = leaf.len() as u64;
            root.push(RootEntry {
                base_tile_id,
                leaf_offset: leaf_data_offset,
                base_data_offset,
                leaf_entry_count: chunk.len() as u32,
                // Filled in by `prefix`, which is the only place that knows whether leaves
                // store compressed: zero here keeps `partition` (and its byte-identity test)
                // about boundaries only, never about bytes.
                stored_len: 0,
            });
            leaf_data_offset += leaf_len_before * index::LEAF_ENTRY_LEN as u64;
            leaves.push(leaf);
            start = end;
        }
        Ok(Some((root, leaves)))
    }

    #[cfg(all(test, feature = "write"))]
    #[allow(dead_code)]
    pub(crate) fn partition_for_test(
        entries: &[Pending],
        capacity: u32,
    ) -> Result<Option<Split>> {
        let w = StreamWriter {
            options: Options::default(),
            data: Spill::create(None, None).expect("spill"),
            entries: entries.to_vec(),
            seen: SeenMap::default(),
            seen_order: std::collections::VecDeque::new(),
            last_id: None,
            last_body: Default::default(),
            distinct_since_sweep: 0,
            tiles_addressed: entries.len() as u64,
            distinct: entries.len() as u64,
            runs_used: false,
        };
        w.partition(capacity)
    }
}
