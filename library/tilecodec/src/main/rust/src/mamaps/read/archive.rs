use super::helpers::{decompress_with, zdict_extent};
use super::leaf_cache::LeafCache;
use crate::mamaps::body::Body;
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::Header;
use crate::mamaps::index::{LeafEntry, RootEntry, self};
use crate::pmtiles::tile_id;
use crate::proto::{Error, Result, err};
use crate::stream::{OPEN_PREFIX_BYTES, RangeReader};
use std::sync::Arc;

/// An open archive.
///
/// The parsed-leaf cache is shared across every reader of one archive: open
/// each worker's view with
/// [`open_with_cache`](MamapsArchive::open_with_cache) over the same
/// [`LeafCache`], and a cold leaf is fetched once no matter how many tiles
/// need it. That is the MapLibre-parity half of issue 815 — with a private
/// cache per worker, four workers covering one viewport fetched the same cold
/// leaves up to four times.
///
/// [`open`](MamapsArchive::open) keeps the old shape — a private cache — for
/// single-reader tools and tests, where sharing buys nothing.
pub struct MamapsArchive<R: RangeReader> {
    /// Reachable from the crate so the request-counting tests in [`super`] can assert on the
    /// number of round trips, which is the contract this whole layout exists to hold.
    pub(crate) reader: R,
    pub header: Header,
    pub dictionary: Dictionary,
    root: Vec<RootEntry>,
    leaves: Arc<LeafCache>,
    /// The archive's trained dictionaries (planet5 v10 headers), fetched once on open.
    zdicts: Option<Arc<crate::mamaps::zdict::ZDicts>>,
}

/// Where one tile's bytes live, decided from the root index plus the shared leaf cache.
///
/// Pure data — no I/O. `Ready` means the leaf is already cached and the body range is known;
/// `Cold` means the leaf is not cached yet and carries the root entry plus the leaf-relative
/// offset, which is everything the batch path needs to fetch the leaf in the same wave as
/// every other cold leaf and resolve the tile afterwards.
enum TileAddress {
    Absent,
    Ready { body_offset: u64, body_length: u32 },
    Cold { root: RootEntry, offset: u32 },
}

impl<R: RangeReader> MamapsArchive<R> {
    /// The archive's trained dictionaries, when its bodies use them (planet5).
    pub fn zdicts(&self) -> Option<&crate::mamaps::zdict::ZDicts> {
        self.zdicts.as_deref()
    }

    /// The reader this archive was opened on.
    ///
    /// So a caller can act on something it only learns from the header — the `build_id`, which
    /// decides whether the reader's disk cache is still addressing this build.
    pub fn reader(&self) -> &R {
        &self.reader
    }
    /// One range request: the header, the dictionary and the root index all live in the prefix.
    ///
    /// A file whose prefix does not hold all three is refused rather than fetched again. The
    /// writer asserts the budget, so a file that misses it was not written by this codec, and
    /// silently paying an extra round trip per open is exactly the cost the layout exists to
    /// avoid.
    ///
    /// The archive gets a private leaf cache. Readers that should share one —
    /// a surface's tile workers — want
    /// [`open_with_cache`](MamapsArchive::open_with_cache) instead.
    pub fn open(reader: R) -> Result<MamapsArchive<R>> {
        Self::open_with_cache(reader, Arc::new(LeafCache::new()))
    }

