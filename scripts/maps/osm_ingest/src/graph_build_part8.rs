    /// Ties on `spatial` are the only case where the two could differ, so the fixture
    /// is mostly ties, with `dense` ascending as the real builder pushes them.
    #[test]
    fn the_spatial_key_is_total_so_an_unstable_sort_matches_the_stable_one() {
        let keys: Vec<(u64, u32)> = vec![
            (5, 0),
            (3, 1),
            (5, 2),
            (1, 3),
            (3, 4),
            (5, 5),
            (1, 6),
            (9, 7),
            (3, 8),
        ];
        // What the code used to do: stable, keyed on `spatial` alone, so ties kept
        // their insertion order -- which was ascending `dense`.
        let mut stable = keys.clone();
        stable.sort_by_key(|(spatial, _)| *spatial);
        // What it does now.
        let mut unstable = keys;
        unstable.par_sort_unstable();
        assert_eq!(unstable, stable);
        // And the property that makes it true: no two entries share a full key.
        let mut seen = unstable.clone();
        seen.dedup();
        assert_eq!(seen.len(), unstable.len(), "the key must be unique per node");
    }

    // ---- the within-way chain path ---------------------------------------

    fn within_opts() -> Options {
        Options {
            within_way_chains: true,
            ..Options::default()
        }
    }

    /// A line of nodes 0.001 degrees apart along 122°W, so no geometry in these
    /// shape tests is ever interpolated or split.
    fn line_nodes(ids: &[i64]) -> Vec<(i64, i32, i32)> {
        ids.iter()
            .map(|id| (*id, 370_000_000 + (*id as i32) * 10_000, -1_220_000_000))
            .collect()
    }

    /// Build `nodes`/`ways` both ways round and return `(legacy, within_way)`.
    fn both_paths(tag: &str, nodes: &[(i64, i32, i32)], ways: &[(i64, &[i64])]) -> (Stats, Stats) {
        let (pbf, dir) = testpbf::write_shape_sample(tag, nodes, ways);
        let legacy = build(&pbf, &dir.join("legacy")).unwrap();
        let within = build_with(
            &pbf,
            &dir.join("within"),
            within_opts(),
        )
        .unwrap();
        (legacy, within)
    }

    fn within_way(tag: &str, nodes: &[(i64, i32, i32)], ways: &[(i64, &[i64])]) -> (Stats, Outputs) {
        let (pbf, dir) = testpbf::write_shape_sample(tag, nodes, ways);
        let stats = build_with(
            &pbf,
            &dir,
            within_opts(),
        )
        .unwrap();
        (stats, read_outputs(&dir))
    }

    #[test]
    fn a_within_way_chain_collapses_a_plain_run() {
        let (stats, o) = within_way(
            "chain_run",
            &line_nodes(&[1, 2, 3, 4, 5]),
            &[(100, &[1, 2, 3, 4, 5])],
        );
        // Only the two ends survive, and the one chain becomes a bidirectional
        // pair carrying every interior vertex as geometry.
        assert_eq!(stats.raw_node_count, 5);
        assert_eq!(stats.node_count, 2);
        assert_eq!(stats.edge_count, 2);
        assert_eq!(stats.raw_edge_count, 8, "four segments, both directions");
        let (pts, _) = edge_coords(&o, 0).unwrap();
        assert_eq!(pts.len(), 5);
    }

    #[test]
    fn an_interior_node_that_another_way_ends_at_is_not_collapsed() {
        // Way 100's node 2 looks interior *within way 100*, but way 101 also ends
        // there, so globally it has three incidences and is a real junction. This
        // is the whole reason the degree count is global rather than per way: a
        // per-way count would read 2 here and collapse a T-junction away.
        let (stats, o) = within_way(
            "chain_tee",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 3]), (101, &[2, 4])],
        );
        assert_eq!(stats.node_count, 4, "nothing may collapse at a T-junction");
        // 1-2, 2-3 and 2-4, each both ways.
        assert_eq!(stats.edge_count, 6);
        for k in 0..stats.edge_count {
            assert!(edge_coords(&o, k).is_none(), "no chain here has an interior vertex");
        }
    }

    #[test]
    fn a_ring_way_closes_on_its_own_first_node() {
        // Every node on the ring has two incidences and none is special, so
        // `compact` has to hunt for an anchor to break it at. Walking positions
        // makes that free: the way's own first node is the boundary, and the walk
        // cannot loop because it is advancing through a finite ref list.
        let (stats, o) = within_way(
            "chain_ring",
            &line_nodes(&[1, 2, 3]),
            &[(100, &[1, 2, 3, 1])],
        );
        assert_eq!(stats.node_count, 1, "the ring keeps exactly one node");
        assert_eq!(stats.edge_count, 2, "a self-loop, both directions");
        for k in 0..stats.edge_count {
            let (target, _, _, _, _) = edge_at(&o, k as usize);
            assert_eq!(target, 0, "both edges close on the anchor");
            let (pts, _) = edge_coords(&o, k).unwrap();
            assert_eq!(pts.len(), 4, "the ring's shape is preserved as geometry");
            assert_eq!(pts[0], pts[3]);
        }
    }

    #[test]
    fn a_repeated_ref_is_skipped_without_breaking_the_run() {
        // `[1, 2, 2, 3]` is a mapping artefact, not a zero-length road at node 2.
        // Counting it would give node 2 four incidences and promote a genuine
        // pass-through into a junction, so both the degree pass and the walk must
        // reject it — and the walk must then still join 1-2 to 2-3.
        let nodes = line_nodes(&[1, 2, 3]);
        let ways: &[(i64, &[i64])] = &[(100, &[1, 2, 2, 3])];
        let (stats, o) = within_way("chain_dup", &nodes, ways);
        assert_eq!(stats.node_count, 2);
        assert_eq!(stats.edge_count, 2);
        let (pts, _) = edge_coords(&o, 0).unwrap();
        assert_eq!(pts.len(), 3, "node 2 survives as a vertex, not as a node");

        // The reference path asks the same question about a pair, so it must reach
        // the same answer. It used to emit a zero-length self-loop here instead.
        let (pbf, dir) = testpbf::write_shape_sample("chain_dup_ref", &nodes, ways);
        let a = dir.join("legacy");
        let b = dir.join("within");
        build(&pbf, &a).unwrap();
        build_with(&pbf, &b, within_opts()).unwrap();
        for f in ["nodes.bin", "edges.bin", "intermediate.bin"] {
            assert_eq!(
                std::fs::read(a.join(f)).unwrap(),
                std::fs::read(b.join(f)).unwrap(),
                "{f} differs on a repeated ref"
            );
        }
    }

    #[test]
    fn a_self_touching_way_cuts_at_the_node_it_revisits() {
        // `[1, 2, 3, 2, 4]` visits node 2 twice, giving it four incidences. The
        // positional walk cuts there both times without needing to notice that it
        // has been there before.
        let (stats, _) = within_way(
            "chain_touch",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 3, 2, 4])],
        );
        assert_eq!(stats.node_count, 3, "node 3 folds into the 2 -> 3 -> 2 loop");
        // 1-2 and 2-4 both ways, plus the self-loop at 2 both ways.
        assert_eq!(stats.edge_count, 6);
    }

    #[test]
    fn a_gap_left_by_a_dangling_ref_ends_the_run_it_interrupts() {
        // Node 999 is referenced but never defined, so 2-999 and 999-3 are not
        // segments. The run 1-2 must be closed and a new one started at 3, not
        // silently joined across the gap.
        let (pbf, dir) = testpbf::write_shape_sample(
            "chain_gap",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 999, 3, 4])],
        );
        let stats = build_with(
            &pbf,
            &dir,
            within_opts(),
        )
        .unwrap();
        assert_eq!(stats.raw_node_count, 4, "999 never becomes a node");
        assert_eq!(stats.node_count, 4, "1-2 and 3-4 are two separate chains");
        assert_eq!(stats.edge_count, 4);
    }

    #[test]
    fn within_way_chains_keep_the_node_where_two_ways_meet() {
        // The accepted regression, stated as a test rather than as an estimate.
        // Two residential ways meet end-to-end at node 3 and agree on every
        // attribute, so `compact` folds node 3 into one five-point chain. Chains
        // confined to a single way cannot, and node 3 stays.
        let (legacy, within) = both_paths(
            "chain_crossway",
            &line_nodes(&[1, 2, 3, 4, 5]),
            &[(100, &[1, 2, 3]), (101, &[3, 4, 5])],
        );
        assert_eq!(legacy.node_count, 2);
        assert_eq!(legacy.edge_count, 2);
        assert_eq!(within.node_count, 3, "node 3 is the cost of within-way chains");
        assert_eq!(within.edge_count, 4);
        // Both keep every original vertex, so nothing about the road's drawn shape
        // changes: the extra node is a routing cost, not a geometry loss.
        assert_eq!(legacy.raw_node_count, within.raw_node_count);
        assert_eq!(legacy.raw_edge_count, within.raw_edge_count);
    }

    #[test]
    fn the_within_way_path_agrees_with_the_reference_path_on_a_single_way() {
        // With one way there is no cross-way merge to lose, so the two paths must
        // produce byte-identical output. That is what makes the comparison above a
        // measurement of the regression rather than of an unrelated difference.
        let nodes = line_nodes(&[1, 2, 3, 4, 5, 6]);
        let ways: &[(i64, &[i64])] = &[(100, &[1, 2, 3, 4, 5, 6])];
        let (pbf, dir) = testpbf::write_shape_sample("chain_same", &nodes, ways);
        let a = dir.join("legacy");
        let b = dir.join("within");
        build(&pbf, &a).unwrap();
        build_with(
            &pbf,
            &b,
            within_opts(),
        )
        .unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(a.join(f)).unwrap(),
                std::fs::read(b.join(f)).unwrap(),
                "{f} differs between the two collapse paths"
            );
        }
    }

    #[test]
    fn the_shared_fixture_is_byte_identical_on_both_paths() {
        // Nothing in this fixture can be merged across a way boundary: node 4 is
        // an endpoint of both Main St and the service road, and node 2 has three
        // incidences. So the two paths must agree byte for byte here — which makes
        // this a check on one-way orientation, lane masks, reverse-geometry flags
        // and stop reconnection all at once, since a within-way walk reaches all of
        // them by different code than `compact` does.
        let (pbf_path, dir) = testpbf::write_sample("chain_fixture");
        let a = dir.join("legacy");
        let b = dir.join("within");
        let legacy = build(&pbf_path, &a).unwrap();
        let within = build_with(
            &pbf_path,
            &b,
            within_opts(),
        )
        .unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(a.join(f)).unwrap(),
                std::fs::read(b.join(f)).unwrap(),
                "{f} differs between the two collapse paths"
            );
        }
        assert_eq!(legacy.node_count, within.node_count);
        assert_eq!(legacy.raw_edge_count, within.raw_edge_count);
        assert_eq!(legacy.geometry_edges, within.geometry_edges);
        assert_eq!(legacy.reversed_edges, within.reversed_edges);
        assert_eq!(within.reconnected_stops, 1);
        let o = read_outputs(&b);
        assert_eq!(local_of(&o, 3), None, "node 3 is interior to Main St");
        assert_eq!(o.names, b"Main St\0Test Stop\0".to_vec());
    }

    #[test]
    fn two_within_way_runs_are_byte_identical() {
        let (pbf_path, dir_a) = testpbf::write_sample("chain_det_a");
        let (_, dir_b) = testpbf::write_sample("chain_det_b");
        let opts = within_opts();
        build_with(&pbf_path, &dir_a, opts.clone()).unwrap();
        build_with(&pbf_path, &dir_b, opts).unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(dir_a.join(f)).unwrap(),
                std::fs::read(dir_b.join(f)).unwrap(),
                "{f} differs between runs"
            );
        }
    }

    #[test]
    fn a_file_with_no_routable_ways_produces_an_empty_graph() {
        // Nothing marks the node bitset, so the dense address space is empty and
        // there is no largest marked id to size a rank index against. Every count
        // has to come out zero rather than underflowing on the way there.
        let (pbf, dir) = testpbf::write_shape_sample("empty_graph", &line_nodes(&[1, 2, 3]), &[]);
        for opts in [Options::default(), within_opts()] {
            let out = dir.join(format!("w{}", opts.within_way_chains));
            let stats = build_with(&pbf, &out, opts).unwrap();
            assert_eq!(stats.raw_node_count, 0);
            assert_eq!(stats.node_count, 0);
            assert_eq!(stats.edge_count, 0);
            assert_eq!(stats.lcc_size, 0);
            let o = read_outputs(&out);
            // The smallest tables `graph.rs` can read zero edges through: one rank
            // entry, no presence bytes at all, one coarse entry, one within-block
            // entry and the `u64 G` trailer; plus `nodes.bin`'s trailing sentinel and
            // a lane index holding only its own header and sentinel.
            assert_eq!(o.nodes.len(), 12);
            // `edges.bin` is not empty even with no edges: the escape block index and
            // the name rank index each carry their final entry, which are the totals
            // the reader cross-checks `escape_count` and `named_edges` against. Four
            // bytes of "no escapes", padding, then eight of "no names".
            assert_eq!(o.edges.len() as u64, EdgeFile::total_bytes(0, 0, 0));
            assert_eq!(o.edges.len(), 4 + 4 + 8);
            assert!(o.edges.iter().all(|b| *b == 0), "every count is zero");
            assert_eq!(stats.escape_count, 0);
            assert_eq!(stats.named_edges, 0);
            assert_eq!(o.inter.len(), 8 + 8 + 2 + 8, "rank, coarse, within, G");
            assert_eq!(o.inter.len() as u64, GeomFile::trailer_bytes(0, 0));
            assert_eq!(o.lanes.len(), 4 + 8);
            assert_eq!(lane_index(&o).0, vec![(u32::MAX, 0)]);
            assert!(o.names.is_empty());
        }
    }

    #[test]
    fn a_region_build_drops_the_nodes_of_filtered_ways() {
        // The 2.47B-node bug: the region filter removed ways but degree-0
        // leftovers survived via `degree != 2`, so every world road-node landed
        // in `nodes.bin` with no edge addressing it. Two disjoint line ways, a
        // bbox covering only the first: the second way's nodes must not survive.
        let nodes = vec![
            (1, 370_000_000, -1_220_000_000),
            (2, 370_010_000, -1_220_000_000),
            (3, 370_020_000, -1_220_000_000),
            (11, 400_000_000, -1_000_000_000),
            (12, 400_010_000, -1_000_000_000),
            (13, 400_020_000, -1_000_000_000),
        ];
        let ways: &[(i64, &[i64])] = &[(100, &[1, 2, 3]), (101, &[11, 12, 13])];
        let (pbf, dir) = testpbf::write_shape_sample("region_drop", &nodes, ways);
        let bbox = crate::bbox::BBox {
            min_lon: -123.0,
            min_lat: 36.0,
            max_lon: -121.0,
            max_lat: 38.0,
        };
        for opts in [
            Options { bbox: Some(bbox), ..Options::default() },
            Options { bbox: Some(bbox), within_way_chains: true, ..Options::default() },
        ] {
            let out = dir.join(format!("w{}", opts.within_way_chains));
            let stats = build_with(&pbf, &out, opts).unwrap();
            assert_eq!(stats.node_count, 2, "only the kept way's ends survive");
            assert_eq!(stats.edge_count, 2);
            let o = read_outputs(&out);
            assert_eq!(node_count(&o), 2);
            for i in 0..2 {
                let (lat, lon, _) = node_at(&o, i);
                assert!((370_000_000..=370_020_000).contains(&lat), "stray node at {lat},{lon}");
            }
        }
    }

    // ---------------------------------------------------------------------
    // edges.bin narrowing: the boundaries no real extract contains
    // ---------------------------------------------------------------------

    fn tmp_edge(source: u32, target: u32, dist_mm: u32) -> TmpEdge {
        TmpEdge {
            source,
            target,
            dist_mm,
            name_offset: NO_NAME,
            type_: 1,
            speed_limit: 50,
            lane_off: NO_LANES,
            lane_count: 0,
            chain: NO_CHAIN,
            chain_rev: false,
            pts_start: 0,
            pts_len: 0,
        }
    }

    /// Push `spec` — `(source, target, dist_mm)` — through the real [`EdgeFile`] and
    /// read every record back through the mirror of `graph.rs`'s decode.
    ///
    /// The delta encoding's boundaries are unreachable from a PBF fixture: putting
    /// two nodes exactly 32,768 apart in Morton order would mean choosing
    /// coordinates for 32,767 nodes in between. So the encoder and the decoder are
    /// exercised as a pair directly, which is also where the off-by-one would be.
    type EdgeRoundtrip = (Vec<(u32, u32, u32, u8, u8)>, Vec<u8>, u64);

    fn roundtrip_edges(
        tag: &str,
        spec: &[(u32, u32, u32)],
    ) -> EdgeRoundtrip {
        let dir = std::env::temp_dir().join(format!("osm_ingest_edges_{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let count = spec.len() as u64;
        let mut f = EdgeFile::create(&dir, "edges.bin", count).unwrap();
        for (source, target, dist) in spec {
            f.push(&tmp_edge(*source, *target, *dist), 1).unwrap();
        }
        let escapes = f.escapes.len() as u64;
        let total = f.finish(count).unwrap();
        let bytes = std::fs::read(dir.join("edges.bin")).unwrap();
        assert_eq!(bytes.len() as u64, total, "finish() lied about the length");
        // v7: the record array is a ZBLK section unpacking to exactly the
        // pushed records; the escapes/names tail follows it. (The v6
        // `total_bytes` formula no longer applies — the records' length is
        // data-dependent.)
        let blocked = tilecodec::blocked::Blocked::parse(&bytes)
            .expect("the record array is a blocked section");
        assert_eq!(blocked.unpacked_len(), count * EDGE_REC_BYTES);
        assert_eq!(blocked.record_len(), EDGE_REC_BYTES as usize);
        let e = Edges::new(&bytes, count, escapes);
        // The block index's last entry is the row total, which is what the reader
        // cross-checks `escape_count` against.
        let blocks = count.div_ceil(ESCAPE_BLOCK) + 1;
        assert_eq!(
            u64::from(e.escape_first(blocks - 1)),
            escapes,
            "the block index total disagrees with the row count"
        );
        let decoded = spec
            .iter()
            .enumerate()
            .map(|(i, (source, _, _))| e.get(*source, i as u64))
            .collect();
        (decoded, bytes, escapes)
    }

    #[test]
    fn every_target_delta_boundary_round_trips() {
        // Far enough from zero that every delta below is a representable u32 target.
        let source = 1_000_000u32;
        let deltas: [i64; 11] = [0, 1, -1, 32766, -32766, 32767, -32767, 32768, -32768, -32769, 40000];
        let spec: Vec<(u32, u32, u32)> = deltas
            .iter()
            .map(|d| (source, (i64::from(source) + d) as u32, 1234))
            .collect();
        let (decoded, _, escapes) = roundtrip_edges("deltas", &spec);
        for (i, d) in deltas.iter().enumerate() {
            assert_eq!(decoded[i].0, spec[i].1, "delta {d} did not round-trip");
            assert_eq!(decoded[i].1, 1234, "delta {d} disturbed dist_mm");
        }
        // ±32767 fit. ±32768, −32769 and +40000 do not — and −32768 is `i16::MIN`,
        // which is the sentinel, so it escapes even though its magnitude would fit.
        // That reservation is what keeps the representable range symmetric.
        assert_eq!(escapes, 4, "expected 32768, -32768, -32769 and 40000 to escape");
    }

    #[test]
    fn every_dist_mm_boundary_round_trips() {
        let source = 5u32;
        let dists = [0u32, 1, 0xFF_FFFE, 0xFF_FFFF, 0x100_0000, u32::MAX];
        let spec: Vec<(u32, u32, u32)> =
            dists.iter().map(|d| (source, source + 1, *d)).collect();
        let (decoded, _, escapes) = roundtrip_edges("dists", &spec);
        for (i, d) in dists.iter().enumerate() {
            assert_eq!(decoded[i].1, *d, "dist {d} did not round-trip");
            assert_eq!(decoded[i].0, source + 1, "dist {d} disturbed the target");
        }
        // 0xFFFFFE is the largest a u24 can hold and be a value; 0xFFFFFF is the
        // sentinel, so it escapes despite fitting. The escape keeps every distance
        // *exact* — the alternative considered was centimetres, which needs no table
        // but quantises all 1.07 G edges.
        assert_eq!(escapes, 3);
    }
