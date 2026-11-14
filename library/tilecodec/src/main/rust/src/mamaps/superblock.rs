//! Superblocks (planet5, header v11): several neighbouring tiles' bodies in one compressed frame.
//!
//! Tile bodies compress independently, so a dictionary is the only thing two neighbouring tiles
//! share. Measured on US West, compressing the bodies of a 4x4 / 8x8 block of same-zoom tiles as
//! one dictionary-zstd frame saves 5-20% (most at z12-z13, where neighbours repeat landuse,
//! roads and terrain): about 4.5 GB on the planet.
//!
//! A superblock's decompressed bytes start with a 16-byte header shaped like a body header, so
//! the stored frame is exactly a body frame (`raw_len` at bytes 4..8, the header stored raw ahead
//! of the zstd frame) and [`crate::mamaps::read::decompress_with`] needs no change:
//!
//! ```text
//! [magic "MSB1"][raw_len u32][member count u16][reserved u16 = 0][reserved u32 = 0]
//! [member: tile_id u64, offset u32, len u32] x count   (offset from the superblock start)
//! [member bodies: complete raw bodies, "MBD" + version ...]
//! ```
//!
//! Member tiles are an aligned 2^g x 2^g block at one zoom, so they are contiguous in tile-id
//! (Hilbert) order; every member's leaf entry addresses the same stored frame. A body starts
//! with "MBD", a superblock with "MSB": the first three bytes tell them apart.

use crate::proto::{err, Result};

pub const SUPERBLOCK_MAGIC: &[u8; 4] = b"MSB1";
pub const SUPERBLOCK_HEADER_LEN: usize = 16;
pub const SUPERBLOCK_MEMBER_LEN: usize = 16;

/// True when decompressed `raw` is a superblock rather than a body.
pub fn is_superblock(raw: &[u8]) -> bool {
    raw.len() >= SUPERBLOCK_HEADER_LEN && &raw[..4] == SUPERBLOCK_MAGIC
}

/// The members `(tile_id, body bytes)` of a decompressed superblock, validated.
pub fn members(raw: &[u8]) -> Result<Vec<(u64, &[u8])>> {
    if !is_superblock(raw) {
        return err("not a .mamaps superblock");
    }
    let raw_len = u32::from_le_bytes(raw[4..8].try_into().unwrap()) as usize;
    if raw_len != raw.len() {
        return err("a .mamaps superblock's length disagrees with its header");
    }
    let n = u16::from_le_bytes([raw[8], raw[9]]) as usize;
    let table_end = SUPERBLOCK_HEADER_LEN + n * SUPERBLOCK_MEMBER_LEN;
    if table_end > raw.len() {
        return err("a .mamaps superblock's member table runs past its end");
    }
    let mut out = Vec::with_capacity(n);
    let mut prev_id: Option<u64> = None;
    for i in 0..n {
        let at = SUPERBLOCK_HEADER_LEN + i * SUPERBLOCK_MEMBER_LEN;
        let id = u64::from_le_bytes(raw[at..at + 8].try_into().unwrap());
        let off = u32::from_le_bytes(raw[at + 8..at + 12].try_into().unwrap()) as usize;
        let len = u32::from_le_bytes(raw[at + 12..at + 16].try_into().unwrap()) as usize;
        if prev_id.is_some_and(|p| id <= p) {
            return err("a .mamaps superblock's members are not in tile-id order");
        }
        prev_id = Some(id);
        if off < table_end || off.checked_add(len).is_none_or(|e| e > raw.len()) {
            return err("a .mamaps superblock member lies outside the superblock");
        }
        out.push((id, &raw[off..off + len]));
    }
    Ok(out)
}

/// The body of `tile_id` inside decompressed superblock `raw`, or `None` when it is not a member.
pub fn member(raw: &[u8], tile_id: u64) -> Result<Option<&[u8]>> {
    let m = members(raw)?;
    Ok(m.binary_search_by_key(&tile_id, |e| e.0).ok().map(|i| m[i].1))
}

/// `raw` itself when it is a body; the requested member's body when it is a superblock (an error
/// when the tile is not a member: the leaf entry pointed a tile at the wrong frame).
pub fn resolve(raw: Vec<u8>, tile_id: u64) -> Result<Vec<u8>> {
    if !is_superblock(&raw) {
        return Ok(raw);
    }
    match member(&raw, tile_id)? {
        Some(b) => Ok(b.to_vec()),
        None => err(format!("tile {tile_id} is not a member of the superblock its leaf entry addresses")),
    }
}

