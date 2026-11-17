    #[test]
    fn an_edge_that_fails_both_tests_is_one_row_not_two() {
        let spec = vec![(0u32, 500_000u32, 0x200_0000u32)];
        let (decoded, _, escapes) = roundtrip_edges("both", &spec);
        assert_eq!(escapes, 1, "one row serves both fields");
        assert_eq!(decoded[0].0, 500_000);
        assert_eq!(decoded[0].1, 0x200_0000);
    }

    #[test]
    fn an_escape_at_either_end_of_the_pack_is_found() {
        // The block index is a prefix-count array, so the first and last edge are
        // where an off-by-one in it shows up.
        let far = 200_000u32;
        let spec = vec![
            (far, 0, 10),
            (far, far + 1, 20),
            (far, far + 2, 30),
            (far, 0, 40),
        ];
        let (decoded, _, escapes) = roundtrip_edges("ends", &spec);
        assert_eq!(escapes, 2);
        let got: Vec<(u32, u32)> = decoded.iter().map(|d| (d.0, d.1)).collect();
        assert_eq!(got, vec![(0, 10), (far + 1, 20), (far + 2, 30), (0, 40)]);
    }

    #[test]
    fn a_pack_with_no_escapes_is_records_padding_and_an_index_sentinel() {
        let spec: Vec<(u32, u32, u32)> = (0..100u32).map(|i| (1000, 1000 + i, i)).collect();
        let (decoded, bytes, escapes) = roundtrip_edges("no_escapes", &spec);
        assert_eq!(escapes, 0);
        // The record array is blocked (v7): it parses and unpacks to exactly
        // the 100 pushed records. Padding assertions belong to the v6 fixed
        // stride; the blocked container carries its own alignment instead.
        let blocked = tilecodec::blocked::Blocked::parse(&bytes).expect("blocked records");
        assert_eq!(blocked.unpacked_len(), 100 * EDGE_REC_BYTES);
        for (i, d) in decoded.iter().enumerate() {
            assert_eq!((d.0, d.1), (1000 + i as u32, i as u32));
            assert_eq!(d.2, NO_NAME, "no fixture edge is named");
        }
    }

    #[test]
    fn escape_rows_spanning_block_boundaries_are_all_found() {
        // Every side of every block boundary a 1024-edge block has, plus both ends.
        let far = 500_000u32;
        let n = 3000u32;
        let escaping = [0u32, 1023, 1024, 1025, 2047, 2048, 2049, n - 1];
        let spec: Vec<(u32, u32, u32)> = (0..n)
            .map(|i| {
                // An escaping edge points a long way back; the rest are a short
                // forward delta.
                let target = if escaping.contains(&i) { i } else { far + i };
                (far, target, 1000 + i)
            })
            .collect();
        let (decoded, _, escapes) = roundtrip_edges("blocks", &spec);
        assert_eq!(escapes, escaping.len() as u64);
        for i in 0..n as usize {
            assert_eq!(
                (decoded[i].0, decoded[i].1),
                (spec[i].1, spec[i].2),
                "edge {i} decoded wrongly"
            );
        }
    }

    /// Named at edge 0 and E−1, unnamed either side of a rank-block boundary, and a
    /// name offset at both ends of the `u32` range.
    ///
    /// The rank index covers 512 edges an entry, so the boundary at 511/512 is where
    /// a rank computed from the wrong block would show up — and it is a boundary a
    /// small fixture never reaches.
    #[test]
    fn the_sparse_name_table_survives_every_boundary() {
        let dir = std::env::temp_dir().join("osm_ingest_edges_names");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let n = 1200u64;
        // Which edges get a name, and what offset. Deliberately includes both ends,
        // both sides of the 512-edge rank boundary and both sides of 1024.
        let named: Vec<u64> = vec![0, 1, 510, 511, 512, 513, 1022, 1023, 1024, 1025, n - 1];
        let mut f = EdgeFile::create(&dir, "edges.bin", n).unwrap();
        for i in 0..n {
            let mut e = tmp_edge(0, i as u32, 7);
            e.speed_limit = 90;
            if let Some(k) = named.iter().position(|x| *x == i) {
                // A distinct offset per named edge, so a rank off by one reads a
                // visibly wrong value rather than a plausible one.
                e.name_offset = (k as u32) * 17;
            }
            f.push(&e, REVERSE_GEOMETRY_FLAG | 3).unwrap();
        }
        let escapes = f.escapes.len() as u64;
        let named_count = f.name_offsets.len() as u64;
        assert_eq!(named_count, named.len() as u64);
        let total = f.finish(n).unwrap();
        let bytes = std::fs::read(dir.join("edges.bin")).unwrap();
        assert_eq!(bytes.len() as u64, total);
        let e = Edges::new(&bytes, n, escapes);
        for i in 0..n {
            let (target, dist, name, type_, speed) = e.get(0, i);
            assert_eq!(target, i as u32);
            assert_eq!(dist, 7);
            assert_eq!(type_, REVERSE_GEOMETRY_FLAG | 3, "edge {i}");
            assert_eq!(speed, 90, "edge {i}");
            let want = match named.iter().position(|x| *x == i) {
                Some(k) => (k as u32) * 17,
                None => NO_NAME,
            };
            assert_eq!(name, want, "edge {i}'s name offset");
        }
    }

    #[test]
    fn a_pack_where_no_edge_is_named_and_one_where_every_edge_is() {
        for all_named in [false, true] {
            let dir = std::env::temp_dir()
                .join(format!("osm_ingest_edges_named_{all_named}"));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            let n = 600u64;
            let mut f = EdgeFile::create(&dir, "edges.bin", n).unwrap();
            for i in 0..n {
                let mut e = tmp_edge(0, i as u32, 1);
                if all_named {
                    e.name_offset = i as u32 * 3;
                }
                f.push(&e, 1).unwrap();
            }
            let named_count = f.name_offsets.len() as u64;
            assert_eq!(named_count, if all_named { n } else { 0 });
            let total = f.finish(n).unwrap();
            let bytes = std::fs::read(dir.join("edges.bin")).unwrap();
            assert_eq!(bytes.len() as u64, total);
            // v7 record array: blocked, unpacking to exactly the pushed records.
            let blocked = tilecodec::blocked::Blocked::parse(&bytes).expect("blocked records");
            assert_eq!(blocked.unpacked_len(), n * EDGE_REC_BYTES);
            let e = Edges::new(&bytes, n, 0);
            for i in 0..n {
                let want = if all_named { i as u32 * 3 } else { NO_NAME };
                assert_eq!(e.get(0, i).2, want, "edge {i} with all_named={all_named}");
            }
        }
    }

    #[test]
    fn the_round_count_does_not_change_a_single_output_byte() {
        // The strongest check on the rounds writer. Round boundaries move the split
        // between which edges each pass collects, which offsets each pass appends
        // and where `nodes.bin`'s running edge counter resumes, and none of that may
        // reach the file. 17 is prime, so with these node counts most of its rounds
        // are empty or hold a single node — exactly the boundaries an even split
        // would never exercise.
        let nodes = line_nodes(&[1, 2, 3, 4, 5, 6, 7, 8, 9]);
        let ways: &[(i64, &[i64])] = &[
            (100, &[1, 2, 3, 4]),
            (101, &[4, 5, 6]),
            (102, &[6, 7, 8, 9]),
            (103, &[3, 7]),
        ];
        let (pbf, dir) = testpbf::write_shape_sample("rounds_shape", &nodes, ways);
        let (stop_pbf, stop_dir) = testpbf::write_sample("rounds_fixture");

        for opts in [Options::default(), within_opts()] {
            for (input, base) in [(&pbf, &dir), (&stop_pbf, &stop_dir)] {
                let mut want: Option<Vec<Vec<u8>>> = None;
                for rounds in [1u32, 4, 17] {
                    let out = base.join(format!("r{rounds}_{}", opts.within_way_chains));
                    build_with(
                        input,
                        &out,
                        Options {
                            rounds,
                            ..opts.clone()
                        },
                    )
                    .unwrap();
                    let got: Vec<Vec<u8>> = [
                        "metadata.bin",
                        "nodes.bin",
                        "edges.bin",
                        "lanes.bin",
                        "road_names.bin",
                        "intermediate.bin",
                    ]
                    .iter()
                    .map(|f| std::fs::read(out.join(f)).unwrap())
                    .collect();
                    match &want {
                        None => want = Some(got),
                        Some(w) => assert_eq!(*w, got, "--rounds {rounds} changed the output"),
                    }
                    // The scratch blob must not survive into the pack.
                    assert!(!out.join("lanes.bin.blob").exists());
                    assert!(!out.join("chain_spill").exists(), "the spill was left behind");
                }
            }
        }
    }

    // ---- the v3 layouts at their boundaries -------------------------------

    #[test]
    fn a_block_of_maximal_polylines_cannot_escape_a_u16_within_offset() {
        // The arithmetic the two-level offset table rests on. A per-edge blob holds
        // only the interior points — 4 bytes each, at most `geom::MAX_POINTS - 2` of
        // them — so a block of `INTERMEDIATE_BLOCK` of them is the worst case the
        // u16 half has to hold. Keying the table on presence rank rather than on
        // edge index makes that bound tight rather than pessimistic: every entry in
        // a block is a real blob, where under v2 most were zero-length.
        let worst_edge = 4 * (u64::from(geom::MAX_POINTS) - 2);
        assert_eq!(worst_edge, 1016);
        assert!(
            INTERMEDIATE_BLOCK * worst_edge <= u64::from(u16::MAX),
            "a block of {INTERMEDIATE_BLOCK} maximal blobs is {} bytes",
            INTERMEDIATE_BLOCK * worst_edge
        );
    }

    #[test]
    fn the_presence_rank_agrees_with_a_brute_force_popcount() {
        // On-disk rank/select is a new primitive, so it is exercised on its own
        // rather than through a graph: ids straddling byte and 512-bit block
        // boundaries — the two places the three-term rank can be off by one — and an
        // edge count past a single rank block, which no fixture small enough to build
        // in a test would reach.
        const EDGES: u64 = 1500;
        let stored: Vec<u64> = vec![0, 1, 7, 8, 9, 63, 64, 65, 511, 512, 513, 1023, 1024, 1499];
        let dir = std::env::temp_dir().join("osm_ingest_present_rank");
        std::fs::create_dir_all(&dir).unwrap();
        let mut f = GeomFile::create(&dir, "intermediate.bin", EDGES).unwrap();
        for k in 0..EDGES {
            match stored.iter().position(|s| *s == k) {
                // A distinct length and content per stored edge, so an offset landing
                // on the wrong entry shows up rather than coinciding.
                Some(g) => f.store(&vec![g as u8; 4 * (g + 1)]).unwrap(),
                None => f.skip(),
            }
        }
        let size = f.finish(EDGES).unwrap();
        let bytes = std::fs::read(dir.join("intermediate.bin")).unwrap();
        assert_eq!(bytes.len() as u64, size);

        let t = Inter::new(&bytes, EDGES);
        assert_eq!(t.geometry_edges, stored.len() as u64);
        assert_eq!(t.blob_bytes, (1..=stored.len()).map(|n| 4 * n).sum::<usize>());
        for k in 0..EDGES {
            assert_eq!(t.present(k), stored.contains(&k), "presence bit {k}");
            // For every id, set or not, the rank is the brute-force count of set
            // bits strictly below it.
            let want = stored.iter().filter(|s| **s < k).count() as u64;
            assert_eq!(t.rank(k), want, "rank at {k}");
            if !stored.contains(&k) {
                assert_eq!(t.blob(k), None, "edge {k} stores nothing");
            }
        }
        // Each stored edge's blob is the one it wrote, found through that rank and
        // both offset levels.
        for (g, k) in stored.iter().enumerate() {
            assert_eq!(t.blob(*k), Some(&vec![g as u8; 4 * (g + 1)][..]), "blob at {k}");
        }
    }

    #[test]
    fn a_chain_at_the_256_point_limit_produces_the_largest_legal_blob() {
        // Exactly `geom::MAX_POINTS` vertices in one chain: the largest per-edge
        // blob the format allows, and so the worst case for the within-block u16.
        // Nodes 0.001 degrees apart, so nothing is interpolated and nothing splits.
        let ids: Vec<i64> = (1..=i64::from(geom::MAX_POINTS)).collect();
        let (stats, o) = within_way("inter_max_points", &line_nodes(&ids), &[(100, &ids)]);
        assert_eq!(stats.node_count, 2, "only the two ends of the run survive");
        assert_eq!(stats.chain_splits, 0, "256 points is exactly the budget");
        assert_eq!(stats.edge_count, 2);
        assert_eq!(stats.geometry_edges, 1, "the twin defers to the stored polyline");

        let t = inter(&o);
        let stored: Vec<u64> = (0..edge_count(&o)).filter(|k| t.present(*k)).collect();
        assert_eq!(stored.len(), 1);
        // Both endpoints are dropped, so 256 points cost 254 deltas.
        assert_eq!(
            t.blob(stored[0]).unwrap().len() as u64,
            4 * (u64::from(geom::MAX_POINTS) - 2)
        );
        assert_eq!(t.blob_bytes, 1016);
        // Both directions still decode the full polyline, one of them backwards.
        for k in 0..edge_count(&o) {
            let (pts, _) = edge_coords(&o, k).unwrap();
            assert_eq!(pts.len(), geom::MAX_POINTS as usize);
        }
    }

    #[test]
    fn the_two_level_offsets_cross_block_boundaries_exactly() {
        // 80 disjoint three-node ways: 80 chains, 160 directed edges and 80 stored
        // polylines, so the *geometry* indices the offset table is keyed on span
        // three coarse blocks and the two boundary cases — `g % 32 == 0` and
        // `g % 32 == 31` — both fall inside the graph rather than at its end, where
        // an off-by-one would be hidden by the sentinel.
        let mut nodes: Vec<(i64, i32, i32)> = Vec::new();
        let mut refs: Vec<Vec<i64>> = Vec::new();
        for w in 0..80i64 {
            let base = w * 10 + 1;
            for k in 0..3i64 {
                nodes.push((
                    base + k,
                    370_000_000 + (w as i32) * 100_000 + (k as i32) * 10_000,
                    -1_220_000_000,
                ));
            }
            refs.push(vec![base, base + 1, base + 2]);
        }
        let ways: Vec<(i64, &[i64])> = refs
            .iter()
            .enumerate()
            .map(|(i, r)| (100 + i as i64, r.as_slice()))
            .collect();
        let (_, o) = within_way("inter_blocks", &nodes, &ways);

        let e = edge_count(&o);
        assert_eq!(e, 160);
        let t = inter(&o);
        assert_eq!(t.geometry_edges, 80);
        assert!(
            t.geometry_edges > 2 * INTERMEDIATE_BLOCK,
            "the offset table must span three blocks"
        );

        // Each chain stores one 3-point polyline, which is a single interior delta,
        // and defers the other direction. So the offsets are a known sequence rather
        // than merely a self-consistent one, and exactly half the presence bits are
        // set.
        let offs: Vec<u64> = (0..=t.geometry_edges).map(|g| t.offset(g)).collect();
        assert!(offs.windows(2).all(|w| w[1] - w[0] == 4), "{offs:?}");
        assert_eq!(*offs.last().unwrap(), 80 * 4);
        assert_eq!((0..e).filter(|k| t.present(*k)).count(), 80);

        // The coarse entry for a block is its first geometry edge's absolute offset,
        // so the within-block value there is zero, and every offset in between
        // reassembles from the pair.
        let coarse = |b: u64| {
            let at = t.coarse_at + b as usize * 8;
            u64::from_le_bytes(o.inter[at..at + 8].try_into().unwrap())
        };
        let within = |g: u64| {
            let at = t.within_at + g as usize * 2;
            u16::from_le_bytes(o.inter[at..at + 2].try_into().unwrap())
        };
        for g in 0..=t.geometry_edges {
            assert_eq!(
                coarse(g / INTERMEDIATE_BLOCK) + u64::from(within(g)),
                offs[g as usize],
                "geometry edge {g} does not reassemble"
            );
            if g.is_multiple_of(INTERMEDIATE_BLOCK) {
                assert_eq!(within(g), 0, "a block's first entry starts at its coarse entry");
            }
        }
        // The whole file is the blob plus a trailer sized from `E` and `G`.
        assert_eq!(
            o.inter.len() as u64,
            *offs.last().unwrap() + GeomFile::trailer_bytes(e, t.geometry_edges)
        );
    }

    #[test]
    fn a_graph_where_every_edge_stores_geometry_and_one_where_none_does() {
        // The two ends of the presence bitmap. A ring collapsed to one node is a
        // self-loop both ways round, and a self-loop can never defer to a twin
        // (`twin_is_unique` refuses, since it would match itself), so both directions
        // store a polyline and every presence bit is set.
        let (all_stats, all) = within_way(
            "present_all",
            &line_nodes(&[1, 2, 3]),
            &[(100, &[1, 2, 3, 1])],
        );
        let t = inter(&all);
        assert_eq!(all_stats.edge_count, 2);
        assert_eq!(t.geometry_edges, 2);
        assert!((0..all_stats.edge_count).all(|k| t.present(k)));
        assert_eq!((t.rank(0), t.rank(1)), (0, 1));

        // A T-junction of two-node chains has no interior vertex anywhere, so nothing
        // is stored, the blob is empty and both offset levels hold only a sentinel.
        let (none_stats, none) = within_way(
            "present_none",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2]), (101, &[2, 3]), (102, &[2, 4])],
        );
        let t = inter(&none);
        assert_eq!(none_stats.edge_count, 6);
        assert_eq!(t.geometry_edges, 0);
        assert_eq!(t.blob_bytes, 0);
        assert!((0..none_stats.edge_count).all(|k| !t.present(k) && t.blob(k).is_none()));
        assert_eq!(t.offset(0), 0, "the sentinel is the empty blob's length");
        assert_eq!(
            none.inter.len() as u64,
            GeomFile::trailer_bytes(none_stats.edge_count, 0)
        );
    }

    #[test]
    #[should_panic(expected = "starts off its source node")]
    fn a_chain_that_starts_off_its_source_node_is_refused() {
        // Dropping the endpoints is only sound because a chain begins and ends on
        // its own nodes' coordinates. Feed the writer's check a mismatched chain to
        // prove it fires rather than encoding a polyline the reader would rebuild
        // wrongly.
        let coords = vec![(370_000_000, -1_220_000_000), (370_020_000, -1_220_000_000)];
        let chain = vec![(0, 0), (370_010_000, -1_220_000_000), coords[1]];
        assert_endpoints(&chain, &coords, 0, 1, 7);
    }

    #[test]
    #[should_panic(expected = "ends off its target node")]
    fn a_chain_that_ends_off_its_target_node_is_refused() {
        let coords = vec![(370_000_000, -1_220_000_000), (370_020_000, -1_220_000_000)];
        let chain = vec![coords[0], (370_010_000, -1_220_000_000), (0, 0)];
        assert_endpoints(&chain, &coords, 0, 1, 7);
    }

    #[test]
    fn a_graph_with_no_turn_lanes_has_an_empty_lane_index() {
        // The common case at planet scale: 99.7% of edges carry no `turn:lanes`, and
        // under the sparse layout they cost nothing at all rather than 8 bytes each.
        let (stats, o) = within_way(
            "lanes_none",
            &line_nodes(&[1, 2, 3, 4]),
            &[(100, &[1, 2, 3, 4])],
        );
        assert!(stats.edge_count > 0);
        let (index, blob) = lane_index(&o);
        assert_eq!(index, vec![(u32::MAX, 0)], "only the sentinel");
        assert!(blob.is_empty());
        assert_eq!(o.lanes.len(), 4 + 8);
        for k in 0..stats.edge_count {
            assert_eq!(lane_masks(&o, k), None);
        }
    }

    #[test]
    fn an_edge_count_past_the_u32_ceiling_is_refused() {
        // `nodes.bin`'s `edge_ptr` is a u32, so a wrapped edge count would point a
        // node at another node's edge range and the build would report success.
        assert_eq!(cap_u32("directed edge", u64::from(u32::MAX)).unwrap(), u32::MAX);
        let err = cap_u32("directed edge", u64::from(u32::MAX) + 1).unwrap_err();
        assert!(err.0.contains("directed edge"), "{}", err.0);    }
