//! Offline reverse/forward geocoding over the packed `geocoder.db`, exposed via JNI.
//!
//! The on-disk format is produced by `scripts/networklocation/geodb_build`: grid-primary record
//! order for cheap reverse lookup, dictionary-indexed fields, delta+zigzag+varint columns in
//! per-4096-record Zstandard blocks, plus two sorted indexes for structured and by-name lookup.
//! All ints/longs are big-endian.
//!
//! ## Memory
//!
//! The whole file is memory-mapped and nothing is decoded at open. Columns and dictionaries are
//! both stored as small independent zstd blocks, each with a one-block cache here, so a lookup
//! decompresses a few kilobytes and the process holds only mapped file pages, which the kernel
//! can reclaim at will. v4 inflated every dictionary on open: about 2 GB of heap for the planet,
//! and over a minute before the first answer. Runtime is decompress-only, so we use the
//! pure-Rust `ruzstd` decoder — no NDK C toolchain needed.
//!
//! ## v5
//!
//! v2 held **only** postal addresses, at e6 coordinates. v3 added named streets, points of
//! interest and populated places, each tagged with a `kind`. v4 cut that down for size: e5
//! coordinates, a forward index of records with a street only, and one name-index entry per
//! street name per grid cell. v5 keeps v4's content and lays it out to be read from a mapping:
//! blocked dictionaries behind an uncompressed first-string table, and column directories that
//! store end offsets.
//!
//! JNI result layout: each result is 10 consecutive strings
//! `[lat, lon, name, house, street, city, state, country, postcode, kind]`, lat/lon to 5dp.
//! `reverse` returns one result or null; `forward` and `searchName` return 10*k strings.

use std::cmp::Ordering;
use std::fs::File;
use std::io::{Cursor, Read};
// `FromRawFd` (fd → File) only exists on unix; the host fallback in
// `geocoder_part1.rs` never touches fds.
#[cfg(unix)]
use std::os::unix::io::FromRawFd;
use std::sync::Mutex;

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jdouble, jint, jlong, jobjectArray};
use jni::{Env, EnvUnowned};

use crate::mmap::Map;

const MAGIC: u32 = 0x4D41_4745;
const VERSION: u32 = 5;
const BLOCK: i32 = 4096;

// Grid geometry, in e5 units. A cell is 0.05 degrees, about 5.5 km of latitude.
const CELL: i64 = 5_000;
const COLS: i64 = 36_000_000 / CELL; // 7200
const MIN_LAT: i32 = -9_000_000;
const MIN_LON: i32 = -18_000_000;

/// How far the reverse search will expand before giving up, in cells.
const MAX_RADIUS: i64 = 32;

const DICTS: usize = 7;
const COLUMNS: usize = 10;
/// Sections after the header: dictionaries, columns, grid, fwd, nm_name, nm_rec.
const SECTIONS: usize = DICTS + COLUMNS + 4;

const C_LAT: usize = 0;
const C_LON: usize = 1;
const C_NAME: usize = 2;
const C_HOUSE: usize = 3;
const C_STREET: usize = 4;
const C_CITY: usize = 5;
const C_STATE: usize = 6;
const C_COUNTRY: usize = 7;
const C_POSTCODE: usize = 8;
const C_KIND: usize = 9;

/// Which columns are delta-encoded against the previous value in their block.
const DELTA: [bool; COLUMNS] = [true, true, false, false, false, false, false, false, false, false];

/// Dictionary slot backing column `c`. Columns 0 and 1 are coordinates and column 9 is a small
/// integer, so only 2..=8 are dictionary-indexed.
const fn dict_of(col: usize) -> usize {
    col - 2
}

/// Strings per result in the JNI array.
const FIELDS: usize = 10;

/// Grid entries are `[i64 cell, u32 start]`, uncompressed.
const GRID_ENTRY: usize = 12;