    /// [`open`](MamapsArchive::open) over a caller-supplied leaf cache.
    ///
    /// Every archive opened over the same [`LeafCache`] shares parsed leaves:
    /// the first worker to need a cold leaf fetches, inflates and parses it,
    /// and the rest take the cached copy. Thread-safe — `tile` and
    /// `tile_bytes` take `&self` — so one cache serves a whole surface.
    pub fn open_with_cache(reader: R, leaves: Arc<LeafCache>) -> Result<MamapsArchive<R>> {
        let prefix = reader.read(0, OPEN_PREFIX_BYTES)?;
        let header = Header::parse(&prefix)?;
        let section = |offset: u64, len: u64, what: &str| -> Result<&[u8]> {
            let end = offset + len;
            if end > prefix.len() as u64 {
                return err(format!(
                    "{what} ends at byte {end}, outside the {OPEN_PREFIX_BYTES} byte prefix a \
                     .mamaps open reads -- this archive was not written by this codec",
                ));
            }
            Ok(&prefix[offset as usize..end as usize])
        };
        let dictionary =
            Dictionary::parse(section(header.dict_offset, header.dict_len as u64, "the dictionary")?)?;
        dictionary.check_matches_schema()?;
        if dictionary.layers.len() != header.layer_count as usize {
            return err("a .mamaps header and dictionary disagree on the layer count");
        }
        let root =
            index::parse_root(section(header.root_offset, header.root_len as u64, "the root index")?)?;
        if root.len() != header.leaf_count as usize {
            return err(format!(
                "a .mamaps header declares {} leaves but its root has {} entries",
                header.leaf_count,
                root.len(),
            ));
        }
        // Planet5: one more read on open for the dictionaries, never one per tile.
        let zdicts = if header.compression == crate::mamaps::header::COMPRESSION_ZSTD_DICT {
            let (start, end) = zdict_extent(&header)?;
            let bytes = if end <= prefix.len() as u64 {
                prefix[start as usize..end as usize].to_vec()
            } else {
                reader.read(start, (end - start) as u32)?
            };
            if bytes.len() as u64 != end - start {
                return err("a .mamaps dictionary section was cut short");
            }
            Some(Arc::new(crate::mamaps::zdict::ZDicts::parse(&bytes)?))
        } else {
            None
        };
        Ok(MamapsArchive { reader, header, dictionary, root, leaves, zdicts })
    }

    /// The decoded body for a tile, or `None` when the archive does not hold it.
    ///
    /// `None` rather than an error: most of a coastal viewport is off the edge of coverage, and
    /// that is the ordinary answer rather than a fault.
    pub fn tile(&self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        let Some(bytes) = self.tile_bytes(z, x, y)? else { return Ok(None) };
        Ok(Some(Body::parse(&bytes)?))
    }

