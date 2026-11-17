//! Phase 5 leaf compression: integration gate.
//!
//! The lib test target is broken at HEAD by in-progress header work (`shared_*` fields
//! referenced from `mamaps::archive::tests` before they land on `Header`), so this file
//! exercises the same invariants through the public API only: it builds against the lib,
//! never against `#[cfg(test)]` code. The unit tests in `mamaps/index.rs` and
//! `mamaps/tests_extra.rs` remain the long-term home and assert the same facts; this file
//! is the gate that actually runs until the header work lands.

use std::cell::RefCell;
use std::rc::Rc;
use tilecodec::mamaps::body::{
    Body, Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;
use tilecodec::mamaps::header::{Header, FORMAT_VERSION, FORMAT_VERSION_V9, HEADER_LEN};
use tilecodec::mamaps::index::{self, LEAF_ENTRY_LEN, ROOT_ENTRY_LEN};
use tilecodec::mamaps::read::{self, MamapsArchive};
use tilecodec::mamaps::write::{Options, StreamWriter};
use tilecodec::pmtiles::tile_id;
use tilecodec::proto::Result;
use tilecodec::stream::{RangeReader, OPEN_PREFIX_BYTES};

type Requests = Rc<RefCell<Vec<(u64, u32)>>>;
type CountingArchive = MamapsArchive<Counting>;

/// A [`RangeReader`] over bytes in memory that logs every range asked for, so a test can assert
/// on the **number of round trips** as well as on the bytes.
///
/// The log is shared by handle: `MamapsArchive` owns the reader (its `reader` field is
/// crate-private for the in-crate counting tests), so the test keeps the other end.
struct Counting {
    bytes: Vec<u8>,
    requests: Rc<RefCell<Vec<(u64, u32)>>>,
}

impl RangeReader for Counting {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        self.requests.borrow_mut().push((offset, length));
        if offset >= self.bytes.len() as u64 {
            return Ok(Vec::new());
        }
        let end = (offset + length as u64).min(self.bytes.len() as u64);
        Ok(self.bytes[offset as usize..end as usize].to_vec())
    }
}

/// A one-line body whose contents depend on `seed`, so two tiles differ unless asked not to.
fn body_for(seed: i16) -> Body {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    roads.coords = vec![(0, 0), (seed, seed)];
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
        region_links: Vec::new(),
    }
}

/// An archive of `(z, x, y, seed)` tiles, fed in ascending id order as the writer requires.
fn archive(tiles: &[(u8, u64, u64, i16)], options: Options) -> Vec<u8> {
    let mut rows: Vec<(u64, i16)> =
        tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
    rows.sort_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(options).expect("options");
    for (id, seed) in rows {
        writer.append(id, &body_for(seed)).expect("append");
    }
    writer.finish().expect("finish")
}

fn open(bytes: Vec<u8>) -> (CountingArchive, Requests) {
    let requests = Rc::new(RefCell::new(Vec::new()));
    let a = MamapsArchive::open(Counting { bytes, requests: Rc::clone(&requests) })
        .expect("open");
    (a, requests)
}

/// Enough tiles for several leaves, with repeats so runs and content dedup both fire.
fn leafy_tiles() -> Vec<(u8, u64, u64, i16)> {
    let mut tiles = Vec::new();
    for z in 0..=4u8 {
        for x in 0..(1u64 << z) {
            for y in 0..(1u64 << z) {
                tiles.push((z, x, y, ((x + y) % 3) as i16));
            }
        }
    }
    tiles
}

/// Each leaf is one raw-DEFLATE frame; the root stays raw at 32 B stride inside the opening
/// prefix. A compressed archive opens in one request, reads back exactly, and its leaf section
/// is smaller than the raw one.
#[test]
fn compressed_leaves_round_trip_and_keep_the_prefix_budget() {
    assert!(Options::default().compress_leaves, "leaf compression is on by default");
    let tiles = leafy_tiles();
    let options = Options { leaf_entry_capacity: 16, ..Options::default() };
    let bytes = archive(&tiles, options);
    let header = Header::parse(&bytes).expect("header");
    assert!(header.leaves_compressed(), "the flag is set");
    assert_eq!(bytes[7], FORMAT_VERSION_V9, "a v9 version byte");
    assert!(header.leaf_count > 1, "this test needs several leaves");
    // The root is still raw fixed stride inside the opening prefix.
    let prefix = HEADER_LEN as u64 + header.dict_len as u64 + header.root_len as u64;
    assert!(prefix <= OPEN_PREFIX_BYTES as u64, "the prefix is {prefix} bytes");
    assert_eq!(header.root_len as usize % ROOT_ENTRY_LEN, 0, "raw 32 B stride");
    // Every leaf's stored length comes from its root entry, and inflates to count * 16.
    let (h2, _, root) = read::open_prefix(&bytes).expect("open_prefix");
    assert_eq!(h2.leaf_count as usize, root.len());
    let mut raw_total: u64 = 0;
    for e in &root {
        let raw_len = e.leaf_entry_count as usize * LEAF_ENTRY_LEN;
        raw_total += raw_len as u64;
        let at = (h2.leaf_offset + e.leaf_offset) as usize;
        if e.stored_len == 0 {
            assert_eq!(raw_len, bytes[at..at + raw_len].len());
        } else {
            let back =
                index::decompress_leaf(&bytes[at..at + e.stored_len as usize], raw_len)
                    .expect("leaf frame");
            assert_eq!(back.len(), raw_len, "exactly count * 16, allocated once");
            assert_eq!(
                index::parse_leaf(&back).expect("parse").len(),
                e.leaf_entry_count as usize
            );
        }
    }
    assert!(header.leaf_len < raw_total, "the stored section is smaller than the raw leaves");
    // And the whole archive reads back tile-for-tile through the range-reader path.
    let (a, requests) = open(bytes);
    assert_eq!(requests.borrow().len(), 1, "open is still one request");
    for (z, x, y, seed) in &tiles {
        let tile = a.tile(*z, *x as u32, *y as u32).expect("read").expect("present");
        assert_eq!(tile, body_for(*seed), "z{z}/{x}/{y}");
    }
}

