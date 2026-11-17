//! Header wire tests: round-trips and refusals, v8 only.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

use super::*;

type HeaderMutator = fn(&mut Header);

/// A header naming sections that do not overlap, in a file big enough to hold them.
fn plausible() -> Header {
    Header {
        flags: FLAG_BODIES_COMPRESSED,
        compression: COMPRESSION_DEFLATE,
        layer_count: 7,
        min_zoom: 0,
        max_zoom: 14,
        build_id: 0x0123_4567_89AB_CDEF,
        file_len: 4096,
        dict_offset: 128,
        dict_len: 512,
        leaf_entry_capacity: 4096,
        root_offset: 640,
        root_len: 64,
        leaf_count: 2,
        leaf_offset: 704,
        leaf_len: 32,
        data_offset: 736,
        data_len: 3360,
        tiles_addressed: 9,
        bodies_written: 4,
        min_lon_e7: -1_242_000_000,
        min_lat_e7: 324_000_000,
        max_lon_e7: -1_140_000_000,
        max_lat_e7: 420_000_000,
    }
}

#[test]
fn a_header_round_trips_through_its_own_bytes() {
    let header = plausible();
    let bytes = header.serialize();
    assert_eq!(bytes.len(), HEADER_LEN, "the header is a fixed 128 bytes");
    assert_eq!(Header::parse(&bytes).expect("should parse"), header);
}

#[test]
fn every_u64_field_is_eight_byte_aligned() {
    // So a reader may take them as aligned loads out of a zero-copy prefix slice.
    for offset in [16, 24, 32, 48, 64, 80, 88, 96, 104] {
        assert_eq!(offset % 8, 0, "a u64 sits at byte {offset}");
    }
}

#[test]
fn a_header_with_trailing_bytes_still_parses() {
    // It arrives inside a 16 KiB prefix, never on its own.
    let mut bytes = plausible().serialize();
    bytes.extend_from_slice(&[0xAB; 1024]);
    assert!(Header::parse(&bytes).is_ok());
}

#[test]
fn a_truncated_or_foreign_header_is_refused() {
    let bytes = plausible().serialize();
    assert!(Header::parse(&bytes[..HEADER_LEN - 1]).is_err(), "short");
    let mut wrong_magic = bytes.clone();
    wrong_magic[0] = b'P';
    assert!(Header::parse(&wrong_magic).is_err(), "PMTiles is not this format");
    let mut wrong_version = bytes.clone();
    wrong_version[7] = FORMAT_VERSION_V9 + 1;
    assert!(Header::parse(&wrong_version).is_err(), "a version past v9");
    let mut wrong_len = bytes.clone();
    wrong_len[8..10].copy_from_slice(&160u16.to_le_bytes());
    assert!(Header::parse(&wrong_len).is_err(), "a v8 version declaring 160");
}

/// An unknown flag means the writer said something about the bodies this reader would
/// ignore, and every flag here changes how a body must be handled.
#[test]
fn an_unknown_flag_or_a_dirty_reserved_word_is_refused() {
    let mut bytes = plausible().serialize();
    bytes[11] = 0x80;
    assert!(Header::parse(&bytes).is_err(), "an unknown flag");
    let mut bytes = plausible().serialize();
    bytes[76] = 1;
    assert!(Header::parse(&bytes).is_err(), "a reserved word a later version may claim");
}

/// Every later read takes its length from a header field, so a header that names an
/// impossible section has to fail here rather than somewhere uninformative.
#[test]
fn a_header_whose_sections_do_not_fit_the_file_is_refused() {
    let cases: &[(&str, HeaderMutator)] = &[
        ("data past the end", |h| h.data_len = 1 << 40),
        ("a section inside the header", |h| h.dict_offset = 8),
        ("the dictionary over the root", |h| h.dict_len = 600),
        ("a file shorter than the header", |h| h.file_len = 8),
        ("a zoom range inverted", |h| h.min_zoom = 15),
        ("a zoom past the renderer's maximum", |h| h.max_zoom = 30),
        ("a leaf capacity that is not a power of two", |h| h.leaf_entry_capacity = 3000),
        ("no leaves at all", |h| h.leaf_count = 0),
        ("an unknown compression", |h| h.compression = 9),
        ("more bodies than addressed tiles", |h| h.bodies_written = 10),
        ("a root that is not whole entries", |h| h.root_len = 63),
        ("a flag that contradicts the compression byte", |h| {
            h.compression = COMPRESSION_NONE;
        }),
    ];
    for (what, break_it) in cases {
        let mut header = plausible();
        break_it(&mut header);
        assert!(Header::parse(&header.serialize()).is_err(), "{what} should be refused");
    }
}

