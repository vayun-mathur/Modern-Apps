#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::body::{Feature, GEOM_LINE, GEOM_POLYGON};
    use tilecodec::mamaps::dict;

    /// A layer holding one polygon feature with the given rings, all labelled by position.
    fn layer_of(rings: &[Vec<(i16, i16)>]) -> Layer {
        let mut layer = Layer::new(dict::LAYER_LANDTYPE);
        layer.features.push(Feature {
            kind: dict::NONE,
            kind_detail: dict::NONE,
            geom_type: GEOM_POLYGON,
            flags: 0,
            name_idx: tilecodec::mamaps::body::NAME_NONE,
            parts_offset: 0,
            part_count: rings.len() as u32,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        for (index, ring) in rings.iter().enumerate() {
            layer.parts.push(Part {
                coord_start: layer.coords.len() as u32,
                point_count: ring.len() as u32,
                winding: if index == 0 { WINDING_OUTER } else { WINDING_HOLE },
            });
            layer.coords.extend_from_slice(ring);
        }
        layer
    }

    /// Positive signed area, which is what this module calls an exterior's winding.
    ///
    /// Tile coordinates put y downward, so a positive shoelace area looks clockwise on screen. The
    /// name follows the formula rather than the screen, because the formula is what the code and
    /// `tess::fill` both use.
    fn square(x: i16, y: i16, size: i16) -> Vec<(i16, i16)> {
        vec![(x, y), (x + size, y), (x + size, y + size), (x, y + size), (x, y)]
    }

    fn reversed(ring: &[(i16, i16)]) -> Vec<(i16, i16)> {
        let mut out = ring.to_vec();
        out.reverse();
        out
    }

    /// **The bug that reached a real build.** A degenerate exterior makes `normalise` drop the
    /// feature, and the id table is indexed by feature position — so dropping one without dropping
    /// its id shifts every id after it onto the wrong feature. The encoder catches the length
    /// mismatch, but only when the casualty happens to be the last feature does it stay a length
    /// mismatch rather than a silent misattribution.
    #[test]
    fn dropping_a_degenerate_feature_drops_its_id_too() {
        // Three features: a good square, a collapsed exterior, and another good square.
        let mut layer = layer_of(&[square(0, 0, 100)]);
        let mut push = |ring: Vec<(i16, i16)>| {
            layer.features.push(Feature {
                kind: dict::NONE,
                kind_detail: dict::NONE,
                geom_type: GEOM_POLYGON,
                flags: 0,
                name_idx: tilecodec::mamaps::body::NAME_NONE,
                parts_offset: layer.parts.len() as u32,
                part_count: 1,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
            });
            layer.parts.push(Part {
                coord_start: layer.coords.len() as u32,
                point_count: ring.len() as u32,
                winding: WINDING_OUTER,
            });
            layer.coords.extend_from_slice(&ring);
        };
        // Zero area: every point the same, so there is no exterior left after normalising.
        push(vec![(5, 5), (5, 5), (5, 5), (5, 5)]);
        push(square(200, 200, 100));

        let mut ids = vec![11u64, 22, 33];
        normalise_with_ids(&mut layer, Some(&mut ids), None, None);

        assert_eq!(layer.features.len(), 2, "the collapsed exterior is dropped");
        assert_eq!(ids, vec![11, 33], "and its id goes with it, not the one after it");
    }

    #[test]
    fn an_exterior_wound_the_wrong_way_is_reversed_not_relabelled() {
        let mut layer = layer_of(&[reversed(&square(0, 0, 100))]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.rewound, 1);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
        // The coordinates changed, not just the label: the winding field states a fact about them.
        assert!(signed_area(layer.points(&layer.parts[0])) > 0.0);
    }

    #[test]
    fn a_hole_wound_the_wrong_way_is_reversed() {
        let mut layer = layer_of(&[square(0, 0, 100), square(20, 20, 20)]);
        let stats = normalise(&mut layer);
        // The hole was given counter-clockwise, which is an exterior's winding.
        assert_eq!(stats.rewound, 1);
        assert_eq!(stats.holes_dropped, 0);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
        assert!(signed_area(layer.points(&layer.parts[1])) < 0.0, "the hole is clockwise");
    }

    /// **The four pathologies the published z0 tile exhibits.** Each is dropped and counted rather
    /// than clipped, because a botched boolean operation is worse than a missing lake.
    #[test]
    fn a_hole_outside_straddling_or_overlapping_is_dropped_and_counted() {
        // Wholly outside.
        let mut layer = layer_of(&[square(0, 0, 100), square(500, 500, 20)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1);
        assert_eq!(layer.features[0].part_count, 1, "only the exterior is left");

        // Straddling the exterior.
        let mut layer = layer_of(&[square(0, 0, 100), square(90, 90, 40)]);
        assert_eq!(normalise(&mut layer).holes_dropped, 1);

        // Overlapping another hole.
        let mut layer =
            layer_of(&[square(0, 0, 200), square(20, 20, 60), square(50, 50, 60)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1, "the first hole is kept, the overlapping one is not");
        assert_eq!(layer.features[0].part_count, 2);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
    }

    /// **The zoom-in island flooding.** A hole clipped onto the tile edge shares exact
    /// vertices with its exterior's clipped edge there. The boundary counts as inside, so
    /// the hole is kept — reading it as outside dropped real islands on fine tiles while
    /// the coarse tile (hole fully inside) stayed pixel-perfect.
    #[test]
    fn a_hole_touching_its_exterior_is_kept() {
        // Left and right edges of the hole sit exactly on the exterior's edges: the shape
        // the tile clipper emits when both rings reach the same tile edge.
        let exterior = vec![(0i16, 0i16), (100, 0), (100, 100), (0, 100), (0, 0)];
        let touching = vec![(0i16, 40i16), (100, 40), (100, 60), (0, 60), (0, 40)];
        let mut layer = layer_of(&[exterior, touching]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 0, "an edge-touching hole is not a straddle");
        assert_eq!(layer.features[0].part_count, 2);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
    }

    /// The complement: a hole with a vertex genuinely outside the exterior still goes.
    /// One vertex at 130 against an exterior ending at 100 is outside, not on the edge,
    /// so no boundary rule rescues it.
    #[test]
    fn a_hole_crossing_its_exterior_by_one_vertex_is_still_dropped() {
        let exterior = vec![(0i16, 0i16), (100, 0), (100, 100), (0, 100), (0, 0)];
        let crossing = vec![(40i16, 30i16), (40, 70), (70, 70), (130, 50), (70, 30), (40, 30)];
        let mut layer = layer_of(&[exterior, crossing]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1);
        assert_eq!(layer.features[0].part_count, 1);
    }

    #[test]
    fn a_zero_area_ring_goes_and_takes_nothing_with_it() {
        // A degenerate hole.
        let mut layer =
            layer_of(&[square(0, 0, 100), vec![(20, 20), (40, 20), (20, 20), (20, 20)]]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.zero_area, 1);
        assert_eq!(layer.features[0].part_count, 1);

        // A degenerate exterior takes its holes with it: there is nothing for them to be in.
        let mut layer = layer_of(&[vec![(0, 0), (10, 0), (0, 0), (0, 0)], square(2, 2, 2)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.zero_area, 1);
        assert!(layer.features.is_empty(), "the feature draws nothing, so it is not carried");
    }

    /// Nested lakes: a hole inside a hole. The inner one is land again, and dropping it is the
    /// conservative answer — it paints as water rather than as a wedge of nothing.
    #[test]
    fn a_lake_nested_inside_another_hole_is_dropped() {
        let mut layer =
            layer_of(&[square(0, 0, 400), square(50, 50, 200), square(100, 100, 50)]);
        let stats = normalise(&mut layer);
        assert_eq!(stats.holes_dropped, 1);
        assert!(check(&layer).is_empty(), "{:?}", check(&layer));
    }

    /// An already-valid polygon must come out untouched, or every rebuild would churn.
    #[test]
    fn a_valid_polygon_is_left_exactly_alone() {
        let rings = [square(0, 0, 200), reversed(&square(20, 20, 40))];
        let mut layer = layer_of(&rings);
        let before = (layer.parts.clone(), layer.coords.clone());
        let stats = normalise(&mut layer);
        assert!(stats.clean(), "{stats:?}");
        assert_eq!((layer.parts, layer.coords), before);
    }

    /// Winding means nothing on an open path, so a line's parts pass through untouched.
    #[test]
    fn a_line_layer_is_not_rewound() {
        let mut layer = Layer::new(dict::LAYER_ROADS);
        layer.features.push(Feature {
            kind: dict::NONE,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: tilecodec::mamaps::body::NAME_NONE,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        layer.parts.push(Part { coord_start: 0, point_count: 3, winding: WINDING_OUTER });
        layer.coords = vec![(0, 0), (50, 0), (50, 50)];
        let before = (layer.parts.clone(), layer.coords.clone());
        let stats = normalise(&mut layer);
        assert!(stats.clean());
        assert_eq!((layer.parts, layer.coords), before);
    }

    /// The check has to be able to fail, or it proves nothing.
    #[test]
    fn the_check_reports_each_invariant_it_can_see_broken() {
        let mut layer = layer_of(&[reversed(&square(0, 0, 100))]);
        assert!(!check(&layer).is_empty(), "a clockwise exterior");

        layer = layer_of(&[square(0, 0, 100), square(20, 20, 20)]);
        let problems = check(&layer);
        assert!(
            problems.iter().any(|p| p.contains("not clockwise")),
            "a counter-clockwise hole: {problems:?}",
        );

        layer = layer_of(&[square(0, 0, 100), reversed(&square(500, 500, 20))]);
        let problems = check(&layer);
        assert!(
            problems.iter().any(|p| p.contains("not inside")),
            "a hole outside its exterior: {problems:?}",
        );
    }

    /// The arena has to stay exactly tiled by the parts after a ring is dropped, or the encoder
    /// refuses the body — which is the check that makes this safe to run over a whole build.
    #[test]
    fn the_arena_stays_tiled_by_its_parts_after_a_drop() {
        let mut layer =
            layer_of(&[square(0, 0, 100), square(500, 500, 20), square(20, 20, 20)]);
        normalise(&mut layer);
        let mut at = 0u32;
        for part in &layer.parts {
            assert_eq!(part.coord_start, at);
            at += part.point_count;
        }
        assert_eq!(at as usize, layer.coords.len(), "no orphaned coordinates");
        let body = tilecodec::mamaps::body::Body {
            extent: 4096,
            layers: vec![layer],
            names: Vec::new(),
            ids: Vec::new(),
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
        };
        // The encoder's own contiguity check, which is the real proof.
        assert!(tilecodec::mamaps::body::serialize(&body).is_ok());
    }

    /// **Verification item 6, over a whole build rather than a synthetic case.** Every polygon of
    /// every tile has to satisfy all five invariants after the tiler runs, because that is exactly
    /// what the archive's `FLAG_RINGS_VALIDATED` claims and what the renderer will skip a repair
    /// pass on the strength of.
    #[test]
    fn every_polygon_of_a_real_build_is_valid() {
        use crate::schema::Class;
        use tilecodec::mamaps::body::Body;
        use tilecodec::mamaps::dict;

        // Shapes chosen to exercise the pathologies: a lake with a hole, a hole outside its
        // exterior, and a shape large enough to be clipped across several tiles at deep zoom.
        let ring = |x: f64, y: f64, size: f64| {
            vec![(x, y), (x + size, y), (x + size, y + size), (x, y + size), (x, y)]
        };
        let features = vec![
            crate::extract::Feature {
                class: Class::area(dict::LAYER_LANDTYPE, crate::schema::kind("lake"), 0),
                geometry: tile_build::geom::Geometry::Polygons(vec![vec![
                    ring(-120.5, 35.0, 1.0),
                    // A hole, wound the same way as its exterior, which stage C has to reverse.
                    ring(-120.2, 35.2, 0.3),
                ]]),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
                building: None,
            },
            crate::extract::Feature {
                class: Class::area(dict::LAYER_LANDTYPE, crate::schema::kind("water"), 0),
                geometry: tile_build::geom::Geometry::Polygons(vec![vec![
                    ring(-119.0, 36.0, 0.5),
                    // A hole nowhere near its exterior, which stage C has to drop.
                    ring(-100.0, 20.0, 0.1),
                ]]),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
                building: None,
            },
        ];
        let settings = crate::tiler::Settings {
            build_id: 1,
            scratch: std::env::temp_dir()
                .join(format!("mamaps_rings_{}.tilechunks", std::process::id())),
            force_chunk_spill_file: false,
            dem: crate::dem::Dem::from_grids(14, 17, Vec::new()),
            region_links: std::collections::HashMap::new(),
            country_zooms: std::collections::HashMap::new(),
        };
        let store = crate::store::Store::of(&features).expect("spill");
        let (bytes, stats) = crate::tiler::build(&store, &settings).expect("build");

        // The archive claims validity, so every tile in it had better be valid.
        let header = tilecodec::mamaps::Header::parse(&bytes).expect("header");
        assert!(header.rings_validated(), "the build claims validated rings");
        let mut checked = 0usize;
        for (id, _, body) in tilecodec::mamaps::read::read_all(&bytes).expect("read") {
            let body = Body::parse(&body).expect("parse");
            for layer in &body.layers {
                let problems = check(layer);
                assert!(problems.is_empty(), "tile {id}: {problems:?}");
                checked += layer.features.len();
            }
        }
        assert!(checked > 0, "the build produced no polygons to check");
        // And stage C really had something to do, or this proves nothing.
        let corrected: crate::rings::Stats =
            stats.iter().fold(crate::rings::Stats::default(), |mut acc, z| {
                acc.add(z.rings);
                acc
            });
        assert!(!corrected.clean(), "stage C corrected nothing: {corrected:?}");
    }

    /// **The zoom-in island flooding, end to end.** Lake Superior at survey scale:
    /// a ~7°-wide lake through the real pipeline — classify (named → min_zoom 6),
    /// simplify, clip per tile, stage C — asserting the water survives at z0, z5
    /// and z14. Before the area floor, an unnamed lake vanished below z12 and a
    /// named one below z6; the renderer then drew earth over the whole basin.
    ///
    /// Superior's real shoreline is thousands of vertices; ten per edge keeps the
    /// shape (and its tile-edge crossings) while the test stays instant.
    #[test]
    fn lake_superior_survives_coarse_and_fine() {
        use tilecodec::mamaps::body::Body;
        use tilecodec::mamaps::dict;

        // Lake Superior's real bbox (~-92.1..-84.4 lon, 46.4..48.9 lat), ten
        // vertices per edge: the tile-edge crossings are what the clipper and
        // stage C must survive, not the shoreline detail.
        let edge = |x0: f64, y0: f64, x1: f64, y1: f64| -> Vec<(f64, f64)> {
            (0..10)
                .map(|i| {
                    let t = i as f64 / 9.0;
                    (x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
                })
                .collect()
        };
        let (x0, y0, x1, y1) = (-92.1, 46.4, -84.4, 48.9);
        let mut ring = edge(x0, y0, x1, y0);
        ring.extend(edge(x1, y0, x1, y1).into_iter().skip(1));
        ring.extend(edge(x1, y1, x0, y1).into_iter().skip(1));
        ring.extend(edge(x0, y1, x0, y0).into_iter().skip(1));
        // Classify exactly as a named lake classifies: min_zoom 0 start with an area
        // floor, which is what carries great water to the world tile.
        let tags: &[(&str, &str)] =
            &[("natural", "water"), ("water", "lake"), ("name", "Lake Superior")];
        let class = crate::schema::landtype::classify_early(tags, true)
            .expect("a named lake classifies");
        assert_eq!(class.min_zoom, 0, "the named-lake start zoom");
        let features = vec![crate::extract::Feature {
            class,
            geometry: tile_build::geom::Geometry::Polygons(vec![vec![ring]]),
            name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                        turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
            building: None,
        }];
        let settings = crate::tiler::Settings {
            build_id: 1,
            scratch: std::env::temp_dir()
                .join(format!("mamaps_superior_{}.tilechunks", std::process::id())),
            force_chunk_spill_file: false,
            dem: crate::dem::Dem::from_grids(14, 17, Vec::new()),
            region_links: std::collections::HashMap::new(),
            country_zooms: std::collections::HashMap::new(),
        };
        let store = crate::store::Store::of(&features).expect("spill");
        let (bytes, _) = crate::tiler::build(&store, &settings).expect("build");

        // A lake-kind polygon must be present at z0 (whole basin in a handful of
        // tiles), z5 and z14. Count tiles carrying any lake-kind feature.
        let mut saw = [false, false, false];
        for (id, _, body) in tilecodec::mamaps::read::read_all(&bytes).expect("read") {
            let (z, _, _) = tilecodec::pmtiles::tile_zxy(id);
            let slot = match z {
                0 => 0,
                5 => 1,
                14 => 2,
                _ => continue,
            };
            let body = Body::parse(&body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_LANDTYPE) else {
                continue;
            };
            let lake_kind = crate::schema::kind("lake");
            if layer.features.iter().any(|f| {
                f.geom_type == tilecodec::mamaps::body::GEOM_POLYGON && f.kind == lake_kind
            }) {
                saw[slot] = true;
            }
        }
        assert!(saw[0], " Superior water reaches z0");
        assert!(saw[1], "Superior water reaches z5");
        if crate::tiler::wash_cap_enabled() {
            assert!(!saw[2], "wash cap drops lake water at z14 (overzoomed from z13)");
        } else {
            assert!(saw[2], "Superior water reaches z14");
        }
    }

    /// **Country label gating, end to end.** Two `country` places through the real
    /// pipeline with scored start zooms: a Russia-scale label (z0) and a Vatican-scale
    /// label (z4). The z0 tile carries only Russia; the z4 tile carries both. A third
    /// unscored country keeps the schema floor and rides every zoom — a missing score
    /// must never hide a country.
    #[test]
    fn country_labels_start_at_their_scored_zoom() {
        use tilecodec::mamaps::body::Body;
        use tilecodec::mamaps::dict;

        fn country(id: u64) -> crate::extract::Feature {
            let class = crate::schema::places::classify(
                &[("place", "country"), ("name", "Test")] as &[(&str, &str)],
            )
            .expect("classified");
            crate::extract::Feature {
                class,
                geometry: tile_build::geom::Geometry::Points(vec![(10.0, 10.0)]),
                name: Some("Test".to_string()),
                id,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
                turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
                building: None,
            }
        }

        let russia = 1001u64;
        let vatican = 1002u64;
        let unscored = 1003u64;
        let features = vec![country(russia), country(vatican), country(unscored)];
        let mut zooms: std::collections::HashMap<u64, u8> = std::collections::HashMap::new();
        zooms.insert(russia, 0);
        zooms.insert(vatican, 4);
        let settings = crate::tiler::Settings {
            build_id: 1,
            scratch: std::env::temp_dir()
                .join(format!("mamaps_countries_{}.tilechunks", std::process::id())),
            force_chunk_spill_file: false,
            dem: crate::dem::Dem::from_grids(14, 17, Vec::new()),
            region_links: std::collections::HashMap::new(),
            country_zooms: zooms,
        };
        let store = crate::store::Store::of(&features).expect("spill");
        let (bytes, _) = crate::tiler::build(&store, &settings).expect("build");

        // Which ids does each zoom's `places` layer carry?
        let mut at_zoom: std::collections::HashMap<u8, Vec<u64>> =
            std::collections::HashMap::new();
        for (id, _, body) in tilecodec::mamaps::read::read_all(&bytes).expect("read") {
            let (z, _, _) = tilecodec::pmtiles::tile_zxy(id);
            if z != 0 && z != 4 {
                continue;
            }
            let body = Body::parse(&body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_PLACES) else {
                continue;
            };
            let country_kind = crate::schema::kind("country");
            let has = |want: u64| {
                layer
                    .features
                    .iter()
                    .enumerate()
                    .filter(|(_, f)| f.kind == country_kind)
                    .any(|(i, _)| body.feature_id(dict::LAYER_PLACES, i) == Some(want))
            };
            for want in [russia, vatican, unscored] {
                if has(want) {
                    at_zoom.entry(z).or_default().push(want);
                }
            }
        }
        let mut z0 = at_zoom.get(&0).cloned().unwrap_or_default();
        let mut z4 = at_zoom.get(&4).cloned().unwrap_or_default();
        z0.sort_unstable();
        z4.sort_unstable();
        z0.dedup();
        z4.dedup();
        assert_eq!(z0, vec![russia, unscored], "z0 carries Russia and the unscored country");
        assert_eq!(
            z4,
            vec![russia, vatican, unscored],
            "z4 carries all three — Vatican joins, nothing leaves"
        );
    }
}
