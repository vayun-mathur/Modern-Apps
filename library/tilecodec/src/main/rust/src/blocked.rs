//! Blocked zstd sections: random-access compression for graph tables.
//!
//! `nodes.bin` (12 B records), `edges.bin` (7 B records) and `elevation.bin`
//! (`i16`s) are read by index throughout routing — `node(id)` is pointer
//! arithmetic — so whole-file compression would cost a full inflate per
//! lookup. Instead each file is independent zstd frames over 4096-record
//! blocks behind a `u64` offset table: a lookup inflates one block (49–196 KB
//! of records) and caches it.
//!
//! Wire (all little-endian):
//!
//! ```text
//! [magic "ZBLK" u32][flags u32, 0][record_len u32][records_per_block u32]
//! [unpacked_len u64][block_count u32][offsets u64 x (block_count + 1)]
//! [zstd frame][zstd frame]...
//! ```
//!
//! * `record_len` is the caller's stride (12/7/2); `records_per_block` is the
//!   blocking (4096). The last block holds the remainder.
//! * `offsets` are absolute from file start; `offsets[block_count]` is the
//!   file end, so a block's length needs no separate table.
//! * A record id maps to `block = id / records_per_block`,
//!   `index = id % records_per_block`, bytes
//!   `block_unpacked[0..] + index * record_len`.
//!
//! The writer ([`write_blocked`], behind the `write` feature) and the reader
//! ([`Blocked`]) share this layout and nothing else: the MARG version gates
//! which layout a file holds, and the magic cross-checks it.

use crate::proto::{Error, Result, err};

/// Magic opening every blocked section.
pub const BLOCKED_MAGIC: u32 = 0x4B4C_425A; // "ZBLK" LE
/// Records per zstd frame. 4096 × 12 B = 48 KiB nodes, 4096 × 7 B = 28 KiB
/// edges: small enough to inflate on a lookup, large enough to compress.
pub const BLOCK_RECORDS: u32 = 4096;

/// A parsed blocked section over bytes the caller keeps alive.
///
/// Holds the header + offset table only — no frames are inflated until
/// [`Blocked::block`] is called, so opening is O(table), not O(file).
pub struct Blocked<'a> {
    bytes: &'a [u8],
    record_len: usize,
    records_per_block: u64,
    unpacked_len: u64,
    offsets: &'a [u8],
    block_count: usize,
}