/// `compress_leaves: false` is the legacy layout bit for bit — no flag, v8 version byte, zero
/// stored words — and it opens on the same reader. Both modes are deterministic.
#[test]
fn raw_leaves_stay_byte_identical_and_still_open() {
    let tiles: Vec<(u8, u64, u64, i16)> =
        (0..4u64).flat_map(|x| (0..4u64).map(move |y| (2u8, x, y, (x * 4 + y) as i16))).collect();
    let raw = archive(&tiles, Options { compress_leaves: false, ..Options::default() });
    let header = Header::parse(&raw).expect("header");
    assert!(!header.leaves_compressed(), "no flag");
    assert_eq!(raw[7], FORMAT_VERSION, "a v8 version byte");
    let (_, _, root) = read::open_prefix(&raw).expect("open_prefix");
    assert!(root.iter().all(|e| e.stored_len == 0), "zero stored words");
    // Legacy determinism holds: two builds are still byte-identical.
    let raw2 = archive(&tiles, Options { compress_leaves: false, ..Options::default() });
    assert_eq!(raw, raw2);
    // And compressed builds are deterministic too.
    let c1 = archive(&tiles, Options::default());
    let c2 = archive(&tiles, Options::default());
    assert_eq!(c1, c2);
    // Both open and read on the same reader.
    for bytes in [raw, c1] {
        let (a, _) = open(bytes);
        assert_eq!(a.tile(2, 3, 3).expect("read").expect("present"), body_for(15));
    }
}

/// A cold tile still costs exactly two requests (leaf + body), a warm tile one — compression
/// changes the bytes fetched, never the count.
#[test]
fn a_cold_tile_is_still_two_requests_and_a_warm_tile_one() {
    let tiles = leafy_tiles();
    let options = Options { leaf_entry_capacity: 16, ..Options::default() };
    let (a, requests) = open(archive(&tiles, options));
    assert!(a.header.leaf_count > 1, "this test needs several leaves");
    requests.borrow_mut().clear();
    assert!(a.tile(4, 0, 0).expect("read").is_some());
    assert_eq!(requests.borrow().len(), 2, "a cold tile is a leaf plus a body");
    requests.borrow_mut().clear();
    assert!(a.tile(4, 1, 1).expect("read").is_some());
    assert_eq!(requests.borrow().len(), 1, "its leaf is now cached, so only the body");
}

/// An old (v8) reader refuses a leaf-compressed archive cleanly: the flag is unknown to it, and
/// so is the non-zero word where a reserved zero used to be. It never misreads the leaf section
/// as raw entries.
#[test]
fn an_old_reader_refuses_a_compressed_archive_rather_than_misreading_it() {
    use tilecodec::mamaps::header::{FLAG_BODIES_COMPRESSED, FLAG_LEAF_LEN_64};
    use tilecodec::mamaps::header::{FLAG_RINGS_VALIDATED, FLAG_RUN_LENGTH_PRESENT};
    let tiles = leafy_tiles();
    let bytes = archive(&tiles, Options { leaf_entry_capacity: 16, ..Options::default() });
    let header = Header::parse(&bytes).expect("new reader opens");
    assert!(header.leaves_compressed());
    // Simulate the v8 refusal rules against the real bytes: unknown flag bits, and a non-zero
    // word where v8's root parser demanded zero.
    const V8_KNOWN: u16 =
        FLAG_BODIES_COMPRESSED | FLAG_RUN_LENGTH_PRESENT | FLAG_RINGS_VALIDATED | FLAG_LEAF_LEN_64;
    assert_ne!(header.flags & !V8_KNOWN, 0, "the new flag is unknown to v8");
    let (_, _, root) = read::open_prefix(&bytes).expect("open_prefix");
    assert!(root.iter().any(|e| e.stored_len != 0), "a v8 zero-check would fire");
    // The 128-byte shape itself is unchanged (same length, same section offsets); it is the
    // flag/word checks in `parse` that refuse, before any leaf is ever addressed.
    assert_eq!(bytes.len(), header.file_len as usize);
    assert_eq!(HEADER_LEN, 128);
}
