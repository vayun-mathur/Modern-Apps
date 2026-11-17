use super::*;
use crate::gz;

/// Real bytes 0..1479 of the published archive: the 127-byte header plus its
/// whole gzipped root directory.
const REAL_HEAD: &[u8] = include_bytes!("../../tests/fixtures/v5ca_header_rootdir.bin");
const REAL_TILE_MVT: &[u8] = include_bytes!("../../tests/fixtures/v5ca_z11_tile.mvt");

/// A body offset past `u32::MAX` must survive [`Archive::tile_offsets`] intact.
///
/// This is the bug that killed the first planet join at 44%: the offset was
/// narrowed to `u32`, so every body beyond 4 GiB pointed at the wrong bytes and
/// surfaced as "not a gzip stream". No fixture is that large, so the type is
/// exercised directly instead.
#[test]
fn a_body_offset_beyond_four_gigabytes_is_not_truncated() {
    let big = 5_000_000_000u64;
    assert!(big > u32::MAX as u64, "the case only matters past u32");

    // What `tile_offsets` does to an entry, in isolation.
    let e = Entry { tile_id: 7, offset: big, length: 128, run_length: 3 };
    let rows: Vec<(u64, u64, u32)> = (0..e.run_length as u64)
        .map(|k| (e.tile_id + k, e.offset, e.length))
        .collect();
    assert_eq!(
        rows,
        vec![(7, big, 128), (8, big, 128), (9, big, 128)],
        "the run expands and the offset is carried at full width"
    );

    // And the reader's bounds check must reject it against a small section rather
    // than wrapping into a valid-looking index.
    let mut b = Builder::new();
    b.min_zoom = 1;
    b.max_zoom = 1;
    b.add_tile(1, 0, 0, b"body");
    let bytes = b.build().unwrap();
    let a = Archive::parse(&bytes).unwrap();
    assert!(
        a.body_at(big, 128).is_err(),
        "an out-of-range offset must error, not index"
    );
}

#[test]
fn zoom_base_matches_the_closed_form() {
    assert_eq!(zoom_base(0), 0);
    assert_eq!(zoom_base(1), 1);
    assert_eq!(zoom_base(2), 5);
    assert_eq!(zoom_base(3), 21);
    // Sum of 4^k for k < z.
    for z in 0..16u8 {
        let want: u64 = (0..z).map(|k| 1u64 << (2 * k as u32)).sum();
        assert_eq!(zoom_base(z), want, "zoom_base({z})");
    }
}

#[test]
fn tile_ids_round_trip_exhaustively_at_low_zoom() {
    // Every tile up to z6 (5461 of them), both directions.
    for z in 0..=6u8 {
        let n = 1u64 << z;
        for x in 0..n {
            for y in 0..n {
                let id = tile_id(z, x, y);
                assert_eq!(tile_zxy(id), (z, x, y), "z{z}/{x}/{y} -> {id}");
            }
        }
    }
}

#[test]
fn tile_ids_are_contiguous_within_a_zoom() {
    // A zoom's ids must exactly fill [base(z), base(z+1)) with no gaps or
    // repeats, which is what makes run-length encoding work.
    for z in 0..=6u8 {
        let n = 1u64 << z;
        let mut ids: Vec<u64> = (0..n).flat_map(|x| (0..n).map(move |y| (x, y)))
            .map(|(x, y)| tile_id(z, x, y))
            .collect();
        ids.sort_unstable();
        let want: Vec<u64> = (zoom_base(z)..zoom_base(z + 1)).collect();
        assert_eq!(ids, want, "zoom {z} ids");
    }
}

#[test]
fn the_spec_anchor_points_hold() {
    // z0 is id 0, and z1 walks its quadrants in Hilbert order.
    assert_eq!(tile_id(0, 0, 0), 0);
    assert_eq!(tile_zxy(0), (0, 0, 0));
    assert_eq!(tile_id(1, 0, 0), 1);
    assert_eq!(tile_id(1, 0, 1), 2);
    assert_eq!(tile_id(1, 1, 1), 3);
    assert_eq!(tile_id(1, 1, 0), 4);
}

#[test]
fn the_real_fixture_tile_id_round_trips() {
    // The id the published archive actually stored for our fixture tile. An
    // earlier hand-computed z/x/y for this was wrong, so the assertion is on
    // the round-trip and the zoom rather than on remembered coordinates.
    let id = 2_229_854u64;
    let (z, x, y) = tile_zxy(id);
    assert_eq!(z, 11, "the fixture came from a zoom-11 entry");
    assert_eq!(tile_id(z, x, y), id, "z{z}/{x}/{y} must map back to {id}");
    assert!(x < (1 << 11) && y < (1 << 11), "coords inside the z11 grid");
}