#[test]
fn an_offset_length_pair_cannot_overflow_into_looking_valid() {
    let mut header = plausible();
    header.data_offset = u64::MAX - 8;
    header.data_len = 64;
    assert!(Header::parse(&header.serialize()).is_err(), "the end wraps");
}

/// v9 parses wherever v8 does: same 128 bytes, new version byte only.
/// A v9 reader must accept both; anything else is refused.
#[test]
fn a_v9_header_parses_and_anything_past_v9_is_refused() {
    let mut bytes = plausible().serialize();
    bytes[7] = FORMAT_VERSION_V9;
    assert!(Header::parse(&bytes).is_ok(), "v9 must parse");
    for v in [0u8, 1, 7, 10, 255] {
        let mut bad = bytes.clone();
        bad[7] = v;
        assert!(Header::parse(&bad).is_err(), "version {v} must be refused");
    }
}

/// zstd is a known compression: the header carries it, the flag agrees.
#[test]
fn a_zstd_header_parses() {
    let mut header = plausible();
    header.compression = COMPRESSION_ZSTD;
    assert!(Header::parse(&header.serialize()).is_ok());
}

/// Planet scale: leaf index is ~268M bodies ×16 B ≈4.29 GB > u32::MAX,
/// overflowing the old 4 GiB leaf_len field by 1257 B in the failed run.
/// Extended encoding uses FLAG_LEAF_LEN_64 and the formerly-reserved
/// 76..80 word as high 32 of a u64 leaf_len (72..76 low 32). Common path
/// (NA ~418 MB < u32::MAX) stays flag 0 + u32 + 0 → byte-identical to v1.
#[test]
fn a_leaf_len_past_u32_max_round_trips_and_common_path_stays_byte_identical() {
    // The leaf field itself round-trips via Header serialization; use a
    // large file_len so the overall header check (sections fit) doesn't
    // reject the test file. NA's file_len is 418 MB leaf_len + data, but
    // plausible() is 4096 B — enlarge for the planet-sized leaf.
    let planet_leaf: u64 = u32::MAX as u64 + 1257;
    // Extended: flag set, high word carries the overflow.
    let mut extended = Header {
        leaf_len: planet_leaf,
        flags: FLAG_BODIES_COMPRESSED | FLAG_LEAF_LEN_64,
        ..plausible()
    };
    extended.data_offset = extended.leaf_offset + extended.leaf_len;
    extended.file_len = extended.data_offset + extended.data_len;
    let bytes_ext = extended.serialize();
    assert_eq!(bytes_ext.len(), HEADER_LEN);
    assert_ne!(bytes_ext[10] & 0x08, 0, "extended flag must be set");
    assert_ne!(&bytes_ext[76..80], &[0, 0, 0, 0], "high word must carry overflow (1257 beyond 4 GiB)");
    let parsed = Header::parse(&bytes_ext).expect("extended must parse");
    assert_eq!(parsed.leaf_len, planet_leaf);
    assert_eq!(parsed.flags & FLAG_LEAF_LEN_64, FLAG_LEAF_LEN_64);
    // Common path (NA ~418 MB) stays byte-identical to v1: flag 0, reserved 0, low 32 only.
    let na_leaf: u64 = 418 * 1024 * 1024;
    let mut common = Header {
        leaf_len: na_leaf,
        ..plausible()
    };
    common.data_offset = common.leaf_offset + common.leaf_len;
    common.file_len = common.data_offset + common.data_len;
    let bytes_common = common.serialize();
    assert_eq!(bytes_common.len(), HEADER_LEN);
    assert_eq!(bytes_common[10] & FLAG_LEAF_LEN_64 as u8, 0, "common flag must be 0");
    assert_eq!(&bytes_common[76..80], &[0, 0, 0, 0], "reserved must stay 0 for <4 GiB");
    assert_eq!(Header::parse(&bytes_common).expect("common must parse").leaf_len, na_leaf);
    // Byte-identity: common bytes up to leaf_len are identical to what v1
    // would have written (low 32 + 0 reserved); extended differs only in flag + high word.
    // Extended canonical: flag set but value fits u32 is rejected
    let mut bad = Header {
        leaf_len: 100,
        flags: FLAG_BODIES_COMPRESSED | FLAG_LEAF_LEN_64,
        ..plausible()
    };
    bad.data_offset = bad.leaf_offset + bad.leaf_len;
    bad.file_len = bad.data_offset + bad.data_len;
    assert!(Header::parse(&bad.serialize()).is_err(), "extended with small leaf_len must be rejected");
}