    /// The decoded bodies for many tiles at once, in request order with one slot per tile.
    ///
    /// The cold path (`tile_bytes`) costs one range read for a cached leaf and two for a cold
    /// one — and the second cannot start until the first lands, so serving tiles one at a time
    /// serialises every tile behind every other on a high-latency radio. Batching overlaps the
    /// latency instead: every cold leaf the batch needs is fetched concurrently first (one
    /// request per distinct leaf, published through the shared [`LeafCache`], so tiles sharing
    /// a leaf still cost one), then every body the batch needs is fetched concurrently. A warm
    /// screenful costs one wave; a fully cold one costs two — never two per tile.
    ///
    /// The waves ride [`RangeReader::read_batch`](crate::stream::RangeReader::read_batch), so a
    /// network reader overlaps them in one multiplexed fetch while a file reader serves them as
    /// slices. Failures and absences stay per-slot, exactly as in [`tile`](Self::tile): a tile
    /// the archive does not hold is `Ok(None)` in its slot, and a failed range is an error for
    /// that slot only.
    pub fn tiles(&self, tiles: &[(u8, u32, u32)]) -> Vec<Result<Option<Body>>> {
        if tiles.len() < 2 {
            return tiles.iter().map(|&(z, x, y)| self.tile(z, x, y)).collect();
        }
        // Pass 1: resolve every tile without I/O when its leaf is cached. Warm tiles yield
        // body addresses immediately; cold tiles queue for the leaf wave.
        let mut bodies: Vec<Option<(u64, u32)>> = Vec::with_capacity(tiles.len());
        let mut cold: Vec<Option<(RootEntry, u32)>> = Vec::with_capacity(tiles.len());
        let mut done: Vec<Option<Result<Option<Body>>>> = Vec::with_capacity(tiles.len());
        for _ in tiles {
            bodies.push(None);
            cold.push(None);
            done.push(None);
        }
        for (slot, &(z, x, y)) in tiles.iter().enumerate() {
            match self.address_of(z, x, y) {
                Ok(TileAddress::Absent) => done[slot] = Some(Ok(None)),
                Ok(TileAddress::Ready { body_offset, body_length }) => {
                    bodies[slot] = Some((body_offset, body_length));
                }
                Ok(TileAddress::Cold { root, offset }) => {
                    cold[slot] = Some((root, offset));
                }
                Err(e) => done[slot] = Some(Err(e)),
            }
        }
        // Wave 1: one request per distinct cold leaf, fetched concurrently.
        let mut leaf_order: Vec<u64> = Vec::new();
        let mut leaf_roots: Vec<RootEntry> = Vec::new();
        for slot in cold.iter().flatten() {
            if !leaf_order.contains(&slot.0.leaf_offset) {
                leaf_order.push(slot.0.leaf_offset);
                leaf_roots.push(slot.0);
            }
        }
        // A leaf whose disk range fails its bounds cannot be fetched; its tiles fail now.
        let mut leaf_failed: Vec<u64> = Vec::new();
        let mut leaf_errors: Vec<(u64, String)> = Vec::new();
        let mut leaf_requests: Vec<(u64, u32)> = Vec::new();
        let mut leaf_slots: Vec<u64> = Vec::new();
        for root in &leaf_roots {
            match self.leaf_disk_range(root) {
                Some((offset, length)) => {
                    leaf_slots.push(root.leaf_offset);
                    leaf_requests.push((offset, length));
                }
                None => {
                    // The root entry's own bounds fail: no read is issued (a read of the wrong
                    // length would poison the disk cache entry), and the leaf's tiles fail now.
                    leaf_failed.push(root.leaf_offset);
                    leaf_errors.push((
                        root.leaf_offset,
                        "a .mamaps root entry runs past the leaf section".to_string(),
                    ));
                }
            }
        }
        if !leaf_requests.is_empty() {
            let fetched = self.reader.read_batch(&leaf_requests);
            for (i, leaf_offset) in leaf_slots.iter().enumerate() {
                let root = &leaf_roots[i];
                match fetched.get(i) {
                    Some(Ok(stored)) if self.publish_leaf(root, stored) => {}
                    Some(Ok(stored)) => {
                        leaf_failed.push(*leaf_offset);
                        leaf_errors.push((
                            *leaf_offset,
                            format!(
                                "a .mamaps leaf of {} bytes is not the leaf its root entry declares",
                                stored.len(),
                            ),
                        ));
                    }
                    Some(Err(e)) => {
                        leaf_failed.push(*leaf_offset);
                        leaf_errors.push((*leaf_offset, e.0.clone()));
                    }
                    None => {
                        leaf_failed.push(*leaf_offset);
                    }
                }
            }
        }
        // Pass 2: cold tiles resolve against the now-warm cache. A leaf that failed its
        // fetch fails its tiles; anything else must hit (it was just published).
        for (slot, slot_cold) in cold.iter().enumerate() {
            if done[slot].is_some() {
                continue;
            }
            let Some((root, offset)) = slot_cold else {
                continue;
            };
            if leaf_failed.contains(&root.leaf_offset) {
                let msg = leaf_errors
                    .iter()
                    .find(|(o, _)| *o == root.leaf_offset)
                    .map(|(_, m)| m.clone())
                    .unwrap_or_else(|| "a .mamaps leaf fetch failed".to_string());
                done[slot] = Some(err(msg));
                continue;
            }
            match self.leaves.get(root.leaf_offset) {
                Some(leaf) => match index::find_tile(&leaf, *offset).copied() {
                    Some(entry) => {
                        let at = root.base_data_offset + entry.offset_delta as u64;
                        if at + entry.length as u64 > self.header.data_len {
                            done[slot] = Some(err(
                                "a .mamaps leaf entry runs past the tile data section",
                            ));
                        } else {
                            bodies[slot] =
                                Some((self.header.data_offset + at, entry.length));
                        }
                    }
                    None => done[slot] = Some(Ok(None)),
                },
                None => {
                    done[slot] = Some(err("a .mamaps batch left a tile unresolved"));
                }
            }
        }
        // Wave 2: every wanted body, fetched concurrently in slot order.
        let mut body_slots: Vec<usize> = Vec::new();
        let mut body_requests: Vec<(u64, u32)> = Vec::new();
        for (slot, slot_body) in bodies.iter().enumerate() {
            if done[slot].is_some() {
                continue;
            }
            if let Some((offset, length)) = slot_body {
                body_slots.push(slot);
                body_requests.push((*offset, *length));
            }
        }
        if !body_requests.is_empty() {
            let found = self.reader.read_batch(&body_requests);
            for (i, slot) in body_slots.iter().enumerate() {
                let (offset, length) = body_requests[i];
                match found.get(i) {
                    Some(Ok(stored)) if stored.len() == length as usize => {
                        match decompress_with(self.header.compression, stored, self.zdicts.as_deref()) {
                            Ok(raw) => match Body::parse(&raw) {
                                Ok(body) => done[*slot] = Some(Ok(Some(body))),
                                Err(e) => done[*slot] = Some(Err(e)),
                            },
                            Err(e) => done[*slot] = Some(Err(e)),
                        }
                    }
                    Some(Ok(stored)) => {
                        done[*slot] = Some(err(format!(
                            "a .mamaps read of a tile body wanted {length} bytes at {offset}, got {}",
                            stored.len(),
                        )));
                    }
                    Some(Err(e)) => done[*slot] = Some(Err(Error(e.0.clone()))),
                    None => {
                        done[*slot] = Some(err("a .mamaps batch left a tile unresolved"));
                    }
                }
            }
        }
        done.into_iter()
            .map(|slot| slot.unwrap_or_else(|| err("a .mamaps batch left a tile unresolved")))
            .collect()
    }