impl<'a> Blocked<'a> {
    /// Parse the header + offset table. Refuses bad magic, unknown flags, a
    /// table that runs past the file, or offsets that are unordered / out of
    /// bounds.
    pub fn parse(bytes: &'a [u8]) -> Result<Blocked<'a>> {
        if bytes.len() < 24 {
            return err("a blocked section ends before its header");
        }
        let u32_at = |o: usize| {
            u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]])
        };
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3], bytes[o + 4],
                bytes[o + 5], bytes[o + 6], bytes[o + 7],
            ])
        };
        if u32_at(0) != BLOCKED_MAGIC {
            return err("not a blocked section (bad ZBLK magic)");
        }
        if u32_at(4) != 0 {
            return err("a blocked section sets unknown flags");
        }
        let record_len = u32_at(8) as usize;
        let records_per_block = u32_at(12) as u64;
        let unpacked_len = u64_at(16);
        if record_len == 0 || records_per_block == 0 {
            return err("a blocked section has a zero stride");
        }
        if bytes.len() < 28 {
            return err("a blocked section ends before its block count");
        }
        let block_count = u32_at(24) as usize;
        let table_at = 28usize;
        let table_end = table_at
            .checked_add((block_count + 1).checked_mul(8).ok_or_else(|| {
                Error("a blocked section's offset table overflows".to_string())
            })?)
            .ok_or_else(|| Error("a blocked section's offset table overflows".to_string()))?;
        if table_end > bytes.len() {
            return err("a blocked section's offset table runs past the file");
        }
        // Offsets ascend within the section; the last is the records' end.
        // It is NOT required to equal the file end: a blocked record array
        // may share its file with trailing tables (`edges.bin`'s escapes and
        // names follow it), so callers that need an exact file shape check
        // the tail themselves. Truncation is still caught: an offset past the
        // available bytes refuses above.
        let mut prev = table_at as u64;
        for i in 0..=block_count {
            let o = u64::from_le_bytes(
                bytes[table_at + i * 8..table_at + i * 8 + 8].try_into().expect("8 bytes"),
            );
            if o < prev || o > bytes.len() as u64 {
                return err("a blocked section's offsets are unordered or out of bounds");
            }
            prev = o;
        }
        Ok(Blocked {
            bytes,
            record_len,
            records_per_block,
            unpacked_len,
            offsets: &bytes[table_at..table_end],
            block_count,
        })
    }

    /// Unpacked byte length the header declares.
    pub fn unpacked_len(&self) -> u64 {
        self.unpacked_len
    }

    /// Start offset of block `b` (absolute from section start), or `None`
    /// when out of range. `offset(block_count)` is the section end.
    pub fn block_offset(&self, b: usize) -> Option<u64> {
        if b > self.block_count {
            return None;
        }
        Some(u64::from_le_bytes(
            self.offsets[b * 8..b * 8 + 8].try_into().ok()?,
        ))
    }

    /// Record stride.
    pub fn record_len(&self) -> usize {
        self.record_len
    }

    /// How many blocks the file holds.
    pub fn block_count(&self) -> usize {
        self.block_count
    }

    fn offset(&self, i: usize) -> u64 {
        u64::from_le_bytes(self.offsets[i * 8..i * 8 + 8].try_into().expect("8 bytes"))
    }

    /// Inflate block `b` (0-based). Returns the unpacked block bytes.
    ///
    /// Pure-Rust decode (`ruzstd`), no C toolchain — same constraint as tile
    /// bodies. The caller caches: a routing lookup touches neighbouring
    /// records, so the same block serves many reads.
    pub fn block(&self, b: usize) -> Result<Vec<u8>> {
        if b >= self.block_count {
            return err(format!("block {b} past the {} in the section", self.block_count));
        }
        let (start, end) = (self.offset(b) as usize, self.offset(b + 1) as usize);
        let frame = &self.bytes[start..end];
        let mut decoder = ruzstd::decoding::StreamingDecoder::new(frame)
            .map_err(|e| Error(format!("a blocked section will not decode: {e:?}")))?;
        let mut out = Vec::new();
        use std::io::Read as _;
        decoder
            .read_to_end(&mut out)
            .map_err(|e| Error(format!("a blocked section will not decode: {e:?}")))?;
        Ok(out)
    }

    /// Copy record `id` into a caller buffer. Convenience over [`Blocked::block`]
    /// for tests; hot loaders cache the block and index it directly.
    pub fn record(&self, id: u64, cache: &mut BlockCache) -> Result<Vec<u8>> {
        let b = (id / self.records_per_block) as usize;
        let block = cache.get(self, b)?;
        let at = (id % self.records_per_block) as usize * self.record_len;
        if at + self.record_len > block.len() {
            return err("a blocked record runs past its block");
        }
        Ok(block[at..at + self.record_len].to_vec())
    }
}

/// A tiny block cache: the last-inflated block plus an LRU spill.
///
/// Routing walks neighbouring records, so a one-block cache hits constantly;
/// the spill covers the working set without an unbounded map. Not thread-safe
/// by itself — the loader owns the locking.
pub struct BlockCache {
    last: Option<(usize, Vec<u8>)>,
    spill: std::collections::HashMap<usize, Vec<u8>>,
    spill_cap: usize,
}

impl BlockCache {
    /// An empty cache holding up to `spill_cap` older blocks besides the last.
    pub fn new(spill_cap: usize) -> BlockCache {
        BlockCache {
            last: None,
            spill: std::collections::HashMap::new(),
            spill_cap,
        }
    }

    /// The bytes of block `b`, inflating once and caching after.
    pub fn get(&mut self, section: &Blocked<'_>, b: usize) -> Result<&Vec<u8>> {
        if self.last.as_ref().is_some_and(|(id, _)| *id == b) {
            return Ok(&self.last.as_ref().expect("checked").1);
        }
        if !self.spill.contains_key(&b) {
            let bytes = section.block(b)?;
            if self.spill.len() >= self.spill_cap {
                // Evict an arbitrary older block; routing locality makes the
                // exact policy second-order.
                if let Some(k) = self.spill.keys().next().copied() {
                    self.spill.remove(&k);
                }
            }
            self.spill.insert(b, bytes);
        }
        // Promote: the last block is the fast path.
        if let Some(prev) = self.last.take() {
            if self.spill.len() < self.spill_cap {
                self.spill.insert(prev.0, prev.1);
            }
        }
        let bytes = self.spill.remove(&b).expect("just inserted");
        self.last = Some((b, bytes));
        Ok(&self.last.as_ref().expect("just set").1)
    }
}

