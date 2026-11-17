// The round trip: a synthetic single-archive file (a v7 header, four graph
// payloads, the directory and footer) must load through `load_archive` with
// the counts the meta names, and any payload the counts do not describe must
// refuse. The graph is degenerate — one node, no edges — because the test is
// about the container path, not routing: every table is zeros, which is also
// what makes each validator's arithmetic checkable by hand (see comments).
#[cfg(test)]
mod archive_tests {
    use super::*;
    use tilecodec::mamaps::archive::{
        ARCHIVE_ALIGN, ArchiveEntry, ArchiveFooter, GRAPH_MAGIC,
    };

    const BUILD_ID: u64 = 0x0123_4567_89AB_CDEF;

    fn test_header(file_len: u64) -> Header {
        Header {
            flags: 0,
            compression: 0,
            layer_count: 12,
            min_zoom: 0,
            max_zoom: 14,
            build_id: BUILD_ID,
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

    fn meta_at(version: u32, node_count: u64, edge_count: u64, escapes: u64, named: u64) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
        out.extend_from_slice(&version.to_le_bytes());
        out.extend_from_slice(&node_count.to_le_bytes());
        out.extend_from_slice(&edge_count.to_le_bytes());
        out.extend_from_slice(&escapes.to_le_bytes());
        out.extend_from_slice(&named.to_le_bytes());
        out
    }

    fn meta(node_count: u64, edge_count: u64, escapes: u64, named: u64) -> Vec<u8> {
        meta_at(GRAPH_VERSION_MIN, node_count, edge_count, escapes, named)
    }

    // Whole file: tile prefix (zeroes under a real serialized header) +
    // payloads laid out 8-aligned in kind order + directory + footer.
    fn assemble(payloads: &[(u8, Vec<u8>, u64)]) -> Vec<u8> {
        let mut header = test_header(0);
        let mut out = vec![0u8; 4096];
        let mut entries = Vec::new();
        for (kind, bytes, extra) in payloads {
            while out.len() as u64 % ARCHIVE_ALIGN != 0 {
                out.push(0);
            }
            let offset = out.len() as u64;
            out.extend_from_slice(bytes);
            entries.push(ArchiveEntry {
                kind: *kind,
                flags: 0,
                offset,
                len: bytes.len() as u64,
                extra: *extra,
            });
        }
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let dir_offset = out.len() as u64;
        header.file_len = 0; // fixed up below, after the footer lands
        let (dir, _) = tilecodec::mamaps::archive::serialize_dir(&entries, BUILD_ID, dir_offset);
        out.extend_from_slice(&dir);
        let footer = ArchiveFooter {
            dir_offset,
            dir_len: dir.len() as u64,
            build_id: BUILD_ID,
        };
        out.extend_from_slice(&footer.serialize());
        header.file_len = out.len() as u64;
        let head = header.serialize();
        out[..head.len()].copy_from_slice(&head);
        out
    }

    fn write_temp(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "graph_archive_{}_{}.mamaps",
            name,
            std::process::id()
        ));
        std::fs::write(&path, bytes).unwrap();
        path
    }

    // Load through `assemble` with owned buffers: the host stub cannot mmap,
    // so file-backed `load_archive` is only exercised on Unix (below). The
    // slices are byte-identical either way — the container parsing is covered
    // by `dbg_which_layer_refuses`'s companions through ArchiveView.
    fn load_slices(payloads: &[(u8, Vec<u8>, u64)]) -> Option<Graph> {
        let bytes = assemble(payloads);
        let header = Header::parse(&bytes).ok()?;
        let view = ArchiveView::parse(&bytes, &header).ok()?;
        // Copy the sections into owned buffers FIRST (no pushes afterwards),
        // then slice: pushing while raw pointers into the Vec are alive could
        // reallocate them out from under the graph.
        let mut owned: Vec<Vec<u8>> = Vec::new();
        for kind in [
            ARCHIVE_KIND_GRAPH_META,
            ARCHIVE_KIND_GRAPH_NODES,
            ARCHIVE_KIND_GRAPH_EDGES,
            ARCHIVE_KIND_GRAPH_INTERMEDIATE,
        ] {
            owned.push(view.section(&bytes, kind)?.to_vec());
        }
        // SAFETY: `owned` moves into the graph below and outlives it; these
        // borrows are only construction-time views of bytes the graph will
        // own. Raw pointers erase the lifetime; ownership keeps them valid —
        // the same contract the mmap regions uphold.
        let at = |i: usize| unsafe {
            let b = &owned[i];
            std::slice::from_raw_parts(b.as_ptr(), b.len())
        };
        let sections = GraphSections {
            meta: at(0),
            nodes: at(1),
            edges: at(2),
            intermediate: at(3),
            road_names: None,
            lanes: None,
            elevation: None,
        };
        Graph::assemble(
            sections,
            GraphOwnership {
                nodes: None,
                edges: None,
                intermediate: None,
                road_names: None,
                lanes: None,
                elevation: None,
                archive: None,
                buffers: owned,
            },
        )
    }

    // One node, no edges. Edges payload is 16 bytes: the one escape-block
    // total (0 escapes) + the 8-byte names rank total (0 named). Intermediate
    // is 26 bytes of trailer with G = 0 and a zero blob. Zeros satisfy every
    // totals check, which is the point: the test would catch a validator that
    // stopped validating.
    //
    // v6 raw tables: the container-path tests pin the old layout so the
    // version dispatch has both sides covered (`*_v7` below pins blocked).
    fn minimal_payloads() -> Vec<(u8, Vec<u8>, u64)> {
        vec![
            (ARCHIVE_KIND_GRAPH_META, meta(1, 0, 0, 0), 0),
            (ARCHIVE_KIND_GRAPH_NODES, vec![0u8; 24], 1),
            (ARCHIVE_KIND_GRAPH_EDGES, vec![0u8; 16], 0),
            (ARCHIVE_KIND_GRAPH_INTERMEDIATE, vec![0u8; 26], 0),
        ]
    }

    /// The same minimal graph with v7 blocked record sections.
    fn minimal_payloads_v7() -> Vec<(u8, Vec<u8>, u64)> {
        let nodes = tilecodec::blocked::write_blocked(&[0u8; 24], 12);
        // Edges v7: blocked records (empty) + the same 16-byte tail.
        let recs = tilecodec::blocked::write_blocked(&[], 7);
        let mut edges = recs;
        while edges.len() % 8 != 0 {
            edges.push(0);
        }
        edges.extend_from_slice(&[0u8; 16]);
        vec![
            (
                ARCHIVE_KIND_GRAPH_META,
                meta_at(tilecodec::mamaps::archive::GRAPH_VERSION_VARINT, 1, 0, 0, 0),
                0,
            ),
            (ARCHIVE_KIND_GRAPH_NODES, nodes, 1),
            (ARCHIVE_KIND_GRAPH_EDGES, edges, 0),
            (ARCHIVE_KIND_GRAPH_INTERMEDIATE, vec![0u8; 26], 0),
        ]
    }

    /// One edge, v7 blocked: the escape index and name totals live behind the
    /// blocked records and must read through the payload base, not null.
    ///
    /// Regression for the on-device SIGSEGV (`OfflineRouter_init`, 2026-10-19):
    /// the v7 path nulled the edges/nodes base, so the escape-totals check in
    /// `assemble` read `0x0 + offset` and faulted. The zero-edge v7 test above
    /// takes the same code path, but with tables at offset 0 a null base still
    /// lands inside the first mapped page on some loaders — one non-empty edge
    /// record pushes the tables out and makes the fault deterministic.
    fn single_edge_payloads_v7() -> Vec<(u8, Vec<u8>, u64)> {
        // Two nodes (ids 0, 1) plus the sentinel: node 0 owns edge 0
        // (`edge_ptr` [0, 1, 1]; the sentinel is `edge_count`).
        let mut node_recs = Vec::new();
        for edge_ptr in [0u32, 1u32, 1u32] {
            node_recs.extend_from_slice(&0i32.to_le_bytes());
            node_recs.extend_from_slice(&0i32.to_le_bytes());
            node_recs.extend_from_slice(&edge_ptr.to_le_bytes());
        }
        let nodes = tilecodec::blocked::write_blocked(&node_recs, 12);
        // One 7-byte edge record: target_delta +1, dist 1000 mm, type 5, limit 50.
        let mut edge_rec = Vec::new();
        edge_rec.extend_from_slice(&1i16.to_le_bytes());
        edge_rec.extend_from_slice(&[0xE8, 0x03, 0x00]); // 1000, u24 LE
        edge_rec.push(5u8);
        edge_rec.push(50u8);
        let recs = tilecodec::blocked::write_blocked(&edge_rec, 7);
        let mut edges = recs;
        while edges.len() % 8 != 0 {
            edges.push(0);
        }
        // E = 1: two escape-block u32s (E/1024 + 1, total 0 escapes),
        // then the names bitmap (rank (1/512 + 1) = 2 u64s + present
        // div_ceil(1/8) = 1 byte), then zero name offsets (0 named).
        edges.extend_from_slice(&0u32.to_le_bytes());
        edges.extend_from_slice(&0u32.to_le_bytes());
        edges.extend_from_slice(&0u64.to_le_bytes());
        edges.extend_from_slice(&0u64.to_le_bytes());
        edges.push(0u8);
        vec![
            (
                ARCHIVE_KIND_GRAPH_META,
                meta_at(tilecodec::mamaps::archive::GRAPH_VERSION_VARINT, 2, 1, 0, 0),
                0,
            ),
            (ARCHIVE_KIND_GRAPH_NODES, nodes, 1),
            (ARCHIVE_KIND_GRAPH_EDGES, edges, 0),
            // G = 0, E = 1: bitmap 17 + coarse 8 + within 2 + G 8 = 35 bytes
            // of trailer, zero blob. Sized from the counts, not copied from
            // the zero-edge fixture (26 bytes: its bitmap is 9 for E = 0).
            (ARCHIVE_KIND_GRAPH_INTERMEDIATE, vec![0u8; 35], 0),
        ]
    }

    #[test]
    fn container_layers_parse_before_assemble_runs() {
        let bytes = assemble(&minimal_payloads());
        let header = Header::parse(&bytes).expect("header parses");
        let view = ArchiveView::parse(&bytes, &header).expect("sidecar parses");
        assert!(view.section(&bytes, ARCHIVE_KIND_GRAPH_META).is_some());
    }

    #[test]
    fn a_minimal_archive_loads_with_the_meta_counts() {
        let g = load_slices(&minimal_payloads()).expect("a minimal archive loads");
        assert_eq!(g.node_count, 1, "the meta's N survives the container");
        assert_eq!(g.edge_count, 0, "the meta's E survives the container");
        assert_eq!(g.escape_count, 0);
        assert_eq!(g.named_edges, 0);
        let node = g.node(0);
        assert_eq!((node.lat_e7, node.lon_e7), (0, 0));
    }

    #[test]
    fn a_minimal_v7_archive_loads_and_reads_through_the_inflate() {
        let g = load_slices(&minimal_payloads_v7()).expect("a minimal v7 archive loads");
        assert_eq!(g.node_count, 1);
        assert_eq!(g.edge_count, 0);
        // Nodes are zeros (sentinel + one zero node): the read went through
        // the blocked section, not past it.
        let node = g.node(0);
        assert_eq!((node.lat_e7, node.lon_e7), (0, 0));
        assert!(g.uses_blocked_sections(), "v7 takes the blocked path");
    }

    #[test]
    fn a_v7_archive_with_an_edge_reads_the_tail_tables_past_the_records() {
        let g =
            load_slices(&single_edge_payloads_v7()).expect("a one-edge v7 archive loads");
        assert_eq!(g.node_count, 2);
        assert_eq!(g.edge_count, 1);
        assert_eq!(g.escape_count, 0);
        assert_eq!(g.named_edges, 0);
        // The record comes back through the inflate cache...
        let e = g.edge(0, 0);
        assert_eq!(e.target, 1, "target_delta +1 from node 0");
        assert_eq!(e.dist_mm, 1000);
        // ...and node 1's range covers the sentinel, which needs the real
        // nodes base, not null.
        assert_eq!(g.edge_range(1), (1, 1), "last node's range ends at edge_count");
    }

    #[test]
    fn a_v7_nodes_payload_with_the_wrong_unpacked_length_is_refused() {
        let mut payloads = minimal_payloads_v7();
        // Corrupt the unpacked length (N = 1 node + sentinel wants 24).
        payloads[1].1[16] = 25;
        assert!(
            load_slices(&payloads).is_none(),
            "unpacked length, not file length, gates v7"
        );
    }

    #[test]
    fn a_nodes_payload_the_meta_does_not_describe_is_refused() {
        let mut payloads = minimal_payloads();
        payloads[1].1.push(0); // 25 bytes for N = 1, want 24
        assert!(
            load_slices(&payloads).is_none(),
            "exact lengths, not at-least: a long payload is a vintage mismatch too"
        );
    }

    #[test]
    fn a_build_id_mismatch_is_refused_before_any_section_loads() {
        let mut bytes = assemble(&minimal_payloads());
        let last = bytes.len() - 1; // inside the footer's build id
        bytes[last] ^= 0xFF;
        // The footer check lives in ArchiveView, above assemble: parse the
        // layers directly since the host cannot mmap.
        let header = Header::parse(&bytes).expect("header still parses");
        assert!(
            ArchiveView::parse(&bytes, &header).is_err(),
            "one archive, one id"
        );
    }

    // File-backed `load_archive` end to end, Unix only: the host stub cannot
    // mmap, so this is where the mapping half is exercised (CI runs it).
    #[cfg(unix)]
    #[test]
    fn load_archive_maps_one_file_and_loads() {
        let bytes = assemble(&minimal_payloads());
        let path = write_temp("minimal", &bytes);
        let g = Graph::load_archive(path.to_str().unwrap()).expect("a minimal archive loads");
        assert_eq!(g.node_count, 1, "the meta's N survives the container");
        assert_eq!(g.edge_count, 0, "the meta's E survives the container");
        std::fs::remove_file(&path).ok();
    }

    /// Blocks inflate independently, so every record must land in the right
    /// block at the right offset, including across boundaries, in the short last
    /// block, and with a non-power-of-two blocking that takes the divide path.
    #[test]
    fn a_blocked_view_reads_every_record_across_block_boundaries() {
        let recs: Vec<u8> = (0..23u8).flat_map(|i| [i, i.wrapping_mul(7), !i]).collect();
        for per_block in [4u32, 5] {
            let bytes = tilecodec::blocked::write_blocked_with(&recs, 3, per_block);
            let view = BlockedView::parse(&bytes).expect("blocked section parses");
            for i in (0..23u8).rev() {
                assert_eq!(
                    view.record(u64::from(i)),
                    Some(&[i, i.wrapping_mul(7), !i][..]),
                    "record {i} with {per_block} per block"
                );
            }
            assert_eq!(view.record(23), None, "past the end with {per_block} per block");
        }
    }
}
