
    #[test]
    fn a_previous_day_trip_that_never_crosses_midnight_is_ignored() {
        // A plain 08:00 trip belongs wholly to its own service day. Swept as
        // "yesterday" it must be dropped, not clamped to 00:00:00 and offered as
        // a departure at midnight.
        let pack = one_route_pack();
        let idx = pack.index();
        let midnight = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let board = stop_departures(&idx, 37.700, -122.400, 0, sched(midnight), 10);
        assert!(
            board.iter().all(|d| d.dep_secs > 0),
            "yesterday's daytime trips must not surface at 00:00:00, got {:?}",
            board.iter().map(|d| d.dep_secs).collect::<Vec<_>>()
        );
        assert_eq!(board.len(), 2, "only today's two trips");
    }

    #[test]
    fn resolves_each_feeds_timezone_by_coordinate() {
        // Two feeds in two zones, far enough apart that the grid separates them.
        let pack = Pack {
            stops: vec![
                Stop { lat: 37.700, lon: -122.400, name: "West1", code: "W1", gtfs_id: "W001" },
                Stop { lat: 37.710, lon: -122.400, name: "West2", code: "W2", gtfs_id: "W002" },
                Stop { lat: 40.700, lon: -74.000, name: "East1", code: "E1", gtfs_id: "E001" },
                Stop { lat: 40.710, lon: -74.000, name: "East2", code: "E2", gtfs_id: "E002" },
            ],
            routes: vec![
                Route {
                    name: "N",
                    color: 0,
                    route_type: 0,
                    feed: 0,
                    pattern: vec![0, 1],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                        service: 0,
                        headsign: "West",
                    }],
                    shape: None,
                },
                Route {
                    name: "A",
                    color: 0,
                    route_type: 1,
                    feed: 1,
                    pattern: vec![2, 3],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                        service: 0,
                        headsign: "East",
                    }],
                    shape: None,
                },
            ],
            services: vec![weekdays()],
            exceptions: Vec::new(),
            feeds: vec![
                ("sfmuni", "America/Los_Angeles", "us-ca-SFMTA"),
                ("mta", "America/New_York", "us-ny-MTA"),
            ],
        };
        let idx = pack.index();
        assert_eq!(idx.timezone_at(37.700, -122.400), "America/Los_Angeles");
        assert_eq!(idx.timezone_at(40.700, -74.000), "America/New_York");
        // Nothing within range -> empty, so the caller keeps the device zone.
        assert_eq!(idx.timezone_at(0.0, 0.0), "");
    }

    #[test]
    fn rejects_a_stale_pack_version() {
        let pack = one_route_pack();
        assert!(
            TransitIndex::from_bytes(pack.build_with_version(VERSION_MIN - 1)).is_none(),
            "a v2 pack must be rejected so Kotlin falls back to MOTIS"
        );
    }

    /// The strided trip table is only a storage change, so every version of the same
    /// pack has to plan identically. Asserted across the whole version window rather
    /// than just v6-vs-v5, because [`Trips`] is the only thing keeping the two reader
    /// paths in agreement and nothing else would notice them drifting.
    #[test]
    fn every_version_of_a_pack_plans_the_same_journey() {
        let pack = one_route_pack();
        let mut baseline: Option<Vec<(u32, u32, String)>> = None;
        for version in [VERSION_MIN, 4, 5, 7] {
            let idx = TransitIndex::from_bytes(pack.build_with_version(version))
                .unwrap_or_else(|| panic!("v{version} loads"));
            let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
                .unwrap_or_else(|| panic!("v{version} finds a journey"));
            let shape: Vec<(u32, u32, String)> = legs
                .iter()
                .map(|l| (l.dep_secs, l.arr_secs, l.name.clone()))
                .collect();
            match &baseline {
                None => baseline = Some(shape),
                Some(want) => assert_eq!(*want, shape, "v{version} planned a different journey"),
            }
        }
    }

    #[test]
    fn every_version_of_a_pack_lists_the_same_departures() {
        let pack = one_route_pack();
        let mut baseline: Option<Vec<u32>> = None;
        for version in [VERSION_MIN, 4, 5, 7] {
            let idx = TransitIndex::from_bytes(pack.build_with_version(version))
                .unwrap_or_else(|| panic!("v{version} loads"));
            let board = stop_departures(&idx, 37.700, -122.400, 0, sched(wednesday()), 10);
            let times: Vec<u32> = board.iter().map(|d| d.dep_secs).collect();
            match &baseline {
                None => baseline = Some(times),
                Some(want) => assert_eq!(*want, times, "v{version} listed different departures"),
            }
        }
    }

    /// A v6 pack still carries the varint stream, so a trip table that disagrees with
    /// the route records is recoverable — and must be recovered from rather than read
    /// out of bounds.
    #[test]
    fn a_trip_table_disagreeing_with_the_route_falls_back_to_the_varint_stream() {
        let pack = one_route_pack();
        let idx = TransitIndex::from_bytes(pack.build_with_version(7)).expect("v6 loads");
        let route = idx.route(0);
        assert!(idx.has_trip_table(), "the fixture writes the table");
        // What the strided path returns, to compare the fallback against.
        let want: Vec<u32> = {
            let trips = idx.trips_for(0, &route);
            (0..trips.len()).map(|i| trips.get(i).unwrap().start_time).collect()
        };
        let mut wrong = route;
        wrong.n_trips += 1;
        let trips = idx.trips_for(0, &wrong);
        assert!(matches!(trips, Trips::Decoded(_)), "a mismatched span must not be trusted");
        let got: Vec<u32> =
            (0..want.len()).map(|i| trips.get(i).unwrap().start_time).collect();
        assert_eq!(want, got, "the fallback decodes the same trips");
    }

    #[test]
    fn a_v3_pack_still_parses_and_plans() {
        // The rollout guard: a device on the newest reader must keep serving
        // offline transit from a v3 pack rather than rejecting it.
        let pack = shaped_route_pack();
        let idx = TransitIndex::from_bytes(pack.build_with_version(VERSION_MIN))
            .expect("a v3 pack still loads");
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(
            ride.coords.len(),
            6,
            "v3 carries no shape sections, so the ride draws one vertex per stop"
        );
    }

    #[test]
    fn a_v5_pack_composes_motis_stop_ids() {
        let idx = one_route_pack().index();
        // <feed prefix>_<raw gtfs stop_id>, the id `/stoptimes` expects. Note it is
        // the stop_id and not `code` ("A1"), which is a different GTFS column.
        assert_eq!(idx.motis_stop_id(0).as_deref(), Some("us-ca-SFMTA_901201"));
        assert_eq!(idx.motis_stop_id(2).as_deref(), Some("us-ca-SFMTA_901203"));
        // Out of range rather than a panic or a bogus id.
        assert_eq!(idx.motis_stop_id(99), None);
    }

    #[test]
    fn pre_v5_packs_report_no_motis_stop_id() {
        // The two id sections are absent, so the accessor must degrade to None
        // instead of reading whatever follows the shape sections.
        let pack = one_route_pack();
        for version in [VERSION_MIN, 4] {
            let idx = TransitIndex::from_bytes(pack.build_with_version(version))
                .expect("an older pack still loads");
            assert_eq!(idx.motis_stop_id(0), None, "v{version} carries no MOTIS ids");
        }
    }

    #[test]
    fn a_feed_with_no_known_prefix_reports_no_motis_stop_id() {
        // build_world_transit.ps1 mangles feed names, so its packs cannot name a
        // Transitous source; those stops must simply have no id.
        let mut pack = one_route_pack();
        pack.feeds = vec![("sfmuni", "America/Los_Angeles", "")];
        let idx = pack.index();
        assert_eq!(idx.motis_stop_id(0), None);
    }

    #[test]
    fn a_ride_leg_follows_the_gtfs_shape() {
        let pack = shaped_route_pack();
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");

        assert_eq!(ride.coords.len(), 10, "all five shape vertices, as [lon, lat] pairs");
        let lons: Vec<f64> = ride.coords.chunks(2).map(|c| c[0]).collect();
        assert!(
            lons.iter().any(|&lon| (lon - -122.390).abs() < 1e-9),
            "the eastward detour vertices are drawn, got {lons:?}"
        );
        // Stop-to-stop is 0.02 deg of latitude; the detour must be longer.
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!(
            ride.dist_m > crow,
            "shape distance {} must exceed the crow-flies {crow}",
            ride.dist_m
        );
        assert_eq!(ride.stop_count, 2, "stop_count still counts stops, not vertices");
    }

    #[test]
    fn a_ride_leg_slices_the_shape_to_the_boarded_span() {
        let pack = shaped_route_pack();
        let idx = pack.index();
        // Alpha -> Beta only: vertices 0..=2, not the whole polyline.
        let legs = plan(&idx, 37.700, -122.400, 37.710, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.coords.len(), 6, "vertices 0, 1, 2");
        assert!((ride.coords[0] - -122.400).abs() < 1e-9);
        assert!((ride.coords[1] - 37.700).abs() < 1e-9);
        assert!((ride.coords[4] - -122.400).abs() < 1e-9);
        assert!((ride.coords[5] - 37.710).abs() < 1e-9);
    }

    #[test]
    fn a_route_without_a_shape_draws_stop_to_stop() {
        // A v4 pack whose feed had no usable shape for this route: NONE offsets,
        // and the leg falls back to one vertex per stop.
        let pack = one_route_pack();
        let idx = pack.index();
        assert!(idx.route_shape_off(0).is_none());
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.coords.len(), 6, "Alpha, Beta, Gamma");
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!((ride.dist_m - crow).abs() < 1.0);
    }

    #[test]
    fn routes_sharing_a_shape_share_one_blob() {
        let mut pack = shaped_route_pack();
        let twin = Route {
            name: "N-express",
            color: 0,
            route_type: 0,
            feed: 0,
            pattern: vec![0, 2],
            trips: vec![Trip {
                start: 28_800,
                stoptimes: vec![(28_800, 28_800), (29_400, 29_400)],
                service: 0,
                headsign: "Downtown",
            }],
            shape: pack.routes[0].shape.take().map(|sh| Shape {
                points: sh.points.clone(),
                stop_vertices: vec![0, 4],
            }),
        };
        pack.routes[0].shape = twin.shape.as_ref().map(|sh| Shape {
            points: sh.points.clone(),
            stop_vertices: vec![0, 2, 4],
        });
        pack.routes.push(twin);
        let idx = pack.index();
        assert_eq!(
            idx.route_shape_off(0),
            idx.route_shape_off(1),
            "identical polylines must be stored once"
        );
        assert!(idx.route_shape_off(0).is_some());
    }

    #[test]
    fn stop_route_pos_matches_the_route_pattern() {
        // A loop route visiting a stop twice: STOP_ROUTE_POS must record the
        // FIRST occurrence, matching the scan it replaced.
        let mut pack = one_route_pack();
        pack.routes[0].pattern = vec![0, 1, 2, 1];
        pack.routes[0].trips = vec![Trip {
            start: 28_800,
            stoptimes: vec![
                (28_800, 28_800),
                (29_100, 29_100),
                (29_400, 29_400),
                (29_700, 29_700),
            ],
            service: 0,
            headsign: "Loop",
        }];
        let idx = pack.index();
        let (s, e) = idx.stop_routes_range(1);
        assert_eq!(e - s, 1, "one route serves stop 1");
        assert_eq!(idx.stop_route_pos(s), 1, "first occurrence, not the last");
    }

    #[test]
    fn departure_board_lists_upcoming_departures_with_realtime() {
        let pack = one_route_pack();
        let idx = pack.index();
        let board = stop_departures(&idx, 37.700, -122.400, 25_000, sched(wednesday()), 10);
        assert_eq!(board.len(), 2, "both trips are upcoming");
        assert_eq!(board[0].dep_secs, 28_800);
        assert_eq!(board[0].route_name, "N");
        assert_eq!(board[0].headsign, "Downtown");
        assert!(!board[0].real_time, "no overlay, so scheduled only");
        assert_eq!(board[0].delay_secs, 0);

        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 120,
                cancelled: false,
            }],
        );
        let live = stop_departures(
            &idx,
            37.700,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
            10,
        );
        let delayed_dep = live.iter().find(|d| d.dep_secs == 28_800).expect("the 08:00 trip");
        assert!(delayed_dep.real_time);
        assert_eq!(delayed_dep.delay_secs, 120);
        assert!(!delayed_dep.cancelled);
    }

    #[test]
    fn departure_board_excludes_the_terminus() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Gamma is the last stop on the only route, so nothing departs from it.
        assert!(stop_departures(&idx, 37.720, -122.400, 0, sched(wednesday()), 10).is_empty());
    }

    #[test]
    fn nothing_running_today_waits_for_the_next_service_day() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Sunday is not in the weekday mask and there is no exception, so the
        // first ride is Monday's 08:00, one service day later.
        let sunday = QueryDay {
            weekday: 6,
            date: 20_240_107,
            prev_weekday: 5,
            prev_date: 20_240_106,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(sunday))
            .expect("Monday's service is within the planning horizon");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, SECS_PER_DAY + 28_800, "Monday 08:00 in Sunday's frame");
    }

    #[test]
    fn nothing_within_the_horizon_is_no_journey() {
        let mut pack = one_route_pack();
        // Service ends on the query day's eve, so no day in the horizon runs it.
        pack.services[0].end = 20_240_102;
        let idx = pack.index();
        assert!(plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday())).is_none());
    }

    /// The hand-built harness above is an independent *reimplementation* of the
    /// writer, which is what makes it a useful cross-check — but it also means both
    /// sides could drift from the real producer together. This reads a pack
    /// actually emitted by `scripts/maps/gtfs_ingest`, so the two crates are pinned
    /// to each other rather than to a shared assumption.
    ///
    /// Regenerate after any format change (from the repo root):
    ///
    /// ```text
    /// cargo run --release --manifest-path scripts/maps/gtfs_ingest/Cargo.toml -- \
    ///     maps/src/main/rust/test_fixtures mini \
    ///     mini=scripts/maps/gtfs_ingest/test_fixtures/mini_feed
    /// ```
    ///
    /// then delete the `mini.transit.json` manifest it writes alongside. The bytes
    /// are a captured artefact, not a reproducible one: the ingester groups trips
    /// out of a `HashMap`, so route order varies between runs. The assertions are
    /// therefore semantic, which is what catches drift anyway.
    #[test]
    fn reads_a_pack_written_by_the_real_ingester() {
        let bytes = include_bytes!("../test_fixtures/mini.transit").to_vec();
        let idx = TransitIndex::from_bytes(bytes).expect("the committed fixture parses");

        // The fixture feed: 3 stops on one route, two trips, a shape detouring east
        // between each pair of stops.
        assert_eq!(idx.stop_count, 3);
        assert_eq!(idx.route_count, 1);
        assert_eq!(idx.timezone_at(37.700, -122.400), "America/Los_Angeles");

        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.name, "N");
        assert_eq!(ride.feed, "mini");
        assert_eq!(ride.from_stop, "Alpha");
        assert_eq!(ride.to_stop, "Gamma");
        assert_eq!(ride.dep_secs, 28_800);
        assert_eq!(ride.arr_secs, 29_400);
        assert_eq!(ride.stop_count, 2);

        // The v4 sections the ingester wrote must decode into real geometry: more
        // vertices than the three stops, and a longer path than the straight line.
        assert!(
            ride.coords.len() > 6,
            "expected shape geometry, got {} coords",
            ride.coords.len()
        );
        let lons: Vec<f64> = ride.coords.chunks(2).map(|c| c[0]).collect();
        assert!(
            lons.iter().any(|&lon| lon > -122.395),
            "the eastward detour is missing: {lons:?}"
        );
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!(ride.dist_m > crow, "shape distance {} must exceed {crow}", ride.dist_m);
    }