/// Write `records` (packed `record_len`-byte records) as a blocked section.
///
/// Behind the `write` feature: host tools only. Level 10 (measured: within
/// noise of 19 on 28–48 KiB blocks, much faster to build).
#[cfg(feature = "write")]
pub fn write_blocked(records: &[u8], record_len: usize) -> Vec<u8> {
    write_blocked_with(records, record_len, BLOCK_RECORDS)
}

/// [`write_blocked`] with an explicit blocking, for tests.
#[cfg(feature = "write")]
pub fn write_blocked_with(records: &[u8], record_len: usize, per_block: u32) -> Vec<u8> {
    assert!(record_len > 0 && per_block > 0);
    assert_eq!(records.len() % record_len, 0, "whole records only");
    let count = records.len() / record_len;
    let block_count = count.div_ceil(per_block as usize);
    let cfg = zstd_rs::CompressionConfig {
        level: 10,
        ..zstd_rs::CompressionConfig::DEFAULT
    };
    let mut enc = zstd_rs::Compressor::new(cfg).expect("valid zstd level");
    let mut frames: Vec<Vec<u8>> = Vec::with_capacity(block_count);
    for b in 0..block_count {
        let lo = b * per_block as usize * record_len;
        let hi = ((b + 1) * per_block as usize * record_len).min(records.len());
        let mut frame = Vec::new();
        enc.compress(&records[lo..hi], None, &mut frame)
            .expect("zstd encode of an in-memory block");
        frames.push(frame);
    }
    assemble_blocked(record_len, per_block, records.len(), &frames)
}

/// One block's zstd frame: a fresh level-10 encoder over `block`, exactly what
/// [`BlockedStreamWriter`] writes for it. Public so a caller can compress
/// blocks on its own thread pool and hand the frames to
/// [`BlockedStreamWriter::push_frames`] in order.
#[cfg(feature = "write")]
pub fn compress_block(block: &[u8]) -> Vec<u8> {
    // One context per thread, reused for every block it compresses. A fresh
    // `Compressor` per block (what this used to do) allocates and zeroes the
    // level-10 match tables — megabytes — for every 28-48 KiB block, which
    // dominated the cost of compressing a planet's nodes.bin/edges.bin. The
    // encoder is built for reuse: its tables are epoch-tagged, so entries from
    // an earlier frame are never matched, and a frame "depends only on the
    // configuration, the input and the dictionary, never on which context runs
    // it". `a_reused_context_frames_blocks_like_a_fresh_one` pins that.
    thread_local! {
        static ENC: std::cell::RefCell<Option<zstd_rs::Compressor>> =
            const { std::cell::RefCell::new(None) };
    }
    ENC.with(|cell| {
        let mut slot = cell.borrow_mut();
        let enc = slot.get_or_insert_with(|| {
            zstd_rs::Compressor::new(zstd_rs::CompressionConfig {
                level: 10,
                ..zstd_rs::CompressionConfig::DEFAULT
            })
            .expect("valid zstd level")
        });
        let mut frame = Vec::new();
        enc.compress(block, None, &mut frame)
            .expect("zstd encode of an in-memory block");
        frame
    })
}

/// Lay out header + offset table + frames. Shared by the whole-slice and
/// streaming writers so the two cannot drift.
#[cfg(feature = "write")]
fn assemble_blocked(
    record_len: usize,
    per_block: u32,
    unpacked_len: usize,
    frames: &[Vec<u8>],
) -> Vec<u8> {
    let block_count = frames.len();
    let mut out = Vec::new();
    out.extend_from_slice(&BLOCKED_MAGIC.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&(record_len as u32).to_le_bytes());
    out.extend_from_slice(&per_block.to_le_bytes());
    out.extend_from_slice(&(unpacked_len as u64).to_le_bytes());
    out.extend_from_slice(&(block_count as u32).to_le_bytes());
    let mut off = (28 + (block_count + 1) * 8) as u64;
    for f in frames {
        out.extend_from_slice(&off.to_le_bytes());
        off += f.len() as u64;
    }
    out.extend_from_slice(&off.to_le_bytes());
    for f in frames {
        out.extend_from_slice(f);
    }
    out
}

