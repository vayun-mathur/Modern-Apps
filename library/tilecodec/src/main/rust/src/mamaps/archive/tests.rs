use super::*;
use crate::mamaps::header::Header;

// Fail-watch convention: each test pins one wire fact as a literal, so a
// revert of that fact quotes its failure (`left` vs `right`) rather than a
// vague mismatch. Revert → quote the failure → restore.

/// A tile header big enough to anchor a sidecar: tile data ending at 4096,
/// file extended past it by the test.
fn v7_header(file_len: u64) -> Header {
    Header {
        flags: 0,
        compression: 0,
        layer_count: 12,
        min_zoom: 0,
        max_zoom: 14,
        build_id: 0x0123_4567_89AB_CDEF,
        file_len,
        dict_offset: 128,
        dict_len: 64,
        leaf_entry_capacity: 4096,
        root_offset: 192,
        root_len: 32,
        leaf_count: 1,
        leaf_offset: 224,
        leaf_len: 16,
        data_offset: 240,
        data_len: 3856,
        tiles_addressed: 1,
        bodies_written: 1,
        min_lon_e7: 0,
        min_lat_e7: 0,
        max_lon_e7: 0,
        max_lat_e7: 0,
    }
}

fn graph_meta(node_count: u64, edge_count: u64, escapes: u64, named: u64) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
    out.extend_from_slice(&GRAPH_VERSION.to_le_bytes());
    out.extend_from_slice(&node_count.to_le_bytes());
    out.extend_from_slice(&edge_count.to_le_bytes());
    out.extend_from_slice(&escapes.to_le_bytes());
    out.extend_from_slice(&named.to_le_bytes());
    out
}

/// Assemble a whole file: tile prefix (zeroes) + payloads + dir + footer.
/// Payloads are laid out 8-aligned in kind order; callers supply
/// `(kind, bytes, extra)`.
fn assemble(header: &Header, payloads: &[(u8, Vec<u8>, u64)]) -> Vec<u8> {
    let mut out = vec![0u8; sidecar_start(header) as usize];
    let mut entries = Vec::new();
    for (kind, bytes, extra) in payloads {
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let offset = out.len() as u64;
        out.extend_from_slice(bytes);
        entries.push(ArchiveEntry { kind: *kind, flags: 0, offset, len: bytes.len() as u64, extra: *extra });
    }
    while out.len() as u64 % ARCHIVE_ALIGN != 0 {
        out.push(0);
    }
    let dir_offset = out.len() as u64;
    let (dir, footer) = serialize_dir(&entries, header.build_id, dir_offset);
    out.extend_from_slice(&dir);
    out.extend_from_slice(&footer.serialize());
    out
}

/// Fail-watched: the footer is 32 bytes behind `MAMA8\0\0\0`, naming the
/// directory and the unified build id. A revert of the magic quotes
/// `left: [88, ...]` vs `MAMA8`; of the length, `left: 24, right: 32`.
#[test]
fn the_footer_is_32_bytes_behind_mama8() {
    let f = ArchiveFooter { dir_offset: 4096, dir_len: 36, build_id: 0x0123_4567_89AB_CDEF };
    let bytes = f.serialize();
    assert_eq!(bytes.len(), 32, "the footer must stay 32 B");
    assert_eq!(&bytes[0..8], b"MAMA8\0\0\0");
    assert_eq!(&bytes[8..16], &4096u64.to_le_bytes(), "dir_offset");
    assert_eq!(&bytes[16..24], &36u64.to_le_bytes(), "dir_len");
    assert_eq!(&bytes[24..32], &0x0123_4567_89AB_CDEFu64.to_le_bytes(), "build_id");
    assert_eq!(ArchiveFooter::parse(&bytes).expect("parse"), f);
    let mut bad = bytes.clone();
    bad[0] = b'X';
    assert!(ArchiveFooter::parse(&bad).is_err(), "bad magic is refused");
}

