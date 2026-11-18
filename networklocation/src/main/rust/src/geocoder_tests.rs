//! Synthetic-buffer tests for the geocoder's pure byte/codec helpers and for
//! `Column`/`Dict` decoding. The DB-backed tests in `geocoder_part2.rs` self-skip
//! without a ~1 GB asset; these build tiny in-memory sections in the exact
//! on-disk layout so the decode paths run in every CI job.

use super::*;
use std::io::Cursor;

fn compress(raw: &[u8]) -> Vec<u8> {
    ruzstd::encoding::compress_to_vec(Cursor::new(raw), ruzstd::encoding::CompressionLevel::Uncompressed)
}

fn zigzag(v: i32) -> u32 {
    ((v << 1) ^ (v >> 31)) as u32
}

fn push_varint(mut v: u32, out: &mut Vec<u8>) {
    loop {
        let b = (v & 0x7F) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

fn push_be32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}

/// Build a `Column` body (as `Column::new` expects at `body`) holding `vals`.
fn column_body(vals: &[i32], delta: bool) -> Vec<u8> {
    let blocks: Vec<Vec<u8>> = vals
        .chunks(BLOCK as usize)
        .map(|chunk| {
            let mut raw = Vec::new();
            let mut prev = 0i32;
            for &v in chunk {
                let dv = if delta { v.wrapping_sub(prev) } else { v };
                prev = v;
                push_varint(zigzag(dv), &mut raw);
            }
            raw
        })
        .collect();
    let mut out = Vec::new();
    push_be32(&mut out, vals.len() as u32);
    push_be32(&mut out, blocks.len() as u32);
    let mut end = 0u32;
    let mut comps = Vec::new();
    for raw in &blocks {
        let comp = compress(raw);
        end += comp.len() as u32;
        push_be32(&mut out, raw.len() as u32);
        push_be32(&mut out, end);
        comps.push(comp);
    }
    for comp in comps {
        out.extend_from_slice(&comp);
    }
    out
}

/// Build a `Dict` body holding `strings` (already in UTF-16 sorted order).
fn dict_body(strings: &[&str], per: usize) -> Vec<u8> {
    let blocks: Vec<&[&str]> = strings.chunks(per).collect();
    let firsts: Vec<u8> = blocks.iter().flat_map(|b| b[0].bytes()).collect();
    let raws: Vec<Vec<u8>> = blocks
        .iter()
        .map(|b| {
            let mut raw = Vec::new();
            for s in *b {
                push_be32(&mut raw, s.len() as u32);
                raw.extend_from_slice(s.as_bytes());
            }
            raw
        })
        .collect();
    let mut out = Vec::new();
    push_be32(&mut out, strings.len() as u32);
    push_be32(&mut out, per as u32);
    push_be32(&mut out, blocks.len() as u32);
    let mut end = 0u32;
    for b in &blocks {
        end += b[0].len() as u32;
        push_be32(&mut out, end);
    }
    out.extend_from_slice(&firsts);
    let comps: Vec<Vec<u8>> = raws.iter().map(|r| compress(r)).collect();
    let mut end = 0u32;
    for c in &comps {
        end += c.len() as u32;
        push_be32(&mut out, end);
    }
    for c in comps {
        out.extend_from_slice(&c);
    }
    out
}

#[test]
fn be_reads_are_bounds_checked() {
    let d = [0x01u8, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08];
    assert_eq!(be32(&d, 0), Some(0x0102_0304));
    assert_eq!(be32(&d, 4), Some(0x0506_0708));
    assert_eq!(be32(&d, 5), None);
    assert_eq!(be32(&d, usize::MAX), None);
    assert_eq!(be64(&d, 0), Some(0x0102_0304_0506_0708));
    assert_eq!(be64(&d, 1), None);
    assert_eq!(be32(&[], 0), None);
}

#[test]
fn unzigzag_inverts_zigzag() {
    for v in [0i32, -1, 1, -2, 2, 123_456, -123_456, i32::MAX, i32::MIN + 1] {
        assert_eq!(unzigzag(zigzag(v)), v);
    }
    assert_eq!(unzigzag(0), 0);
    assert_eq!(unzigzag(1), -1);
    assert_eq!(unzigzag(2), 1);
    assert_eq!(unzigzag(u32::MAX), i32::MIN);
}

#[test]
fn varint_round_trip_and_truncation() {
    for v in [0u32, 1, 0x7F, 0x80, 300, 16_384, 1 << 20, u32::MAX] {
        let mut buf = Vec::new();
        push_varint(v, &mut buf);
        let mut p = 0usize;
        assert_eq!(read_varint(&buf, &mut p), Some(v));
        assert_eq!(p, buf.len());
    }
    // Truncated continuation byte: must miss, not panic or loop.
    let mut p = 0usize;
    assert_eq!(read_varint(&[0x80], &mut p), None);
    let mut p = 0usize;
    assert_eq!(read_varint(&[], &mut p), None);
}

#[test]
fn varint_overlong_encoding_is_rejected() {
    // A u32 varint is at most 5 bytes; ten continuation bytes is malformed.
    // The decoder must reject it instead of shifting past 32 bits.
    let buf = [0xFFu8; 10];
    let mut p = 0usize;
    assert_eq!(read_varint(&buf, &mut p), None);
}

#[test]
fn cmp_utf16_uses_utf16_code_units() {
    assert_eq!(cmp_utf16("Apple", "apple"), Ordering::Less);
    assert_eq!(cmp_utf16("abc", "abc"), Ordering::Equal);
    assert_eq!(cmp_utf16("ab", "abc"), Ordering::Less);
    // U+FFFF is a single UTF-16 unit 0xFFFF; U+10000 is a surrogate pair
    // starting at 0xD800, so UTF-16 order puts U+10000 first — the opposite
    // of code-point/UTF-8 order.
    assert_eq!(cmp_utf16("\u{FFFF}", "\u{10000}"), Ordering::Greater);
    assert_eq!(cmp_utf16("\u{10000}", "\u{FFFF}"), Ordering::Less);
    assert_eq!(cmp_utf16("", "a"), Ordering::Less);
}

#[test]
fn to_e5_quantizes_to_nearest() {
    assert_eq!(to_e5(0.0), 0);
    assert_eq!(to_e5(37.7749295), 3_777_493);
    assert_eq!(to_e5(-122.4194155), -12_241_942);
    assert_eq!(to_e5(-0.000001), 0);
    assert_eq!(to_e5(90.0), 9_000_000);
    assert_eq!(to_e5(-180.0), -18_000_000);
    // Just below/above a half-step boundary.
    assert_eq!(to_e5(0.0000149), 1);
    assert_eq!(to_e5(0.0000151), 2);
    assert_eq!(to_e5(-0.0000149), -1);
}

#[test]
fn column_non_delta_round_trip_and_out_of_range() {
    let vals: Vec<i32> = vec![0, -1, 1, 42, -99_999, i32::MAX / 2, 7];
    let body = column_body(&vals, false);
    let mut col = Column::new(&body, 0, false).expect("column parses");
    for (i, &v) in vals.iter().enumerate() {
        assert_eq!(col.get(&body, i as i32), v);
    }
    assert_eq!(col.get(&body, -1), 0);
    assert_eq!(col.get(&body, vals.len() as i32), 0);
    // Re-reading exercises the single-block cache path.
    assert_eq!(col.get(&body, 3), 42);
}

#[test]
fn column_delta_round_trip_across_blocks() {
    // One value past a full block forces a second block; delta state resets
    // per block, exactly as the builder encodes it.
    let mut vals: Vec<i32> = (0..BLOCK + 3).map(|i| i * 3 - 5000).collect();
    vals[BLOCK as usize] = -777;
    let body = column_body(&vals, true);
    let mut col = Column::new(&body, 0, true).expect("column parses");
    for (i, &v) in vals.iter().enumerate() {
        assert_eq!(col.get(&body, i as i32), v, "index {i}");
    }
    // Jump back to block 0: cache must re-decode correctly.
    assert_eq!(col.get(&body, 0), vals[0]);
}

#[test]
fn column_rejects_truncated_or_inconsistent_headers() {
    assert!(Column::new(&[0u8; 4], 0, false).is_none());
    // n claims more blocks than the directory can hold.
    let mut body = Vec::new();
    push_be32(&mut body, (BLOCK + 1) as u32);
    push_be32(&mut body, 1);
    push_be32(&mut body, 0);
    push_be32(&mut body, 0);
    assert!(Column::new(&body, 0, false).is_none());
}

#[test]
fn dict_lookup_get_and_index_of() {
    let strings = ["Apple", "Banana", "Cherry", "Date", "Elderberry", "Fig"];
    let body = dict_body(&strings, 2);
    let end = body.len();
    let mut dict = Dict::new(&body, 0, end).expect("dict parses");
    for (i, s) in strings.iter().enumerate() {
        assert_eq!(dict.get(&body, i), *s);
        assert_eq!(dict.index_of(&body, s), Some(i));
    }
    assert_eq!(dict.get(&body, strings.len()), "");
    assert_eq!(dict.index_of(&body, "Blueberry"), None);
    assert_eq!(dict.index_of(&body, ""), None);
    assert_eq!(dict.index_of(&body, "apple"), None, "lookup is case-sensitive");
    assert_eq!(dict.first(&body, 1), "Cherry");
    assert_eq!(dict.first(&body, 99), "");
}

#[test]
fn dict_partition_point_boundaries() {
    let strings = ["Apple", "Banana", "Cherry", "Date"];
    let body = dict_body(&strings, 2);
    let end = body.len();
    let mut dict = Dict::new(&body, 0, end).expect("dict parses");
    // Count of strings < key.
    assert_eq!(dict.partition_point(&body, |s| cmp_utf16(s, "Apple") == Ordering::Less), 0);
    assert_eq!(dict.partition_point(&body, |s| cmp_utf16(s, "Cherry") == Ordering::Less), 2);
    assert_eq!(dict.partition_point(&body, |s| cmp_utf16(s, "Zebra") == Ordering::Less), 4);
    assert_eq!(dict.partition_point(&body, |_| true), 4);
    assert_eq!(dict.partition_point(&body, |_| false), 0);
}

#[test]
fn dict_rejects_bad_geometry() {
    // per == 0
    let mut body = Vec::new();
    push_be32(&mut body, 3);
    push_be32(&mut body, 0);
    push_be32(&mut body, 0);
    assert!(Dict::new(&body, 0, body.len()).is_none());
    // blocks inconsistent with count/per
    let body = dict_body(&["A", "B"], 2);
    let mut bad = body.clone();
    bad[8..12].copy_from_slice(&5u32.to_be_bytes());
    assert!(Dict::new(&bad, 0, bad.len()).is_none());
}

// ---------------------------------------------------------------------------
// Whole-database fixture: 3 records in 2 grid cells, in the exact v5 layout
// `Reader::from_map` walks. Rec 0 is an address, rec 1 a POI in the same
// cell, rec 2 a place in a neighbouring cell.
// ---------------------------------------------------------------------------

const REC_COLS: [[i32; 10]; 3] = [
    [3_777_493, -12_241_942, -1, 0, 0, 0, 0, 0, 0, 1],
    [3_777_500, -12_241_900, 0, -1, 0, 0, 0, 0, -1, 3],
    [3_800_000, -12_200_000, 1, -1, 1, 0, 0, 0, 1, 4],
];

fn cell_of(lat_e5: i32, lon_e5: i32) -> i64 {
    ((lat_e5 as i64 - MIN_LAT as i64) / CELL) * COLS + (lon_e5 as i64 - MIN_LON as i64) / CELL
}

fn synthetic_db() -> Vec<u8> {
    let dicts: [&[&str]; DICTS] = [
        &["Alpha Cafe", "Beta Park", "Gamma Plaza"],
        &["10", "20"],
        &["Main St", "Oak Ave"],
        &["Springfield"],
        &["Illinois"],
        &["US"],
        &["00001", "00002"],
    ];
    let mut bodies: Vec<Vec<u8>> = dicts.iter().map(|d| dict_body(d, 2)).collect();
    for c in 0..COLUMNS {
        let vals: Vec<i32> = REC_COLS.iter().map(|r| r[c]).collect();
        bodies.push(column_body(&vals, DELTA[c]));
    }
    let mut grid = Vec::new();
    push_be32(&mut grid, 2);
    grid.extend_from_slice(&cell_of(REC_COLS[0][0], REC_COLS[0][1]).to_be_bytes());
    push_be32(&mut grid, 0);
    grid.extend_from_slice(&cell_of(REC_COLS[2][0], REC_COLS[2][1]).to_be_bytes());
    push_be32(&mut grid, 2);
    bodies.push(grid);
    bodies.push(column_body(&[0, 1, 2], true));
    bodies.push(column_body(&[0, 1], true));
    bodies.push(column_body(&[1, 2], false));
    assert_eq!(bodies.len(), SECTIONS);

    let mut out = Vec::new();
    push_be32(&mut out, MAGIC);
    push_be32(&mut out, VERSION);
    push_be32(&mut out, REC_COLS.len() as u32);
    for b in &bodies {
        push_be32(&mut out, b.len() as u32);
        out.extend_from_slice(b);
    }
    out
}

fn open_synthetic(bytes: &[u8]) -> Reader {
    use std::sync::atomic::{AtomicUsize, Ordering};
    static SEQ: AtomicUsize = AtomicUsize::new(0);
    let path = std::env::temp_dir().join(format!("geocoder-synthetic-{}-{}.db", std::process::id(), SEQ.fetch_add(1, Ordering::Relaxed)));
    std::fs::write(&path, bytes).expect("write synthetic db");
    let r = Reader::open_path(&path);
    let _ = std::fs::remove_file(&path);
    r.expect("synthetic db parses")
}

#[test]
fn synthetic_db_reverse_resolve_and_grid() {
    let mut r = open_synthetic(&synthetic_db());
    assert_eq!(r.n, 3);
    assert_eq!(r.grid_index(cell_of(REC_COLS[0][0], REC_COLS[0][1])), Some(0));
    assert_eq!(r.grid_index(cell_of(REC_COLS[2][0], REC_COLS[2][1])), Some(1));
    assert_eq!(r.grid_index(42), None);

    // Exact record position resolves to that record (distance zero wins).
    assert_eq!(r.reverse(37.77493, -122.41942), Some(0));
    // A hair east is nearer the POI than the address.
    assert_eq!(r.reverse(37.775_00, -122.419_00), Some(1));
    // The far cell's record, and nothing near the Gulf of Guinea.
    assert_eq!(r.reverse(38.0, -122.0), Some(2));
    assert_eq!(r.reverse(0.0, 0.0), None);

    let a = r.resolve(0);
    assert_eq!(a, ["37.77493", "-122.41942", "", "10", "Main St", "Springfield", "Illinois", "US", "00001", "1"]);
    let b = r.resolve(1);
    assert_eq!(b[2], "Alpha Cafe");
    assert_eq!(b[3], "", "missing house id resolves empty");
    assert_eq!(b[8], "", "missing postcode id resolves empty");
    assert_eq!(b[9], "3");
    let c = r.resolve(2);
    assert_eq!(c[2], "Beta Park");
    assert_eq!(c[4], "Oak Ave");
    assert_eq!(c[9], "4");
}

#[test]
fn synthetic_db_forward_and_name_search() {
    let mut r = open_synthetic(&synthetic_db());
    assert_eq!(r.forward("US", "Illinois", "Springfield", "Main St", 10), vec![0, 1]);
    assert_eq!(r.forward("US", "Illinois", "Springfield", "Main St", 1), vec![0]);
    assert_eq!(r.forward("US", "Illinois", "Springfield", "Oak Ave", 10), vec![2]);
    assert!(r.forward("US", "Illinois", "Springfield", "Nowhere Rd", 10).is_empty());
    assert!(r.forward("CA", "Illinois", "Springfield", "Main St", 10).is_empty());

    assert_eq!(r.name_prefix_range("Alpha"), (0, 1));
    assert_eq!(r.name_prefix_range("Beta"), (1, 2));
    assert_eq!(r.search_name("Alpha", 10), vec![1]);
    assert_eq!(r.search_name("Beta", 10), vec![2]);
    assert_eq!(r.search_name("Gamma", 10), Vec::<i32>::new(), "in dict but no record");
    assert!(r.search_name("", 10).is_empty());
    assert!(r.search_name("Zzz", 10).is_empty());
    assert_eq!(r.compare_key(0, [0, 0, 0, 0]), 0);
    assert!(r.compare_key(2, [0, 0, 0, 0]) > 0);
    assert!(r.compare_key(0, [0, 0, 0, 1]) < 0);
}

#[test]
fn synthetic_db_rejects_bad_headers() {
    let good = synthetic_db();
    let mut bad_magic = good.clone();
    bad_magic[0..4].copy_from_slice(&0xDEAD_BEEFu32.to_be_bytes());
    let path = std::env::temp_dir().join(format!("geocoder-bad-{}-{}.db", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0)));
    std::fs::write(&path, &bad_magic).expect("write");
    assert!(Reader::open_path(&path).is_none(), "bad magic rejected");
    let mut bad_ver = good.clone();
    bad_ver[4..8].copy_from_slice(&4u32.to_be_bytes());
    std::fs::write(&path, &bad_ver).expect("write");
    assert!(Reader::open_path(&path).is_none(), "old version rejected");
    std::fs::write(&path, &good[..good.len() / 2]).expect("write");
    assert!(Reader::open_path(&path).is_none(), "truncated db rejected");
    let _ = std::fs::remove_file(&path);
}