/// Streaming blocked writer: records in, frames to scratch, table in memory.
///
/// Whole-slice [`write_blocked`] holds every record at once (5 GB for planet
/// `nodes.bin`); the graph writers stream, so this matches them: one block
/// (4096 records) in memory, frames spilled to a scratch file beside the
/// output, the 8-bytes-per-block offset table in memory (524 KB for 268 M
/// planet bodies). [`BlockedStreamWriter::finish`] returns the assembled
/// section; the caller writes it to the final path.
#[cfg(feature = "write")]
pub struct BlockedStreamWriter {
    record_len: usize,
    per_block: u32,
    block: Vec<u8>,
    frames_path: std::path::PathBuf,
    frames_out: std::fs::File,
    frame_lens: Vec<u64>,
    records: u64,
}

#[cfg(feature = "write")]
impl BlockedStreamWriter {
    /// Stage frames in `scratch_path` (removed by [`BlockedStreamWriter::finish`]).
    pub fn create(
        record_len: usize,
        scratch_path: &std::path::Path,
    ) -> std::io::Result<BlockedStreamWriter> {
        Ok(BlockedStreamWriter {
            record_len,
            per_block: BLOCK_RECORDS,
            block: Vec::with_capacity(record_len * BLOCK_RECORDS as usize),
            frames_path: scratch_path.to_path_buf(),
            frames_out: std::fs::File::create(scratch_path)?,
            frame_lens: Vec::new(),
            records: 0,
        })
    }

    /// Append whole records. Must be a multiple of the stride.
    pub fn push(&mut self, records: &[u8]) -> std::io::Result<()> {
        use std::io::Write as _;
        assert_eq!(records.len() % self.record_len, 0, "whole records only");
        self.block.extend_from_slice(records);
        self.records += (records.len() / self.record_len) as u64;
        while self.block.len() >= self.per_block as usize * self.record_len {
            self.flush_block()?;
        }
        Ok(())
    }

    fn flush_block(&mut self) -> std::io::Result<()> {
        use std::io::Write as _;
        let n = self.per_block as usize * self.record_len;
        let frame = compress_block(&self.block[..n]);
        self.frames_out.write_all(&frame)?;
        self.frame_lens.push(frame.len() as u64);
        self.block.drain(..n);
        Ok(())
    }

    /// Bytes in one full block (`records_per_block * record_len`): the unit
    /// [`compress_block`] frames for [`BlockedStreamWriter::push_frames`].
    pub fn block_bytes(&self) -> usize {
        self.per_block as usize * self.record_len
    }

    /// Append whole blocks the caller already compressed with
    /// [`compress_block`] — the parallel form of [`BlockedStreamWriter::push`].
    ///
    /// Byte-identical to pushing the same records: every frame is an
    /// independent zstd frame of exactly one block, compressed by a fresh
    /// encoder at the same level, so a frame is a pure function of its block
    /// whoever compresses it. Only valid on a block boundary (nothing staged
    /// by `push`), and every frame must hold a full block. The frames go out in
    /// one write, so the scratch file sees large sequential appends.
    pub fn push_frames(&mut self, frames: &[Vec<u8>]) -> std::io::Result<()> {
        use std::io::Write as _;
        assert!(self.block.is_empty(), "push_frames needs a block boundary");
        let total: usize = frames.iter().map(Vec::len).sum();
        let mut joined = Vec::with_capacity(total);
        for f in frames {
            joined.extend_from_slice(f);
            self.frame_lens.push(f.len() as u64);
        }
        self.frames_out.write_all(&joined)?;
        self.records += frames.len() as u64 * u64::from(self.per_block);
        Ok(())
    }

