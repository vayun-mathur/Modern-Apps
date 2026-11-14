//! ZSEC: random-access block compression for byte-addressed sidecar sections (planet5).
//!
//! [`crate::blocked`] (ZBLK) compresses *record* tables, where a lookup is `record(id)`. Transit
//! and other sidecars are addressed by byte offset instead (varint streams, string pools,
//! polyline blobs, fixed-stride tables read by index), so ZSEC blocks the raw bytes: fixed
//! `block_size` byte ranges, each an independent zstd frame, behind an offset table. A reader
//! asks for the bytes at `[pos, pos + n)`, inflates the one or two blocks they span and caches
//! them; nothing is ever decoded whole.
//!
//! ```text
//! [magic "ZSEC" u32][block_size u32][raw_len u64][block_count u32][reserved u32 = 0]
//! [offsets u64 x (block_count + 1)]   absolute from the section start; last = section end
//! [zstd frame][zstd frame]...
//! ```
//!
//! Measured on the planet: 64 KiB blocks at zstd-19 shrink `world.transit` 2.56x and the POI
//! sections 1.4-40x, at ~0.4 ms of host decode per cold block.

use crate::proto::{err, Error, Result};

pub const ZSEC_MAGIC: &[u8; 4] = b"ZSEC";
pub const ZSEC_HEADER_LEN: usize = 24;
/// The block size the writers use.
pub const ZSEC_BLOCK: usize = 64 * 1024;

/// A parsed ZSEC section over bytes the caller keeps alive.
#[derive(Clone, Copy)]
pub struct ZSec<'a> {
    bytes: &'a [u8],
    block_size: usize,
    raw_len: u64,
    block_count: usize,
}