/// Fail-watched: entries are 32 bytes, the overlay is kind 14, the POI region
/// sidecar is kind 15, and any non-zero kind parses (so a newer writer's
/// section does not lock out older readers) while only the defined ones count
/// as known.
#[test]
fn entries_are_32_bytes_and_unknown_kinds_parse() {
    let e = ArchiveEntry { kind: ARCHIVE_KIND_TRANSIT, flags: 0, offset: 8192, len: 128, extra: 0 };
    assert_eq!(e.serialize().len(), 32, "entries must stay 32 B");
    assert_eq!(ArchiveEntry::parse(&e.serialize()).expect("parse"), e);
    assert_eq!(ARCHIVE_KIND_GRAPH_META, 1, "graph meta is kind 1");
    assert_eq!(ARCHIVE_KIND_TRANSIT, 13, "transit is kind 13");
    assert_eq!(ARCHIVE_KIND_GRAPH_OVERLAY, 14, "the highway overlay is kind 14");
    assert_eq!(ARCHIVE_KIND_POI_REGION, 15, "the POI region sidecar is kind 15");
    assert_eq!(ARCHIVE_KIND_POI_ADMIN, 16, "the POI admin hierarchy is kind 16");
    assert_eq!(ARCHIVE_KIND_MAX_KNOWN, ARCHIVE_KIND_POI_ADMIN);
    let mut future = e.serialize();
    future[0] = 99;
    assert_eq!(ArchiveEntry::parse(&future).expect("a future kind parses").kind, 99);
    assert!(!is_known_kind(99) && is_known_kind(ARCHIVE_KIND_POI_REGION) && !is_known_kind(0));
    assert!(is_known_kind(ARCHIVE_KIND_POI_ADMIN));
    let mut zero = e.serialize();
    zero[0] = 0;
    assert!(ArchiveEntry::parse(&zero).is_err(), "kind 0 is never a section");
}

/// An archive carrying a section this build has never heard of still opens,
/// and the known sections around it still slice.
#[test]
fn an_archive_with_an_unknown_section_still_opens() {
    let mut transit = vec![0u8; 80 + 20 * 16];
    transit[0..4].copy_from_slice(&TRANSIT_MAGIC.to_le_bytes());
    transit[4..8].copy_from_slice(&TRANSIT_VERSION.to_le_bytes());
    transit[8..12].copy_from_slice(&20u32.to_le_bytes());
    let mut header = v7_header(0);
    let payloads = vec![(ARCHIVE_KIND_TRANSIT, transit.clone(), 0), (99u8, vec![7u8; 24], 0)];
    let bytes = assemble(&header, &payloads);
    header.file_len = bytes.len() as u64;
    let bytes = assemble(&header, &payloads);
    let view = ArchiveView::parse(&bytes, &header).expect("an unknown kind is skipped, not refused");
    assert_eq!(view.section(&bytes, ARCHIVE_KIND_TRANSIT), Some(&transit[..]));
    assert_eq!(view.section(&bytes, 99), Some(&[7u8; 24][..]));
}

/// Fail-watched: the sidecar starts past the tile data — never inside the header tail
/// it coordinates with.
#[test]
fn the_sidecar_starts_past_the_tiles() {
    let v7 = v7_header(8192);
    assert_eq!(sidecar_start(&v7), 240 + 3856, "v7: data end");
}

/// Fail-watched: a minimal sidecar (meta + nodes + transit stub) parses and
/// slices. The transit stub is a real TRIX header so the count check runs.
#[test]
fn a_minimal_sidecar_parses_and_slices() {
    let nodes = vec![0u8; (4 + 1) * 12];
    let mut transit = vec![0u8; 80 + 20 * 16];
    transit[0..4].copy_from_slice(&TRANSIT_MAGIC.to_le_bytes());
    transit[4..8].copy_from_slice(&TRANSIT_VERSION.to_le_bytes());
    transit[8..12].copy_from_slice(&20u32.to_le_bytes());
    let mut header = v7_header(0);
    // Raw (unblocked) node records are a v6 graph; a v7 meta would demand ZBLK sections.
    let mut meta = graph_meta(4, 2, 0, 0);
    meta[4..8].copy_from_slice(&GRAPH_VERSION_MIN.to_le_bytes());
    let payloads = vec![
        (ARCHIVE_KIND_GRAPH_META, meta, 0),
        (ARCHIVE_KIND_GRAPH_NODES, nodes.clone(), 0),
        (ARCHIVE_KIND_TRANSIT, transit.clone(), 0),
    ];
    let bytes = assemble(&header, &payloads);
    header.file_len = bytes.len() as u64;
    let bytes = assemble(&header, &payloads);
    let view = ArchiveView::parse(&bytes, &header).expect("parse");
    assert_eq!(view.entries.len(), 3);
    assert!(view.section(&bytes, ARCHIVE_KIND_GRAPH_NODES).is_some());
    assert!(view.section(&bytes, ARCHIVE_KIND_POI_INDEX).is_none(), "absent is None, not empty");
    assert_eq!(view.location(ARCHIVE_KIND_GRAPH_META).expect("loc").1, GRAPH_META_LEN);
}

