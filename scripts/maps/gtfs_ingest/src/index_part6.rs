#[cfg(test)]
mod shape_tests {
    use super::*;
    use super::tests::{agency, assert_shape_invariants, one_feed, shape_map, shaped_feed};
    use crate::gtfs::parse_csv;
    use crate::reader::{Reader, SEC_SHAPE_COORDS};

    #[test]
    fn a_mismatched_shape_is_dropped_to_the_stop_to_stop_fallback() {
        let t = shaped_feed(false);
        let ag = agency("America/Los_Angeles");
        // The same shape_id, but 4000 km east.
        let sh = shape_map(vec![(
            "SH1",
            vec![(40.700, -74.000), (40.710, -74.000), (40.720, -74.000)],
            None,
        )]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 0);
        assert_eq!(stats.dropped_shape_routes, 1);
        assert!(r.route_shape_off(0).is_none(), "a bad shape must not be stored");
        assert_eq!(r.sec_bytes(SEC_SHAPE_COORDS).len(), 0);
        assert_shape_invariants(&r);
    }

    #[test]
    fn a_reference_to_a_missing_shape_id_falls_back_silently() {
        let t = shaped_feed(false);
        let ag = agency("America/Los_Angeles");
        // shapes.txt exists but does not contain SH1.
        let sh = shape_map(vec![("OTHER", vec![(37.700, -122.400), (37.710, -122.400)], None)]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 0);
        assert_eq!(stats.dropped_shape_routes, 0, "an absent shape is not a validation drop");
        assert!(r.route_shape_off(0).is_none());
        assert_shape_invariants(&r);
    }

    #[test]
    fn the_modal_shape_id_wins_when_a_pattern_disagrees() {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,37.710,-122.400,B2\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,\n",
        );
        // Two trips on SH_GOOD, one on SH_BAD: same pattern, so one RAPTOR route.
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,Downtown,SH_GOOD\n\
             R1,WK,T2,Downtown,SH_GOOD\n\
             R1,WK,T3,Downtown,SH_BAD\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:10:00,08:10:00\n\
             T2,S1,1,09:00:00,09:00:00\n\
             T2,S2,2,09:10:00,09:10:00\n\
             T3,S1,1,10:00:00,10:00:00\n\
             T3,S2,2,10:10:00,10:10:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        let t = (stops, routes, trips, stop_times, calendar);
        let ag = agency("America/Los_Angeles");
        let sh = shape_map(vec![
            // The modal shape detours west, so it is identifiable in the output.
            (
                "SH_GOOD",
                vec![(37.700, -122.400), (37.705, -122.410), (37.710, -122.400)],
                None,
            ),
            ("SH_BAD", vec![(37.700, -122.400), (37.710, -122.400)], None),
        ]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.multi_shape_routes, 1, "the disagreement is reported");
        assert_eq!(stats.shaped_routes, 1);
        let pts = r.shape_points(r.route_shape_off(0).expect("shaped"));
        assert!(
            pts.iter().any(|&(_, lon)| lon < -1_224_050_000),
            "the modal SH_GOOD detour is missing: {pts:?}"
        );
        assert_shape_invariants(&r);
    }

    #[test]
    fn two_routes_sharing_a_shape_store_it_once() {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,37.710,-122.400,B2\n",
        );
        // Two GTFS routes over the same stop pattern -> two RAPTOR routes, one
        // shape_id between them.
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,\n\
             R2,NX,Judah Express,0,\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,Downtown,SH1\n\
             R2,WK,T2,Downtown,SH1\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:10:00,08:10:00\n\
             T2,S1,1,09:00:00,09:00:00\n\
             T2,S2,2,09:10:00,09:10:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        let t = (stops, routes, trips, stop_times, calendar);
        let ag = agency("America/Los_Angeles");
        let sh = shape_map(vec![(
            "SH1",
            vec![(37.700, -122.400), (37.705, -122.410), (37.710, -122.400)],
            None,
        )]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(r.route_count(), 2, "route_count");
        assert_eq!(stats.shaped_routes, 2);
        let a = r.route_shape_off(0).expect("route 0 shaped");
        let b = r.route_shape_off(1).expect("route 1 shaped");
        assert_eq!(a, b, "identical geometry must be stored once");
        assert_eq!(
            r.sec_bytes(SEC_SHAPE_COORDS).len(),
            shapes::encode(&r.shape_points(a)).len(),
            "SHAPE_COORDS holds exactly one blob"
        );
        assert_shape_invariants(&r);
    }

    #[test]
    fn a_shape_with_huge_gaps_roundtrips() {
        // Consecutive points ~40 km apart: an i16 delta at 1e-7 degrees would
        // overflow, which is why SHAPE_COORDS uses zigzag varints.
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,38.060,-122.400,B2\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,X,Express,2,\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,North,SH1\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:40:00,08:40:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        let t = (stops, routes, trips, stop_times, calendar);
        let ag = agency("America/Los_Angeles");
        let sh = shape_map(vec![(
            "SH1",
            vec![(37.700, -122.400), (37.880, -122.300), (38.060, -122.400)],
            None,
        )]);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 1);
        let pts = r.shape_points(r.route_shape_off(0).expect("shaped"));
        assert_eq!(pts.first(), Some(&(377_000_000, -1_224_000_000)));
        assert_eq!(pts.last(), Some(&(380_600_000, -1_224_000_000)));
        assert!(
            pts.iter().any(|&(lat, lon)| lat == 378_800_000 && lon == -1_223_000_000),
            "the midpoint deviates far more than the tolerance and must survive: {pts:?}"
        );
        assert_shape_invariants(&r);
    }
}