impl<'a> ZSec<'a> {
    pub fn parse(bytes: &'a [u8]) -> Result<ZSec<'a>> {
        if bytes.len() < ZSEC_HEADER_LEN || &bytes[..4] != ZSEC_MAGIC {
            return err("not a ZSEC section (bad magic)");
        }
        let u32_at = |o: usize| u32::from_le_bytes(bytes[o..o + 4].try_into().unwrap());
        let block_size = u32_at(4) as usize;
        let raw_len = u64::from_le_bytes(bytes[8..16].try_into().unwrap());
        let block_count = u32_at(16) as usize;
        if u32_at(20) != 0 {
            return err("a ZSEC section sets reserved bits");
        }
        if block_size == 0 || (raw_len as u128) > block_count as u128 * block_size as u128 {
            return err("a ZSEC section's blocks cannot hold its length");
        }
        if block_count > 0 && (raw_len as u128) <= (block_count as u128 - 1) * block_size as u128 {
            return err("a ZSEC section has more blocks than its length needs");
        }
        let table_end = ZSEC_HEADER_LEN + (block_count + 1) * 8;
        if table_end > bytes.len() {
            return err("a ZSEC offset table runs past its section");
        }
        let z = ZSec { bytes, block_size, raw_len, block_count };
        let mut prev = table_end as u64;
        for i in 0..=block_count {
            let o = z.offset(i);
            if o < prev || o > bytes.len() as u64 {
                return err("a ZSEC offset table is out of order or out of bounds");
            }
            prev = o;
        }
        if z.offset(block_count) != bytes.len() as u64 {
            return err("a ZSEC section's last offset is not its end");
        }
        Ok(z)
    }

    fn offset(&self, i: usize) -> u64 {
        let at = ZSEC_HEADER_LEN + i * 8;
        u64::from_le_bytes(self.bytes[at..at + 8].try_into().unwrap())
    }

    pub fn raw_len(&self) -> u64 {
        self.raw_len
    }

    pub fn block_size(&self) -> usize {
        self.block_size
    }

    pub fn block_count(&self) -> usize {
        self.block_count
    }

    /// The decoded length of block `b` (the last one holds the remainder).
    pub fn block_len(&self, b: usize) -> usize {
        let start = b as u64 * self.block_size as u64;
        (self.raw_len - start).min(self.block_size as u64) as usize
    }

    /// Inflate block `b`.
    pub fn block(&self, b: usize) -> Result<Vec<u8>> {
        if b >= self.block_count {
            return err(format!("ZSEC block {b} of {}", self.block_count));
        }
        let frame = &self.bytes[self.offset(b) as usize..self.offset(b + 1) as usize];
        let mut out = vec![0u8; self.block_len(b)];
        let mut dec = ruzstd::decoding::FrameDecoder::new();
        let n = dec
            .decode_all(frame, &mut out)
            .map_err(|e| Error(format!("a ZSEC block will not decode: {e:?}")))?;
        if n != out.len() {
            return err("a ZSEC block decoded to the wrong length");
        }
        Ok(out)
    }
}

/// Compress `raw` into a ZSEC section: `block_size` blocks, zstd `level`, blocks compressed on
/// all cores (each is independent, so the output is the same at any thread count).
#[cfg(feature = "write")]
pub fn write_zsec(raw: &[u8], block_size: usize, level: i32) -> Vec<u8> {
    let blocks: Vec<&[u8]> = raw.chunks(block_size.max(1)).collect();
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).max(1);
    let per = blocks.len().div_ceil(threads).max(1);
    let frames: Vec<Vec<u8>> = std::thread::scope(|s| {
        let handles: Vec<_> = blocks
            .chunks(per)
            .map(|run| {
                s.spawn(move || {
                    let cfg = zstd_rs::CompressionConfig { level, ..zstd_rs::CompressionConfig::DEFAULT };
                    let mut enc = zstd_rs::Compressor::new(cfg).expect("valid zstd level");
                    run.iter()
                        .map(|b| {
                            let mut out = Vec::with_capacity(b.len() / 2);
                            enc.compress(b, None, &mut out).expect("zstd encode of an in-memory block");
                            out
                        })
                        .collect::<Vec<_>>()
                })
            })
            .collect();
        handles.into_iter().flat_map(|h| h.join().expect("a ZSEC compression thread")).collect()
    });
    let table_end = ZSEC_HEADER_LEN + (frames.len() + 1) * 8;
    let total = table_end + frames.iter().map(|f| f.len()).sum::<usize>();
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(ZSEC_MAGIC);
    out.extend_from_slice(&(block_size as u32).to_le_bytes());
    out.extend_from_slice(&(raw.len() as u64).to_le_bytes());
    out.extend_from_slice(&(frames.len() as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    let mut at = table_end as u64;
    for f in &frames {
        out.extend_from_slice(&at.to_le_bytes());
        at += f.len() as u64;
    }
    out.extend_from_slice(&at.to_le_bytes());
    for f in &frames {
        out.extend_from_slice(f);
    }
    out
}

#[cfg(all(test, feature = "write"))]
mod tests {
    use super::*;

    #[test]
    fn a_zsec_section_round_trips_every_block() {
        for len in [0usize, 1, 1000, ZSEC_BLOCK, ZSEC_BLOCK + 1, 3 * ZSEC_BLOCK + 17] {
            let raw: Vec<u8> = (0..len).map(|i| (i * 31 % 251) as u8).collect();
            let sec = write_zsec(&raw, ZSEC_BLOCK, 19);
            let z = ZSec::parse(&sec).expect("parse");
            assert_eq!(z.raw_len(), len as u64);
            let mut back = Vec::new();
            for b in 0..z.block_count() {
                back.extend_from_slice(&z.block(b).expect("block"));
            }
            assert_eq!(back, raw, "len {len}");
        }
    }

    #[test]
    fn a_corrupt_zsec_section_is_refused() {
        let sec = write_zsec(&[7u8; 100_000], ZSEC_BLOCK, 3);
        assert!(ZSec::parse(&sec[..sec.len() - 1]).is_err(), "truncated");
        let mut bad = sec.clone();
        bad[0] = b'X';
        assert!(ZSec::parse(&bad).is_err(), "magic");
    }
}