#[test]
fn a_directory_round_trips() {
    let entries = vec![
        Entry { tile_id: 0, offset: 0, length: 100, run_length: 1 },
        // Contiguous: exercises the offset-0 shorthand.
        Entry { tile_id: 1, offset: 100, length: 50, run_length: 3 },
        // A jump, so a real offset must be written.
        Entry { tile_id: 99, offset: 9000, length: 7, run_length: 1 },
        // A leaf pointer.
        Entry { tile_id: 500, offset: 12, length: 34, run_length: 0 },
    ];
    let body = serialize_directory(&entries);
    assert_eq!(parse_directory(&body).unwrap(), entries);
}

#[test]
fn an_empty_directory_round_trips() {
    assert_eq!(parse_directory(&serialize_directory(&[])).unwrap(), vec![]);
}

#[test]
fn a_header_round_trips() {
    let h = Header {
        root_offset: 127,
        root_length: 1352,
        metadata_offset: 1479,
        metadata_length: 67106,
        leaf_offset: 68585,
        leaf_length: 2340693,
        tile_data_offset: 2409278,
        tile_data_length: 1614828579,
        addressed_tiles: 1571621,
        tile_entries: 1324064,
        tile_contents: 1026180,
        clustered: true,
        internal_compression: COMPRESSION_GZIP,
        tile_compression: COMPRESSION_GZIP,
        tile_type: TILE_TYPE_MVT,
        min_zoom: 0,
        max_zoom: 16,
        min_lon_e7: -1_800_000_000,
        min_lat_e7: -850_511_290,
        max_lon_e7: 1_800_000_000,
        max_lat_e7: 850_511_290,
        center_zoom: 16,
        center_lon_e7: -1_224_069_210,
        center_lat_e7: 377_945_930,
    };
    let bytes = h.serialize();
    assert_eq!(bytes.len(), HEADER_LEN);
    let back = Header::parse(&bytes).unwrap();
    assert_eq!(back.root_offset, h.root_offset);
    assert_eq!(back.tile_data_length, h.tile_data_length);
    assert_eq!(back.center_lat_e7, h.center_lat_e7);
    assert_eq!(back.max_zoom, h.max_zoom);
    assert!(back.clustered);
}

// --- Against the real published archive --------------------------------

#[test]
fn the_real_header_parses_to_its_known_values() {
    let h = Header::parse(REAL_HEAD).expect("the published header");
    assert_eq!(h.root_offset, 127);
    assert_eq!(h.root_length, 1352);
    assert_eq!(h.metadata_offset, 1479);
    assert_eq!(h.leaf_offset, 68585);
    assert_eq!(h.tile_data_offset, 2409278);
    assert_eq!(h.addressed_tiles, 1571621);
    assert_eq!(h.tile_entries, 1324064);
    assert_eq!(h.tile_contents, 1026180);
    assert!(h.clustered);
    assert_eq!(h.internal_compression, COMPRESSION_GZIP);
    assert_eq!(h.tile_compression, COMPRESSION_GZIP);
    assert_eq!(h.tile_type, TILE_TYPE_MVT);
    assert_eq!((h.min_zoom, h.max_zoom), (0, 16));
    // Whole Mercator world, centred on San Francisco.
    assert_eq!(h.min_lon_e7, -1_800_000_000);
    assert_eq!(h.max_lat_e7, 850_511_290);
    assert_eq!(h.center_lon_e7, -1_224_069_210);
    assert_eq!(h.center_lat_e7, 377_945_930);
}

#[test]
fn the_real_root_directory_parses_and_re_serializes() {
    let h = Header::parse(REAL_HEAD).unwrap();
    let raw = &REAL_HEAD[h.root_offset as usize..(h.root_offset + h.root_length) as usize];
    let body = gz::decompress(raw).expect("gzipped root directory");
    assert_eq!(body.len(), 2004, "known inflated size of the real root directory");

    let entries = parse_directory(&body).expect("the real root directory");
    assert_eq!(entries.len(), 324, "known entry count");
    // Every root entry in this archive is a leaf pointer.
    assert!(entries.iter().all(|e| e.run_length == 0), "all leaf pointers");
    assert_eq!(entries[0].tile_id, 0);
    assert_eq!(entries[0].offset, 0);
    assert_eq!(entries[0].length, 9222);
    assert_eq!(entries[1].tile_id, 2_230_397);

    // Byte-exact: our serializer reproduces tippecanoe's directory encoding,
    // including its use of the contiguous-offset shorthand.
    assert_eq!(serialize_directory(&entries), body, "directory re-serializes byte-exactly");
}