// --------------------------------------------------------------------------- byte helpers
fn be32(d: &[u8], p: usize) -> Option<u32> {
    Some(u32::from_be_bytes(d.get(p..p + 4)?.try_into().ok()?))
}
fn be64(d: &[u8], p: usize) -> Option<i64> {
    Some(i64::from_be_bytes(d.get(p..p + 8)?.try_into().ok()?))
}
fn unzigzag(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}
fn read_varint(a: &[u8], p: &mut usize) -> Option<u32> {
    let mut v: u32 = 0;
    let mut shift = 0;
    loop {
        let b = *a.get(*p)?;
        *p += 1;
        v |= ((b & 0x7F) as u32) << shift;
        if b & 0x80 == 0 {
            return Some(v);
        }
        shift += 7;
    }
}
fn zstd_decompress(comp: &[u8], size_hint: usize) -> Option<Vec<u8>> {
    let mut dec = ruzstd::decoding::StreamingDecoder::new(Cursor::new(comp)).ok()?;
    let mut out = Vec::with_capacity(size_hint);
    dec.read_to_end(&mut out).ok()?;
    Some(out)
}
/// Kotlin `String.compareTo` compares UTF-16 code units; the searchable dictionaries
/// (name/street/city/state/country) are sorted that way, so the binary search must match. The
/// house/postcode dictionaries are frequency-ordered and only ever indexed for display.
fn cmp_utf16(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

// --------------------------------------------------------------------------- column reader
/// One column. The directory is read from the mapping on demand; only the most recently
/// decoded block is held.
struct Column {
    n: i32,
    blocks: usize,
    delta: bool,
    /// `[u32 raw_len, u32 end]` per block.
    dir: usize,
    /// Start of the compressed blocks.
    data: usize,
    cached_block: i32,
    cached: Vec<i32>,
}
impl Column {
    fn new(d: &[u8], body: usize, delta: bool) -> Option<Column> {
        let n = be32(d, body)? as i32;
        let blocks = be32(d, body + 4)? as usize;
        let dir = body + 8;
        let data = dir.checked_add(blocks.checked_mul(8)?)?;
        if data > d.len() || (n > 0 && blocks < (n as usize).div_ceil(BLOCK as usize)) {
            return None;
        }
        Some(Column { n, blocks, delta, dir, data, cached_block: -1, cached: Vec::new() })
    }

    fn ensure(&mut self, d: &[u8], block: i32) -> bool {
        if block == self.cached_block {
            return true;
        }
        let b = block as usize;
        if b >= self.blocks {
            return false;
        }
        let decoded = (|| {
            let raw_len = be32(d, self.dir + b * 8)? as usize;
            let end = be32(d, self.dir + b * 8 + 4)? as usize;
            let start = if b == 0 { 0 } else { be32(d, self.dir + (b - 1) * 8 + 4)? as usize };
            let raw = zstd_decompress(d.get(self.data + start..self.data + end)?, raw_len)?;
            let count = std::cmp::min(BLOCK, self.n - block * BLOCK) as usize;
            let mut vals = Vec::with_capacity(count);
            let (mut rp, mut prev) = (0usize, 0i32);
            for _ in 0..count {
                let dv = unzigzag(read_varint(&raw, &mut rp)?);
                let actual = if self.delta { prev.wrapping_add(dv) } else { dv };
                vals.push(actual);
                prev = actual;
            }
            Some(vals)
        })();
        match decoded {
            Some(vals) => {
                self.cached = vals;
                self.cached_block = block;
                true
            }
            None => false,
        }
    }

    fn get(&mut self, d: &[u8], i: i32) -> i32 {
        if i < 0 || i >= self.n {
            return 0;
        }
        let block = i / BLOCK;
        if !self.ensure(d, block) {
            return 0;
        }
        self.cached[(i - block * BLOCK) as usize]
    }
}

// --------------------------------------------------------------------------- dictionary reader
/// One blocked dictionary (`codec::encode_dict` in the builder). Searches binary-search the
/// uncompressed first-string table in the mapping, then decode one block; only that block is
/// held.
struct Dict {
    count: usize,
    per: usize,
    blocks: usize,
    first_dir: usize,
    firsts: usize,
    block_dir: usize,
    data: usize,
    cached_block: usize,
    raw: Vec<u8>,
    /// `(start, len)` of each string of the cached block within `raw`.
    spans: Vec<(u32, u32)>,
}
impl Dict {
    fn new(d: &[u8], body: usize, end: usize) -> Option<Dict> {
        let count = be32(d, body)? as usize;
        let per = be32(d, body + 4)? as usize;
        let blocks = be32(d, body + 8)? as usize;
        if per == 0 || blocks != count.div_ceil(per) {
            return None;
        }
        let first_dir = body + 12;
        let firsts = first_dir.checked_add(blocks.checked_mul(4)?)?;
        let firsts_len = if blocks == 0 { 0 } else { be32(d, firsts - 4)? as usize };
        let block_dir = firsts.checked_add(firsts_len)?;
        let data = block_dir.checked_add(blocks.checked_mul(4)?)?;
        let data_len = if blocks == 0 { 0 } else { be32(d, data - 4)? as usize };
        if data.checked_add(data_len)? > end {
            return None;
        }
        Some(Dict {
            count,
            per,
            blocks,
            first_dir,
            firsts,
            block_dir,
            data,
            cached_block: usize::MAX,
            raw: Vec::new(),
            spans: Vec::new(),
        })
    }

    /// First string of block `b`, straight from the mapping.
    fn first<'a>(&self, d: &'a [u8], b: usize) -> &'a str {
        let range = (|| {
            let start = if b == 0 { 0 } else { be32(d, self.first_dir + (b - 1) * 4)? as usize };
            let end = be32(d, self.first_dir + b * 4)? as usize;
            std::str::from_utf8(d.get(self.firsts + start..self.firsts + end)?).ok()
        })();
        range.unwrap_or("")
    }

    fn ensure(&mut self, d: &[u8], b: usize) -> bool {
        if b == self.cached_block {
            return true;
        }
        if b >= self.blocks {
            return false;
        }
        let decoded = (|| {
            let start = if b == 0 { 0 } else { be32(d, self.block_dir + (b - 1) * 4)? as usize };
            let end = be32(d, self.block_dir + b * 4)? as usize;
            let raw = zstd_decompress(d.get(self.data + start..self.data + end)?, 0)?;
            let want = self.per.min(self.count - b * self.per);
            let mut spans = Vec::with_capacity(want);
            let mut p = 0usize;
            for _ in 0..want {
                let len = be32(&raw, p)? as usize;
                p += 4;
                raw.get(p..p + len)?;
                spans.push((p as u32, len as u32));
                p += len;
            }
            Some((raw, spans))
        })();
        match decoded {
            Some((raw, spans)) => {
                self.raw = raw;
                self.spans = spans;
                self.cached_block = b;
                true
            }
            None => false,
        }
    }

    /// String `k` of the cached block.
    fn cached_str(&self, k: usize) -> &str {
        let (s, l) = self.spans[k];
        std::str::from_utf8(&self.raw[s as usize..(s + l) as usize]).unwrap_or("")
    }

    /// String `i`, or empty if out of range or unreadable.
    fn get(&mut self, d: &[u8], i: usize) -> String {
        if i >= self.count || !self.ensure(d, i / self.per) {
            return String::new();
        }
        self.cached_str(i % self.per).to_owned()
    }

    /// Number of leading strings for which `pred` holds, given that it holds for a prefix of
    /// the (sorted) dictionary and then never again. One binary search over the first-string
    /// table picks the only block the boundary can be in; one more inside that block finds it.
    fn partition_point(&mut self, d: &[u8], pred: impl Fn(&str) -> bool) -> usize {
        let (mut lo, mut hi) = (0usize, self.blocks);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if pred(self.first(d, mid)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo == 0 {
            return 0;
        }
        let b = lo - 1;
        if !self.ensure(d, b) {
            return b * self.per;
        }
        let (mut l, mut h) = (0usize, self.spans.len());
        while l < h {
            let m = (l + h) / 2;
            if pred(self.cached_str(m)) {
                l = m + 1;
            } else {
                h = m;
            }
        }
        b * self.per + l
    }

    /// Id of exactly `key` in a UTF-16-sorted dictionary.
    fn index_of(&mut self, d: &[u8], key: &str) -> Option<usize> {
        let i = self.partition_point(d, |s| cmp_utf16(s, key) == Ordering::Less);
        (i < self.count && self.get(d, i) == key).then_some(i)
    }
}

// --------------------------------------------------------------------------- reader
pub struct Reader {
    map: Map,
    n: i32,
    /// name, house, street, city, state, country, postcode.
    dicts: Vec<Dict>,
    /// Start of the grid's `[i64 cell, u32 start]` entries.
    grid: usize,
    grid_count: usize,
    cols: Vec<Column>,
    /// Records that carry a street, by structured address.
    fwd: Column,
    /// Name ids, ascending; parallel to [`Reader::nm_rec`].
    nm_name: Column,
    /// Record index for each entry of [`Reader::nm_name`].
    nm_rec: Column,
}

include!("geocoder_part1.rs");
include!("geocoder_part2.rs");