    /// Where one tile's bytes live, decided from the root index plus the shared leaf cache.
    ///
    /// Pure except for the cache lookup (a lock, never I/O): `Cold` carries the root entry and
    /// the leaf-relative offset, which is everything the batch path needs to fetch the leaf in
    /// the same wave as every other cold leaf and resolve the tile afterwards.
    fn address_of(&self, z: u8, x: u32, y: u32) -> Result<TileAddress> {
        if z < self.header.min_zoom || z > self.header.max_zoom || z >= 32 {
            return Ok(TileAddress::Absent);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(TileAddress::Absent);
        }
        let want = tile_id(z, x as u64, y as u64);
        let Some(root) = index::find_leaf(&self.root, want).copied() else {
            return Ok(TileAddress::Absent);
        };
        // Ahead of the leaf's base is impossible (the root is ordered), so this only rules out a
        // tile past a `u32` of span, which the writer refuses to produce.
        let Ok(offset) = u32::try_from(want - root.base_tile_id) else {
            return Ok(TileAddress::Absent);
        };
        match self.leaves.get(root.leaf_offset) {
            Some(leaf) => {
                let Some(entry) = index::find_tile(&leaf, offset).copied() else {
                    return Ok(TileAddress::Absent);
                };
                // Every bound from a field, checked before the read rather than after it.
                let at = root.base_data_offset + entry.offset_delta as u64;
                if at + entry.length as u64 > self.header.data_len {
                    return err("a .mamaps leaf entry runs past the tile data section");
                }
                Ok(TileAddress::Ready {
                    body_offset: self.header.data_offset + at,
                    body_length: entry.length,
                })
            }
            None => Ok(TileAddress::Cold { root, offset }),
        }
    }

    /// The on-disk range of a leaf, for batched pre-warming. `None` when the root entry's own
    /// bounds fail — the caller fails those tiles rather than issuing a read of a wrong length
    /// (which would poison the disk cache entry for that range).
    fn leaf_disk_range(&self, root: &RootEntry) -> Option<(u64, u32)> {
        let raw_len = root.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        let stored_len = if root.stored_len == 0 { raw_len } else { root.stored_len as u64 };
        if root.leaf_offset + stored_len > self.header.leaf_len {
            return None;
        }
        let length = u32::try_from(stored_len).ok()?;
        Some((self.header.leaf_offset + root.leaf_offset, length))
    }

    /// Inflate, parse and publish one fetched leaf — exactly what [`leaf`](Self::leaf) does
    /// after its read. `false` when the bytes are not a leaf at all (wrong length, corrupt
    /// frame, unparsable), so the caller fails those tiles instead of caching garbage.
    fn publish_leaf(&self, root: &RootEntry, stored: &[u8]) -> bool {
        let raw_len = root.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        let raw = if root.stored_len == 0 {
            if stored.len() as u64 != raw_len {
                return false;
            }
            stored.to_vec()
        } else {
            let Ok(raw_len) = usize::try_from(raw_len) else {
                return false;
            };
            match index::decompress_leaf(stored, raw_len) {
                Ok(raw) => raw,
                Err(_) => return false,
            }
        };
        match index::parse_leaf(&raw) {
            Ok(entries) => {
                self.leaves.insert(root.leaf_offset, entries);
                true
            }
            Err(_) => false,
        }
    }

