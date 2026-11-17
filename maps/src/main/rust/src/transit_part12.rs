    // --- Corridor-bundled rail lines + trip itineraries (`transit_part11`) ---

    #[test]
    fn rail_lines_serve_a_shaped_route_with_its_colour() {
        let idx = shaped_route_pack().index();
        let lines = rail_lines(&idx, 37.69, -122.41, 37.73, -122.39);
        assert_eq!(lines.len(), 1, "the one shaped route serves the bbox");
        let l = &lines[0];
        assert_eq!(l.name, "N");
        assert_eq!(l.color, 0x0000FF);
        assert_eq!(l.feed, "sfmuni");
        // The fitted shape detours east: 5 vertices = 10 doubles, and the
        // second point must swing off the stops' shared meridian.
        assert_eq!(l.coords.len(), 10);
        assert!(l.coords[2] > -122.400, "on the eastward detour");
    }

    #[test]
    fn rail_lines_skip_a_bbox_no_stop_serves() {
        let idx = shaped_route_pack().index();
        let lines = rail_lines(&idx, 40.0, -75.0, 41.0, -74.0);
        assert!(lines.is_empty(), "far from the SF stops: nothing to draw");
    }

    #[test]
    fn trip_itinerary_lists_every_stop_with_times() {
        let idx = one_route_pack().index();
        // Vehicle id for route 0, trip 0, today: the packing active_vehicles uses.
        let id = 0;
        let itin =
            trip_itinerary(&idx, id, sched(wednesday())).expect("trip 0 runs Wednesday");
        assert_eq!(itin.route_name, "N");
        assert_eq!(itin.headsign, "Downtown");
        assert!(!itin.cancelled);
        assert_eq!(itin.stops.len(), 3);
        assert_eq!(itin.stops[0].name, "Alpha");
        assert_eq!(itin.stops[2].name, "Gamma");
        assert_eq!(
            (itin.stops[0].dep_secs, itin.stops[2].arr_secs),
            (28_800, 29_400)
        );
        // Dwell at Beta: arrives 29_100, departs 29_160.
        assert_eq!(
            (itin.stops[1].arr_secs, itin.stops[1].dep_secs),
            (29_100, 29_160)
        );
    }

    #[test]
    fn trip_itinerary_refuses_an_unknown_trip() {
        let idx = one_route_pack().index();
        // Route 9 does not exist; trip 99 neither.
        assert!(
            trip_itinerary(&idx, 9i64 << 32, sched(wednesday())).is_none()
        );
        assert!(
            trip_itinerary(&idx, 99i64 << 1, sched(wednesday())).is_none()
        );
    }

    /// Two routes over one track, in different agency colours, fan into
    /// adjacent lanes rather than stacking.
    fn two_colour_pack() -> Pack {
        let mut pack = one_route_pack();
        pack.routes.push(Route {
            name: "M",
            color: 0xFF0000,
            route_type: 0,
            feed: 0,
            pattern: vec![0, 1, 2],
            trips: vec![Trip {
                start: 28_800,
                stoptimes: vec![(28_800, 28_800), (29_100, 29_160), (29_400, 29_400)],
                service: 0,
                headsign: "Downtown",
            }],
            // Same alignment, surveyed a metre east: one corridor, not two.
            shape: Some(Shape {
                points: vec![
                    (37.700, -122.39999),
                    (37.705, -122.38999),
                    (37.710, -122.39999),
                    (37.715, -122.38999),
                    (37.720, -122.39999),
                ],
                stop_vertices: vec![0, 2, 4],
            }),
        });
        pack.routes[0].shape = Some(Shape {
            points: vec![
                (37.700, -122.400),
                (37.705, -122.390),
                (37.710, -122.400),
                (37.715, -122.390),
                (37.720, -122.400),
            ],
            stop_vertices: vec![0, 2, 4],
        });
        pack
    }

    #[test]
    fn shared_track_fans_into_adjacent_lanes() {
        let idx = two_colour_pack().index();
        let lines = rail_lines(&idx, 37.69, -122.41, 37.73, -122.39);
        assert!(!lines.is_empty(), "both routes serve the bbox");
        // Every span of the two colours shares one corridor: same lane
        // count, complementary ordinals.
        let mut by_color: std::collections::HashMap<u32, Vec<(u8, u8)>> =
            std::collections::HashMap::new();
        for l in &lines {
            by_color.entry(l.color).or_default().push((l.ordinal, l.lanes));
        }
        assert_eq!(by_color.len(), 2, "both agency colours draw: {by_color:?}");
        for (color, spans) in &by_color {
            assert!(
                spans.iter().any(|&(_, lanes)| lanes == 2),
                "colour {color:06X} fans as one of two: {spans:?}"
            );
        }
        let ordinals: std::collections::HashSet<u8> =
            lines.iter().filter(|l| l.lanes == 2).map(|l| l.ordinal).collect();
        assert_eq!(ordinals, std::collections::HashSet::from([0, 1]), "{ordinals:?}");
    }

    #[test]
    fn a_byte_identical_republication_draws_once() {
        let shape_points = vec![
            (37.700, -122.400),
            (37.705, -122.390),
            (37.710, -122.400),
            (37.715, -122.390),
            (37.720, -122.400),
        ];
        let mut pack = one_route_pack();
        pack.routes[0].shape = Some(Shape {
            points: shape_points.clone(),
            stop_vertices: vec![0, 2, 4],
        });
        // Same geometry, same colour, new name: a republished feed.
        pack.routes.push(Route {
            name: "N-dup",
            color: 0x0000FF,
            route_type: 0,
            feed: 0,
            pattern: vec![0, 1, 2],
            trips: vec![Trip {
                start: 28_800,
                stoptimes: vec![(28_800, 28_800), (29_100, 29_160), (29_400, 29_400)],
                service: 0,
                headsign: "Downtown",
            }],
            shape: Some(Shape { points: shape_points, stop_vertices: vec![0, 2, 4] }),
        });
        let idx = pack.index();
        let lines = rail_lines(&idx, 37.69, -122.41, 37.73, -122.39);
        let blue: Vec<_> = lines.iter().filter(|l| l.color == 0x0000FF).collect();
        assert_eq!(blue.len(), 1, "one blue line, not two: {lines:?}");
    }

    #[test]
    fn stop_lines_name_only_serving_routes_and_keep_buses() {
        // The shaped tram plus a crosstown line through the same stops and a
        // bus serving the middle stop: tapping that stop draws all three
        // (buses included), tapping far away draws nothing.
        let mut pack = shaped_route_pack();
        pack.routes.push(Route {
            name: "Crosstown",
            color: 0x00FF00,
            route_type: 1,
            feed: 0,
            pattern: vec![0, 1, 2],
            trips: vec![Trip {
                start: 28_800,
                stoptimes: vec![(28_800, 28_800), (29_100, 29_160), (29_400, 29_400)],
                service: 0,
                headsign: "East",
            }],
            shape: None,
        });
        pack.routes.push(Route {
            name: "38",
            color: 0xFF8800,
            route_type: 3,
            feed: 0,
            pattern: vec![1, 2],
            trips: vec![Trip {
                start: 28_800,
                stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                service: 0,
                headsign: "Downtown",
            }],
            shape: None,
        });
        let idx = pack.index();
        // Beta (37.710) sits on all three patterns (shared platforms). Assert
        // on the route-name set, not the span count: corridor fanning may cut
        // one route into several spans.
        let lines = routes_for_stop(&idx, 37.710, -122.400);
        let names: std::collections::HashSet<&str> =
            lines.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(
            names,
            std::collections::HashSet::from(["N", "Crosstown", "38"]),
            "every route serving Beta draws: {lines:?}"
        );
        assert!(
            lines.iter().any(|l| l.name == "38"),
            "the selected-stop case keeps buses: {lines:?}"
        );
        // Far from every stop: nothing to draw.
        let far = routes_for_stop(&idx, 40.0, -75.0);
        assert!(far.is_empty(), "no stop near: nothing to draw");
    }