    /// [`BlockedStreamWriter::finish`], streamed: compress the tail block, then
    /// write header + offset table into `out` and copy the frames straight from
    /// the scratch file behind them, never holding the section in memory.
    /// Byte-identical to writing `finish()`'s bytes. Returns the section length.
    pub fn finish_into<W: std::io::Write>(mut self, out: &mut W) -> std::io::Result<u64> {
        use std::io::Write as _;
        if !self.block.is_empty() {
            let frame = compress_block(&self.block);
            self.frames_out.write_all(&frame)?;
            self.frame_lens.push(frame.len() as u64);
        }
        self.frames_out.flush()?;
        drop(self.frames_out);
        let block_count = self.frame_lens.len();
        let unpacked = self.records * self.record_len as u64;
        let mut head = Vec::with_capacity(28 + (block_count + 1) * 8);
        head.extend_from_slice(&BLOCKED_MAGIC.to_le_bytes());
        head.extend_from_slice(&0u32.to_le_bytes());
        head.extend_from_slice(&(self.record_len as u32).to_le_bytes());
        head.extend_from_slice(&self.per_block.to_le_bytes());
        head.extend_from_slice(&unpacked.to_le_bytes());
        head.extend_from_slice(&(block_count as u32).to_le_bytes());
        let mut off = (28 + (block_count + 1) * 8) as u64;
        for len in &self.frame_lens {
            head.extend_from_slice(&off.to_le_bytes());
            off += len;
        }
        head.extend_from_slice(&off.to_le_bytes());
        out.write_all(&head)?;
        let frames_bytes: u64 = self.frame_lens.iter().sum();
        let mut f = std::fs::File::open(&self.frames_path)?;
        let copied = std::io::copy(&mut f, out)?;
        drop(f);
        let _ = std::fs::remove_file(&self.frames_path);
        if copied != frames_bytes {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                format!("blocked scratch holds {copied} frame byte(s), expected {frames_bytes}"),
            ));
        }
        Ok(off)
    }

    /// Compress the tail block, assemble header + table + frames, remove the
    /// scratch file, and return the section bytes.
    pub fn finish(mut self) -> std::io::Result<Vec<u8>> {
        use std::io::Write as _;
        if !self.block.is_empty() {
            // Tail block: fewer than per_block records. Same framing; the
            // reader derives record counts from unpacked_len, never from drum-tight blocks.
            let frame = compress_block(&self.block);
            self.frames_out.write_all(&frame)?;
            self.frame_lens.push(frame.len() as u64);
        }
        drop(self.frames_out);
        let mut frames = Vec::with_capacity(self.frame_lens.len());
        let mut f = std::fs::File::open(&self.frames_path)?;
        use std::io::Read as _;
        for &len in &self.frame_lens {
            let mut frame = vec![0u8; len as usize];
            f.read_exact(&mut frame)?;
            frames.push(frame);
        }
        drop(f);
        let _ = std::fs::remove_file(&self.frames_path);
        let unpacked = self.records * self.record_len as u64;
        Ok(assemble_blocked(self.record_len, self.per_block, unpacked as usize, &frames))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pattern_records(n: usize, len: usize) -> Vec<u8> {
        // Coordinate-like: small ints LE plus a slow counter, like NodeRecs.
        let mut out = Vec::with_capacity(n * len);
        for i in 0..n {
            out.extend_from_slice(&((350_000_000 + i as i32 * 37) as i32).to_le_bytes());
            out.extend_from_slice(&((-1_200_000_000 + i as i32 * 11) as i32).to_le_bytes());
            out.extend_from_slice(&(i as u32 * 3).to_le_bytes());
        }
        assert_eq!(out.len(), n * len);
        out
    }

    #[test]
    fn a_blocked_section_round_trips() {
        let records = pattern_records(10_000, 12);
        let bytes = write_blocked(&records, 12);
        let s = Blocked::parse(&bytes).expect("parse");
        assert_eq!(s.unpacked_len(), records.len() as u64);
        assert_eq!(s.block_count(), 3, "10000 / 4096 rounds up");
        let mut cache = BlockCache::new(4);
        let mut back = Vec::with_capacity(records.len());
        for id in 0..10_000u64 {
            back.extend_from_slice(&s.record(id, &mut cache).expect("record"));
        }
        assert_eq!(back, records);
    }

    #[test]
    fn a_blocked_section_refuses_garbage() {
        let records = pattern_records(100, 12);
        let mut bytes = write_blocked(&records, 12);
        assert!(Blocked::parse(&bytes).is_ok());
        bytes[0] = b'X';
        assert!(Blocked::parse(&bytes).is_err(), "bad magic");
        let mut bytes = write_blocked(&records, 12);
        bytes[4] = 1;
        assert!(Blocked::parse(&bytes).is_err(), "unknown flags");
        assert!(Blocked::parse(&bytes[..10]).is_err(), "truncated");
    }

    #[test]
    fn last_block_holds_the_remainder() {
        let records = pattern_records(4097, 7);
        let bytes = write_blocked(&records, 7);
        let s = Blocked::parse(&bytes).expect("parse");
        assert_eq!(s.block_count(), 2);
        let mut cache = BlockCache::new(0);
        // First record of the last block and the very last record.
        assert_eq!(
            s.record(4096, &mut cache).expect("first of last"),
            records[4096 * 7..4097 * 7]
        );
        assert_eq!(
            s.record(4096, &mut cache).expect("cached"),
            records[4096 * 7..4097 * 7]
        );
    }

    #[test]
    fn the_streaming_writer_matches_the_whole_slice_writer() {
        let records = pattern_records(10_000, 12);
        let dir = std::env::temp_dir();
        let scratch = dir.join("tilecodec_blocked_stream_test.tmp");
        let _ = std::fs::remove_file(&scratch);
        let mut w = BlockedStreamWriter::create(12, &scratch).expect("create");
        // Odd-sized pushes, including across block boundaries.
        for chunk in records.chunks(5000) {
            w.push(chunk).expect("push");
        }
        let streamed = w.finish().expect("finish");
        assert!(!scratch.exists(), "scratch is removed");
        let whole = write_blocked(&records, 12);
        assert_eq!(streamed, whole, "streaming must be byte-identical to whole-slice");
        // And the streamed bytes read back.
        let s = Blocked::parse(&streamed).expect("parse");
        assert_eq!(s.block_count(), 3);
        let mut cache = BlockCache::new(4);
        assert_eq!(
            s.record(9999, &mut cache).expect("last"),
            records[9999 * 12..10_000 * 12]
        );
    }

    #[test]
    fn pushing_precompressed_frames_matches_pushing_records() {
        let records = pattern_records(10_000, 12);
        let dir = std::env::temp_dir();
        let a = dir.join("tilecodec_blocked_frames_a.tmp");
        let b = dir.join("tilecodec_blocked_frames_b.tmp");
        let mut plain = BlockedStreamWriter::create(12, &a).expect("create");
        plain.push(&records).expect("push");
        let plain = plain.finish().expect("finish");
        let mut framed = BlockedStreamWriter::create(12, &b).expect("create");
        let bb = framed.block_bytes();
        let full = records.len() / bb * bb;
        let frames: Vec<Vec<u8>> = records[..full].chunks(bb).map(compress_block).collect();
        framed.push_frames(&frames).expect("frames");
        framed.push(&records[full..]).expect("tail");
        let framed = framed.finish().expect("finish");
        assert_eq!(framed, plain, "frames compressed elsewhere must be byte-identical");
    }

    #[test]
    fn a_reused_context_frames_blocks_like_a_fresh_one() {
        // Blocks of varied size and content, large before small and back, so
        // any table state leaking from one frame into the next would show.
        let mut blocks: Vec<Vec<u8>> = Vec::new();
        for (n, seed) in [(4096usize, 1u32), (17, 2), (4096, 3), (1, 4), (2500, 5), (4096, 1)] {
            let mut b = pattern_records(n, 12);
            for (i, byte) in b.iter_mut().enumerate() {
                *byte ^= (seed.wrapping_mul(2654435761).rotate_left(i as u32 % 31)) as u8 & 3;
            }
            blocks.push(b);
        }
        let fresh = |b: &[u8]| {
            let mut enc = zstd_rs::Compressor::new(zstd_rs::CompressionConfig {
                level: 10,
                ..zstd_rs::CompressionConfig::DEFAULT
            })
            .unwrap();
            let mut f = Vec::new();
            enc.compress(b, None, &mut f).unwrap();
            f
        };
        for b in &blocks {
            assert_eq!(compress_block(b), fresh(b), "reused context diverged");
        }
    }

    #[test]
    fn finishing_into_a_writer_matches_finish() {
        let records = pattern_records(10_000, 12);
        let dir = std::env::temp_dir();
        let a = dir.join("tilecodec_blocked_into_a.tmp");
        let b = dir.join("tilecodec_blocked_into_b.tmp");
        let mut w1 = BlockedStreamWriter::create(12, &a).expect("create");
        w1.push(&records).expect("push");
        let whole = w1.finish().expect("finish");
        let mut w2 = BlockedStreamWriter::create(12, &b).expect("create");
        w2.push(&records).expect("push");
        let mut streamed = Vec::new();
        let n = w2.finish_into(&mut streamed).expect("finish_into");
        assert_eq!(n as usize, streamed.len());
        assert_eq!(streamed, whole);
        assert!(!b.exists(), "scratch is removed");
    }
}