/// Fail-watched: the build id binds header to footer. A footer carrying
/// another id quotes both hexes in the refusal.
#[test]
fn a_footer_build_id_must_equal_the_header() {
    let mut header = v7_header(0);
    let payloads = vec![(ARCHIVE_KIND_GRAPH_META, graph_meta(0, 0, 0, 0), 0)];
    let mut bytes = assemble(&header, &payloads);
    header.file_len = bytes.len() as u64;
    bytes = assemble(&header, &payloads);
    // Corrupt the footer's build id (last 8 bytes).
    let at = bytes.len() - 8;
    bytes[at..].copy_from_slice(&0xDEAD_BEEFu64.to_le_bytes());
    let failure = ArchiveView::parse(&bytes, &header).expect_err("build mismatch");
    assert!(failure.0.contains("build"), "{}", failure.0);
}

/// Fail-watched: sections get the same three refusals as every tile section
/// — unaligned, overlapping the tiles, overlapping each other — plus a bad
/// magic (MARG/TRIX) and a count mismatch (nodes length, POI ordinal join).
#[test]
fn a_sidecar_section_must_fit_without_overlapping() {
    let mut header = v7_header(0);
    let payloads = vec![(ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0)];
    let mut bytes = assemble(&header, &payloads);
    header.file_len = bytes.len() as u64;
    bytes = assemble(&header, &payloads);
    let view = ArchiveView::parse(&bytes, &header).expect("good parses");
    assert_eq!(view.entries.len(), 1);

    // Bad MARG magic.
    let mut bad_meta = graph_meta(4, 2, 0, 0);
    bad_meta[0] = b'X';
    let bad = assemble(&header, &[(ARCHIVE_KIND_GRAPH_META, bad_meta, 0)]);
    let mut h2 = header.clone();
    h2.file_len = bad.len() as u64;
    let bad = assemble(&h2, &[(ARCHIVE_KIND_GRAPH_META, {
        let mut m = graph_meta(4, 2, 0, 0);
        m[0] = b'X';
        m
    }, 0)]);
    assert!(ArchiveView::parse(&bad, &h2).is_err(), "bad MARG magic");

    // Nodes length disagreeing with meta's N.
    let short_nodes = vec![0u8; 12];
    let bad = assemble(&h2, &[
        (ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0),
        (ARCHIVE_KIND_GRAPH_NODES, short_nodes, 0),
    ]);
    let mut h3 = header.clone();
    h3.file_len = bad.len() as u64;
    let bad = assemble(&h3, &[
        (ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0),
        (ARCHIVE_KIND_GRAPH_NODES, vec![0u8; 12], 0),
    ]);
    assert!(ArchiveView::parse(&bad, &h3).is_err(), "nodes count mismatch");
}

/// Fail-watched: the unified id moves with any of its five inputs and is
/// stable otherwise. A revert quoting `left == right` means a separator lost.
#[test]
fn the_unified_build_id_moves_with_any_input() {
    let base = unified_build_id(b"gr1", b"osm", b"gtfs", b"dem", b"tiler");
    assert_eq!(base, unified_build_id(b"gr1", b"osm", b"gtfs", b"dem", b"tiler"), "stable");
    for other in [
        unified_build_id(b"gr2", b"osm", b"gtfs", b"dem", b"tiler"),
        unified_build_id(b"gr1", b"osm2", b"gtfs", b"dem", b"tiler"),
        unified_build_id(b"gr1", b"osm", b"gtfs2", b"dem", b"tiler"),
        unified_build_id(b"gr1", b"osm", b"gtfs", b"dem2", b"tiler"),
        unified_build_id(b"gr1", b"osm", b"gtfs", b"dem", b"tiler2"),
    ] {
        assert_ne!(base, other, "a changed input should change the id");
    }
}