    /// The decompressed body bytes for a tile, undecoded.
    pub fn tile_bytes(&self, z: u8, x: u32, y: u32) -> Result<Option<Vec<u8>>> {
        if z < self.header.min_zoom || z > self.header.max_zoom || z >= 32 {
            return Ok(None);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(None);
        }
        let want = tile_id(z, x as u64, y as u64);
        let Some(root) = index::find_leaf(&self.root, want).copied() else { return Ok(None) };
        // Ahead of the leaf's base is impossible (the root is ordered), so this only rules out a
        // tile past a `u32` of span, which the writer refuses to produce.
        let Ok(offset) = u32::try_from(want - root.base_tile_id) else { return Ok(None) };

        let leaf = self.leaf(&root)?;
        let Some(entry) = index::find_tile(&leaf, offset).copied() else { return Ok(None) };

        // Every bound from a field, checked before the read rather than after it.
        let at = root.base_data_offset + entry.offset_delta as u64;
        if at + entry.length as u64 > self.header.data_len {
            return err("a .mamaps leaf entry runs past the tile data section");
        }
        let stored = self.exact(self.header.data_offset + at, entry.length, "a tile body")?;
        Ok(Some(decompress_with(self.header.compression, &stored, self.zdicts.as_deref())?))
    }

    /// The parsed leaf a root entry addresses, from the shared cache or over the wire.
    ///
    /// A stored leaf is one raw-DEFLATE frame; it inflates whole to exactly
    /// `leaf_entry_count * 16` bytes and then binary-searches in memory like a raw leaf always
    /// did. The request count is unchanged: one range read per cold leaf, whatever it stores —
    /// but now one per cold leaf *per cache*, not per reader, because the
    /// fetch happens outside the cache lock and the first copy published wins.
    fn leaf(&self, root: &RootEntry) -> Result<Arc<Vec<LeafEntry>>> {
        if let Some(hit) = self.leaves.get(root.leaf_offset) {
            return Ok(hit);
        }
        let raw_len = root.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        let stored_len = if root.stored_len == 0 { raw_len } else { root.stored_len as u64 };
        if root.leaf_offset + stored_len > self.header.leaf_len as u64 {
            return err("a .mamaps root entry runs past the leaf section");
        }
        let length = u32::try_from(stored_len)
            .map_err(|_| Error("a .mamaps leaf is larger than a single read".to_string()))?;
        let stored = self.exact(self.header.leaf_offset + root.leaf_offset, length, "a leaf")?;
        // Zero is a raw legacy leaf; anything else is the frame length. The inflated length is
        // known from the entry count either way, so a corrupt frame fails here rather than as a
        // wrong tile later — and a read of the wrong length never reaches the disk cache.
        let raw = if root.stored_len == 0 {
            if stored.len() as u64 != raw_len {
                return err(format!(
                    "a .mamaps read of a leaf wanted {raw_len} bytes at {}, got {}",
                    self.header.leaf_offset + root.leaf_offset,
                    stored.len(),
                ));
            }
            stored
        } else {
            let raw_len = usize::try_from(raw_len)
                .map_err(|_| Error("a .mamaps leaf is larger than a single read".to_string()))?;
            index::decompress_leaf(&stored, raw_len)?
        };
        let entries = index::parse_leaf(&raw)?;
        Ok(self.leaves.insert(root.leaf_offset, entries))
    }

    /// A read that must come back at its full length.
    ///
    /// A short body means the range was not honoured. Decompressing it would fail somewhere far
    /// less informative, and caching it would poison that range for every later read.
    fn exact(&self, offset: u64, length: u32, what: &str) -> Result<Vec<u8>> {
        let body = self.reader.read(offset, length)?;
        if body.len() != length as usize {
            return err(format!(
                "a .mamaps read of {what} wanted {length} bytes at {offset}, got {}",
                body.len(),
            ));
        }
        Ok(body)
    }
}
