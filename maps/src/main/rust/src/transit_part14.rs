
    /// Two long-distance trains meeting at a mid-continent hub, ~510 km end to
    /// end, plus a short local bus at the hub. Stop 0 is the origin, 4 the
    /// destination, 2 the hub, 5 a stop 4 km from it.
    fn intercity_pack() -> Pack {
        let stop = |lon: f64, name: &'static str| Stop { lat: 40.0, lon, name, code: name, gtfs_id: name };
        Pack {
            stops: vec![
                stop(-120.00, "Origin"),
                stop(-119.99, "OriginSide"),
                stop(-118.00, "Hub"),
                stop(-116.00, "Junction"),
                stop(-114.00, "Destination"),
                stop(-117.95, "HubSide"),
            ],
            routes: vec![
                // Reaches the hub at 10:00, after the connection's 09:00 departure.
                Route {
                    name: "Zephyr",
                    color: 0,
                    route_type: 2,
                    feed: 0,
                    pattern: vec![0, 2],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (36_000, 36_000)],
                        service: 0,
                        headsign: "East",
                    }],
                    shape: None,
                },
                Route {
                    name: "Limited",
                    color: 0x00_3366,
                    route_type: 2,
                    feed: 0,
                    pattern: vec![2, 4],
                    trips: vec![Trip {
                        start: 32_400,
                        stoptimes: vec![(32_400, 32_400), (54_000, 54_000)],
                        service: 1,
                        headsign: "East",
                    }],
                    shape: None,
                },
                Route {
                    name: "Local",
                    color: 0,
                    route_type: 3,
                    feed: 0,
                    pattern: vec![2, 5],
                    trips: vec![Trip {
                        start: 37_000,
                        stoptimes: vec![(37_000, 37_000), (37_600, 37_600)],
                        service: 0,
                        headsign: "Side",
                    }],
                    shape: None,
                },
            ],
            services: vec![
                Service { mask: 0b0111_1111, start: 20_240_101, end: 20_241_231 },
                Service { mask: 0b0111_1111, start: 20_240_101, end: 20_241_231 },
            ],
            exceptions: Vec::new(),
            feeds: vec![("rail", "America/Denver", "us-Rail")],
        }
    }

    #[test]
    fn a_connection_leaving_tomorrow_completes_the_journey() {
        let idx = intercity_pack().index();
        let legs = plan(&idx, 40.0, -120.0, 40.0, -114.0, 25_200, sched(wednesday()))
            .expect("the hub connection runs the next morning");
        let rides: Vec<&TransitLeg> = legs.iter().filter(|l| l.kind == LegKind::Ride).collect();
        assert_eq!(rides.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(), ["Zephyr", "Limited"]);
        assert_eq!(rides[0].arr_secs, 36_000);
        assert_eq!(rides[1].dep_secs, SECS_PER_DAY + 32_400, "Thursday 09:00");
        assert_eq!(rides[1].arr_secs, SECS_PER_DAY + 54_000);
        assert!(legs.iter().any(|l| l.kind == LegKind::Wait && l.arr_secs == SECS_PER_DAY + 32_400));
    }

    #[test]
    fn a_connection_two_days_out_is_still_found() {
        let mut pack = intercity_pack();
        // The connection only runs on Fridays: two service days after Wednesday.
        pack.services[1].mask = 1 << 4;
        let idx = pack.index();
        let legs = plan(&idx, 40.0, -120.0, 40.0, -114.0, 25_200, sched(wednesday()))
            .expect("Friday is within the horizon");
        let last = legs.iter().rfind(|l| l.kind == LegKind::Ride).expect("a ride");
        assert_eq!(last.dep_secs, 2 * SECS_PER_DAY + 32_400);
    }

    #[test]
    fn later_service_days_resolve_their_weekday_and_date() {
        assert_eq!(add_days(20_240_228, 1), 20_240_229, "leap day");
        assert_eq!(add_days(20_240_228, 2), 20_240_301);
        assert_eq!(add_days(20_231_231, 1), 20_240_101);
        assert_eq!(add_days(20_240_301, -1), 20_240_229);
        let sunday = QueryDay { weekday: 6, date: 20_240_107, prev_weekday: 5, prev_date: 20_240_106 };
        assert_eq!(sunday.on(-1), (5, 20_240_106));
        assert_eq!(sunday.on(0), (6, 20_240_107));
        assert_eq!(sunday.on(2), (1, 20_240_109), "Tuesday");
    }

    #[test]
    fn long_routes_are_classified_by_span_and_mode() {
        let idx = intercity_pack().index();
        let ic = idx.intercity();
        assert!(ic.is_long(0) && ic.is_long(1), "both trains span 170+ km");
        assert!(!ic.is_long(2), "a 4 km bus is local");
    }

    #[test]
    fn only_long_routes_are_boarded_far_from_both_ends() {
        let idx = intercity_pack().index();
        assert!(IntercityScope::for_trip(&idx, 40.0, -120.0, 40.0, -119.5).is_none(), "a 43 km trip");
        let scope = IntercityScope::for_trip(&idx, 40.0, -120.0, 40.0, -114.0).expect("a 510 km trip");
        assert!(!scope.long_only_at(&idx, 1), "next to the origin");
        assert!(scope.long_only_at(&idx, 2), "the hub is far from both ends");
        assert!(!scope.long_only_at(&idx, 4), "the destination");
        assert!(scope.is_long(1) && !scope.is_long(2));
    }

