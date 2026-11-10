
    // ---- stop reconnection by component size ------------------------------

    /// Three road networks and a stop on each, plus a stop attached to nothing.
    ///
    /// This is the planet case stated at the smallest scale that can state it.
    /// Continents are not road-connected, so a planet's largest component is
    /// Eurasia-plus-Africa at 57.7% and the old "reconnect everything outside the
    /// largest component" rule tried to drag every American, Australian, Japanese,
    /// British and Indonesian stop across an ocean — failing 1,173,347 times. The
    /// rule is component *size*, so a stop already on a real road network is left
    /// alone whichever network that is.
    #[test]
    fn a_stop_on_any_routable_component_is_left_alone() {
        const A: u32 = 1200; // the largest component
        const B: u32 = 1000; // exactly MIN_ROUTABLE_COMPONENT, so still routable
        const C: u32 = 999; // one node short of it, so not
        let total = A + B + C + 1;

        // A is far west; B and C are adjacent in both index and space, so the
        // widening window around a stop in C reaches B before anything else.
        let mut coords: Vec<geom::Pt> = Vec::with_capacity(total as usize);
        for i in 0..A {
            coords.push((370_000_000 + i as i32 * 1000, -1_220_000_000));
        }
        for i in 0..B {
            coords.push((400_000_000 + i as i32 * 1000, -740_000_000));
        }
        for i in 0..C {
            coords.push((410_000_000 + i as i32 * 1000, -740_000_000));
        }
        // The stop attached to nothing sits beside B's far end.
        coords.push((400_999_500, -740_000_000));

        let mut edges: Vec<(u32, u32)> = Vec::new();
        let chain = |base: u32, len: u32, edges: &mut Vec<(u32, u32)>| {
            for i in 1..len {
                edges.push((base + i - 1, base + i));
                edges.push((base + i, base + i - 1));
            }
        };
        chain(0, A, &mut edges);
        chain(A, B, &mut edges);
        chain(A + B, C, &mut edges);
        let csr = csr_of(total, &edges);

        let mut stops = Bitset::new(u64::from(total));
        for s in [A / 2, A + B / 2, A + B + C / 2, A + B + C] {
            stops.set(u64::from(s));
        }

        let rec = reconnect_isolated_stops(&coords, &stops, &csr);
        assert_eq!(rec.lcc_size, u64::from(A));
        assert_eq!(
            rec.already_connected, 2,
            "the stops in A and in B are both already on a routable component"
        );
        assert_eq!(rec.unreachable, 0);
        // The stop in C and the stop attached to nothing, both connectors both ways.
        assert_eq!(rec.synth.len(), 4);
        for source in [A + B + C / 2, A + B + C] {
            let target = rec
                .synth
                .iter()
                .find(|s| s.source == source)
                .expect("every isolated stop gets a connector")
                .target;
            assert!(
                target < A + B,
                "stop {source} was connected to node {target}, which is in C"
            );
            assert_eq!(target, A + B - 1, "and to the nearest such node");
        }
        // Both directions of each connector are present, which is what makes the
        // stop reachable rather than merely reaching.
        for s in &rec.synth {
            assert!(
                rec.synth
                    .iter()
                    .any(|t| t.source == s.target && t.target == s.source),
                "connector {} -> {} has no return",
                s.source,
                s.target
            );
        }
    }

    #[test]
    fn the_routable_threshold_falls_back_to_the_largest_component() {
        // A graph whose largest component is under MIN_ROUTABLE_COMPONENT still has
        // a road network — it is just a small one. Requiring 1000 nodes there would
        // qualify nothing and reconnect nothing, so the threshold is capped at the
        // largest component that exists. Two nodes of road and a stop off to one
        // side: the stop must still find the road.
        let coords: Vec<geom::Pt> = vec![
            (370_000_000, -1_220_000_000),
            (370_010_000, -1_220_000_000),
            (380_000_000, -1_220_000_000),
        ];
        let csr = csr_of(3, &[(0, 1), (1, 0)]);
        let mut stops = Bitset::new(3);
        stops.set(2);

        let rec = reconnect_isolated_stops(&coords, &stops, &csr);
        assert_eq!(rec.lcc_size, 2);
        assert_eq!(rec.already_connected, 0);
        assert_eq!(rec.unreachable, 0);
        assert_eq!(rec.synth.len(), 2);
        assert_eq!(rec.synth[0].source, 1, "the nearest node of the only road");
        assert_eq!(rec.synth[0].target, 2);
    }

    #[test]
    fn args_default_the_output_directory() {
        let (input, out, opts) = parse_args(&["cal.osm.pbf".into()]).unwrap();
        assert_eq!(input, PathBuf::from("cal.osm.pbf"));
        assert_eq!(out, PathBuf::from("map_data"));
        assert!(
            opts.within_way_chains,
            "the streaming within-way path is the CLI default"
        );
        let (_, out, _) = parse_args(&["cal.osm.pbf".into(), "--out".into(), "d".into()]).unwrap();
        assert_eq!(out, PathBuf::from("d"));
        let (_, _, opts) =
            parse_args(&["cal.osm.pbf".into(), "--reference-collapse".into()]).unwrap();
        assert!(
            !opts.within_way_chains,
            "--reference-collapse opts back into the reference path"
        );
        assert_eq!(opts.round_count(), 1, "one round is the default");
        assert!(opts.spill_dir.is_none(), "the spill defaults to the output dir");
        let (_, _, opts) = parse_args(&["cal.osm.pbf".into(), "--rounds".into(), "8".into()]).unwrap();
        assert_eq!(opts.round_count(), 8);
        let (_, _, opts) =
            parse_args(&["c.pbf".into(), "--spill-dir".into(), "/fast".into()]).unwrap();
        assert_eq!(opts.spill_dir, Some(PathBuf::from("/fast")));
        assert!(parse_args(&["a".into(), "--spill-dir".into()]).is_err());
        let (_, _, opts) = parse_args(&[
            "c.pbf".into(),
            "--spill-dir".into(),
            "/roomy".into(),
            "--spill-pts-dir".into(),
            "/fast".into(),
        ])
        .unwrap();
        assert_eq!(opts.spill_dir, Some(PathBuf::from("/roomy")));
        assert_eq!(opts.spill_pts_dir, Some(PathBuf::from("/fast")));
        assert!(parse_args(&["a".into(), "--spill-pts-dir".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into(), "0".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into(), "many".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into()]).is_err());
        let (_, _, opts) = parse_args(&["c.pbf".into()]).unwrap();
        assert!(opts.threads.is_none(), "the pool defaults to the box");
        let (_, _, opts) =
            parse_args(&["c.pbf".into(), "--threads".into(), "4".into()]).unwrap();
        assert_eq!(opts.threads, Some(4));
        assert!(parse_args(&["a".into(), "--threads".into(), "0".into()]).is_err());
        assert!(parse_args(&["a".into(), "--threads".into()]).is_err());
        assert!(parse_args(&[]).is_err());
        assert!(parse_args(&["a".into(), "b".into()]).is_err());
        assert!(parse_args(&["--wat".into()]).is_err());
    }

    // ---------------------------------------------------------------------
    // TODO(write-pipe) re-entry checklist, step 1: bake_batch on a synthetic
    // buffer, before any pool or drain exists.
    // ---------------------------------------------------------------------

    /// A one-point `chains.pts` mapping: the synthetic edges carry no chain,
    /// so nothing reads it, but `bake_batch` takes the shared mapping.
    fn dummy_points(tag: &str) -> crate::mem::Mapped {
        let dir = std::env::temp_dir().join(format!("osm_ingest_bake_{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("chains.pts");
        std::fs::write(&path, [0u8; 8]).unwrap();
        crate::mem::Mapped::open(&path).unwrap()
    }

    /// `(node id, edge_ptr)` of every opener, in bake order.
    fn bake_openers(b: &BakedBatch) -> Vec<(u32, u32)> {
        (0..b.node_count())
            .map(|i| {
                let (lat, _, ptr) = b.opener(i);
                (lat as u32, ptr)
            })
            .collect()
    }

    #[test]
    fn bake_batch_opens_every_node_in_its_span_at_base_plus_run_offset() {
        // Nodes 10..20, whose lat_e7 is the node id so an opener names its node.
        let kept = 24u32;
        let node_coords: Vec<geom::Pt> = (0..kept).map(|i| (i as i32, 0)).collect();
        // Runs: 11 x2, 13 x1, 15 x3. Edgeless: 10 (leading), 12, 14 (interior),
        // 16..20 (trailing) — the trailing ones are the case a batch that ends
        // on an edgeless node must still open.
        let spec: &[(u32, u32)] = &[(11, 3), (11, 4), (13, 2), (15, 1), (15, 5), (15, 6)];
        let mut edges: Vec<TmpEdge> = spec.iter().map(|(s, t)| tmp_edge(*s, *t, 1000)).collect();
        // One escaped (dist past u24) and one named edge, to pin their offsets.
        edges[2].dist_mm = DIST_MM_ESCAPE;
        edges[4].name_offset = 77;
        let csr = csr_of(kept, spec);
        let points = dummy_points("span");
        let (first, end, base) = (10u32, 20u32, 100u64);
        let b =
            bake_batch(&edges, first, end, base, &node_coords, &[], &csr, &[], &points).unwrap();
        let openers = bake_openers(&b);
        assert_eq!(
            openers.len(),
            (end - first) as usize,
            "opener count must equal the node span"
        );
        let want: Vec<(u32, u32)> = vec![
            (10, 100),
            (11, 100),
            (12, 102),
            (13, 102),
            (14, 103),
            (15, 103),
            (16, 106),
            (17, 106),
            (18, 106),
            (19, 106),
        ];
        assert_eq!(openers, want, "opener edge_ptrs must be base + run offsets");
        // One record per edge, side tables keyed by the edge's offset in the batch.
        assert_eq!(b.edge_count, edges.len() as u64);
        assert_eq!(b.edge_recs.len(), edges.len() * EDGE_REC_BYTES as usize);
        assert_eq!(b.escapes, vec![(2, 2, DIST_MM_ESCAPE)]);
        assert_eq!(b.names, vec![(4, 77)]);
        // The census saw every node of the span, edgeless ones included.
        assert_eq!(b.census.degree_zero_nodes, 7);
        assert_eq!(b.census.max_degree, 3);
    }

    #[test]
    fn bake_batch_refuses_an_edge_outside_its_span() {
        let node_coords: Vec<geom::Pt> = (0..8).map(|i| (i, 0)).collect();
        let csr = csr_of(8, &[(6, 1)]);
        let points = dummy_points("stray");
        let edges = [tmp_edge(6, 1, 10)];
        assert!(bake_batch(&edges, 2, 6, 0, &node_coords, &[], &csr, &[], &points).is_err());
    }

    #[test]
    fn bake_batch_opens_a_wholly_edgeless_span() {
        let node_coords: Vec<geom::Pt> = (0..8).map(|i| (i, 0)).collect();
        let csr = csr_of(8, &[]);
        let points = dummy_points("edgeless");
        let b = bake_batch(&[], 2, 6, 7, &node_coords, &[], &csr, &[], &points).unwrap();
        assert_eq!(bake_openers(&b), vec![(2, 7), (3, 7), (4, 7), (5, 7)]);
        assert_eq!(b.edge_count, 0);
    }

    #[test]
    fn a_duplicated_node_id_keeps_its_last_coordinates() {
        // The parallel node scan lets only the first claimant write a slot, so
        // a repeated id must fall back to the serial fold, where the LAST
        // occurrence wins — as it always has.
        let mut nodes = line_nodes(&[1, 2, 3]);
        nodes.push((3, 370_090_000, -1_220_000_000));
        let (pbf, dir) = testpbf::write_shape_sample("dup_node", &nodes, &[(100, &[1, 2, 3])]);
        let stats = build_with(&pbf, &dir, within_opts()).unwrap();
        assert_eq!(stats.raw_node_count, 3, "a repeated id is still one node");
        let o = read_outputs(&dir);
        let lats: Vec<i32> = (0..node_count(&o)).map(|i| node_at(&o, i).0).collect();
        assert!(lats.contains(&370_090_000), "the last occurrence wins: {lats:?}");
        assert!(!lats.contains(&370_030_000), "the first occurrence is gone: {lats:?}");
    }