// --- Whole-archive round trip -------------------------------------------

#[test]
fn an_archive_round_trips_through_build_and_parse() {
    let mut b = Builder::new();
    b.max_zoom = 2;
    b.metadata = br#"{"name":"test"}"#.to_vec();
    b.add_tile(0, 0, 0, b"tile-zero");
    b.add_tile(1, 0, 0, b"tile-one");
    b.add_tile(2, 3, 1, REAL_TILE_MVT);
    let bytes = b.build().unwrap();

    let a = Archive::parse(&bytes).expect("our own archive parses");
    assert_eq!(a.metadata, br#"{"name":"test"}"#);
    assert_eq!(a.header.max_zoom, 2);
    assert_eq!(a.tile(0, 0, 0).unwrap().unwrap(), b"tile-zero");
    assert_eq!(a.tile(1, 0, 0).unwrap().unwrap(), b"tile-one");
    assert_eq!(a.tile(2, 3, 1).unwrap().unwrap(), REAL_TILE_MVT);
    // Absent tiles are None, not an error.
    assert!(a.tile(1, 1, 1).unwrap().is_none());
    assert!(a.tile(9, 0, 0).unwrap().is_none());
}

#[test]
fn identical_tiles_are_stored_once_and_run_length_encoded() {
    let mut b = Builder::new();
    b.max_zoom = 4;
    // Four consecutive ids with identical bodies, the ocean-tile case.
    let base = tile_id(4, 0, 0);
    for k in 0..4 {
        b.add_tile_raw(base + k, gz::compress(b"same"));
    }
    let bytes = b.build().unwrap();
    let a = Archive::parse(&bytes).unwrap();
    assert_eq!(a.header.addressed_tiles, 4, "four addressable tiles");
    assert_eq!(a.header.tile_entries, 1, "collapsed into one run");
    assert_eq!(a.header.tile_contents, 1, "one distinct body");
    // All four still resolve.
    for k in 0..4 {
        let (z, x, y) = tile_zxy(base + k);
        assert_eq!(a.tile(z, x, y).unwrap().unwrap(), b"same", "tile {k}");
    }
}

#[test]
fn a_large_archive_spills_into_leaf_directories() {
    // Enough distinct tiles that the root cannot hold them all, forcing the
    // two-level layout the real archive uses.
    let mut b = Builder::new();
    b.max_zoom = 8;
    let n = 20_000u64;
    let base = tile_id(8, 0, 0);
    for k in 0..n {
        b.add_tile_raw(base + k, gz::compress(format!("tile {k}").as_bytes()));
    }
    let bytes = b.build().unwrap();
    let a = Archive::parse(&bytes).unwrap();
    assert!(a.header.leaf_length > 0, "must have spilled into leaves");
    assert!(
        a.header.root_length as usize <= super::types::MAX_ROOT_BYTES,
        "root must stay within one request"
    );
    // Spot-check across leaf boundaries.
    for k in [0u64, 1, 4095, 4096, 4097, 9999, n - 1] {
        let (z, x, y) = tile_zxy(base + k);
        assert_eq!(
            a.tile(z, x, y).unwrap().unwrap(),
            format!("tile {k}").as_bytes(),
            "tile {k}"
        );
    }
    assert_eq!(a.iter_tiles().unwrap().len(), n as usize, "every tile enumerated");
}

#[test]
fn a_truncated_archive_errors_rather_than_panicking() {
    let mut b = Builder::new();
    b.add_tile(0, 0, 0, b"x");
    let bytes = b.build().unwrap();
    assert!(Archive::parse(&bytes[..HEADER_LEN - 1]).is_err(), "short header");
    assert!(Archive::parse(&bytes[..bytes.len() / 2]).is_err(), "clipped body");
    let mut bad = bytes.clone();
    bad[0] = b'X';
    assert!(Archive::parse(&bad).is_err(), "bad magic");
    let mut bad = bytes.clone();
    bad[7] = 2;
    assert!(Archive::parse(&bad).is_err(), "wrong spec version");
}