/// Assemble a superblock from `(tile_id, raw body)` members in ascending tile-id order.
pub fn assemble(members: &[(u64, Vec<u8>)]) -> Vec<u8> {
    assert!(!members.is_empty() && members.len() <= u16::MAX as usize);
    let table_end = SUPERBLOCK_HEADER_LEN + members.len() * SUPERBLOCK_MEMBER_LEN;
    let total = table_end + members.iter().map(|m| m.1.len()).sum::<usize>();
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(SUPERBLOCK_MAGIC);
    out.extend_from_slice(&(total as u32).to_le_bytes());
    out.extend_from_slice(&(members.len() as u16).to_le_bytes());
    out.extend_from_slice(&[0u8; 6]);
    let mut at = table_end;
    for (id, body) in members {
        out.extend_from_slice(&id.to_le_bytes());
        out.extend_from_slice(&(at as u32).to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        at += body.len();
    }
    for (_, body) in members {
        out.extend_from_slice(body);
    }
    out
}

/// A small shared cache of decompressed superblocks, keyed by stored offset: every member of a
/// block costs one decompress, not one each. Bounded by decoded bytes.
pub struct SuperblockCache {
    #[allow(clippy::type_complexity)]
    inner: std::sync::Mutex<(std::collections::HashMap<(u64, u64), std::sync::Arc<Vec<u8>>>, std::collections::VecDeque<(u64, u64)>, usize)>,
    cap_bytes: usize,
}

impl SuperblockCache {
    pub fn new(cap_bytes: usize) -> SuperblockCache {
        SuperblockCache { inner: std::sync::Mutex::new(Default::default()), cap_bytes }
    }

    /// The cached superblock stored at `offset` of the archive with `build_id`.
    pub fn get(&self, build_id: u64, offset: u64) -> Option<std::sync::Arc<Vec<u8>>> {
        self.inner.lock().ok()?.0.get(&(build_id, offset)).cloned()
    }

    pub fn put(&self, build_id: u64, offset: u64, raw: std::sync::Arc<Vec<u8>>) {
        let Ok(mut g) = self.inner.lock() else { return };
        let key = (build_id, offset);
        if g.0.contains_key(&key) {
            return;
        }
        g.2 += raw.len();
        g.0.insert(key, raw);
        g.1.push_back(key);
        while g.2 > self.cap_bytes {
            let Some(old) = g.1.pop_front() else { break };
            if let Some(v) = g.0.remove(&old) {
                g.2 -= v.len();
            }
        }
    }
}

/// The process-wide cache every [`crate::mamaps::MamapsArchive`] shares (the renderer runs one
/// archive view per worker; the tiles of one block are often decoded by different workers).
pub fn shared_cache() -> &'static SuperblockCache {
    static CACHE: std::sync::OnceLock<SuperblockCache> = std::sync::OnceLock::new();
    CACHE.get_or_init(SuperblockCache::default)
}

impl Default for SuperblockCache {
    fn default() -> Self {
        // 16 MiB: a screenful of 8x8 z13 blocks.
        SuperblockCache::new(16 << 20)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_superblock_round_trips_and_resolves_members() {
        let m = vec![(5u64, b"MBD\x0bbody-five".to_vec()), (9, b"MBD\x0bnine".to_vec()), (12, b"MBD\x0btwelve!".to_vec())];
        let raw = assemble(&m);
        assert!(is_superblock(&raw));
        let back = members(&raw).expect("members");
        assert_eq!(back.len(), 3);
        for ((id, body), (bid, bbody)) in m.iter().zip(back.iter()) {
            assert_eq!(id, bid);
            assert_eq!(&body[..], *bbody);
        }
        assert_eq!(resolve(raw.clone(), 9).unwrap(), b"MBD\x0bnine".to_vec());
        assert!(resolve(raw.clone(), 10).is_err(), "a non-member is an error");
        assert_eq!(resolve(b"MBD\x0bplain".to_vec(), 77).unwrap(), b"MBD\x0bplain".to_vec());
        let mut bad = raw.clone();
        bad[4] ^= 1;
        assert!(members(&bad).is_err(), "length mismatch");
    }

    #[test]
    fn the_cache_evicts_by_bytes() {
        let c = SuperblockCache::new(100);
        c.put(7, 1, std::sync::Arc::new(vec![0; 60]));
        c.put(7, 2, std::sync::Arc::new(vec![0; 60]));
        assert!(c.get(7, 1).is_none() && c.get(7, 2).is_some());
        assert!(c.get(8, 2).is_none(), "keyed by build id too");
    }
}
